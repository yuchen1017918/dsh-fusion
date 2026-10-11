package io.dsh.fusion.engine.provision

import io.dsh.fusion.engine.LogBus
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 多镜像 + 断点续传 + sha256 校验的下载器。
 *
 * 社区的共识是：运行时动辄几百 MB，国内直连 GitHub 基本等于不可用。
 * 所以这里镜像列表是可配置的第一公民，任何一个源挂掉都自动跳到下一个，
 * 半途断网也不会从 0 重来（.part 文件保留 + Range 续传）。
 */
class HttpDownloader(private val mirrors: List<String>) {

    data class Progress(
        val received: Long,
        val total: Long,
        val bps: Long,
        val attempt: Int,
        val mirror: String,
        val label: String,
    ) {
        val percent: Int get() = if (total > 0) ((received * 100) / total).toInt() else 0
    }

    class DownloadException(message: String) : Exception(message)

    /** path 是相对路径，例如 `ubuntu-base-22.04.5-base-arm64.tar.gz`。 */
    fun to(
        dest: File,
        path: String,
        sha256: String? = null,
        label: String = path,
        onProgress: ((Progress) -> Unit)? = null,
    ): File {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")

        if (dest.exists() && sha256 != null && sha256(dest).equals(sha256, true)) {
            LogBus.i(TAG, "$label 已存在且校验通过，跳过下载")
            return dest
        }

        var lastError: String? = null
        for ((index, base) in mirrors.withIndex()) {
            val url = joinUrl(base, path)
            try {
                LogBus.i(TAG, "尝试镜像 #${index + 1}: $url")
                fetch(url, part, sha256, index + 1, label, onProgress)
                if (dest.exists() && !dest.delete()) {
                    LogBus.w(TAG, "旧文件删不掉，直接覆盖: ${dest.absolutePath}")
                }
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    if (!part.delete()) LogBus.w(TAG, "临时文件残留: ${part.name}")
                }
                LogBus.i(TAG, "✔ $label  ${dest.length() / 1024} KB  -> ${dest.absolutePath}")
                return dest
            } catch (e: Exception) {
                lastError = e.message
                LogBus.w(TAG, "镜像 #${index + 1} 失败：${e.message}")
            }
        }
        throw DownloadException("所有镜像都无法提供 $label；最后一次错误：$lastError")
    }

    private fun fetch(
        url: String,
        part: File,
        sha256: String?,
        attempt: Int,
        label: String,
        onProgress: ((Progress) -> Unit)?,
    ) {
        val existing = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }

        val code = conn.responseCode
        if (code !in 200..299) throw DownloadException("HTTP $code")

        val resuming = code == 206
        if (!resuming && existing > 0) {
            if (!part.delete()) throw DownloadException("无法重置半成品文件")
        }
        val total = if (resuming) existing + conn.contentLength.toLong() else conn.contentLength.toLong()
        var received = if (resuming) existing else 0L
        val started = System.currentTimeMillis()
        var lastReport = 0L

        FileOutputStream(part, resuming).use { out ->
            conn.inputStream.use { input ->
                val buf = ByteArray(96 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    received += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 400L) {
                        lastReport = now
                        val elapsed = (now - started).coerceAtLeast(1L)
                        onProgress?.invoke(
                            Progress(received, total, (received - existing) * 1000 / elapsed, attempt, url, label)
                        )
                    }
                }
            }
        }

        if (total > 0 && received < total) {
            throw DownloadException("传输不完整：$received/$total，已保留断点，可从下一镜像续传")
        }
        if (sha256 != null) {
            val actual = sha256(part)
            if (!actual.equals(sha256, ignoreCase = true)) {
                throw DownloadException("sha256 校验失败：期望 ${sha256.take(16)}…，实得 ${actual.take(16)}…")
            }
        }
    }

    companion object {
        private const val TAG = "下载"
        private const val UA = "DshFusion/1.0 (Android)"

        fun joinUrl(base: String, path: String): String {
            val b = if (base.endsWith("/")) base else "$base/"
            return b + path.trimStart('/')
        }

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
