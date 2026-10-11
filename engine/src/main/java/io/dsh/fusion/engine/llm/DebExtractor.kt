package io.dsh.fusion.engine.llm

import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.provision.TarExt
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * 从 `.deb` 里取出文件。
 *
 * deb 是 **ar 归档**，不是 tar —— 这一点很容易踩：直接当 tar 解会立刻失败，
 * 而错误信息只是「这可能不是 tar 归档」，看不出真正的原因。
 *
 * ar 的格式非常简单（没有索引、没有压缩）：
 *
 *     "!<arch>\n"                       全局头，8 字节
 *     每条成员：
 *       名称  16 字节（GNU 变体以 '/' 结尾，或 "name/" 形式）
 *       时间  12 字节（十进制）
 *       uid    6 字节
 *       gid    6 字节
 *       权限   8 字节（八进制）
 *       大小  10 字节（十进制）
 *       魔数   2 字节 = "`\n"
 *       数据  大小 字节，奇数长度会补一个 '\n' 对齐
 *
 * 所以这里自己解析就够了，不用引第三方库。
 */
object DebExtractor {

    /** 一个 ar 成员。 */
    private data class Member(val name: String, val offset: Long, val size: Long)

    /**
     * 解出 deb 的 `data.tar.*` 并释放到 [destDir]。
     *
     * @param onlyUnder 只解这一段（deb 里是 `data/data/com.termux/files/usr/`）
     * @return 解出的条目数
     */
    fun extractData(
        deb: File,
        destDir: File,
        onlyUnder: String = TERMUX_USR_PREFIX,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Int {
        val members = readIndex(deb)
        val data = members.firstOrNull { it.name.startsWith("data.tar") }
            ?: throw IOException("deb 里没有 data.tar.*：${members.joinToString { it.name }}")
        LogBus.i(TAG, "deb 成员：${members.joinToString { "${it.name}(${it.size}B)" }}")

        FileInputStream(deb).use { fis ->
            fis.channel.position(data.offset)
            val limited = LimitedStream(BufferedInputStream(fis, 1 shl 16), data.size, onProgress)
            // TarExt 按魔数自动判 xz / gzip / 裸 tar，所以 data.tar.xz 直接喂进去就行
            return TarExt.extractStream(
                source = limited,
                name = data.name,
                destDir = destDir,
                stripComponents = 0,
                onlyUnder = onlyUnder,
            )
        }
    }

    /** 读 ar 的成员表（不解数据）。 */
    private fun readIndex(file: File): List<Member> {
        val out = mutableListOf<Member>()
        FileInputStream(file).use { fis ->
            val global = ByteArray(8)
            if (fis.read(global) != 8 || String(global, StandardCharsets.ISO_8859_1) != "!<arch>\n") {
                throw IOException("不是 ar 归档：${file.name}")
            }
            var pos = 8L
            val header = ByteArray(60)
            while (true) {
                val read = readFully(fis, header)
                if (read < 60) break
                val text = String(header, StandardCharsets.ISO_8859_1)
                if (text.substring(58, 60) != "`\n") {
                    LogBus.w(TAG, "ar 成员头魔数不对（偏移 $pos），停止解析")
                    break
                }
                // 名称：GNU 变体用 '/' 结尾；BSD 变体可能带空格
                val name = text.substring(0, 16).trim().trimEnd('/')
                val size = text.substring(48, 58).trim().toLongOrNull() ?: break
                val dataStart = pos + 60
                out += Member(name, dataStart, size)
                // 成员数据按 2 字节对齐
                pos = dataStart + size + (size % 2)
                if (pos >= file.length()) break
                fis.channel.position(pos)
            }
        }
        return out
    }

    private fun readFully(fis: InputStream, buf: ByteArray): Int {
        var read = 0
        while (read < buf.size) {
            val r = fis.read(buf, read, buf.size - read)
            if (r <= 0) break
            read += r
        }
        return read
    }

    /** 只让读前 [limit] 字节的包装流 —— deb 成员之后还有别的成员，不能越过。 */
    private class LimitedStream(
        private val inner: InputStream,
        private val limit: Long,
        private val onProgress: ((Long, Long) -> Unit)?,
    ) : InputStream() {
        private var read = 0L
        private var lastReport = 0L

        override fun read(): Int {
            if (read >= limit) return -1
            val b = inner.read()
            if (b >= 0) tick(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read >= limit) return -1
            val want = minOf(len.toLong(), limit - read).toInt()
            val n = inner.read(b, off, want)
            if (n > 0) tick(n.toLong())
            return n
        }

        private fun tick(n: Long) {
            read += n
            val now = System.currentTimeMillis()
            if (now - lastReport > 300) {
                lastReport = now
                onProgress?.invoke(read, limit)
            }
        }
    }

    /** deb 里所有文件都在这层下面；我们只想要 `usr/` 那一层往上的部分。 */
    const val TERMUX_USR_PREFIX = "data/data/com.termux/files/usr/"
    private const val TAG = "deb"
}
