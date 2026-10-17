package io.dsh.fusion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.LogLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 引擎控制台。
 *
 * 手机上没有终端，但「看得见它在干什么」是刚需 —— 尤其是引擎起不来的时候。
 * 这里把 LogBus 的内容实时铺出来，并提供复制/落盘，方便把问题甩给开发者。
 */
class ConsoleActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var summary: TextView
    private var errorsOnly = false
    private var rendered = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        tail()
    }

    private fun buildUi(): View {
        val column = UiKit.column(this)

        // ── 顶部：返回 + 标题 ──────────────────────────────────────────
        //
        // 之前这里只有标题，**进来了就出不去** —— 只能靠系统返回手势或返回键。
        // 而系统返回在全面屏手势下是一条不明显的边缘划动，
        // 在这一页尤其难受：它是一整屏的滚动日志，手指很容易划在日志上。
        // 一个看得见的返回入口是必需品，不是装饰。
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            UiKit.pill(this, "← 返回", false) { finish() },
            LinearLayout.LayoutParams(WRAP, WRAP),
        )
        header.addView(
            UiKit.text(this, "引擎控制台", 18f, UiKit.TEXT, true).apply {
                setPadding(UiKit.dp(this@ConsoleActivity, 12f), 0, 0, 0)
            },
            LinearLayout.LayoutParams(WRAP, WRAP),
        )
        column.addView(header)

        summary = UiKit.text(this, "", 12f, UiKit.TEXT_DIM).apply {
            setPadding(0, UiKit.dp(this@ConsoleActivity, 10f), 0, UiKit.dp(this@ConsoleActivity, 12f))
        }
        column.addView(summary)

        // ── 动作按钮：按内容宽度，不铺满整行 ──────────────────────────
        //
        // 之前用 `LayoutParams(0, WRAP, 1f)` 让三个按钮各占 1/3 屏宽。
        // 后果是它们比界面上其它按钮整整大一圈 —— 而这三件事
        //（只看错误 / 复制 / 清空）都是**次要动作**，
        // 给它们主操作那种体量，视觉上就把「日志本身」这个主角压下去了。
        //
        // 现在按内容宽度排，左对齐，间距 8dp。
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        fun actionPill(label: String, onClick: () -> Unit) {
            actions.addView(
                UiKit.pill(this, label, false) { onClick() },
                LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    marginEnd = UiKit.dp(this@ConsoleActivity, 8f)
                },
            )
        }
        actionPill("仅错误") {
            errorsOnly = !errorsOnly
            rendered = 0
            output.text = ""
            Toast.makeText(this, if (errorsOnly) "只看错误与警告" else "显示全部", Toast.LENGTH_SHORT).show()
        }
        actionPill("复制") { copyAll() }
        actionPill("清空") {
            LogBus.clear()
            output.text = ""
            rendered = 0
        }
        column.addView(actions)
        column.addView(UiKit.spacer(this, 12f))

        output = UiKit.text(this, "", 11f, UiKit.TEXT).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(UiKit.dp(this@ConsoleActivity, 12f), UiKit.dp(this@ConsoleActivity, 12f),
                UiKit.dp(this@ConsoleActivity, 12f), UiKit.dp(this@ConsoleActivity, 12f))
            background = UiKit.rounded(UiKit.SURFACE, 12f, this@ConsoleActivity, UiKit.STROKE)
        }
        scroll = ScrollView(this).apply {
            addView(output, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        column.addView(scroll)
        return column
    }

    private fun tail() {
        lifecycleScope.launch {
            while (isActive) {
                val all = LogBus.snapshot.value
                val lines = if (errorsOnly) all.filter { it.level == LogLevel.WARN || it.level == LogLevel.ERROR } else all
                val window = lines.takeLast(400)
                val text = window.joinToString("\n") { it.format() }
                if (text != output.text) {
                    val atBottom = scroll.getChildAt(0)?.let { it.height - scroll.height - scroll.scrollY } ?: 0
                    output.text = text
                    if (atBottom < 120) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
                summary.text = "共 ${all.size} 行 · 警告 ${all.count { it.level == LogLevel.WARN }} · 错误 ${all.count { it.level == LogLevel.ERROR }}" +
                    if (errorsOnly) " · 已过滤" else ""
                delay(900)
            }
        }
    }

    private fun copyAll() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val payload = LogBus.dump() + "\n\n--- 环境 ---\n" +
            io.dsh.fusion.engine.FusionRuntime.paths().describe().entries.joinToString("\n") { "${it.key} = ${it.value}" }
        clipboard.setPrimaryClip(ClipData.newPlainText("dsh-fusion-log", payload))
        Toast.makeText(this, "日志已复制（${payload.length} 字符）", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
