package io.dsh.fusion.engine.runtime

import io.dsh.fusion.engine.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/** 起进程 + 把 stdout/stderr 汇进 LogBus。 */
class ProcessRunner(private val tag: String) {

    class StartFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun start(
        command: List<String>,
        env: Map<String, String>,
        cwd: File?,
        quiet: Boolean = false,
    ): Process {
        if (!quiet) LogBus.i(tag, "$ ${command.joinToString(" ")}")
        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(true)
        if (cwd != null && cwd.isDirectory) pb.directory(cwd)
        pb.environment().putAll(env)
        return try {
            pb.start()
        } catch (e: Exception) {
            throw StartFailure(
                "无法启动 ${command.firstOrNull()}: ${e.message}（Android 10+ 只允许执行 nativeLibraryDir 里的文件）",
                e,
            )
        }
    }

    /** 边读边转发，返回退出码。 */
    suspend fun pump(process: Process, forward: ((String) -> Unit)? = null): Int =
        withContext(Dispatchers.IO) {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        LogBus.i(tag, line)
                        forward?.invoke(line)
                    }
                }
            } catch (e: Exception) {
                LogBus.w(tag, "读取输出中断: ${e.message}")
            }
            try {
                process.waitFor()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                -1
            }
        }

    suspend fun run(
        command: List<String>,
        env: Map<String, String>,
        cwd: File? = null,
        forward: ((String) -> Unit)? = null,
    ): Int {
        val p = start(command, env, cwd)
        return pump(p, forward)
    }
}
