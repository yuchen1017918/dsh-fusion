package io.dsh.fusion.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/**
 * 流体云回复胶囊。
 *
 * 模型在写的时候，屏幕顶部浮出一条胶囊（不进通知栏、不打断当前操作）；
 * 点开就地展开成卡片看正文。这是「AI 在应用外跟你说话」的最轻形态 ——
 * 比切回应用便宜，比通知栏温柔。
 */
class CloudCapsuleView(context: Context) : View(context) {

    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4F8FF")
        textSize = sp(13f)
        isFakeBoldText = true
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C6D2E2")
        textSize = sp(12f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var title = "DSH"
    private var body = ""
    private var expanded = false
    private var progress = 0f
    private var phase = 0f

    private val rect = RectF()

    fun update(newTitle: String, newBody: String, isDone: Boolean) {
        title = newTitle
        body = newBody
        progress = if (isDone) 1f else progress
        invalidate()
    }

    fun setExpanded(value: Boolean) {
        expanded = value
        requestLayout()
        invalidate()
    }

    fun tick() {
        phase = (phase + 0.08f) % (2f * Math.PI.toFloat())
        invalidate()
    }

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val padding = dp(14f)
        val textWidth = width - padding * 2 - dp(22f)
        val titleLines = if (expanded) 1 else 1
        val bodyMax = if (expanded) 5 else 1
        val bodyText = TextUtils.ellipsize(body, bodyPaint, textWidth.toFloat(), TextUtils.TruncateAt.END)
        val height = padding * 2 +
            (titlePaint.fontMetrics.bottom - titlePaint.fontMetrics.top) * titleLines +
            dp(4f) +
            (bodyPaint.fontMetrics.bottom - bodyPaint.fontMetrics.top) * bodyMax
        setMeasuredDimension(width, height.toInt())
        if (!expanded) body = bodyText.toString()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = dp(14f)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())

        val accent = if (progress >= 1f) Color.parseColor("#3DDC97") else Color.parseColor("#5B8CFF")
        background.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(0xF21A2233.toInt(), 0xF2141A26.toInt(), withAlpha(accent, 40)),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        val radius = height / 2f
        canvas.drawRoundRect(rect, radius, radius, background)

        // 左侧状态点
        dotPaint.color = accent
        canvas.drawCircle(padding + dp(4f), height / 2f - dp(4f), dp(4.5f), dotPaint)

        // 进度条：底部一条细线
        val barTop = height - dp(3f)
        canvas.drawRect(0f, barTop, width * progress.coerceIn(0f, 1f), height.toFloat(), dotPaint)

        val textX = padding + dp(18f)
        canvas.drawText(title, textX, padding + (-titlePaint.fontMetrics.top), titlePaint)
        val bodyY = padding + (titlePaint.fontMetrics.bottom - titlePaint.fontMetrics.top) + dp(4f) + (-bodyPaint.fontMetrics.top)
        val lines = body.split('\n')
        val maxLines = if (expanded) 5 else 1
        lines.take(maxLines).forEachIndexed { index, line ->
            canvas.drawText(line, textX, bodyY + index * (bodyPaint.fontMetrics.bottom - bodyPaint.fontMetrics.top), bodyPaint)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
