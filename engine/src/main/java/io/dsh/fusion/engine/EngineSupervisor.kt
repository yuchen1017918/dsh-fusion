package io.dsh.fusion.engine

import io.dsh.fusion.engine.runtime.PortPicker
import io.dsh.fusion.engine.runtime.ProcessRunner
import io.dsh.fusion.engine.runtime.ProotLauncher
import io.dsh.fusion.engine.runtime.RuntimeEnv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.lang.Process
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 引擎监督者 —— 融合体的心脏起搏器。
 *
 * 职责：
 *  · 按 [EngineBackend] 组装启动命令（原生直跑 / PRoot Ubuntu 两种跑法共用同一份内核）；
 *  · 从 stdout 里**嗅探出真实监听地址**（而不是赌某个写死的标志位），
 *    因为 dsh 会自己挑端口并打印带 token 的 URL —— 这个 URL 才是 WebView 该加载的东西；
 *  · 看门狗：进程掉了自动拉回，连续失败就停下并把原因摊开给用户看。
 */
class EngineSupervisor(
    private val paths: FusionPaths,
    private val settings: FusionSettings,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runner = ProcessRunner("引擎")
    private val proot = ProotLauncher(paths)

    private val _status = MutableStateFlow(EngineStatus(EngineState.IDLE, EngineBackend.BIONIC_NATIVE))
    val status: StateFlow<EngineStatus> = _status

    private var process: Process? = null
    private var pumpJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile private var stopping = false

    /** 端口冲突只自动重试一次，避免在真实故障上反复重启。 */
    private var portRetried = false

    /**
     * 本地模型服务的地址提供者（由 FusionRuntime 注入；没启用本地模型时为空）。
     *
     * 一旦非空且返回了地址，就把它作为 `DEEPSEEK_BASE_URL` 注入引擎的**继承环境** ——
     * 官方 `dsh-llm-deepseek` 解析 baseURL 的顺序是：
     *   `config.baseURL ?? environment.get("DEEPSEEK_BASE_URL") ?? 官方默认`
     * 而这个变量在「只能由启动环境提供」的名单里（与 `ALL_PROXY`、`CURL_CA_BUNDLE` 同列），
     * 所以**走环境变量是官方认可的正规入口**，不需要改用户那个 100 KB 的 cordis.patch.yml。
     */
    var localModelBaseUrl: (() -> String?)? = null

    val url: String? get() = _status.value.url
    val isRunning: Boolean get() = _status.value.running

    // ------------------------------------------------------------------ 启动

    suspend fun start(): EngineStatus {
        if (_status.value.state == EngineState.RUNNING) return _status.value
        return startInternal(settings.backend, _status.value.restarts)
    }

    private suspend fun startInternal(backend: EngineBackend, restarts: Int): EngineStatus {
        stopping = false
        paths.ensure()

        if (!paths.runtimeSeeded()) {
            return fail(backend, "运行时未就位：${paths.nodeBin.absolutePath} 不存在（ABI 必须是 arm64-v8a）")
        }
        if (!paths.dshInstalled()) {
            return fail(backend, "dsh 内核还没装。先到「内核」页点一次安装。")
        }
        if (backend == EngineBackend.UBUNTU_PROOT && !proot.rootfsReady()) {
            return fail(backend, "Ubuntu rootfs 未就绪。先到「运行时」页点一次装配。")
        }

        val port = pickPort(settings.preferredPort)
        val command = buildCommand(backend, port)
        _status.value = _status.value.copy(
            state = EngineState.STARTING,
            backend = backend,
            port = port,
            restarts = restarts,
            detail = "正在拉起（${backend.label}）…",
        )

        // 显式标注类型：try/catch 作为表达式时若 catch 分支是 return，
        // 编译器可能把整体推成 Any，后面 proc.pid() 就会以「Unresolved reference」的形式炸掉
        val proc: Process = try {
            runner.start(command, envFor(backend, port), paths.root)
        } catch (e: Exception) {
            return fail(backend, e.message ?: "进程启动失败")
        }
        process = proc

        val seen = CompletableDeferred<String?>()
        pumpJob = scope.launch {
            runner.pump(proc) { line ->
                URL_REGEX.find(line)?.let { match ->
                    if (!seen.isCompleted) seen.complete(match.value)
                }
            }
            if (!seen.isCompleted) seen.complete(null)
        }

        val url = withTimeoutOrNull(START_TIMEOUT_MS) { seen.await() }
        if (url == null) {
            val exitCode = runCatching { proc.exitValue() }.getOrNull()
            // 端口冲突是最容易被误诊的一种失败：现象与「启动超时」一模一样，
            // 但用户能做的事完全不同（换端口 vs 查依赖）。这里把它认出来。
            val recent = recentLines(60).joinToString("\n") { it.message }
            val portBusy = recent.contains("EADDRINUSE") || recent.contains("address already in use")
            if (portBusy && port != 0 && !portRetried) {
                LogBus.w(TAG, "端口 $port 被占用，改用系统分配端口重试一次")
                portRetried = true
                _status.value = _status.value.copy(
                    state = EngineState.STARTING,
                    detail = "端口 $port 被占用，正在换端口重试…",
                )
                return startInternal(backend, restarts)
            }
            return fail(
                backend,
                if (portBusy) {
                    "端口 $port 被占用（EADDRINUSE）。同一台设备上可能有另一个 DSH 在跑，" +
                        "或这个端口被别的应用占了。到「设置 → 引擎」把首选端口改成 0（自动选择）即可。"
                } else {
                    "启动超时（${START_TIMEOUT_MS / 1000}s）或进程提前退出，退出码=$exitCode"
                },
            )
        }
        portRetried = false

        _status.value = EngineStatus(
            state = EngineState.RUNNING,
            backend = backend,
            port = port,
            url = url,
            pid = pidOf(proc),
            since = System.currentTimeMillis(),
            restarts = restarts,
            detail = "已就绪 · ${backend.label}",
        )
        LogBus.i(TAG, "引擎就绪：$url（pid=${_status.value.pid}）")
        startWatchdog()
        return _status.value
    }

    // ------------------------------------------------------------------ 停止

    suspend fun stop() {
        stopping = true
        watchdogJob?.cancel()
        watchdogJob = null
        val proc = process
        process = null
        if (proc != null) {
            _status.value = _status.value.copy(state = EngineState.STOPPING, detail = "正在停止…")
            runCatching { proc.destroy() }
            val exited = withContext(Dispatchers.IO) { runCatching { proc.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false) }
            if (!exited) runCatching { proc.destroyForcibly() }
        }
        pumpJob?.cancel()
        pumpJob = null
        _status.value = EngineStatus(EngineState.IDLE, settings.backend, detail = "已停止")
        LogBus.i(TAG, "引擎已停止")
    }

    suspend fun restart(): EngineStatus {
        stop()
        delay(600)
        return start()
    }

    /** 后端热切换：停掉当前引擎，用新后端拉起来（内核与数据都不动）。 */
    suspend fun switchBackend(backend: EngineBackend): EngineStatus {
        settings.backend = backend
        LogBus.i(TAG, "切换后端 -> ${backend.label}")
        return restart()
    }

    // ------------------------------------------------------------------ 看门狗

    private fun startWatchdog() {
        watchdogJob?.cancel()
        if (!settings.watchdog) {
            LogBus.i(TAG, "看门狗已关闭（设置里可开）")
            return
        }
        watchdogJob = scope.launch {
            var consecutive = 0
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (stopping) break
                val proc = process ?: continue
                if (!proc.isAlive) {
                    consecutive++
                    val restarts = _status.value.restarts + 1
                    LogBus.w(TAG, "引擎进程不在了（第 $consecutive 次），尝试第 $restarts 次拉起")
                    _status.value = _status.value.copy(
                        state = EngineState.DEGRADED,
                        restarts = restarts,
                        detail = "掉线，正在第 $restarts 次重启…",
                    )
                    if (consecutive > MAX_WATCHDOG_RESTARTS) {
                        _status.value = _status.value.copy(
                            state = EngineState.FAILED,
                            detail = "连续 $consecutive 次重启失败，已停下。看控制台日志定位原因。",
                        )
                        LogBus.e(TAG, "看门狗放弃：连续 $consecutive 次失败")
                        EventBus.emit(
                            FusionEvent.Failure("引擎连续 $consecutive 次重启失败，已停止自动拉回。到控制台看日志。")
                        )
                        break
                    }
                    startInternal(_status.value.backend, restarts)
                } else {
                    val port = _status.value.port
                    if (port != null && !probe(port)) {
                        LogBus.w(TAG, "端口 $port 无响应，但进程仍在 —— 继续观察")
                    } else {
                        consecutive = 0
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun buildCommand(backend: EngineBackend, port: Int): List<String> {
        // cordis 的 HMR / require-builtin 插件要求 --expose-internals；
        // 这个标志只能走命令行，塞进 NODE_OPTIONS 是无效的。
        val nodeFlags = if (settings.exposeInternals) listOf("--expose-internals") else emptyList()

        /**
         * 有宿主脚本就走我们自己的入口，否则退回官方 CLI。
         *
         * 差别只在一处：官方 bin.js 调 runProfile 时不传 packageManager，
         * 于是插件安装器会去 PATH 里找 pnpm —— 而 Android 上那是不可能的
         * （没装 + W^X 禁止执行数据目录里的脚本）。自建入口按官方为打包应用
         * 留的口子把 packageManager 显式传进去，官方插件页就活了。
         */
        val useHost = paths.hostReady()
        val guest = backend == EngineBackend.UBUNTU_PROOT
        val nodeExe = if (guest) "${ProotLauncher.Guest.BIONIC}/libnode.so" else paths.nodeBin.absolutePath
        val entry = if (useHost) {
            if (guest) "${ProotLauncher.Guest.HOST}/fusion-host.mjs" else paths.hostScript.absolutePath
        } else {
            if (guest) "${ProotLauncher.Guest.DSH}/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
            else paths.dshEntry.absolutePath
        }

        // 走宿主脚本时 profile 与 app 参数由环境变量传（脚本会自己拼），
        // 走官方 CLI 时它们是位置参数。
        val entryArgs = if (useHost) emptyList() else listOf("web", "--port", port.toString(), "--host", "127.0.0.1")

        LogBus.i(
            TAG,
            if (useHost) "启动方式：自建宿主入口（插件安装用带内 pnpm）"
            else "启动方式：官方 CLI（未找到宿主脚本，插件安装页会找不到 pnpm）",
        )

        return when (backend) {
            EngineBackend.BIONIC_NATIVE ->
                listOf(nodeExe) + nodeFlags + listOf(entry) + entryArgs

            EngineBackend.UBUNTU_PROOT ->
                proot.command(listOf(nodeExe) + nodeFlags + listOf(entry) + entryArgs, ProotLauncher.Guest.DSH_HOME)
        }
    }

    /**
     * 把宿主路径翻译成客人视角的路径（PRoot 后端用）。
     * 顺序有讲究：先匹配更长的前缀，否则 /root/home 会被 /root 先吃掉。
     */
    private fun toGuest(hostPath: String): String = when {
        hostPath.startsWith(paths.hostDir.absolutePath) ->
            hostPath.replaceFirst(paths.hostDir.absolutePath, ProotLauncher.Guest.HOST)
        hostPath.startsWith(paths.nativeDir.absolutePath) ->
            hostPath.replaceFirst(paths.nativeDir.absolutePath, ProotLauncher.Guest.BIONIC)
        hostPath.startsWith(paths.prefixDir.absolutePath) ->
            hostPath.replaceFirst(paths.prefixDir.absolutePath, ProotLauncher.Guest.DSH)
        hostPath.startsWith(paths.dshHome.absolutePath) ->
            hostPath.replaceFirst(paths.dshHome.absolutePath, ProotLauncher.Guest.DSH_HOME)
        else -> hostPath
    }

    private fun envFor(backend: EngineBackend, port: Int): Map<String, String> {
        // 桥的地址与 token 只能通过环境变量交给插件 —— 端口是启动时动态选的
        val extra = HashMap<String, String>(bridgeEnv())
        extra.putAll(hostEnv(backend, port))
        // 本地模型在跑 → 把 DSH 指过去。取不到地址就不设，让它照常走云端。
        runCatching { localModelBaseUrl?.invoke() }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
            extra["DEEPSEEK_BASE_URL"] = it
            LogBus.i(TAG, "本地模型已启用：DEEPSEEK_BASE_URL=$it")
        }
        val base = RuntimeEnv.base(paths, settings.npmRegistry, settings.heapMb, extra)
        return when (backend) {
            EngineBackend.BIONIC_NATIVE -> base
            // 客人视角的路径必须覆盖掉宿主视角，顺序不能反
            EngineBackend.UBUNTU_PROOT -> base + RuntimeEnv.guest(paths)
        }
    }

    /**
     * 宿主脚本需要的环境变量。
     *
     * 走 PRoot 后端时这些路径必须是**客人视角**的 —— 脚本在容器里跑，
     * 给它宿主路径等于让它去找一个不存在的文件。
     */
    private fun hostEnv(backend: EngineBackend, port: Int): Map<String, String> {
        if (!paths.hostReady()) return emptyMap()
        val guest = backend == EngineBackend.UBUNTU_PROOT
        fun p(f: File): String = if (guest) toGuest(f.absolutePath) else f.absolutePath

        val env = HashMap<String, String>()
        env["DSH_FUSION_DSH_DIR"] = p(paths.dshDir)
        env["DSH_FUSION_PROFILE"] = "web"
        env["DSH_FUSION_NODE_BIN"] = p(paths.nodeBin)
        // web app 自己的参数；用 NUL 分隔，避免任何引号/空格转义问题
        env["DSH_FUSION_APP_ARGS"] = listOf("--port", port.toString(), "--host", "127.0.0.1").joinToString(NUL)
        if (paths.pnpmReady()) {
            env["DSH_FUSION_PNPM_ENTRY"] = p(paths.pnpmEntry)
        } else {
            LogBus.w(TAG, "pnpm 未就位：官方插件安装页会失败。首次装配时会自动装上。")
        }
        return env
    }

    /** 把回环桥的位置告诉 node 侧插件。 */
    private fun bridgeEnv(): Map<String, String> {
        val bridge = runCatching { FusionRuntime.bridge() }.getOrNull() ?: return emptyMap()
        val port = settings.bridgePort
        if (port <= 0) return emptyMap()
        return mapOf(
            "DSH_FUSION_BRIDGE_URL" to "http://127.0.0.1:$port",
            "DSH_FUSION_BRIDGE_TOKEN" to settings.bridgeToken,
        )
    }

    /**
     * 挑一个端口。实现在 [PortPicker]（那里有完整的三条行为说明，也便于单测）。
     *
     * 这里只做一件事：把 `preferred <= 0` 的语义透传下去 —— 返回 0 表示
     * 「让操作系统在 listen 那一刻原子分配」，这是没有竞态的路径。
     */
    private fun pickPort(preferred: Int): Int = PortPicker.pick(preferred)

    fun probe(port: Int): Boolean = runCatching {
        val conn = (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500
            readTimeout = 2500
            requestMethod = "GET"
        }
        val code = conn.responseCode
        conn.disconnect()
        code in 200..499
    }.getOrDefault(false)

    /**
     * 取子进程 pid。
     *
     * 故意不写 `process.pid()`：这个方法的可见性在「Android 26+ 才有」和
     * 「不同工具链下 Kotlin 是否解析得到」之间并不一致（本工程实测直接在编译期报
     * Unresolved reference）。反射取一次最稳，拿不到就当没有 —— pid 只是给人看的。
     */
    private fun pidOf(process: Process): Long? = runCatching {
        val method = process.javaClass.getMethod("pid")
        (method.invoke(process) as? Number)?.toLong()
    }.getOrNull()

    private fun fail(backend: EngineBackend, detail: String): EngineStatus {
        LogBus.e(TAG, detail)
        _status.value = _status.value.copy(state = EngineState.FAILED, backend = backend, detail = detail)
        // 让悬浮球与流体云也看得见失败 —— 否则用户只看到一个不动的球
        EventBus.emit(FusionEvent.Failure(detail))
        return _status.value
    }

    /**
     * 装配期标记。
     *
     * 之前 EngineState.PROVISIONING 是个只被 when 分支处理、从未被赋值的死状态；
     * 现在由 Provisioner 在真正下载内核时置位，界面就能区分
     * 「在装东西」和「在启动引擎」—— 这两件事的等待理由完全不同。
     */
    fun markProvisioning(detail: String) {
        LogBus.i(TAG, "进入装配态：$detail")
        _status.value = _status.value.copy(state = EngineState.PROVISIONING, detail = detail)
    }

    fun clearProvisioning() {
        if (_status.value.state == EngineState.PROVISIONING) {
            _status.value = _status.value.copy(state = EngineState.IDLE, detail = "装配结束")
        }
    }

    /** 最近的引擎输出行，用于「流体云」胶囊与错误提示。 */
    fun recentLines(limit: Int = 40): List<LogLine> =
        LogBus.snapshot.value.takeLast(limit).filter { it.tag == "引擎" }

    companion object {
        private const val TAG = "引擎"

        /** 环境变量里分隔多个参数的哨兵字符（不可能出现在参数里）。 */
        private const val NUL = "\u0000"
        private const val START_TIMEOUT_MS = 180_000L
        private const val WATCHDOG_INTERVAL_MS = 4_000L
        private const val MAX_WATCHDOG_RESTARTS = 5

        val URL_REGEX = Regex("""https?://(?:127\.0\.0\.1|localhost):\d+/?(?:\?token=[\w.\-]+)?""")
    }
}
