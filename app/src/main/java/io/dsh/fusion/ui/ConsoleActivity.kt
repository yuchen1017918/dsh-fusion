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
        column.addView(UiKit.text(this, "引擎控制台", 18f, UiKit.TEXT, true))
        summary = UiKit.text(this, "", 12f, UiKit.TEXT_DIM).apply {
            setPadding(0, UiKit.dp(this@ConsoleActivity, 6f), 0, UiKit.dp(this@ConsoleActivity, 12f))
        }
        column.addView(summary)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(UiKit.pill(this, "仅错误") {
            errorsOnly = !errorsOnly
            rendered = 0
            output.text = ""
            Toast.makeText(this, if (errorsOnly) "只看错误与警告" else "显示全部", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = UiKit.dp(this@ConsoleActivity, 6f) })
        actions.addView(UiKit.pill(this, "复制") { copyAll() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginEnd = UiKit.dp(this@ConsoleActivity, 6f)
        })
        actions.addView(UiKit.pill(this, "清空") {
            LogBus.clear()
            output.text = ""
            rendered = 0
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
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
