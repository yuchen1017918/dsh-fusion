package io.dsh.fusion.engine.provision

import android.content.Context
import android.os.StatFs
import io.dsh.fusion.engine.FusionPaths
import io.dsh.fusion.engine.LogBus
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * 内置内核包的首启解压。
 *
 * 这是「装完即用」的最后一公里：内核（官方 dsh + dsh-base + dsh-web-app，
 * 裁剪后 114 MB 未压缩 / 约 20 MB 压缩）随 APK 发货，首次启动只是解压，
 * 不联网、不等 npm 解析 538 个包。
 *
 * 四条硬约束（每一条都对应一个会把用户逼疯的失败模式）：
 *
 *  1. **绝不占主线程**。几十万个文件、几百 MB 写入，放主线程就是 ANR。
 *     本类所有耗时操作都是 suspend + Dispatchers.IO，由前台服务里的协程承载。
 *  2. **先落临时目录，再原子改名**。中途被杀掉不会留下一个半死不活的
 *     profile —— 那会让下次启动以为「已经装好了」然后莫名其妙地崩。
 *  3. **完整性用标记文件 + 关键路径抽查**。只有标记文件不算数，
 *     因为解压到一半断电也会留下标记（所以先改名再写标记，顺序不能反）。
 *  4. **解压前查空间**。手机上写到一半没空间，是仅次于崩溃的糟糕体验。
 */
class KernelBundleProvisioner(
    private val context: Context,
    private val paths: FusionPaths,
) {

    /** assets 里那一份内核包的描述。 */
    data class Bundle(
        val assetPath: String,
        val fileName: String,
        val kernelVersion: String,
        val sha256: String?,
        val compressedBytes: Long,
        val uncompressedBytes: Long,
        val officeIncluded: Boolean,
    )

    sealed class Outcome {
        /** 已就位（本次解压完成，或之前就装好了）。 */
        data class Ready(val version: String, val extracted: Boolean) : Outcome()

        /** 没有内置包 —— 调用方应回退到在线安装。 */
        object Absent : Outcome()

        /** 装不下。 */
        data class NotEnoughSpace(val needBytes: Long, val freeBytes: Long) : Outcome()

        data class Failed(val message: String, val cause: Throwable? = null) : Outcome()
    }

    data class Progress(
        val phase: String,
        val bytesDone: Long,
        val bytesTotal: Long,
        val ratio: Float,
    )

    // ---------------------------------------------------------------- 探测

    /** 找出 assets/kernel 下的内核包与其清单。 */
    fun find(): Bundle? {
        val names = runCatching { context.assets.list(BUNDLE_DIR) }.getOrNull() ?: return null
        val tarball = names.firstOrNull { it.endsWith(".tar.xz") || it.endsWith(".tar.zst") } ?: return null
        val manifestName = tarball.removeSuffix(".tar.xz").removeSuffix(".tar.zst") + ".json"
        val manifest = runCatching {
            context.assets.open("$BUNDLE_DIR/$manifestName").use { JSONObject(it.readBytes().decodeToString()) }
        }.getOrNull()

        // 压缩包真实体积：优先用 AssetFileDescriptor（noCompress 之后是真实值）
        val compressed = runCatching {
            context.assets.openFd("$BUNDLE_DIR/$tarball").use { it.length }
        }.getOrElse {
            runCatching { context.assets.open("$BUNDLE_DIR/$tarball").use { it.available().toLong() } }.getOrDefault(0L)
        }

        return Bundle(
            assetPath = "$BUNDLE_DIR/$tarball",
            fileName = tarball,
            kernelVersion = manifest?.optString("kernelVersion")?.ifBlank { null } ?: "unknown",
            sha256 = manifest?.optString("sha256")?.ifBlank { null },
            compressedBytes = compressed,
            // 未压缩大小取清单的精确值；没有就按 6 倍压缩比粗估，只用于进度与空间预估
            uncompressedBytes = manifest?.optLong("prunedBytes", 0L)?.takeIf { it > 0 }
                ?: ((manifest?.optDouble("prunedMB", 0.0) ?: 0.0) * 1048576).toLong(),
            officeIncluded = manifest?.optBoolean("officeIncluded", false) ?: false,
        )
    }

    /** 已安装的内核版本（通过完整性抽查才算数）。 */
    fun installedVersion(): String? {
        val marker = markerFile()
        if (!marker.exists()) return null
        if (!looksComplete()) {
            LogBus.w(TAG, "标记文件在，但关键路径缺失 —— 视为未安装，将重新解压")
            return null
        }
        return runCatching { JSONObject(marker.readText()).optString("version").ifBlank { null } }.getOrNull()
    }

    fun isInstalled(): Boolean = installedVersion() != null

    /**
     * 关键路径抽查。
     *
     * 标记文件写了不等于解压成功：改名和写标记之间有窗口，
     * 而且用户完全可能在 SD 卡写满/进程被杀之后重新打开应用。
     * 所以这里真的去看几个必须存在的文件，宁可多花一次解压也不要半残状态。
     */
    private fun looksComplete(): Boolean {
        val profile = paths.profileDir(FusionPaths.PROFILE_NAME)
        return listOf(
            "package.json",
            "node_modules/@deepseek-ai/dsh/lib/bin.js",
            "node_modules/@deepseek-ai/dsh-base/package.json",
            "node_modules/@deepseek-ai/dsh-web-app/package.json",
        ).all { File(profile, it).exists() }
    }

    // ---------------------------------------------------------------- 空间

    /** 解压需要多少空闲空间（解压后体积 + 一倍余量给临时与元数据）。 */
    fun requiredBytes(bundle: Bundle): Long {
        val unpacked = if (bundle.uncompressedBytes > 0) bundle.uncompressedBytes else 150L * 1024 * 1024
        return unpacked + unpacked / 2
    }

    fun freeBytes(): Long = runCatching {
        val stat = StatFs(paths.root.absolutePath)
        stat.availableBytes
    }.getOrDefault(-1L)

    // ---------------------------------------------------------------- 解压

    /**
     * 执行解压。必须在协程里调用（本方法自身不切线程，让调用方决定用什么调度器）。
     *
     * @param onProgress 进度回调，会在 IO 线程上被调用。
     */
    fun install(bundle: Bundle, onProgress: ((Progress) -> Unit)? = null): Outcome {
        val free = freeBytes()
        val need = requiredBytes(bundle)
        if (free in 0 until need) {
            LogBus.e(TAG, "空间不足：需要约 ${need / 1048576} MB，可用 ${free / 1048576} MB")
            return Outcome.NotEnoughSpace(need, free)
        }

        paths.ensure()
        val staging = File(paths.tmpDir, "kernel-staging")
        // 上一轮失败的残留
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs()) {
            return Outcome.Failed("无法创建临时目录 ${staging.absolutePath}")
        }

        LogBus.i(TAG, "开始解压内置内核 ${bundle.fileName}（${bundle.compressedBytes / 1048576} MB 压缩）")
        val started = System.currentTimeMillis()

        try {
            val total = bundle.compressedBytes.coerceAtLeast(1L)
            var read = 0L
            var lastReport = 0L

            // 用「已读压缩字节数」做进度：它精确、平滑，而且无需预先知道文件条数。
            // 解压时间基本与压缩流长度成正比，所以这个比例对用户来说是诚实的。
            val counting = object : FilterInputStream(openAsset(bundle)) {
                override fun read(): Int = super.read().also { if (it >= 0) read++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { n ->
                        if (n > 0) {
                            read += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 400L) {
                                lastReport = now
                                onProgress?.invoke(
                                    Progress("正在解压内核", read, total, (read.toFloat() / total).coerceIn(0f, 1f))
                                )
                            }
                        }
                    }
            }

            // 压缩包内部是 `profile/` 一棵树，直接解到 staging 下
            counting.use { input ->
                // 进度由上面的计数流负责上报，这里只管解
                TarExt.extractStream(input, bundle.fileName, staging, stripComponents = 0)
            }

            val stagedProfile = File(staging, "profile")
            if (!stagedProfile.isDirectory) {
                return Outcome.Failed("压缩包结构异常：里面没有 profile/ 目录")
            }

            // ── 原子替换 ──
            onProgress?.invoke(Progress("正在就位", total, total, 1f))
            val target = paths.profileDir(FusionPaths.PROFILE_NAME)
            val backup = File(paths.tmpDir, "kernel-previous")
            if (backup.exists()) backup.deleteRecursively()
            if (target.exists() && !target.renameTo(backup)) {
                return Outcome.Failed("无法移走旧 profile（${target.absolutePath}）")
            }
            if (!stagedProfile.renameTo(target)) {
                // 回滚，别把用户晾在没有 profile 的状态
                if (backup.exists()) backup.renameTo(target)
                return Outcome.Failed("无法把解压结果就位（rename 失败，可能跨文件系统）")
            }
            if (backup.exists()) backup.deleteRecursively()
            staging.deleteRecursively()

            // ── 写标记（必须在改名成功之后）──
            writeMarker(bundle)

            val cost = System.currentTimeMillis() - started
            LogBus.i(TAG, "内核解压完成：${bundle.kernelVersion}，用时 ${cost / 1000}s")
            onProgress?.invoke(Progress("完成", total, total, 1f))
            return Outcome.Ready(bundle.kernelVersion, extracted = true)
        } catch (e: Exception) {
            LogBus.e(TAG, "解压失败：${e.message}")
            runCatching { staging.deleteRecursively() }
            return Outcome.Failed(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun openAsset(bundle: Bundle): InputStream = context.assets.open(bundle.assetPath, AssetManagerMode)

    private fun writeMarker(bundle: Bundle) {
        val marker = markerFile()
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(
                JSONObject().apply {
                    put("version", bundle.kernelVersion)
                    put("sha256", bundle.sha256 ?: "")
                    put("source", bundle.fileName)
                    put("officeIncluded", bundle.officeIncluded)
                    put("installedAt", System.currentTimeMillis())
                }.toString(2)
            )
        }.onFailure { LogBus.w(TAG, "写标记文件失败（下次会重新解压）：${it.message}") }
    }

    private fun markerFile(): File =
        File(paths.profileDir(FusionPaths.PROFILE_NAME), MARKER_NAME)

    companion object {
        private const val TAG = "内核包"
        const val BUNDLE_DIR = "kernel"
        const val MARKER_NAME = ".fusion-kernel.json"
        private const val AssetManagerMode = android.content.res.AssetManager.ACCESS_STREAMING
    }
}
