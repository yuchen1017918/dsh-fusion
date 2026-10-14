package io.dsh.fusion

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.dsh.fusion.devicectl.DeviceControlManager
import io.dsh.fusion.engine.EventBus
import io.dsh.fusion.engine.FusionEvent
import io.dsh.fusion.engine.FusionRuntime
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.ensureNotificationChannels
import io.dsh.fusion.engine.bridge.BridgeRouter
import io.dsh.fusion.engine.bridge.BridgeServer
import java.net.InetAddress
import java.net.ServerSocket

/**
 * 融合体的进程级装配。
 *
 * 这里做四件事，顺序不能乱：
 *   1. 装运行时门面（路径、设置、引擎监督者、装配器）；
 *   2. 给内核装配器接上 assets 读取器（桥插件要从 APK 里拷出来）；
 *   3. 起回环能力桥 —— 必须在引擎起来**之前**，因为桥的端口要写进引擎的环境变量；
 *   4. 注册设备能力（这一步失败也不影响引擎，降级是常态）。
 */
class FusionApplication : Application() {

    lateinit var deviceManager: DeviceControlManager
        private set

    /** 危险工具的人工确认闸。通知按钮经由 [ConfirmationReceiver] 回调到它。 */
    lateinit var confirmationGate: ToolConfirmationGate
        private set

    /** 当前是否有界面在前台 —— Android 12+ 只允许这时候启动前台服务。 */
    @Volatile
    var uiVisible: Boolean = false
        private set

    private var activityCount = 0

    override fun onCreate() {
        super.onCreate()

        FusionRuntime.install(this)
        val paths = FusionRuntime.paths()
        paths.ensure()

        ensureNotificationChannels(this)

        // 危险操作确认闸。桥插件不再需要「全局 assets 读取器」——
        // 插件文件由 Provisioner（它有 Context）读出来直接传给内核装配器。
        confirmationGate = ToolConfirmationGate(this).also {
            it.register()
// 本地模型：一旦推理服务在跑，就把引擎的 DEEPSEEK_BASE_URL 指过去。
        // 这个 lambda 在每次启动引擎时被调用，所以「先启服务再重启引擎」的顺序天然成立。
        runCatching {
            val prov = FusionRuntime.provisioner()
            FusionRuntime.supervisor().localModelBaseUrl = { prov.llmServer.baseUrl() }
        }.onFailure { LogBus.w("应用", "本地模型接线失败：${it.message}") }

                    FusionRuntime.attachConfirmation(it)
        }
        startBridge()
        setupDeviceControl()
        trackForeground()

        LogBus.i(TAG, "DSH 融合版启动：${paths.describe().entries.joinToString(" | ") { "${it.key}=${it.value}" }}")
    }

    // ---------------------------------------------------------------- 能力桥

    /**
     * 起桥。端口优先用上次记住的，被占就往后找一个 ——
     * 端口是**动态**的，所以只能通过环境变量交给 node 侧插件，
     * 不能在插件里写死。
     */
    private fun startBridge() {
        if (FusionRuntime.bridge()?.isRunning == true) return
        val settings = FusionRuntime.settings()
        val paths = FusionRuntime.paths()

        val desired = if (settings.bridgePort > 0) settings.bridgePort else DEFAULT_BRIDGE_PORT
        val port = findFreePort(desired)
        if (port != desired) LogBus.w(TAG, "桥端口 $desired 被占，改用 $port")
        settings.bridgePort = port

        val router = BridgeRouter(paths, settings)
        val server = BridgeServer(port, settings.bridgeToken, router::route)
        runCatching { server.start() }
            .onSuccess {
                FusionRuntime.attachBridge(server)
                LogBus.i(TAG, "能力桥就绪：http://127.0.0.1:$port（token 已生成，仅回环可达）")
            }
            .onFailure { LogBus.e(TAG, "能力桥启动失败：${it.message}") }
    }

    private fun findFreePort(start: Int): Int {
        for (offset in 0 until 40) {
            val candidate = start + offset
            val free = runCatching {
                ServerSocket(candidate, 8, InetAddress.getByName("127.0.0.1")).use { true }
            }.getOrDefault(false)
            if (free) return candidate
        }
        return start
    }

    // ---------------------------------------------------------------- 设备能力

    private fun setupDeviceControl() {
        deviceManager = DeviceControlManager(this, FusionRuntime.paths())
        FusionRuntime.attachToolHost(object : FusionRuntime.ToolHost {
            override fun registerDeviceTools() {
                if (!FusionRuntime.settings().deviceToolsEnabled) {
                    LogBus.w(TAG, "设备工具已在设置里关掉，本次不注册")
                    return
                }
                deviceManager.registerTools()

                // 语音转写：把设备本地的 Vosk 挂上能力桥，
                // 官方 speech-to-text 抽象层的 Android 后端要靠它。
                runCatching {
                    io.dsh.fusion.voice.VoiceTools.modelDirProvider = {
                        val paths = FusionRuntime.paths()
                        // 挑**已经装好的**那个模型，而不是写死中文小模型 ——
                        // 用户可能只下了英文的；写死会让「明明下过却提示没下」。
                        // 中文优先（这个应用的用户群以中文为主）。
                        io.dsh.fusion.voice.VoiceModels.all
                            .firstOrNull { io.dsh.fusion.voice.VoiceModels.installed(paths, it) }
                            ?.let { io.dsh.fusion.voice.VoiceModels.modelDir(paths, it) }
                    }
                    io.dsh.fusion.voice.VoiceTools.registerAll()
                }.onFailure { LogBus.w("应用", "语音转写能力注册失败：${it.message}") }

                refreshToolManifest()
            }

            override fun statusLines(): List<String> = deviceManager.status().map { s ->
                "${if (s.ready) "✔" else "✘"} ${s.label}：${s.detail}"
            }

            override fun requestShizuku(): Boolean = deviceManager.requestShizukuPermission()
        })
        // 首启就先注册一遍，让内核装配时能导出完整清单
        runCatching { deviceManager.registerTools() }
    }

    override fun onTerminate() {
        confirmationGate.unregister()
        super.onTerminate()
    }

    /** 能力清单变了要重新落盘 —— 插件在下次引擎启动时读取。 */
    fun refreshToolManifest() {
        runCatching {
            val provisioner = FusionRuntime.provisioner()
            provisioner.dsh.writeToolManifest()
        }.onFailure { LogBus.w(TAG, "导出能力清单失败：${it.message}") }
    }

    // ---------------------------------------------------------------- 前台判定

    private fun trackForeground() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                activityCount++
                uiVisible = true
            }

            override fun onActivityStopped(activity: Activity) {
                activityCount = (activityCount - 1).coerceAtLeast(0)
                uiVisible = activityCount > 0
                if (!uiVisible) EventBus.emit(FusionEvent.Message("app", "界面退到后台"))
            }

            override fun onActivityCreated(activity: Activity, bundle: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private companion object {
        const val TAG = "应用"
        const val DEFAULT_BRIDGE_PORT = 7310
    }
}
