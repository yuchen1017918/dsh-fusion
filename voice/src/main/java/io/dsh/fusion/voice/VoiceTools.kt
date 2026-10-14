package io.dsh.fusion.voice

import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.Tool
import io.dsh.fusion.engine.ToolBus
import io.dsh.fusion.engine.ToolChannel
import io.dsh.fusion.engine.ToolParam
import io.dsh.fusion.engine.ToolSpec
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * 把设备本地的语音转写能力挂上能力桥。
 *
 * ── 为什么要挂到桥上 ────────────────────────────────────────────
 * 官方的语音栈里，识别是**在 Node 侧**发生的（SenseVoice 后端 spawn 一个子进程）。
 * 而 Android 上没有可用的 sherpa-onnx 原生库，识别只能回到**原生侧**做。
 *
 * 所以这条链是：
 *
 *     dsh-fusion-speech-android（插件）
 *       └─ ctx.speechToText.register(provider)
 *            └─ transcribe() → POST /invoke → 这里 → Vosk
 *
 * 音频经 127.0.0.1 的回环桥走一圈，**不出设备**。
 *
 * ── 为什么用 base64 而不是流 ────────────────────────────────────
 * 桥是 JSON 的。几秒的 16 kHz 单声道 WAV 大约几十到几百 KB，
 * base64 后膨胀 33% 仍在可接受范围；而为了流式传输给桥加一条二进制通道，
 * 会把这个「工具调用」的简单模型弄复杂。**先按够用的做。**
 */
object VoiceTools {

    private const val TAG = "语音工具"

    /** 模型目录的提供者。由应用启动时注入 —— 语音模块不认识 FusionPaths。 */
    @Volatile
    var modelDirProvider: (() -> File?)? = null

    fun registerAll() {
        ToolBus.register(transcribeTool())
        LogBus.i(TAG, "语音转写能力已挂上能力桥")
    }

    private fun transcribeTool(): Tool {
        val spec = ToolSpec(
            id = "android_voice_transcribe",
            title = "本地语音转写",
            description = "把一段 WAV 音频在**设备本地**转成文字（Vosk 离线识别，不联网）。" +
                "只接受 16-bit 单声道 WAV。首次使用需要先在设置页下载语音模型。",
            channel = ToolChannel.APP,
            params = listOf(
                ToolParam(
                    name = "wav",
                    type = "string",
                    description = "完整 WAV 文件的 base64（含 44 字节头）",
                    required = true,
                ),
                ToolParam(
                    name = "language",
                    type = "string",
                    description = "语言提示。当前只有中文模型，传 zh / zh-CN / auto 效果相同",
                    required = false,
                    default = "auto",
                ),
            ),
        )
        return Tool(spec) { args ->
            val b64 = args.optString("wav")
            if (b64.isBlank()) {
                fail("缺少 wav 参数")
            } else {
                val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
                if (bytes == null) {
                    fail("wav 不是合法的 base64")
                } else if (bytes.size > MAX_BYTES) {
                    // 明确拒绝而不是硬塞 —— Vosk 遇到超长音频会把内存吃满，
                    // 而表现是被系统杀掉，用户只会看到「闪退」
                    fail("音频太大（${bytes.size / 1024} KB），上限 ${MAX_BYTES / 1024} KB")
                } else {
                    val dir = modelDirProvider?.invoke()
                    if (dir == null || !dir.exists()) {
                        fail("语音模型还没下载。去设置页点「下载离线语音模型」（约 42 MB）")
                    } else {
                        val r = VoiceTranscriber.transcribe(bytes, dir)
                        if (r == null) {
                            fail("转写失败。若音频格式不是 16-bit 单声道 WAV，或模型不完整，会走到这里")
                        } else {
                            JSONObject().apply {
                                put("ok", true)
                                put("text", r.text)
                                put("seconds", r.seconds)
                                put("sampleRate", r.sampleRate)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun fail(message: String) = JSONObject().apply {
        put("ok", false)
        put("error", message)
    }

    /** 约 5 分钟的 16 kHz 单声道 WAV。够长，又不会把内存吃穿。 */
    private const val MAX_BYTES = 10 * 1024 * 1024
}
