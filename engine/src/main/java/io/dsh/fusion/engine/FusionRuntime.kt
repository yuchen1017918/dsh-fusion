package io.dsh.fusion.engine

import android.content.Context
import io.dsh.fusion.engine.bridge.BridgeServer
import org.json.JSONObject

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
    @Volatile private var confirmationRef: ToolConfirmation? = null

    /**
     * 危险工具的人工确认闸。
     *
     * 接口放在 engine、实现放在 app：engine 不该知道通知怎么发，
     * 而 app 不该知道桥怎么路由。中间这条缝就是它。
     */
    fun interface ToolConfirmation {
        /**
         * @param toolId 工具 id，例如 android_shell
         * @param title 面向人的标题
         * @param args 模型给的参数（用于展示「它想干什么」）
         * @return true 放行；false 拒绝。超时一律按拒绝处理。
         */
        suspend fun confirm(toolId: String, title: String, args: JSONObject): Boolean
    }

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

    fun attachConfirmation(confirmation: ToolConfirmation) {
        confirmationRef = confirmation
    }

    /** 没挂确认闸时返回 null —— 调用方自行决定「放行」还是「拒绝」。 */
    fun confirmation(): ToolConfirmation? = confirmationRef

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
