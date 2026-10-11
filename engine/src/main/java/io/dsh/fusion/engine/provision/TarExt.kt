package io.dsh.fusion.engine.provision

import io.dsh.fusion.engine.LogBus
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.PushbackInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

/**
 * tar / tar.gz 解包 —— Ubuntu rootfs 与 npm 安装包都靠它。
 *
 * 必须自己处理软链接与可执行位：Ubuntu rootfs 里 /usr/bin 大半是符号链接，
 * 丢掉它们 apt 会直接崩。/ 硬链接同理。
 */
object TarExt {

    class ExtractException(message: String) : Exception(message)

    fun extract(
        archive: File,
        destDir: File,
        stripComponents: Int = 0,
        onEntry: ((String) -> Unit)? = null,
    ): Int {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw ExtractException("无法创建目录 ${destDir.absolutePath}")
        }
        val destRoot = destDir.canonicalFile
        val gz = archive.name.endsWith(".gz", true) || archive.name.endsWith(".tgz", true)

        LogBus.i(TAG, "解包 ${archive.name} -> ${destDir.absolutePath}")
        return openArchive(archive, gz).use { tar -> drain(tar, destDir, destRoot, stripComponents, onEntry) }
    }

    /** 真正干活的那个循环：两个入口共用。 */
    private fun drain(
        tar: TarArchiveInputStream,
        destDir: File,
        destRoot: File,
        stripComponents: Int,
        onEntry: ((String) -> Unit)?,
    ): Int {
        var count = 0
        var lastReport = 0L
        val started = System.currentTimeMillis()
        run {
            var entry = tar.nextEntry
            while (entry != null) {
                val raw = entry.name.trimStart('/')
                val rel = strip(raw, stripComponents)
                if (rel.isNotEmpty()) {
                    val out = File(destDir, rel)
                    val canonical = try {
                        out.canonicalFile
                    } catch (e: IOException) {
                        null
                    }
                    if (canonical == null || !canonical.path.startsWith(destRoot.path)) {
                        LogBus.w(TAG, "跳过越界条目: $raw")
                    } else {
                        when {
                            entry.isDirectory -> out.mkdirs()

                            entry.isSymbolicLink -> {
                                out.parentFile?.mkdirs()
                                if (out.exists()) out.delete()
                                runCatching {
                                    Files.createSymbolicLink(Paths.get(out.absolutePath), Paths.get(entry.linkName))
                                }.onFailure { LogBus.t(TAG, "软链失败 ${rel}: ${it.message}") }
                            }

                            entry.isLink -> {
                                out.parentFile?.mkdirs()
                                val target = File(destDir, strip(entry.linkName.trimStart('/'), stripComponents))
                                if (out.exists()) out.delete()
                                runCatching { Files.createLink(Paths.get(out.absolutePath), Paths.get(target.absolutePath)) }
                                    .onFailure { LogBus.t(TAG, "硬链失败 ${rel}: ${it.message}") }
                            }

                            else -> {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { fos -> tar.copyTo(fos) }
                                val mode = entry.mode
                                if (mode and 0b001_000_000 != 0 || mode and 0b000_001_000 != 0) {
                                    out.setExecutable(true, false)
                                }
                            }
                        }
                    }
                }
                count++
                val now = System.currentTimeMillis()
                if (now - lastReport > 3000L) {
                    lastReport = now
                    onEntry?.invoke("已解开 $count 个条目（${rel.ifEmpty { raw }}）")
                }
                entry = tar.nextEntry
            }
        }
        LogBus.i(TAG, "解包完成 ${destDir.absolutePath}，共 $count 条，用时 ${(System.currentTimeMillis() - started) / 1000}s")
        return count
    }

    private fun openArchive(archive: File, gz: Boolean): TarArchiveInputStream {
        val base = BufferedInputStream(archive.inputStream(), 1 shl 16)
        return if (gz) TarArchiveInputStream(GzipCompressorInputStream(base, true)) else TarArchiveInputStream(base)
    }

    /**
     * 从**流**解包。
     *
     * 需要它是因为内置内核包躺在 APK 的 assets 里，只能流式读，
     * 没有可供 seek 的真实文件路径。
     *
     * 压缩格式靠**魔数**判断而不是靠扩展名：文件名是人起的，魔数是事实。
     *   xz   : FD 37 7A 58 5A 00
     *   gzip : 1F 8B
     * 其余一律当裸 tar。
     */
    fun extractStream(
        source: java.io.InputStream,
        name: String,
        destDir: File,
        stripComponents: Int = 0,
        onEntry: ((String) -> Unit)? = null,
    ): Int {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw ExtractException("无法创建目录 ${destDir.absolutePath}")
        }
        val destRoot = destDir.canonicalFile
        val base = BufferedInputStream(source, 1 shl 16)
        val head = PushbackInputStream(base, 6)
        val magic = ByteArray(6)
        val read = head.read(magic)
        if (read > 0) head.unread(magic, 0, read)

        val tar = when {
            read >= 6 && magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() &&
                magic[2] == 0x7A.toByte() && magic[3] == 0x58.toByte() &&
                magic[4] == 0x5A.toByte() && magic[5] == 0x00.toByte() ->
                TarArchiveInputStream(XZCompressorInputStream(head, true))

            read >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() ->
                TarArchiveInputStream(GzipCompressorInputStream(head, true))

            else -> TarArchiveInputStream(head)
        }
        LogBus.i(TAG, "流式解包 $name -> ${destDir.absolutePath}（格式首字节 ${magic.take(read).joinToString(" ") { "%02X".format(it) }}）")
        return drain(tar, destDir, destRoot, stripComponents, onEntry)
    }

    private fun strip(path: String, components: Int): String {
        if (components <= 0) return path
        var rest = path
        repeat(components) {
            val idx = rest.indexOf('/')
            if (idx < 0) {
                rest = ""
                return@repeat
            }
            rest = rest.substring(idx + 1)
        }
        return rest
    }

    private const val TAG = "解包"
}
