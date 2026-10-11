package io.dsh.fusion.devicectl

import io.dsh.fusion.engine.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** 一条 shell 命令的结果。 */
sealed class ShellResult {
    data class Ok(val code: Int, val stdout: String, val stderr: String) : ShellResult() {
        val trimmed: String get() = stdout.trim().ifEmpty { stderr.trim() }
    }

    data class Failure(val message: String) : ShellResult()
}

/**
 * 三种取 shell 的路子，按「代价从低到高」排序，运行时自动挑可用的那条：
 *   Shizuku（免 root，用户授权一次） > Root（su） > App（自身 uid，权限有限但永不失败）。
 *
 * 这是社区两派（Shizuku 派 / Root 派）的合流点：谁在就用谁，都不在也不会崩。
 */
interface ShellBackend {
    val id: String
    val label: String
    fun available(): Boolean
    fun exec(command: String, timeoutMs: Long = 25_000): ShellResult
    fun execBytes(command: String, timeoutMs: Long = 25_000): ByteArray?
}

// ---------------------------------------------------------------- Shizuku

internal class ShizukuProcess(private val remote: moe.shizuku.server.IRemoteProcess) : Process() {

    private val stdin: java.io.OutputStream =
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream)
    private val stdout: InputStream =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
    private val stderr: InputStream =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)

    override fun getOutputStream(): java.io.OutputStream = stdin
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = stderr
    override fun waitFor(): Int = remote.waitFor()
    override fun exitValue(): Int = remote.exitValue()
    override fun destroy() { runCatching { remote.destroy() } }
    override fun isAlive(): Boolean = runCatching { remote.alive() }.getOrDefault(false)
}

class ShizukuBackend : ShellBackend {

    override val id = "shizuku"
    override val label = "Shizuku（免 root）"

    override fun available(): Boolean = runCatching {
        rikka.shizuku.Shizuku.pingBinder() &&
            rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun service(): moe.shizuku.server.IShizukuService? = runCatching {
        moe.shizuku.server.IShizukuService.Stub.asInterface(rikka.shizuku.Shizuku.getBinder())
    }.getOrNull()

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        val svc = service() ?: return ShellResult.Failure("Shizuku 服务未连接")
        val process = try {
            ShizukuProcess(svc.newProcess(arrayOf("sh", "-c", command), null, null))
        } catch (e: Exception) {
            return ShellResult.Failure("Shizuku newProcess 失败：${e.message}")
        }
        return ShellPump.run(process, timeoutMs)
    }

    override fun execBytes(command: String, timeoutMs: Long): ByteArray? {
        val svc = service() ?: return null
        return try {
            val process = ShizukuProcess(svc.newProcess(arrayOf("sh", "-c", command), null, null))
            ShellPump.readAll(process.inputStream, timeoutMs)
        } catch (e: Exception) {
            LogBus.e(TAG, "Shizuku 取字节流失败：${e.message}")
            null
        }
    }

    private companion object { const val TAG = "Shizuku" }
}

// ---------------------------------------------------------------- Root

class RootBackend : ShellBackend {

    override val id = "root"
    override val label = "Root（su）"

    private var cached: Boolean? = null

    override fun available(): Boolean {
        cached?.let { return it }
        val ok = runCatching {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            text.contains("uid=0")
        }.getOrDefault(false)
        cached = ok
        return ok
    }

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        val process = runCatching { ProcessBuilder("su", "-c", command).redirectErrorStream(false).start() }
            .getOrElse { return ShellResult.Failure("su 启动失败：${it.message}") }
        return ShellPump.run(process, timeoutMs)
    }

    override fun execBytes(command: String, timeoutMs: Long): ByteArray? = runCatching {
        val process = ProcessBuilder("su", "-c", command).start()
        ShellPump.readAll(process.inputStream, timeoutMs)
    }.getOrNull()
}

// ---------------------------------------------------------------- 应用自身

class LocalBackend : ShellBackend {

    override val id = "app"
    override val label = "应用自身权限"

    override fun available(): Boolean = true

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        val process = runCatching { ProcessBuilder("sh", "-c", command).start() }
            .getOrElse { return ShellResult.Failure("sh 启动失败：${it.message}") }
        return ShellPump.run(process, timeoutMs)
    }

    override fun execBytes(command: String, timeoutMs: Long): ByteArray? = runCatching {
        ShellPump.readAll(ProcessBuilder("sh", "-c", command).start().inputStream, timeoutMs)
    }.getOrNull()
}

// ---------------------------------------------------------------- 泵

internal object ShellPump {

    fun run(process: Process, timeoutMs: Long): ShellResult {
        val out = StringBuilder()
        val err = StringBuilder()
        var code = -1

        val tOut = Thread { runCatching { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
        val tErr = Thread { runCatching { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } } }
        val tWait = Thread { code = runCatching { process.waitFor() }.getOrDefault(-1) }
        tOut.isDaemon = true; tErr.isDaemon = true; tWait.isDaemon = true
        tOut.start(); tErr.start(); tWait.start()

        tWait.join(timeoutMs)
        if (tWait.isAlive) {
            runCatching { process.destroy() }
            tWait.join(1500)
            return ShellResult.Failure("命令超时（${timeoutMs}ms）：${out.toString().take(400)}")
        }
        tOut.join(2000)
        tErr.join(2000)
        return ShellResult.Ok(code, out.toString(), err.toString())
    }

    fun readAll(stream: InputStream, timeoutMs: Long): ByteArray? {
        var data: ByteArray? = null
        val t = Thread {
            runCatching {
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(1 shl 16)
                while (true) {
                    val n = stream.read(chunk)
                    if (n <= 0) break
                    buffer.write(chunk, 0, n)
                    if (buffer.size() > MAX_BYTES) break
                }
                data = buffer.toByteArray()
            }
        }
        t.isDaemon = true
        t.start()
        t.join(timeoutMs)
        return data
    }

    private const val MAX_BYTES = 48 shl 20
}

// ---------------------------------------------------------------- 选择器

class ShellRouter {

    val shizuku = ShizukuBackend()
    val root = RootBackend()
    val local = LocalBackend()

    /** 按可用性排序的全部通道。 */
    fun candidates(): List<ShellBackend> = listOf(shizuku, root, local).filter { runCatching { it.available() }.getOrDefault(false) }

    /** 挑当前最好的一条；都不可用时退回 app 自身。 */
    fun best(): ShellBackend = candidates().firstOrNull() ?: local

    /** 能力最强的那条（做需要真正系统权限的事时用）。 */
    fun privileged(): ShellBackend? = candidates().firstOrNull { it.id != "app" }

    suspend fun exec(command: String, timeoutMs: Long = 25_000): ShellResult = withContext(Dispatchers.IO) {
        val backend = best()
        LogBus.t("Shell", "[${backend.id}] $command")
        backend.exec(command, timeoutMs)
    }

    fun describe(): List<String> = listOf(shizuku, root, local).map { backend ->
        "${backend.label}: ${if (runCatching { backend.available() }.getOrDefault(false)) "可用" else "不可用"}"
    }
}
