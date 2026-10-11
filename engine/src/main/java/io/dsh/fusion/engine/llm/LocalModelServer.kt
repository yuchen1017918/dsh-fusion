package io.dsh.fusion.engine.llm

import io.dsh.fusion.engine.FusionPaths
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.runtime.PortPicker
import io.dsh.fusion.engine.runtime.ProcessRunner
import io.dsh.fusion.engine.runtime.ProotLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在手机上跑一个 OpenAI 兼容的推理服务，并把 DSH 指过来。
 *
 * ── 为什么走这条路 ──────────────────────────────────────────────
 * 官方内核的 `dsh-llm` 是 **provider 无关的抽象层**，而 `dsh-llm-deepseek`
 * 的配置里有一个 `baseURL`，协议是 `chat/completions`（OpenAI 兼容）。
 * 也就是说：**只要本机起一个说同样协议的服务，DSH 就能用它，一行官方代码都不用改。**
 *
 * 而 `llama-server`（llama.cpp 自带）正好提供 `/v1/chat/completions`。
 *
 * ── 怎么让 DSH 用上它 ───────────────────────────────────────────
 * 读 `dsh-llm-deepseek` 的解析逻辑：
 *
 *     baseURL: config.baseURL ?? environment?.get("DEEPSEEK_BASE_URL")?.value ?? "https://api.deepseek.com"
 *
 * 而 `DEEPSEEK_BASE_URL` 在「**只能由启动环境提供**」的变量名单里
 * （同列的还有 `ALL_PROXY`、`CURL_CA_BUNDLE`、`GIT_*` —— 都是决定进程怎么启动、
 * 怎么上网的那一类）。所以我们**不改用户的 cordis.patch.yml**（那个文件 100 KB+），
 * 只要在 [io.dsh.fusion.engine.runtime.RuntimeEnv] 里把它注入子进程的继承环境即可。
 *
 * ── 为什么用 proot 启动 ─────────────────────────────────────────
 * Android 10+ 禁止 `execve` 应用数据目录里的文件。Node 的解法是塞进 jniLibs
 * 伪装成 `lib*.so`，但**运行期下载的东西进不了只读的 nativeLibraryDir**。
 * proot 用 loader 映射绕过 W^X，正好解决这个 —— 代价是慢一点，而对一个
 * 推理进程来说启动开销可以忽略。
 */
class LocalModelServer(
    private val paths: FusionPaths,
    private val runner: ProcessRunner,
    private val proot: ProotLauncher,
    private val installer: LlmRuntimeInstaller,
) {

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    private var process: Process? = null

    private val stopping = AtomicBoolean(false)

    val isRunning: Boolean get() = process?.isAlive == true

    /** 已下载的模型文件（可能不止一个，取清单里第一个存在的）。 */
    fun installedModels(): List<Pair<LocalModelCatalog.LocalModel, File>> =
        LocalModelCatalog.MODELS.mapNotNull { m ->
            val f = File(modelsDir, m.fileName)
            if (f.exists() && f.length() > 0) m to f else null
        }

    val modelsDir: File get() = File(paths.root, "models")

    fun modelDir(m: LocalModelCatalog.LocalModel): File = File(modelsDir, m.fileName)

    fun downloadedBytes(): Long {
        if (!modelsDir.isDirectory) return 0
        var total = 0L
        modelsDir.listFiles()?.forEach { if (it.isFile) total += it.length() }
        return total
    }

    // ---------------------------------------------------------------- 启动 / 停止

    /**
     * 启动推理服务。返回监听端口，失败返回 null。
     *
     * @param onLine 引擎输出回调（llama-server 的加载进度很有信息量）
     */
    fun start(model: File, onLine: ((String) -> Unit)? = null): Int? {
        if (isRunning) {
            LogBus.i(TAG, "推理服务已在运行（端口 $port）")
            return port
        }
        if (!installer.isInstalled()) {
            LogBus.e(TAG, "LLM 运行时还没装")
            return null
        }
        if (!model.exists()) {
            LogBus.e(TAG, "模型文件不存在：${model.absolutePath}")
            return null
        }

        // 端口复用 PortPicker：传 0 让操作系统分配，没有 TOCTOU 竞态。
        // 这里没得选「指定端口」——本地服务不需要可预测性。
        port = PortPicker.OS_ASSIGNED
        stopping.set(false)

        val guestBin = "${ProotLauncher.Guest.LLM}/usr/bin/llama-server"
        val guestModel = "${ProotLauncher.Guest.LLM}-models/${model.name}"
        val guestLib = "${ProotLauncher.Guest.LLM}/usr/lib"

        // 模型放在 /opt/llm 之外（models 目录），所以再绑一个
        val argv = listOf(
            guestBin,
            "-m", guestModel,
            "--host", "127.0.0.1",
            "--port", "0",                       // 让 llama-server 自己挑
            "-c", CONTEXT_WINDOW.toString(),
            "-t", threads().toString(),
            "--no-webui",                        // 不需要它自带的网页界面
        )
        val cmd = proot.command(argv, ProotLauncher.Guest.LLM)

        val env = HashMap<String, String>()
        // 关键：让动态链接器在 proot 里也能找到那 8 个 .so。
        // 这里**不需要** SONAME 归一化 —— proot 下文件可以叫任何名字。
        env["LD_LIBRARY_PATH"] = "$guestLib:/system/lib64"

        LogBus.i(TAG, "启动推理服务：${model.name}（${model.length() / 1048576} MB）")
        val proc = try {
            runner.start(cmd, env, paths.root)
        } catch (e: Exception) {
            LogBus.e(TAG, "启动失败：${e.message}")
            return null
        }
        process = proc

        // 从输出里抓真实端口 —— 和引擎用的是同一招
        val seen = java.util.concurrent.CompletableFuture<Int>()
        Thread({
            // pump 是 suspend 的（它要给转发回调留出让点），而这里是一个裸线程
            runBlocking {
                runner.pump(proc) { line ->
                    onLine?.invoke(line)
                    PORT_REGEX.find(line)?.let { m ->
                        if (!seen.isDone) seen.complete(m.groupValues[1].toIntOrNull() ?: 0)
                    }
                }
            }
            if (!seen.isDone) seen.complete(0)
        }, "llm-pump").apply { isDaemon = true; start() }

        val parsed = runCatching { seen.get(START_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(0)
        if (parsed <= 0) {
            LogBus.e(TAG, "推理服务没有报告端口（${START_TIMEOUT_SEC}s 内）")
            stop()
            return null
        }
        port = parsed
        LogBus.i(TAG, "推理服务已就绪：http://127.0.0.1:$port/v1")
        return port
    }

    fun stop() {
        stopping.set(true)
        val p = process ?: return
        runCatching {
            p.destroy()
            if (!p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
        port = 0
        LogBus.i(TAG, "推理服务已停止")
    }

    /** 一次真实调用，确认它真的在说 OpenAI 协议（不能只看端口通不通）。 */
    fun healthCheck(): Boolean {
        val p = port
        if (p <= 0) return false
        return runCatching {
            val conn = (URL("http://127.0.0.1:$p/v1/models").openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
                requestMethod = "GET"
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        }.getOrDefault(false)
    }

    /** DSH 要用的 baseURL。 */
    fun baseUrl(): String? = if (port > 0) "http://127.0.0.1:$port/v1" else null

    private fun threads(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)

    private companion object {
        const val TAG = "本地模型"

        /** llama-server 启动时会打印 `Listening on http://127.0.0.1:8080` 之类。 */
        val PORT_REGEX = Regex("""(?:listen|Listening|listening)[^\d]*(\d{2,5})""", RegexOption.IGNORE_CASE)

        const val START_TIMEOUT_SEC = 180L

        /**
         * 上下文窗口。
         *
         * 4096 是手机上比较稳的值：KV cache 随上下文线性增长，手机上开太大
         * 会直接触发系统 OOM 杀掉整个应用。而 DSH 的工具调用需要一定长度，
         * 太小又会让多轮对话频繁截断。
         */
        const val CONTEXT_WINDOW = 4096
    }
}
