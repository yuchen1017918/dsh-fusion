package io.dsh.fusion.engine.provision

import io.dsh.fusion.engine.LogBus
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * zip 解包（语音模型等以 zip 分发）。
 *
 * 用 commons-compress 而不是 JDK 的 ZipInputStream：只有前者能读到
 * `unixMode`，而 Vosk 模型包里带着可执行位的文件（Kaldi 的二进制表）丢了这个位就跑不起来。
 */
object ZipExt {

    fun extract(archive: File, destDir: File, onEntry: ((String) -> Unit)? = null): Int {
        if (!destDir.exists()) destDir.mkdirs()
        val root = destDir.canonicalFile
        var count = 0

        ZipArchiveInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(destDir, entry.name)
                val canonical = runCatching { out.canonicalFile }.getOrNull()
                when {
                    canonical == null || !canonical.path.startsWith(root.path) ->
                        LogBus.w(TAG, "跳过越界条目 ${entry.name}")

                    entry.isDirectory -> out.mkdirs()

                    else -> {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos -> zip.copyTo(fos, 1 shl 16) }
                        val mode = entry.unixMode
                        if (mode and 0b001_000_000 != 0) out.setExecutable(true, false)
                    }
                }
                count++
                if (count % 500 == 0) onEntry?.invoke("已解开 $count 个条目")
            }
        }
        LogBus.i(TAG, "zip 解包完成 ${archive.name} -> ${destDir.absolutePath}（$count 条）")
        return count
    }

    private const val TAG = "解包"
}
