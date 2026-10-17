package io.dsh.fusion.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 全程序化 UI 工具箱。
 *
 * 为什么不上 Compose、也不写一堆 layout XML：这个应用真正的界面是 WebView 里
 * 那套官方 DSH Web UI，原生侧只需要几个「壳页面」。用代码画反而更省事，
 * 而且配色、圆角、层级能集中在一处调 —— 改一个色号全局生效。
 */
object UiKit {

    // 与桌面端一致的暗色调色板
    const val BG = 0xFF0B0F16.toInt()
    const val SURFACE = 0xFF141A26.toInt()
    const val SURFACE_HIGH = 0xFF1C2434.toInt()
    const val STROKE = 0xFF26314A.toInt()
    const val TEXT = 0xFFE8F0FF.toInt()
    const val TEXT_DIM = 0xFF8FA0BC.toInt()
    const val ACCENT = 0xFF3DDC97.toInt()
    const val BLUE = 0xFF5B8CFF.toInt()
    const val WARN = 0xFFFFC24B.toInt()
    const val DANGER = 0xFFFF6B6B.toInt()

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    fun rounded(color: Int, radiusDp: Float, context: Context, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
            strokeColor?.let { setStroke(dp(context, 1f), it) }
        }

    /** 竖直方向带内边距的容器。 */
    fun column(context: Context, paddingDp: Float = 16f): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp))
    }

    fun scroll(context: Context, content: View): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        setBackgroundColor(BG)
        addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun text(
        context: Context,
        value: String,
        sizeSp: Float = 14f,
        color: Int = TEXT,
        bold: Boolean = false,
    ): TextView = TextView(context).apply {
        text = value
        setTextColor(color)
        textSize = sizeSp
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(dp(context, 3f).toFloat(), 1f)
    }

    fun title(context: Context, value: String): TextView = text(context, value, 15f, TEXT, true)

    fun body(context: Context, value: String): TextView = text(context, value, 13f, TEXT_DIM)

    /** 一张卡片：标题 + 可选正文 + 可选自定义内容。 */
    fun card(context: Context, title: String?, content: View? = null): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, 16f, context, STROKE)
            setPadding(dp(context, 15f), dp(context, 14f), dp(context, 15f), dp(context, 14f))
        }
        title?.let {
            card.addView(title(context, it))
        }
        content?.let {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (title != null) lp.topMargin = dp(context, 8f)
            card.addView(it, lp)
        }
        return card
    }

    fun sectionLabel(context: Context, value: String): TextView = text(context, value, 12f, TEXT_DIM).apply {
        letterSpacing = 0.08f
        setPadding(dp(context, 4f), dp(context, 10f), 0, dp(context, 6f))
    }

    /** 药丸按钮。primary 为真时是实心强调色。 */
    /**
     * 胶囊按钮。
     *
     * @param compact 排在一行里的多个等宽按钮用这个。
     *
     * ── 为什么需要 compact ──────────────────────────────────────────────
     * 底部导航那五个按钮是等宽的（weight=1 各占 1/5），而它们的标签字数
     * **不一样**：对话 / 体检 / 设置 / 重启 都是 2 个字，**控制台是 3 个**。
     *
     * 高度用的是 WRAP_CONTENT，于是字数多的那个在窄槽里换行成两行，
     * 渲染出来就比旁边几个高一截。而横向 LinearLayout 的行高取最高的那个，
     * 看起来像是「控制台这个按钮特别大」。
     *
     * compact 做三件事：单行、收紧左右内边距、固定高度。
     * **固定高度是关键** —— 只加单行的话，一旦某个标签更长就会被截断成省略号，
     * 而高度一致能保证「一排按钮看起来是一排」这个最基本的秩序。
     */
    fun pill(
        context: Context,
        label: String,
        primary: Boolean = false,
        color: Int? = null,
        compact: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): TextView {
        val accent = color ?: if (primary) ACCENT else BLUE
        val hPad = if (compact) 6f else 16f
        return TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (primary) 0xFF06110C.toInt() else TEXT)
            background = rounded(if (primary) accent else SURFACE_HIGH, 12f, context, if (primary) null else STROKE)
            setPadding(dp(context, hPad), dp(context, 10f), dp(context, hPad), dp(context, 10f))
            if (compact) {
                maxLines = 1
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                // 同一行里的按钮高度必须一致：13sp 一行 ≈ 18dp，加 10dp×2 内边距 = 38dp，
                // 取 40dp 留一点余量，字体缩放设到最大时也不会被裁掉
                height = dp(context, 40f)
            }
            isClickable = true
            isFocusable = true
            onClick?.let { action -> setOnClickListener { action() } }
        }
    }

    /** 一行「标题 / 副标题 / 右侧控件」。 */
    fun row(
        context: Context,
        titleText: String,
        subtitleText: String? = null,
        trailing: View? = null,
        accentColor: Int? = null,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8f), 0, dp(context, 8f))
        }
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        left.addView(
            text(context, titleText, 14f, accentColor ?: TEXT, true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        subtitleText?.let {
            left.addView(
                text(context, it, 12f, TEXT_DIM),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(context, 3f) },
            )
        }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let {
            row.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return row
    }

    /** 状态圆点：绿=就绪，黄=注意，红=阻塞，灰=未知。 */
    fun dot(context: Context, color: Int): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val size = dp(context, 9f)
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    fun spacer(context: Context, heightDp: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(context, heightDp))
    }

    /** 分隔线。 */
    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(STROKE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1f))
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    @Suppress("unused")
    private val unusedGuard = Color.TRANSPARENT
}
