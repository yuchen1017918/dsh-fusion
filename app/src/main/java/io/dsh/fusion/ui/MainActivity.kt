package io.dsh.fusion.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.dsh.fusion.engine.EngineState
import io.dsh.fusion.engine.desktop.DesktopBrowserHost
import io.dsh.fusion.engine.desktop.DesktopCarrier
import io.dsh.fusion.engine.FusionRuntime
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.service.EngineService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 主界面：一个 WebView + 一圈原生壳。
 *
 * 判断依据很朴素 —— DSH 的界面本来就是 Web 应用，手机端最有价值的做法
 * 不是重写一遍 UI，而是把**官方 UI 原样装进 WebView**，
 * 原生这层只负责它做不到的事：起引擎、保活、给设备能力、出问题看得见。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    /**
     * 侧边栏浏览器的原生承载层。
     *
     * 压在主 WebView **之上**、状态条与底栏**之下** —— 顺序很讲究：
     * 官方 UI 的侧边栏抽屉要能盖住它，而状态条与底栏要始终可点。
     */
    private lateinit var browserLayer: FrameLayout
    private var browserHost: DesktopBrowserHost? = null
    private var carrier: DesktopCarrier? = null
    private var canaryRunning = false

    /** 官方界面骨架是否渲染出来了。金丝雀用它区分「契约失效」与「整页白屏」。 */
    @Volatile
    private var shellRendered = false
    private lateinit var statusPill: TextView
    private lateinit var emptyState: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyBody: TextView
    private lateinit var emptyAction: TextView
    private var loadedUrl: String? = null
    private var webViewWarningShown = false

    // 白屏探针（见 startShellProbe 的注释）
    private val probeHandler = Handler(Looper.getMainLooper())
    private var shellProbeRunning = false
    private var shellProbeAttempt = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        configureWebView()
        observeEngine()
        observeLogs()

        if (!FusionRuntime.paths().dshInstalled()) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        EngineService.start(this)
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(UiKit.BG) }

        webView = WebView(this).apply {
            setBackgroundColor(UiKit.BG)
            visibility = View.GONE
        }
        root.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))

        // 原生浏览器的承载层。默认不占位、不拦事件 —— 没有租约时它完全透明。
        browserLayer = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            visibility = View.VISIBLE
        }
        root.addView(browserLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        emptyState = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(UiKit.dp(this@MainActivity, 28f), 0, UiKit.dp(this@MainActivity, 28f), 0)
        }
        emptyTitle = UiKit.text(this, "引擎未就绪", 19f, UiKit.TEXT, true)
        emptyBody = UiKit.text(this, "正在检查运行时与内核…", 13f, UiKit.TEXT_DIM).apply {
            setPadding(0, UiKit.dp(this@MainActivity, 10f), 0, UiKit.dp(this@MainActivity, 18f))
        }
        emptyAction = UiKit.pill(this, "启动引擎", primary = true) { onEmptyAction() }
        emptyState.addView(emptyTitle)
        emptyState.addView(emptyBody)
        emptyState.addView(emptyAction)
        root.addView(emptyState, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

        // 顶部状态条
        statusPill = UiKit.text(this, "…", 12f, UiKit.TEXT).apply {
            background = UiKit.rounded(UiKit.withAlpha(UiKit.SURFACE_HIGH, 235), 14f, this@MainActivity, UiKit.STROKE)
            setPadding(UiKit.dp(this@MainActivity, 12f), UiKit.dp(this@MainActivity, 6f),
                UiKit.dp(this@MainActivity, 12f), UiKit.dp(this@MainActivity, 6f))
            setOnClickListener { startActivity(Intent(this@MainActivity, ConsoleActivity::class.java)) }
        }
        root.addView(
            statusPill,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
                leftMargin = UiKit.dp(this@MainActivity, 12f)
                topMargin = UiKit.dp(this@MainActivity, 12f)
            },
        )

        // 底部胶囊导航
        root.addView(buildBottomBar(), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            bottomMargin = UiKit.dp(this@MainActivity, 14f)
            leftMargin = UiKit.dp(this@MainActivity, 14f)
            rightMargin = UiKit.dp(this@MainActivity, 14f)
        })

        return root
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = UiKit.rounded(UiKit.withAlpha(UiKit.SURFACE, 240), 22f, this@MainActivity, UiKit.STROKE)
            setPadding(UiKit.dp(this@MainActivity, 8f), UiKit.dp(this@MainActivity, 8f),
                UiKit.dp(this@MainActivity, 8f), UiKit.dp(this@MainActivity, 8f))
        }
        data class Item(val label: String, val action: () -> Unit)
        val items = listOf(
            Item("对话") { webView.visibility = View.VISIBLE },
            Item("控制台") { startActivity(Intent(this, ConsoleActivity::class.java)) },
            Item("体检") { startActivity(Intent(this, DoctorActivity::class.java)) },
            Item("设置") { startActivity(Intent(this, SettingsActivity::class.java)) },
            Item("重启") { restartEngine() },
        )
        items.forEach { item ->
            // compact：五个按钮等宽，但标签字数不同（控制台是 3 个字，其余 2 个）。
            // 不加 compact 时它会在窄槽里折成两行，比旁边几个高一截 ——
            // 横向 LinearLayout 的行高取最高的那个，看起来就像这个按钮特别大。
            val button = UiKit.pill(this, item.label, compact = true) { item.action() }
            bar.addView(button, LinearLayout.LayoutParams(0, UiKit.dp(this@MainActivity, 40f), 1f).apply {
                marginStart = UiKit.dp(this@MainActivity, 3f)
                marginEnd = UiKit.dp(this@MainActivity, 3f)
            })
        }
        return bar
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(io.dsh.fusion.BuildConfig.DEBUG)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // DSH 的界面是深色单页应用，让它按深色渲染，省得白闪
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                forceDark = WebSettings.FORCE_DARK_ON
            }
            // 回环地址本来就是可信上下文（127.0.0.1 属于 potentially trustworthy origin），
            // 这里再兜一层：不允许明文混合内容之外的例外
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.webChromeClient = WebChromeClient()

        // ── 桌面宿主桥 + 载体注入 ──────────────────────────────────
        // 顺序要紧：桥必须先挂上。carrier 在文档**最开始**就会去读
        // window.__DSH_FUSION_HOST__，没有桥它就直接放弃 —— 那是对的，
        // 宁可退回 iframe 降级，也不要挂一个半残的宿主。
        //
        // 整段定位是「锦上添花」：任何一步失败都只记日志、不影响主流程。
        runCatching {
            // 第三个参数是原生往**主页面**回传 JS 的通道（比如「用户点了新链接」）。
            // 必须在主线程调 —— WebView 的 API 都要求主线程。
            val host = DesktopBrowserHost(this, browserLayer) { js ->
                if (isFinishing || isDestroyed) return@DesktopBrowserHost
                webView.post { runCatching { webView.evaluateJavascript(js, null) } }
            }
            browserHost = host
            webView.addJavascriptInterface(host, DesktopBrowserHost.JS_INTERFACE)

            val c = DesktopCarrier(this)
            carrier = c
            if (c.install(webView)) {
                LogBus.i(TAG, "侧边栏浏览器：按桌面宿主形态接管（金丝雀守护中）")
            } else {
                LogBus.i(TAG, "侧边栏浏览器：保持 iframe 降级形态")
            }
        }.onFailure { LogBus.w(TAG, "桌面宿主桥安装失败，浏览器面板保持降级：${it.message}") }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // 回环与本地一律留在应用内；外部链接交给系统浏览器
                if (url.contains("127.0.0.1") || url.contains("localhost") || url.startsWith("file:")) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.visibility = View.VISIBLE
                startShellProbe()
            }
        }
        warnIfOldWebView()
    }

    // ------------------------------------------------- 白屏探针（宿主才做得到的事）

    /**
     * 界面到底渲染出来了没有。
     *
     * 插件坏掉最严重的一种表现不是布局错乱，而是**整页白屏** ——
     * 那时页面里的 JS 可能压根没跑起来，所以任何画在网页里的提示都不会出现。
     * 只有宿主能在这个时刻说话，这正是原生宿主存在的意义之一。
     * （桌面版为此在 Electron 主进程里做了 fatal-recovery 原生弹窗。）
     *
     * 探针锚点用的是官方 shell 自己的 `[data-shell-overlay]` ——
     * 它存在，说明官方 UI 至少挂到了 shell 这一层。
     */
    private fun startShellProbe() {
        if (shellProbeRunning) return
        shellProbeRunning = true
        shellProbeAttempt = 0
        probeHandler.postDelayed(probeRunnable, SHELL_PROBE_INTERVAL_MS)
    }

    private val probeRunnable = object : Runnable {
        override fun run() {
            if (!shellProbeRunning) return
            runCatching {
                webView.evaluateJavascript(SHELL_PROBE_JS) { raw ->
                    if (!shellProbeRunning) return@evaluateJavascript
                    if (raw == "true") {
                        shellProbeRunning = false
                        LogBus.i(TAG, "界面骨架已渲染（第 ${shellProbeAttempt + 1} 次探测）")
                        shellRendered = true
                        startCarrierCanary()
                        return@evaluateJavascript
                    }
                    shellProbeAttempt++
                    if (shellProbeAttempt >= SHELL_PROBE_TRIES) {
                        shellProbeRunning = false
                        onShellMissing()
                    } else {
                        probeHandler.postDelayed(this, SHELL_PROBE_INTERVAL_MS)
                    }
                }
            }.onFailure { shellProbeRunning = false }
        }
    }

    // ------------------------------------------------- 金丝雀（守注入的契约）

    /**
     * 盯着 carrier 到底有没有被官方客户端消费。
     *
     * carrier 挂的 `globalThis.dshDesktop` 是官方客户端的**内部契约**，
     * 不在任何公开文档里。它一旦失效**不会报错** —— 表现只是浏览器面板
     * 悄悄退回 iframe，是典型的最难发现的那类故障。
     *
     * 所以这里等一个观察窗口，读 carrier 里 getter 陷阱记下的报告：
     *   · 进了桌面分支 → 什么都不用做
     *   · 没进（没读 / 读了没读 browser / 自己抛错 / 整页白屏）
     *     → **摘掉注入并重载**，回到今天这个已知可用的形态
     *
     * 这就是「失败半径不对称」的正解：功能没拿到，但应用照常能用。
     */
    private fun startCarrierCanary() {
        val c = carrier ?: return
        if (!c.isInstalled || canaryRunning) return
        canaryRunning = true
        lifecycleScope.launch {
            val report = c.observe(webView) { shellRendered }
            LogBus.i(TAG, "金丝雀结论：${report.verdict} —— ${report.detail}")
            if (!report.shouldUninstall) return@launch

            // 记账：同一应用版本上不再重复试。升级后会自动重试 ——
            // 升级可能把官方客户端也带上去了，契约可能又认了。
            c.markTripped(report.detail)
            if (c.uninstall(webView)) {
                LogBus.w(TAG, "已摘掉桌面宿主载体，重载回到 iframe 降级形态")
                browserHost?.destroyAll()
                webView.reload()
            }
        }
    }


    /**
     * 等了足够久还是没骨架 —— 给一条原生出路。
     *
     * 措辞上刻意不说「插件有问题」：我们并不知道原因（可能是 WebView 太老、
     * 可能是内核起了一半、也可能是插件）。给出**可做的动作**比给一个判断有用。
     */
    private fun onShellMissing() {
        if (isFinishing || isDestroyed) return
        val third = runCatching { FusionRuntime.provisioner().safeMode.diagnose().thirdPartyCount }
            .getOrDefault(0)
        val seconds = SHELL_PROBE_TRIES * SHELL_PROBE_INTERVAL_MS / 1000
        AlertDialog.Builder(this)
            .setTitle("界面没有正常渲染")
            .setMessage(
                "等了 $seconds 秒也没看到界面骨架，页面可能是空白的。\n\n" +
                    if (third > 0) {
                        "检测到 $third 个第三方插件 —— 插件是最常见的原因。\n" +
                            "可以先用安全模式把它们全部禁用（不会删除任何文件，之后能还原），再重启试试。"
                    } else {
                        "可以先到「控制台」看看引擎输出，或用「设备体检」排查环境。"
                    },
            )
            .setPositiveButton("进入安全模式") { _, _ ->
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
            .setNegativeButton("再等等", null)
            .show()
    }

    /**
     * 老 WebView 会白屏 —— 这是社区里排第一位的问题。
     * 与其让用户对着白屏发呆，不如提前把话说明白。
     */
    private fun warnIfOldWebView() {
        if (webViewWarningShown) return
        val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WebView.getCurrentWebViewPackage() else null
        val major = pkg?.versionName?.split('.')?.firstOrNull()?.toIntOrNull() ?: 0
        if (major in 1 until 100) {
            webViewWarningShown = true
            LogBus.w(TAG, "WebView 版本偏低（${pkg?.versionName}），界面可能渲染异常，建议升级 Android System WebView")
            Toast.makeText(this, "系统 WebView 版本偏低（v${pkg?.versionName}），界面可能异常，建议在应用商店升级", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ 引擎联动

    private fun observeEngine() {
        lifecycleScope.launch {
            FusionRuntime.supervisor().status.collect { status ->
                statusPill.text = when (status.state) {
                    EngineState.RUNNING -> "● 运行中 · ${status.backend.label} · :${status.port}"
                    EngineState.STARTING -> "◐ 启动中…"
                    EngineState.PROVISIONING -> "◐ 装配中…"
                    EngineState.DEGRADED -> "◐ 掉线重启中（第 ${status.restarts} 次）"
                    EngineState.FAILED -> "✕ 引擎失败"
                    EngineState.STOPPING -> "◐ 停止中…"
                    EngineState.IDLE -> "○ 已停止"
                }
                statusPill.setTextColor(
                    when (status.state) {
                        EngineState.RUNNING -> UiKit.ACCENT
                        EngineState.FAILED -> UiKit.DANGER
                        EngineState.DEGRADED -> UiKit.WARN
                        else -> UiKit.TEXT_DIM
                    }
                )

                val url = status.url
                if (status.state == EngineState.RUNNING && url != null && url != loadedUrl) {
                    loadedUrl = url
                    LogBus.i(TAG, "把引擎地址装进 WebView：${url.substringBefore('?')}")
                    webView.loadUrl(url)
                    webView.visibility = View.VISIBLE
                    emptyState.visibility = View.GONE
                } else if (status.state != EngineState.RUNNING) {
                    webView.visibility = View.GONE
                    emptyState.visibility = View.VISIBLE
                    emptyTitle.text = when (status.state) {
                        EngineState.FAILED -> "引擎启动失败"
                        EngineState.STARTING, EngineState.DEGRADED -> "正在拉起引擎"
                        else -> "引擎未就绪"
                    }
                    // 顺带把引擎的最后一行输出摊在这里 —— 起不来的时候，
                    // 「正在下载什么」比一句「启动中…」有用得多
                    val tail = FusionRuntime.supervisor().recentLines(1).firstOrNull()?.message
                    val detail = status.detail.ifBlank { "点下面的按钮启动，或先去「体检」看看环境。" }

                    // 引擎起不来时，最大的嫌疑是插件 —— 坏插件能让整个 cordis
                    // 装配挂在半路。桌面版正是在这一刻弹原生恢复对话框；
                    // 我们既有常驻入口（设置页），这里再给一次直达引导。
                    val thirdParty = if (status.state == EngineState.FAILED) {
                        runCatching {
                            FusionRuntime.provisioner().safeMode.diagnose().thirdPartyCount
                        }.getOrDefault(0)
                    } else {
                        0
                    }

                    emptyBody.text = buildString {
                        append(if (tail.isNullOrBlank()) detail else "$detail\n\n$tail")
                        if (thirdParty > 0) {
                            append("\n\n检测到 $thirdParty 个第三方插件 —— 引擎起不来时它们嫌疑最大。")
                        }
                    }

                    // 失败且装了第三方插件时，主按钮直接给安全模式。
                    // 不给「重试」：重试多半还是同样的失败，而用户不知道该改什么。
                    // 注意文本与监听器必须一起设，否则会出现「写着重试、点了跳设置」。
                    if (status.state == EngineState.FAILED && thirdParty > 0) {
                        emptyAction.text = "进入安全模式"
                        emptyAction.setOnClickListener {
                            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                        }
                    } else {
                        emptyAction.text = when (status.state) {
                            EngineState.FAILED -> "重试"
                            EngineState.STARTING, EngineState.DEGRADED -> "查看控制台"
                            else -> "启动引擎"
                        }
                        emptyAction.setOnClickListener { onEmptyAction() }
                    }
                }
            }
        }
    }

    private fun onEmptyAction() {
        val state = FusionRuntime.supervisor().status.value.state
        when (state) {
            EngineState.FAILED -> restartEngine()
            EngineState.STARTING, EngineState.DEGRADED -> startActivity(Intent(this, ConsoleActivity::class.java))
            else -> {
                if (!FusionRuntime.paths().dshInstalled()) {
                    startActivity(Intent(this, SetupActivity::class.java))
                } else {
                    EngineService.start(this)
                }
            }
        }
    }

    private fun restartEngine() {
        if (!EngineService.start(this, EngineService.ACTION_RESTART)) {
            Toast.makeText(this, "请保持界面在前台再试一次", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "正在重启引擎…", Toast.LENGTH_SHORT).show()
        }
    }

    /** 引擎日志里出现「回复完成」这类信号时，顺手喂给流体云。 */
    private fun observeLogs() {
        lifecycleScope.launch {
            var counter = 0
            while (isActive) {
                val lines = LogBus.snapshot.value
                if (lines.size > counter) counter = lines.size
                delay(1200)
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 从通知/桌宠回来时，如果引擎已经好了就直接显示对话
        val current = FusionRuntime.supervisor().status.value.url
        if (current != null && current != loadedUrl) {
            webView.loadUrl(current)
            loadedUrl = current
        }
    }

    companion object {
        private const val TAG = "主界面"

        /** 白屏探针：每 2 秒探一次，共 12 次（约 24 秒）。 */
        private const val SHELL_PROBE_TRIES = 12
        private const val SHELL_PROBE_INTERVAL_MS = 2000L

        /** 官方 shell 的锚点。用 try 包住：页面可能还没解析完。 */
        private const val SHELL_PROBE_JS =
            "(function(){try{return document.querySelector('[data-shell-overlay]')!==null}catch(e){return false}})()"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
