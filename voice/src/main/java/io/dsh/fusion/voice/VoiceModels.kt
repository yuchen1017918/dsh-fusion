package io.dsh.fusion.voice

import io.dsh.fusion.engine.FusionPaths
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.provision.HttpDownloader
import io.dsh.fusion.engine.provision.ZipExt
import java.io.File

/**
 * 离线唤醒模型。
 *
 * 「离线」这两个字是有代价的：模型不能只存在于云端。所以模型首次用时按需下载、
 * 落盘后**永不联网**，识别全在本地神经网络里跑 —— 麦克风数据一步都不出设备。
 */
object VoiceModels {

    data class ModelInfo(
        val id: String,
        val label: String,
        val fileName: String,
        val approxBytes: Long,
        val description: String,
    )

    val CHINESE_SMALL = ModelInfo(
        id = "vosk-model-small-cn-0.22",
        label = "中文小型模型（推荐）",
        fileName = "vosk-model-small-cn-0.22.zip",
        approxBytes = 42L * 1024 * 1024,
        description = "约 42 MB，离线可跑，唤醒词与短指令识别足够",
    )

    val ENGLISH_SMALL = ModelInfo(
        id = "vosk-model-small-en-us-0.15",
        label = "English small",
        fileName = "vosk-model-small-en-us-0.15.zip",
        approxBytes = 40L * 1024 * 1024,
        description = "约 40 MB，英文唤醒词",
    )

    val all = listOf(CHINESE_SMALL, ENGLISH_SMALL)

    /** 官方分发点在前，GitHub 反代在后（国内两个都试，谁通用谁）。 */
    private val MIRRORS = listOf(
        "https://alphacephei.com/vosk/models/",
        "https://ghfast.top/https://github.com/alphacep/vosk-api/releases/download/models/",
    )

    fun modelDir(paths: FusionPaths, info: ModelInfo): File = File(paths.root, "voice/${info.id}")

    fun installed(paths: FusionPaths, info: ModelInfo): Boolean =
        File(modelDir(paths, info), "am/final.mdl").exists() ||
            File(modelDir(paths, info), "conf/model.conf").exists()

    /** 下载并解包。返回可直接喂给 Vosk Model 的目录。 */
    fun ensure(
        paths: FusionPaths,
        info: ModelInfo = CHINESE_SMALL,
        onStep: (String) -> Unit,
        onProgress: ((HttpDownloader.Progress) -> Unit)? = null,
    ): File? {
        val dir = modelDir(paths, info)
        if (installed(paths, info)) {
            LogBus.i(TAG, "${info.id} 已就位")
            return dir
        }
        paths.ensure()
        val zip = File(paths.tmpDir, info.fileName)
        onStep("下载语音模型 ${info.label}（约 ${info.approxBytes / 1024 / 1024} MB）…")
        return runCatching {
            val downloader = HttpDownloader(MIRRORS)
            downloader.to(zip, info.fileName, null, info.label, onProgress)
            onStep("解包语音模型…")
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            ZipExt.extract(zip, dir) { msg -> onStep(msg) }
            // zip 内部通常还有一层同名目录，摊平一层
            val nested = File(dir, info.id)
            if (nested.isDirectory && File(nested, "am").exists()) {
                nested.listFiles()?.forEach { child -> child.renameTo(File(dir, child.name)) }
                nested.deleteRecursively()
            }
            if (!zip.delete()) LogBus.w(TAG, "模型压缩包残留：${zip.absolutePath}")
            onStep("语音模型就绪")
            dir
        }.getOrElse {
            LogBus.e(TAG, "模型装配失败：${it.message}")
            onStep("语音模型装配失败：${it.message}")
            null
        }
    }

    private const val TAG = "语音模型"
}
