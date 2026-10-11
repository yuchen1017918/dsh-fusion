package io.dsh.fusion.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.min

/**
 * 悬浮球 / 桌宠本体。
 *
 * 全部用 Canvas 画 —— 没有一个位图资源。
 * 好处不只是体积：状态色、呼吸感、光晕都能随引擎状态实时变，
 * 而且任何 DPI 下都不糊。
 */
class PetView(context: Context) : View(context) {

    enum class Mood { IDLE, BUSY, OFFLINE, ERROR }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2FBFF") }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var mood: Mood = Mood.IDLE
    private var phase = 0f
    private val ringRect = RectF()

    var onMoodChanged: ((Mood) -> Unit)? = null

    fun setMood(next: Mood) {
        if (mood == next) return
        mood = next
        onMoodChanged?.invoke(next)
        invalidate()
    }

    fun mood(): Mood = mood

    /** 让光晕缓慢呼吸；由服务在可见时驱动。 */
    fun tick() {
        phase = (phase + 0.06f) % (2f * Math.PI.toFloat())
        invalidate()
    }

    private fun accent(): Int = when (mood) {
        Mood.IDLE -> Color.parseColor("#3DDC97")
        Mood.BUSY -> Color.parseColor("#FFC24B")
        Mood.OFFLINE -> Color.parseColor("#7A8AA0")
        Mood.ERROR -> Color.parseColor("#FF6B6B")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f
        val color = accent()

        // 呼吸光晕
        val breath = 0.82f + 0.18f * kotlin.math.sin(phase)
        glowPaint.shader = RadialGradient(
            cx, cy, radius * 0.98f,
            intArrayOf(
                withAlpha(color, (90 * breath).toInt()),
                withAlpha(color, (36 * breath).toInt()),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0.30f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius * 0.98f, glowPaint)

        // 身体：高光偏左上，看起来是立体的
        bodyPaint.shader = RadialGradient(
            cx - radius * 0.28f, cy - radius * 0.30f, radius * 0.92f,
            intArrayOf(lighten(color, 0.42f), color, darken(color, 0.36f)),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius * 0.70f, bodyPaint)

        // 顶部高光
        highlightPaint.shader = RadialGradient(
            cx - radius * 0.22f, cy - radius * 0.34f, radius * 0.36f,
            intArrayOf(0x66FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx - radius * 0.22f, cy - radius * 0.34f, radius * 0.36f, highlightPaint)

        // 眼睛：忙的时候眯成一条缝，闲的时候是两颗圆点
        val eyeY = cy - radius * 0.06f
        val eyeDx = radius * 0.24f
        val eyeR = radius * 0.11f
        if (mood == Mood.BUSY) {
            val rect = RectF(cx - eyeDx - eyeR, eyeY - eyeR * 0.35f, cx - eyeDx + eyeR, eyeY + eyeR * 0.35f)
            canvas.drawRoundRect(rect, eyeR * 0.4f, eyeR * 0.4f, eyePaint)
            val rect2 = RectF(cx + eyeDx - eyeR, eyeY - eyeR * 0.35f, cx + eyeDx + eyeR, eyeY + eyeR * 0.35f)
            canvas.drawRoundRect(rect2, eyeR * 0.4f, eyeR * 0.4f, eyePaint)
        } else {
            canvas.drawCircle(cx - eyeDx, eyeY, eyeR, eyePaint)
            canvas.drawCircle(cx + eyeDx, eyeY, eyeR, eyePaint)
        }

        // 外圈：细环表示状态，比文字更省地方
        ringPaint.color = withAlpha(color, 170)
        ringPaint.strokeWidth = radius * 0.055f
        val inset = radius * 0.86f
        ringRect.set(cx - inset, cy - inset, cx + inset, cy + inset)
        canvas.drawArc(ringRect, -70f, 300f * breath, false, ringPaint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun lighten(color: Int, amount: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun darken(color: Int, amount: Float): Int {
        val r = (Color.red(color) * (1f - amount)).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1f - amount)).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1f - amount)).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
