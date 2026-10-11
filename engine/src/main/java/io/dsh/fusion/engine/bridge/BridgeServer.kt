package io.dsh.fusion.engine.bridge

import io.dsh.fusion.engine.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * 手写的回环 HTTP 桥。
 *
 * 为什么不用现成框架：这个桥必须
 *   1) 只绑 127.0.0.1，绝不出设备（社区里被反复强调的隐私底线）；
 *   2) 零第三方依赖，任何插件冲突都影响不到它；
 *   3) 由 dsh 侧的 `android_*` 工具插件反向调用 —— 手机能力就是这样交到模型手上的。
 *
 * 协议：POST /invoke  {"id":"android_tap","args":{...}}
 * 鉴权：X-Fusion-Token 头，或 ?token= 查询参数。
 */
class BridgeServer(
    private val port: Int,
    private val token: String,
    private val router: suspend (method: String, path: String, query: Map<String, String>, body: JSONObject?) -> JSONObject,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 并发闸。
     *
     * 模型完全可能一口气并发调十几个设备工具（截屏 + 读屏 + 点击……），
     * 而每个都可能起子进程。Android 的幻影进程杀手在子进程超阈值时会把整个
     * 进程组静默杀掉 —— 所以这里必须有个上限，宁可让调用方排队或失败，
     * 也不能让引擎被系统清理。
     */
    private val gate = Semaphore(MAX_CONCURRENT)

    @Volatile
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null

    val isRunning: Boolean get() = server?.isClosed == false

    fun start() {
        if (isRunning) return
        val ss = ServerSocket(port, 24, InetAddress.getByName("127.0.0.1"))
        ss.reuseAddress = true
        server = ss
        acceptJob = scope.launch {
            LogBus.i(TAG, "能力桥已监听 127.0.0.1:$port")
            while (!ss.isClosed) {
                val socket = try {
                    ss.accept()
                } catch (e: Exception) {
                    if (!ss.isClosed) LogBus.w(TAG, "accept 中断: ${e.message}")
                    break
                }
                launch { handle(socket) }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        acceptJob?.cancel()
        acceptJob = null
    }

    private suspend fun handle(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use { s ->
            runCatching { s.soTimeout = 20000 }
            try {
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input)
                if (requestLine.isNullOrBlank()) return@use
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    respond(s, 400, err("bad request line"))
                    return@use
                }
                val method = parts[0].uppercase()
                val target = parts[1]
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))

                var contentLength = 0
                var chunked = false
                var presented: String? = null
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx <= 0) continue
                    val key = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    when (key) {
                        "content-length" -> contentLength = value.toIntOrNull() ?: 0
                        // Node 的 fetch 会带 Content-Length，但 curl --data-binary @- 之类
                        // 的客户端会用 chunked。手工排查时天天用 curl，所以必须支持。
                        "transfer-encoding" -> if (value.contains("chunked", true)) chunked = true
                        "x-fusion-token" -> presented = value
                        "authorization" -> presented = value.removePrefix("Bearer").trim()
                    }
                }

                val bodyText = when {
                    chunked -> readChunked(input)
                    contentLength in 1..MAX_BODY_BYTES -> readN(input, contentLength)
                    else -> ""
                }
                val auth = presented ?: query["token"]
                if (auth != token) {
                    LogBus.w(TAG, "拒绝 $method $path：token 不匹配")
                    respond(s, 403, err("invalid token"))
                    return@use
                }

                val body = if (bodyText.isBlank()) null else runCatching { JSONObject(bodyText) }.getOrNull()
                // 超过并发上限就直接拒绝，不进队列：排队会让模型的等待时间不可预测，
                // 而「现在不行」这种明确回答它反而好处理
                if (gate.tryAcquire().not()) {
                    LogBus.w(TAG, "$method $path 被拒：并发已达上限 $MAX_CONCURRENT")
                    respond(s, 200, err("桥正忙（并发上限 $MAX_CONCURRENT），稍后重试"))
                    return@use
                }
                val result = try {
                    gate.withPermit { router(method, path, query, body) }
                } catch (e: Exception) {
                    LogBus.e(TAG, "$method $path 失败: ${e.message}")
                    err(e.message ?: e.javaClass.simpleName)
                } finally {
                    gate.release()
                }
                respond(s, 200, result)
            } catch (e: Exception) {
                LogBus.e(TAG, "连接异常: ${e.message}")
            }
        }
    }

    private fun respond(socket: Socket, code: Int, payload: JSONObject) {
        val body = payload.toString().toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(if (code == 200) "OK" else "ERROR").append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(header)
        out.write(body)
        out.flush()
    }

    private fun err(message: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", message)
    }

    private fun readLine(input: InputStream): String? {
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.isEmpty()) null else buf.toString()
            if (b == '\n'.code) return buf.toString().trimEnd('\r')
            buf.append(b.toChar())
            if (buf.length > 16384) return buf.toString()
        }
    }

    /**
     * 读 chunked 请求体。
     *
     * 注意先收字节、最后一次性按 UTF-8 解码：如果逐块解码，
     * 一个汉字被切在块边界上就会变成乱码 —— 而中文参数太常见了。
     */
    private fun readChunked(input: InputStream): String {
        val buffer = java.io.ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input)?.trim() ?: break
            if (sizeLine.isEmpty()) continue
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size <= 0) {
                readLine(input) // 结束块后面的 CRLF
                break
            }
            val chunk = readBytes(input, size)
            buffer.write(chunk)
            readLine(input) // 每个块后面的 CRLF
            if (buffer.size() > MAX_BODY_BYTES) {
                LogBus.w(TAG, "chunked 请求体超过上限 ${MAX_BODY_BYTES / 1048576} MB，已截断")
                break
            }
        }
        return String(buffer.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun readBytes(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r <= 0) break
            read += r
        }
        return if (read == n) buf else buf.copyOf(read)
    }

    private fun readN(input: InputStream, n: Int): String {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r <= 0) break
            read += r
        }
        return String(buf, 0, read, StandardCharsets.UTF_8)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            out[java.net.URLDecoder.decode(k, "UTF-8")] = java.net.URLDecoder.decode(v, "UTF-8")
        }
        return out
    }

    companion object {
        private const val TAG = "Bridge"

        /** 同时处理的请求上限。见 gate 的注释：这个数字是给幻影进程杀手留的余量。 */
        private const val MAX_CONCURRENT = 6

        /** 请求体上限 8 MB（截图走响应体，不走请求体，所以不用给太大）。 */
        private const val MAX_BODY_BYTES = 8 shl 20
    }
}
