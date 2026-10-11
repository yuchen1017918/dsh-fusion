package io.dsh.fusion.engine

import android.content.Context
import io.dsh.fusion.engine.bridge.BridgeServer

/**
 * 应用级单例装配点。
 *
 * 为什么要有它：引擎属于进程，不属于某个 Activity —— 界面来回切、被系统回收，
 * 引擎都不该跟着重启。所以所有长生命周期对象挂在这里，
 * Activity 只做「读状态 + 发指令」，不做「持有」。
 */
object FusionRuntime {

    @Volatile private var appContext: Context? = null
    @Volatile private var pathsRef: FusionPaths? = null
    @Volatile private var settingsRef: FusionSettings? = null
    @Volatile private var supervisorRef: EngineSupervisor? = null
    @Volatile private var provisionerRef: Provisioner? = null
    @Volatile private var bridgeRef: BridgeServer? = null
    @Volatile private var toolHost: ToolHost? = null

    /** 设备控制域的挂载点（由 app 模块在启动时填进来）。 */
    interface ToolHost {
        fun registerDeviceTools()
        fun statusLines(): List<String>
        fun requestShizuku(): Boolean
    }

    fun install(context: Context) {
        val app = context.applicationContext
        if (appContext === app && pathsRef != null) return
        appContext = app
        pathsRef = FusionPaths(app)
        settingsRef = FusionSettings(app)
        supervisorRef = EngineSupervisor(pathsRef!!, settingsRef!!)
        provisionerRef = Provisioner(app, pathsRef!!, settingsRef!!)
        LogBus.i("运行时", "融合体已装配：${pathsRef!!.root.absolutePath}")
    }

    fun attachToolHost(host: ToolHost) {
        toolHost = host
    }

    fun attachBridge(bridge: BridgeServer) {
        bridgeRef = bridge
    }

    fun context(): Context = appContext ?: error("FusionRuntime.install() 还没被调用")
    fun paths(): FusionPaths = pathsRef ?: error("FusionRuntime.install() 还没被调用")
    fun settings(): FusionSettings = settingsRef ?: error("FusionRuntime.install() 还没被调用")
    fun supervisor(): EngineSupervisor = supervisorRef ?: error("FusionRuntime.install() 还没被调用")
    fun provisioner(): Provisioner = provisionerRef ?: error("FusionRuntime.install() 还没被调用")
    fun bridge(): BridgeServer? = bridgeRef
    fun toolHostOrNull(): ToolHost? = toolHost
    fun installed(): Boolean = pathsRef != null
}

/** 便捷访问：别的地方只想拿监督者时不至于写一长串。 */
fun supervisorOrNull(): EngineSupervisor? = runCatching { FusionRuntime.supervisor() }.getOrNull()
fun pathsOrNull(): FusionPaths? = runCatching { FusionRuntime.paths() }.getOrNull()
fun settingsOrNull(): FusionSettings? = runCatching { FusionRuntime.settings() }.getOrNull()
fun provisionerOrNull(): Provisioner? = runCatching { FusionRuntime.provisioner() }.getOrNull()
