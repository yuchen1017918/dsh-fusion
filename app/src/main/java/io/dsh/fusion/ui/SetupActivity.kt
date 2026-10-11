package io.dsh.fusion.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.dsh.fusion.engine.FusionRuntime
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.service.EngineService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首次装配向导。
 *
 * 手机端最劝退的一步就是「第一次打开要等几分钟」。所以这里把每一步都说清楚：
 * 现在在干什么、大概多久、失败了会怎样。三件事里前两件必须联网，
 * 第三件（Ubuntu rootfs）是可选的 —— 装不上也不影响日常使用。
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var stepsBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var action: TextView
    private lateinit var progress: android.widget.ProgressBar
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refresh()
    }

    private fun buildUi(): View {
        val column = UiKit.column(this)
        column.addView(UiKit.text(this, "首次装配", 20f, UiKit.TEXT, true))
        column.addView(UiKit.text(
            this,
            "融合体要先把运行时与官方内核铺好。全程在本机完成，引擎只监听回环地址。",
            13f, UiKit.TEXT_DIM,
        ).apply { setPadding(0, UiKit.dp(this@SetupActivity, 8f), 0, UiKit.dp(this@SetupActivity, 14f)) })

        stepsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(UiKit.card(this, "装配清单", stepsBox))

        column.addView(UiKit.spacer(this, 14f))
        statusText = UiKit.text(this, "准备就绪", 13f, UiKit.TEXT).apply {
            background = UiKit.rounded(UiKit.SURFACE, 12f, this@SetupActivity, UiKit.STROKE)
            setPadding(UiKit.dp(this@SetupActivity, 12f), UiKit.dp(this@SetupActivity, 10f),
                UiKit.dp(this@SetupActivity, 12f), UiKit.dp(this@SetupActivity, 10f))
        }
        column.addView(statusText)
        progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        column.addView(progress, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = UiKit.dp(this@SetupActivity, 10f) })

        column.addView(UiKit.spacer(this, 14f))
        action = UiKit.pill(this, "开始装配", primary = true) { startSetup() }
        column.addView(action)
        column.addView(UiKit.spacer(this, 10f))

        val secondary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        secondary.addView(UiKit.pill(this, "设备体检") { startActivity(Intent(this, DoctorActivity::class.java)) },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = UiKit.dp(this@SetupActivity, 6f) })
        secondary.addView(UiKit.pill(this, "直接进入") { finish() },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        column.addView(secondary)

        column.addView(UiKit.spacer(this, 16f))
        column.addView(UiKit.text(
            this,
            "装配期间会联网下载官方 @deepseek-ai/dsh 内核（几百 MB，取决于插件）。\n" +
                "国内走 npmmirror，可在设置里切换。断网可以重来，npm 缓存会复用。",
            12f, UiKit.TEXT_DIM,
        ))
        return UiKit.scroll(this, column)
    }

    private fun refresh() {
        val provisioner = FusionRuntime.provisioner()
        stepsBox.removeAllViews()
        provisioner.survey().forEach { step ->
            val row = UiKit.row(
                this,
                step.label + if (step.done) "" else "",
                step.detail.ifBlank { if (step.done) "已完成" else "待装配" },
                UiKit.dot(this, if (step.done) UiKit.ACCENT else UiKit.WARN),
            )
            stepsBox.addView(row)
        }
        action.text = if (FusionRuntime.paths().dshInstalled()) "重新装配" else "开始装配"
    }

    private fun startSetup() {
        if (busy) return
        busy = true
        progress.visibility = View.VISIBLE
        action.isEnabled = false
        action.alpha = 0.5f

        lifecycleScope.launch {
            val provisioner = FusionRuntime.provisioner()
            val ok = withContext(Dispatchers.IO) {
                provisioner.provisionCore { message ->
                    LogBus.i("装配", message)
                    lifecycleScope.launch {
                        statusText.text = message
                        refresh()
                    }
                }
            }
            busy = false
            progress.visibility = View.GONE
            action.isEnabled = true
            action.alpha = 1f
            statusText.text = if (ok) {
                "装配完成，正在拉起引擎…"
            } else {
                "装配失败 —— 打开「控制台」看具体在哪一步断的；也可以点「设备体检」排查环境"
            }
            refresh()
            if (ok) {
                EngineService.start(this@SetupActivity)
                finish()
            }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
