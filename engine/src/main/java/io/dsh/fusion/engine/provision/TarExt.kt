package io.dsh.fusion.engine.provision

import io.dsh.fusion.engine.LogBus
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
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

        var count = 0
        var lastReport = 0L
        val started = System.currentTimeMillis()

        openArchive(archive, gz).use { tar ->
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
        LogBus.i(TAG, "解包完成 ${archive.name} -> ${destDir.absolutePath}，共 $count 条，用时 ${(System.currentTimeMillis() - started) / 1000}s")
        return count
    }

    private fun openArchive(archive: File, gz: Boolean): TarArchiveInputStream {
        val base = BufferedInputStream(archive.inputStream(), 1 shl 16)
        return if (gz) TarArchiveInputStream(GzipCompressorInputStream(base, true)) else TarArchiveInputStream(base)
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
