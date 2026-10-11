package io.dsh.fusion.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.dsh.fusion.FusionApplication
import io.dsh.fusion.devicectl.CompatDoctor
import io.dsh.fusion.engine.FusionRuntime
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.overlay.OverlayPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设备体检。
 *
 * 这个页面存在的理由很直接：DSH 在 Android 上会死在十几个各不相同的地方
 * （W^X、FUSE 不支持硬链接、WebView 太老、幻影进程杀手、华为纯净模式、鸿蒙 NEXT……），
 * 每一条都能让用户对着「起不来」三个字发半天呆。
 * 与其让他去翻日志，不如开机就逐条过一遍，红黄绿摆出来，能修的给按钮。
 */
class DoctorActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var summaryView: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = UiKit.column(this)
        setContentView(UiKit.scroll(this, container))
        runDiagnosis()
    }

    private fun runDiagnosis() {
        container.removeAllViews()
        container.addView(UiKit.text(this, "设备体检", 18f, UiKit.TEXT, true))
        summaryView = UiKit.text(this, "正在逐项检查…", 12f, UiKit.TEXT_DIM).apply {
            setPadding(0, UiKit.dp(this@DoctorActivity, 6f), 0, UiKit.dp(this@DoctorActivity, 12f))
        }
        container.addView(summaryView)

        lifecycleScope.launch {
            val doctor = CompatDoctor(
                this@DoctorActivity,
                FusionRuntime.paths(),
                FusionRuntime.settings(),
                (application as FusionApplication).deviceManager.shell,
            )
            val findings = withContext(Dispatchers.IO) { doctor.diagnose() }
            withContext(Dispatchers.IO) { doctor.logAll(findings) }
            render(doctor, findings)
        }
    }

    private fun render(doctor: CompatDoctor, findings: List<CompatDoctor.Finding>) {
        container.removeAllViews()
        container.addView(UiKit.text(this, "设备体检", 18f, UiKit.TEXT, true))

        val blocks = findings.count { it.level == CompatDoctor.Level.BLOCK }
        val warns = findings.count { it.level == CompatDoctor.Level.WARN }
        val summary = UiKit.text(
            this,
            if (blocks > 0) "发现 $blocks 项阻塞、$warns 项警告 —— 先处理红色项"
            else if (warns > 0) "$warns 项建议处理，其余正常"
            else "全部通过 · 这台设备可以放心跑",
            13f,
            if (blocks > 0) UiKit.DANGER else if (warns > 0) UiKit.WARN else UiKit.ACCENT,
        ).apply { setPadding(0, UiKit.dp(this@DoctorActivity, 6f), 0, UiKit.dp(this@DoctorActivity, 10f)) }
        container.addView(summary)

        container.addView(UiKit.pill(this, "重新体检") { runDiagnosis() })
        container.addView(UiKit.spacer(this, 12f))

        // ── 安装引导 ──
        // 这条卡片有意做得比体检项更显眼：安装被拦截时应用还没装上，
        // 用户不可能在应用内看到任何提示。所以引导必须**装之前就能读到**，
        // 这里的「复制全文」就是为了让他能拷到电脑上先看。
        val guide = CompatDoctor(
            this, FusionRuntime.paths(), FusionRuntime.settings(),
            (application as FusionApplication).deviceManager.shell,
        ).installGuide()
        val guideBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        guideBox.addView(UiKit.text(this, guide, 11.5f, UiKit.TEXT_DIM))
        guideBox.addView(UiKit.spacer(this, 10f))
        val guideActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        guideActions.addView(UiKit.pill(this, "复制全文", primary = true) { copyGuide(guide) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = UiKit.dp(this@DoctorActivity, 6f) })
        guideActions.addView(UiKit.pill(this, "电池优化白名单") { requestBatteryWhitelist() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        guideBox.addView(guideActions)
        container.addView(
            UiKit.card(this, "华为 / 鸿蒙 · 安装与首启引导", guideBox).apply {
                background = UiKit.rounded(UiKit.SURFACE, 16f, this@DoctorActivity, UiKit.withAlpha(UiKit.BLUE, 120))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = UiKit.dp(this@DoctorActivity, 12f) },
        )

        findings.sortedBy { it.level.ordinal }.forEach { finding ->
            val color = when (finding.level) {
                CompatDoctor.Level.OK -> UiKit.ACCENT
                CompatDoctor.Level.WARN -> UiKit.WARN
                CompatDoctor.Level.BLOCK -> UiKit.DANGER
            }
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(UiKit.text(this, finding.detail, 13f, UiKit.TEXT_DIM))
            finding.fix?.let {
                content.addView(UiKit.text(this, "→ $it", 12f, UiKit.TEXT).apply {
                    setPadding(0, UiKit.dp(this@DoctorActivity, 6f), 0, 0)
                })
            }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            finding.fixCommand?.let { command ->
                actions.addView(UiKit.pill(this, "一键关闭监控", primary = true) { runFix(command) })
            }
            when (finding.id) {
                "accessibility" -> actions.addView(UiKit.pill(this, "去开启") { openSystem(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
                "overlay" -> actions.addView(UiKit.pill(this, "去授权") { OverlayPrefs.requestPermission(this) })
                "notification" -> actions.addView(UiKit.pill(this, "去授权") {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
                    }
                })
                "webview" -> actions.addView(UiKit.pill(this, "检查更新") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=Android System WebView"))) }
                })
                "shell" -> actions.addView(UiKit.pill(this, "请求 Shizuku") {
                    val ok = FusionRuntime.toolHostOrNull()?.requestShizuku() == true
                    Toast.makeText(this, if (ok) "已授权" else "不可用或未授权", Toast.LENGTH_SHORT).show()
                })
                "battery" -> actions.addView(UiKit.pill(this, "去加入白名单", primary = true) { requestBatteryWhitelist() })
                "huawei_install" -> actions.addView(UiKit.pill(this, "复制安装引导") { copyInstallGuide() })
                "harmony" -> actions.addView(UiKit.pill(this, "复制安装引导") { copyInstallGuide() })
            }
            if (actions.childCount > 0) {
                actions.setPadding(0, UiKit.dp(this@DoctorActivity, 8f), 0, 0)
                content.addView(actions)
            }

            container.addView(
                UiKit.card(this, finding.title, content).apply {
                    background = UiKit.rounded(UiKit.SURFACE, 16f, this@DoctorActivity, UiKit.withAlpha(color, 90))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = UiKit.dp(this@DoctorActivity, 10f) },
            )
        }

        container.addView(UiKit.spacer(this, 8f))
        container.addView(UiKit.text(this, doctor.summary(findings), 12f, UiKit.TEXT_DIM))
    }

    private fun runFix(command: String) {
        lifecycleScope.launch {
            val shell = (application as FusionApplication).deviceManager.shell
            val result = withContext(Dispatchers.IO) { shell.exec(command) }
            val message = when (result) {
                is io.dsh.fusion.devicectl.ShellResult.Ok ->
                    if (result.code == 0) "已执行：$command" else "执行返回码 ${result.code}：${result.trimmed.take(160)}"
                is io.dsh.fusion.devicectl.ShellResult.Failure -> "失败：${result.message}"
            }
            LogBus.i("体检", message)
            Toast.makeText(this@DoctorActivity, message, Toast.LENGTH_LONG).show()
            runDiagnosis()
        }
    }

    /** 把安装引导拷到剪贴板 —— 让用户能贴到电脑上，在点安装之前就读到。 */
    private fun copyGuide(text: String) {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("dsh-fusion-guide", text))
        Toast.makeText(this, "安装引导已复制（${text.length} 字符），可以粘到电脑上先看", Toast.LENGTH_LONG).show()
    }

    private fun copyInstallGuide() {
        val doctor = CompatDoctor(
            this, FusionRuntime.paths(), FusionRuntime.settings(),
            (application as FusionApplication).deviceManager.shell,
        )
        copyGuide(doctor.installGuide())
    }

    /**
     * 申请加入电池优化白名单。
     * 华为的后台清理比原生激进，这一步是用户侧最有效的一招。
     */
    private fun requestBatteryWhitelist() {
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure {
            // 部分 ROM 屏蔽了这个 action，退回列表页
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { Toast.makeText(this, "请手动到 设置 → 电池 → 更多设置 里把本应用设为不受限制", Toast.LENGTH_LONG).show() }
        }
    }

    private fun openSystem(action: String) {
        runCatching { startActivity(Intent(action)) }
            .onFailure { Toast.makeText(this, "跳转失败，请手动到系统设置里找", Toast.LENGTH_SHORT).show() }
    }

    @Suppress("unused")
    private val unusedGuard: View? = null
}
