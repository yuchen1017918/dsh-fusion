package io.dsh.fusion.voice

import io.dsh.fusion.engine.LogBus
import org.json.JSONObject

/**
 * 唤醒引擎的抽象。
 *
 * 之所以留一层接口：离线唤醒的方案在演进（Vosk → 专用 KWS → 声纹），
 * 换引擎不应该动上层服务，也不应该动「唤醒之后干什么」。
 */
interface WakeWordEngine {
    val id: String

    /** 是否已经在听。 */
    val listening: Boolean

    /** 加载模型并开始监听。 */
    fun start(phrase: String, onWake: (String) -> Unit): Boolean

    fun stop()

    fun release()
}

/**
 * Vosk 实现的离线唤醒。
 *
 * 唤醒判定走**部分结果**（partial result）而不是最终结果：说「小深小深」的时候
 * 你不希望等它把整句话断完才响应。命中即回调，同时把随后的整句交给上层做指令。
 */
class VoskWakeWordEngine(private val modelDirProvider: () -> java.io.File?) : WakeWordEngine {

    override val id = "vosk"

    private var speechService: org.vosk.android.SpeechService? = null
    private var recognizer: org.vosk.Recognizer? = null
    private var model: org.vosk.Model? = null
    private var phrase: String = ""

    @Volatile
    private var awake = false

    @Volatile
    override var listening: Boolean = false
        private set

    override fun start(phrase: String, onWake: (String) -> Unit): Boolean {
        stop()
        this.phrase = normalize(phrase)
        val dir = modelDirProvider()
        if (dir == null || !dir.exists()) {
            LogBus.e(TAG, "语音模型目录不存在，无法启动唤醒")
            return false
        }
        return runCatching {
            val m = org.vosk.Model(dir.absolutePath)
            model = m
            val rec = org.vosk.Recognizer(m, SAMPLE_RATE)
            recognizer = rec
            val service = org.vosk.android.SpeechService(rec, SAMPLE_RATE)
            speechService = service
            service.startListening(object : org.vosk.android.RecognitionListener {

                override fun onPartialResult(hypothesis: String?) {
                    val text = extract(hypothesis, "partial")
                    if (text.isBlank()) return
                    if (matches(text) && !awake) {
                        awake = true
                        LogBus.i(TAG, "唤醒命中：$text")
                        onWake(text)
                    }
                }

                override fun onResult(hypothesis: String?) {
                    val text = extract(hypothesis, "text")
                    if (text.isNotBlank() && matches(text) && !awake) {
                        awake = true
                        LogBus.i(TAG, "唤醒命中（整句）：$text")
                        onWake(text)
                    }
                    // 一句话结束就复位，下一次还能再喊
                    awake = false
                }

                override fun onFinalResult(hypothesis: String?) {
                    awake = false
                }

                override fun onError(exception: Exception?) {
                    LogBus.w(TAG, "识别出错：${exception?.message}")
                    listening = false
                }

                override fun onTimeout() {
                    awake = false
                }
            })
            listening = true
            LogBus.i(TAG, "离线唤醒已启动，唤醒词=「$phrase」，采样率 ${SAMPLE_RATE.toInt()} Hz")
            true
        }.getOrElse {
            LogBus.e(TAG, "唤醒启动失败：${it.message}")
            listening = false
            false
        }
    }

    override fun stop() {
        runCatching { speechService?.stop() }
        runCatching { speechService?.shutdown() }
        speechService = null
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
        awake = false
        listening = false
    }

    override fun release() = stop()

    /** 中文模型会把「小深小深」识别成「小深深」「小婶小婶」之类，做宽松匹配。 */
    private fun matches(text: String): Boolean {
        val t = normalize(text)
        if (t.isEmpty() || phrase.isEmpty()) return false
        if (t.contains(phrase)) return true
        // 退一步：连续两段相同字被识别出来也算（小深小深 -> 深深）
        val head = phrase.take(phrase.length / 2)
        if (head.length >= 2 && t.contains(head + head)) return true
        // 再退一步：编辑距离足够近
        return t.windowed(phrase.length, 1).any { levenshtein(it, phrase) <= 1 }
    }

    private fun normalize(text: String): String =
        text.replace(Regex("[\\s，。、,.!?！？]"), "")

    private fun extract(hypothesis: String?, key: String): String {
        if (hypothesis.isNullOrBlank()) return ""
        return runCatching { JSONObject(hypothesis).optString(key) }.getOrDefault("")
    }

    companion object {
        private const val TAG = "唤醒"
        const val SAMPLE_RATE = 16000.0f

        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                val swap = prev; prev = cur; cur = swap
            }
            return prev[b.length]
        }
    }
}
