package io.dsh.fusion.voice

import io.dsh.fusion.engine.LogBus
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 设备本地的语音转写（Vosk）。
 *
 * ── 为什么是 Vosk，不是官方的 SenseVoice ─────────────────────────
 * 官方语音栈里的本地后端用 `sherpa-onnx-node`，而 sherpa 的
 * optionalDependencies 只有六个平台：darwin×2 / linux×2 / win×2 ——
 * **没有 android / bionic 的任何构建**。所以它在 Android 上不是配置问题，
 * 是没有可用的原生库。
 *
 * Vosk 有 Android 构建（`vosk-android` 的 jni/arm64-v8a/libvosk.so），
 * 而这个应用本来就带着它 —— 离线唤醒用的就是它。
 *
 * ── 与唤醒引擎的分工 ────────────────────────────────────────────
 * `VoskWakeWordEngine` 是**常驻监听**，只关心「有没有命中唤醒词」，
 * 判定走部分结果（不等整句断完）。
 * 这里是**一次性转写**：拿到一段完整的 WAV，吐出整句话。
 * 两者共用同一个模型目录，但不共用 Recognizer —— 一个长驻、一个用完即弃。
 */
object VoiceTranscriber {

    private const val TAG = "语音转写"

    data class Result(val text: String, val seconds: Double, val sampleRate: Int)

    /**
     * 转写一段 WAV。
     *
     * @param wav 完整的 WAV 字节（带 44 字节头）
     * @param modelDir Vosk 模型目录
     * @return 转写结果；失败返回 null（调用方负责把原因说给用户）
     */
    fun transcribe(wav: ByteArray, modelDir: File?): Result? {
        if (modelDir == null || !modelDir.exists()) {
            LogBus.e(TAG, "模型目录不存在：${modelDir?.absolutePath ?: "(null)"}")
            return null
        }
        val parsed = parseWav(wav)
        if (parsed == null) {
            LogBus.e(TAG, "不是合法的 WAV（长度 ${wav.size}）")
            return null
        }

        var model: Model? = null
        var rec: Recognizer? = null
        return try {
            model = Model(modelDir.absolutePath)
            // Recognizer 的采样率必须与音频一致，否则识别结果会是乱的 ——
            // 而且不会报错，只是「识别得莫名其妙」，很难查
            rec = Recognizer(model, parsed.sampleRate.toFloat())

            // Vosk 的 `acceptWaveForm(byte[], int)` 把数组**从下标 0 开始**读 len 个字节 ——
            // 没有 offset 重载。所以分块要自己切片；直接传 (整个数组, 剩余长度)
            // 会在每一轮重复识别开头那段，而且它不会报错，只是结果莫名其妙。
            val chunk = 32 * 1024
            var offset = 0
            while (offset < parsed.pcm.size) {
                val n = minOf(chunk, parsed.pcm.size - offset)
                val slice = if (offset == 0 && n == parsed.pcm.size) {
                    parsed.pcm
                } else {
                    parsed.pcm.copyOfRange(offset, offset + n)
                }
                rec.acceptWaveForm(slice, n)
                offset += n
            }
            val json = JSONObject(rec.finalResult)
            val text = json.optString("text", "").trim()
            val seconds = parsed.pcm.size.toDouble() / (parsed.sampleRate * 2)  // 16-bit mono
            LogBus.i(TAG, "转写完成：${text.length} 字 / %.1f 秒".format(seconds))
            Result(text, seconds, parsed.sampleRate)
        } catch (e: Throwable) {
            // Vosk 是 JNI 的，native 侧的错误会以 Error 而非 Exception 抛出 ——
            // 只 catch Exception 会漏掉它们，然后整个调用栈崩掉
            LogBus.e(TAG, "转写失败：${e.message}")
            null
        } finally {
            runCatching { rec?.close() }
            runCatching { model?.close() }
        }
    }

    private class ParsedWav(val sampleRate: Int, val pcm: ByteArray)

    /**
     * 解析 WAV 头，取出采样率与 PCM 数据。
     *
     * 不用 `AudioSystem.getAudioInputStream` 是因为它依赖 javax.sound，
     * 在 Android 上不存在。WAV 的头很简单，自己走一遍 chunk 更可靠 ——
     * 而且能容忍录音端多出来的 LIST/fact 之类附加块。
     */
    private fun parseWav(wav: ByteArray): ParsedWav? {
        if (wav.size < 44) return null
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF....WAVE
        if (buf.get(0) != 'R'.code.toByte() || buf.get(1) != 'I'.code.toByte() ||
            buf.get(2) != 'F'.code.toByte() || buf.get(3) != 'F'.code.toByte()
        ) return null
        if (buf.get(8) != 'W'.code.toByte() || buf.get(9) != 'A'.code.toByte() ||
            buf.get(10) != 'V'.code.toByte() || buf.get(11) != 'E'.code.toByte()
        ) return null

        var pos = 12
        var sampleRate = 16000
        var bitsPerSample = 16
        var channels = 1
        var data: ByteArray? = null

        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            if (size < 0 || body + size > wav.size) {
                // 长度越界：容忍「最后一个块的长度写错」这种情况，但只对 data 块容忍
                if (id == "data") { data = wav.copyOfRange(body, wav.size); }
                break
            }
            when (id) {
                "fmt " -> {
                    channels = buf.getShort(body + 2).toInt()
                    sampleRate = buf.getInt(body + 4)
                    bitsPerSample = buf.getShort(body + 14).toInt()
                }
                "data" -> data = wav.copyOfRange(body, body + size)
            }
            pos = body + size + (size % 2)   // 块按偶数字节对齐
        }

        if (data == null) { LogBus.e(TAG, "WAV 里没有 data 块"); return null }
        if (bitsPerSample != 16 || channels != 1) {
            // 如实说明不支持，而不是喂给 Vosk 让它给出一段乱码
            LogBus.e(TAG, "只支持 16-bit 单声道 WAV，收到 ${bitsPerSample}bit/${channels}声道")
            return null
        }
        return ParsedWav(sampleRate, data)
    }
}
