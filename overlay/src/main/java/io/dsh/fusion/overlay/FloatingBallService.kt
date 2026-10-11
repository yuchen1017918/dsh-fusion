package io.dsh.fusion.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.dsh.fusion.engine.Channels
import io.dsh.fusion.engine.EngineState
import io.dsh.fusion.engine.EventBus
import io.dsh.fusion.engine.FusionEvent
import io.dsh.fusion.engine.FusionPaths
import io.dsh.fusion.engine.FusionSettings
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.Tool
import io.dsh.fusion.engine.ToolBus
import io.dsh.fusion.engine.ToolChannel
import io.dsh.fusion.engine.ToolParam
import io.dsh.fusion.engine.ToolSpec
import io.dsh.fusion.engine.buildForegroundNotification
import io.dsh.fusion.engine.supervisorOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮球桌宠 + 流体云回复卡。
 *
 * 两个窗口、一个服务：
 *  · 球：常驻，颜色跟着引擎状态走，单击回应用，长按出快捷菜单；
 *  · 胶囊：只在模型说话时浮现，几秒后自己退场，绝不赖着。
 *
 * 权限是硬门槛（SYSTEM_ALERT_WINDOW），没有就直接不启动 —— 不弹窗骚扰。
 */
class FloatingBallService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var paths: FusionPaths
    private lateinit var settings: FusionSettings

    private var pet: PetView? = null
    private var petParams: WindowManager.LayoutParams? = null
    private var menu: LinearLayout? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var capsule: CloudCapsuleView? = null
    private var capsuleParams: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var capsuleTimer: Job? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        paths = FusionPaths(this)
        settings = FusionSettings(this)
        registerOverlayTools()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canDrawOverlays()) {
            LogBus.e(TAG, "没有悬浮窗权限，桌宠不会启动（设置页可以一键去授权）")
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        attachPet()
        observeEvents()
        startTicker()
        return START_STICKY
    }

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun startInForeground() {
        val notification = buildForegroundNotification(
            this,
            Channels.PET,
            "桌宠在线",
            "单击回到 DSH，长按出快捷菜单（可随时关掉）",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ------------------------------------------------------------------ 球

    private fun attachPet() {
        if (pet != null) return
        val size = dp(62f)
        val view = PetView(this)
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10f)
            y = resources.displayMetrics.heightPixels / 3
        }
        wireDrag(view, params)
        windowManager.addView(view, params)
        pet = view
        petParams = params
        LogBus.i(TAG, "桌宠已吸附到屏幕")
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /** 拖动 + 单击 + 长按。三者共用一次触摸序列，分开判定。 */
    private fun wireDrag(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downAt = 0L
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    downAt = System.currentTimeMillis()
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > dp(6f) || abs(dy) > dp(6f)) moved = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val held = System.currentTimeMillis() - downAt
                    if (!moved && held > 600) {
                        toggleMenu()
                    } else if (!moved) {
                        snapToEdge(params, view)
                        openApp()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** 松手后自动贴边，别让小圆点挡住内容。 */
    private fun snapToEdge(params: WindowManager.LayoutParams, view: View) {
        val screenWidth = resources.displayMetrics.widthPixels
        val target = if (params.x + view.width / 2 < screenWidth / 2) dp(10f) else screenWidth - view.width - dp(10f)
        scope.launch {
            var current = params.x
            val step = if (target > current) dp(12f) else -dp(12f)
            while (abs(target - current) > dp(13f)) {
                current += step
                params.x = current
                runCatching { windowManager.updateViewLayout(view, params) }
                delay(8)
            }
            params.x = target
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    // ------------------------------------------------------------------ 快捷菜单

    private fun toggleMenu() {
        if (menu != null) {
            detachMenu()
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x00000000)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        listOf(
            "对话" to { openApp() },
            "控制台" to { openActivity("io.dsh.fusion.ui.ConsoleActivity") },
            "设置" to { openActivity("io.dsh.fusion.ui.SettingsActivity") },
        ).forEach { (label, action) ->
            container.addView(
                TextView(this).apply {
                    text = label
                    setTextColor(0xFFE8F0FF.toInt())
                    textSize = 13f
                    setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
                    setBackgroundColor(0xE6141A26.toInt())
                    setOnClickListener {
                        detachMenu()
                        action()
                    }
                }
            )
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (petParams?.x ?: 0) + dp(10f)
            y = (petParams?.y ?: 0) + dp(70f)
        }
        runCatching {
            windowManager.addView(container, params)
            menu = container
            menuParams = params
        }
    }

    private fun detachMenu() {
        menu?.let { runCatching { windowManager.removeView(it) } }
        menu = null
        menuParams = null
    }

    // ------------------------------------------------------------------ 流体云

    private fun showCapsule(text: String, done: Boolean) {
        if (!settings.cloudCapsule) return
        capsuleTimer?.cancel()
        val width = resources.displayMetrics.widthPixels - dp(24f)
        if (capsule == null) {
            val view = CloudCapsuleView(this)
            val params = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = dp(6f)
            }
            view.setOnClickListener {
                val expanded = (it.tag as? Boolean) ?: false
                it.tag = !expanded
                (it as CloudCapsuleView).setExpanded(!expanded)
                if (!expanded) {
                    // 展开时多留一会儿让用户读完
                    capsuleTimer?.cancel()
                    capsuleTimer = scope.launch {
                        delay(12_000)
                        hideCapsule()
                    }
                }
            }
            runCatching {
                windowManager.addView(view, params)
                capsule = view
                capsuleParams = params
            }
        }
        capsule?.update(if (done) "回复完成" else "正在思考…", text, done)
        capsuleTimer = scope.launch {
            delay(if (done) 7_000 else 60_000)
            hideCapsule()
        }
    }

    private fun hideCapsule() {
        capsule?.let { runCatching { windowManager.removeView(it) } }
        capsule = null
        capsuleParams = null
    }

    // ------------------------------------------------------------------ 事件

    private fun observeEvents() {
        scope.launch {
            EventBus.events.collect { event ->
                when (event) {
                    is FusionEvent.Reply -> showCapsule(event.text, event.done)
                    is FusionEvent.EngineReady -> pet?.setMood(PetView.Mood.IDLE)
                    is FusionEvent.EngineLost -> pet?.setMood(PetView.Mood.ERROR)
                    is FusionEvent.ToolInvoked -> pet?.setMood(PetView.Mood.BUSY)
                    is FusionEvent.Failure -> showCapsule(event.message, true)
                    is FusionEvent.Wake -> showCapsule("唤醒命中：${event.text}", false)
                    is FusionEvent.Message -> Unit
                }
            }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                pet?.tick()
                capsule?.tick()
                pet?.setMood(moodFromEngine())
                delay(90)
            }
        }
    }

    private fun moodFromEngine(): PetView.Mood = when (supervisorOrNull()?.status?.value?.state) {
        EngineState.RUNNING -> PetView.Mood.IDLE
        EngineState.STARTING, EngineState.PROVISIONING, EngineState.DEGRADED -> PetView.Mood.BUSY
        EngineState.FAILED -> PetView.Mood.ERROR
        else -> PetView.Mood.OFFLINE
    }

    // ------------------------------------------------------------------ 工具

    private fun registerOverlayTools() {
        ToolBus.registerAll(
            listOf(
                Tool(
                    ToolSpec(
                        id = "android_show_capsule",
                        title = "弹出流体云胶囊",
                        description = "在屏幕顶部浮出一条胶囊显示消息，不打断用户当前操作，几秒后自动消失。",
                        channel = ToolChannel.APP,
                        params = listOf(
                            ToolParam("text", "string", true, "要显示的正文"),
                            ToolParam("title", "string", false, "标题"),
                        ),
                    )
                ) { args ->
                    val text = args.optString("text")
                    if (text.isBlank()) {
                        org.json.JSONObject().put("ok", false).put("error", "缺少 text")
                    } else {
                        showCapsule(text, true)
                        org.json.JSONObject().put("ok", true).put("shown", true)
                    }
                },
            )
        )
    }

    // ------------------------------------------------------------------ 生命周期

    private fun openApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun openActivity(className: String) {
        runCatching {
            startActivity(
                Intent().setClassName(packageName, className).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onDestroy() {
        ticker?.cancel()
        capsuleTimer?.cancel()
        detachMenu()
        hideCapsule()
        pet?.let { runCatching { windowManager.removeView(it) } }
        pet = null
        LogBus.i(TAG, "桌宠已退场")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "桌宠"
        private const val NOTIFICATION_ID = 7303

        const val ACTION_START = "io.dsh.fusion.overlay.START"
        const val ACTION_STOP = "io.dsh.fusion.overlay.STOP"

        fun start(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBallService::class.java))
        }
    }
}
