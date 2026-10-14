package io.dsh.fusion.installer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * 多渠道 + 断点续传下载器。
 *
 * ── 为什么这两件事必须一起做 ────────────────────────────────────
 * 手机上下 100 MB 的包，**中断是常态不是异常**：切后台、锁屏、地铁进隧道、
 * 换个 Wi-Fi。所以：
 *
 *   · **断点续传**：写到 `<name>.part`，下次带 `Range` 头接着下。
 *     没这个功能的话，用户断在第 90% 就要从头再来。
 *   · **多渠道路由**：国内直连 GitHub 经常只有几十 KB/s 甚至超时。
 *     一个渠道失败就换下一个，而不是让用户重试。
 *
 * 两个放在一起才成立：单一渠道 + 续传 = 渠道挂了就永远下不完；
 * 多渠道 + 不续传 = 每次换渠道都从 0 开始。
 *
 * ── 一个容易被做错的地方 ────────────────────────────────────────
 * 服务器**不一定**支持续传。如果它忽略了 `Range` 头、直接返回 200 全文，
 * 而我们继续往 `.part` 后面追加，就会得到一个「头部是旧数据、尾部是新数据」
 * 的损坏文件 —— 而且大小看起来是对的，只有 sha256 能发现。
 * 所以这里严格检查 `206 Partial Content`，见到 200 就**从头重写**。
 */
class ResumableDownloader(
    private val channels: List<Channel>,
    private val onLog: (String) -> Unit = {},
) {

    data class Progress(
        val received: Long,
        val total: Long,
        val bps: Long,
        val channel: String,
        val attempt: Int,
    ) {
        val percent: Int get() = if (total > 0) ((received * 100) / total).toInt() else 0
    }

    sealed class Result {
        data class Ok(val file: File, val channel: String) : Result()
        data class Failed(val message: String, val triedChannels: List<String>) : Result()
    }

    /**
     * 下载 [artifact] 到 [destDir]，按渠道优先级依次尝试，每个渠道内部支持续传。
     *
     * @param expectedSha256 非空时下载完成会校验；不匹配会删掉文件并返回失败
     */
    suspend fun download(
        catalog: ArtifactCatalog,
        artifact: Artifact,
        destDir: File,
        expectedSha256: String? = artifact.sha256.ifBlank { null },
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val name = artifact.fileName ?: return@withContext Result.Failed("清单里没有文件名", emptyList())
        destDir.mkdirs()
        val dest = File(destDir, name)
        val part = File(destDir, "$name.part")

        // 已经下好且校验通过 → 直接复用（重装场景）
        if (dest.exists() && expectedSha256 != null && sha256(dest).equals(expectedSha256, true)) {
            onLog("已有完整文件且校验通过，跳过下载")
            return@withContext Result.Ok(dest, "cache")
        }

        val tried = mutableListOf<String>()
        var lastError = "没有可用的下载渠道"

        for ((index, ch) in channels.withIndex()) {
            val url = catalog.urlOf(artifact, ch) ?: continue
            tried += ch.name
            onLog("尝试渠道 ${index + 1}/${channels.size}：${ch.name}")
            try {
                fetch(url, part, expectedSha256, ch.name, index + 1, onProgress)
                // 就位：先删旧文件再改名。保留 .part 到最后一步，
                // 这样中途失败下次还能续。
                if (dest.exists() && !dest.delete()) {
                    onLog("旧文件删不掉，直接覆盖")
                }
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                onLog("下载完成（${dest.length() / 1048576} MB，来自 ${ch.name}）")
                return@withContext Result.Ok(dest, ch.name)
            } catch (e: Exception) {
                // 取消要原样抛出去，不能当成「渠道失败」然后傻乎乎试下一个
                coroutineContext.ensureActive()
                lastError = "${ch.name}: ${e.message}"
                onLog("渠道 ${ch.name} 失败：${e.message}")
                if (expectedSha256 != null && part.exists() && !verify(part, expectedSha256)) {
                    // 校验不过说明这个渠道给的是坏数据，续传也没意义
                    onLog("该校验不通过，丢弃已下部分")
                    part.delete()
                }
            }
        }
        Result.Failed(lastError, tried)
    }

    private fun fetch(
        url: String,
        part: File,
        expectedSha256: String?,
        channelName: String,
        attempt: Int,
        onProgress: (Progress) -> Unit,
    ) {
        val existing = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }

        val code = conn.responseCode
        val resuming = code == HttpURLConnection.HTTP_PARTIAL  // 206

        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code")
        }

        // 服务器忽略了 Range、返回 200 全文 → 必须从头写，否则文件会坏
        val startAt = if (resuming) existing else 0L
        if (existing > 0 && !resuming) {
            onLog("服务器不支持续传（返回 $code），从 0 重新下载")
            part.delete()
        }

        val remaining = conn.contentLengthLong.takeIf { it > 0 } ?: -1
        val total = if (remaining > 0 && resuming) startAt + remaining else remaining

        // 整段包 try/finally 释放连接。
        //
        // 这不是洁癖：**下载中断正是这个类存在的理由**（切后台、锁屏、进隧道）。
        // 原来 `conn.disconnect()` 写在这段之后 —— 中断时 `input.read` 抛异常，
        // 那一行永远到不了，于是**每次中断都漏一条连接**。
        try {
        conn.inputStream.use { input ->
            RandomAccessFile(part, "rw").use { out ->
                out.seek(startAt)
                val buf = ByteArray(1 shl 16)
                var got = startAt
                var lastTick = 0L
                val t0 = System.currentTimeMillis()
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    got += n
                    val now = System.currentTimeMillis()
                    if (now - lastTick > 400) {
                        lastTick = now
                        val elapsed = (now - t0).coerceAtLeast(1)
                        val bps = ((got - startAt) * 1000) / elapsed
                        onProgress(Progress(got, total, bps, channelName, attempt))
                    }
                }
                onProgress(Progress(got, total, bps = 0, channel = channelName, attempt = attempt))
            }
        }
        } finally {
            conn.disconnect()
        }

        // 大小对不上就是没下完，抛出去让外层决定（续传 or 换渠道）
        if (total > 0 && part.length() < total) {
            throw IOException("下载不完整（${part.length()}/$total 字节），可续传")
        }
        if (expectedSha256 != null && !verify(part, expectedSha256)) {
            throw IOException("sha256 校验不通过")
        }
    }

    private fun verify(file: File, expected: String): Boolean =
        sha256(file).equals(expected, true)

    companion object {
        private const val UA = "DSH-Fusion-Installer/0.1 (+https://github.com/yuchen1017918/dsh-fusion)"

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
