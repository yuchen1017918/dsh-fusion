package io.dsh.fusion.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.dsh.fusion.FusionApplication
import io.dsh.fusion.engine.EngineBackend
import io.dsh.fusion.engine.FusionRuntime
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.overlay.OverlayPrefs
import io.dsh.fusion.service.EngineService
import io.dsh.fusion.voice.VoiceModels
import io.dsh.fusion.voice.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页。
 *
 * 手机端的设置页很容易变成「一堆看不懂的开关」。这里的取舍是：
 * 每一项后面都跟一句「关了会怎样」，而且动完就生效，不需要重启应用。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private val settings get() = FusionRuntime.settings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val column = UiKit.column(this)
        column.addView(UiKit.text(this, "设置", 18f, UiKit.TEXT, true))
        column.addView(UiKit.text(this, "改动即时生效；引擎相关的改动会顺带重启引擎。", 12f, UiKit.TEXT_DIM)
            .apply { setPadding(0, UiKit.dp(this@SettingsActivity, 6f), 0, UiKit.dp(this@SettingsActivity, 12f)) })

        // ---------------- 运行时后端 ----------------
        column.addView(UiKit.card(this, "运行时后端", buildBackendChooser()))

        // ---------------- 引擎 ----------------
        val engineCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        engineCard.addView(toggle("看门狗自动重启", "引擎进程掉线时自动拉回；连续失败 5 次会停下并报到控制台", settings.watchdog) {
            settings.watchdog = it
        })
        engineCard.addView(toggle("--expose-internals", "cordis 的 HMR / require-builtin 插件需要它；关掉后热更新类插件会报错", settings.exposeInternals) {
            settings.exposeInternals = it
        })
        engineCard.addView(stepper("V8 堆上限", "${settings.heapMb} MB", "手机内存吃得紧，太大反而会被系统 OOM 杀掉；建议不超过设备内存的 1/4") {
            settings.heapMb = (settings.heapMb + it).coerceIn(128, 3072)
            render()
        })
        column.addView(spaced(UiKit.card(this, "引擎", engineCard)))

        // ---------------- 交互 ----------------
        val uiCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        uiCard.addView(toggle("悬浮球桌宠", "常驻小球：单击回应用，长按出快捷菜单；颜色跟着引擎状态走", settings.petEnabled) {
            settings.petEnabled = it
            if (it) {
                if (!OverlayPrefs.enable(this)) {
                    Toast.makeText(this, "需要先授予悬浮窗权限", Toast.LENGTH_LONG).show()
                }
            } else {
                OverlayPrefs.disable(this)
            }
        })
        uiCard.addView(toggle("流体云回复卡", "AI 输出时在屏幕顶部浮出胶囊，点开看正文，不打断当前操作", settings.cloudCapsule) {
            settings.cloudCapsule = it
        })
        column.addView(spaced(UiKit.card(this, "交互", uiCard)))

        // ---------------- 语音 ----------------
        val voiceCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        voiceCard.addView(toggle("离线语音唤醒", "模型完全在本地跑，麦克风数据不出设备；首次使用需下载模型", settings.wakeEnabled) {
            if (it) {
                // 没有麦克风权限时前台服务只会静静失败，这里先把权限要了
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
                } else {
                    settings.wakeEnabled = true
                    WakeWordService.start(this)
                }
            } else {
                settings.wakeEnabled = false
                WakeWordService.stop(this)
            }
        })
        voiceCard.addView(UiKit.row(this, "唤醒词", settings.wakePhrase, UiKit.pill(this, "修改") { editWakePhrase() }))
        val modelReady = VoiceModels.installed(FusionRuntime.paths(), VoiceModels.CHINESE_SMALL)
        voiceCard.addView(UiKit.row(
            this,
            "中文模型（约 42 MB）",
            if (modelReady) "已就位" else "未下载 —— 点右边按需拉取",
            UiKit.pill(this, if (modelReady) "重新下载" else "下载") { downloadModel() },
        ))
        column.addView(spaced(UiKit.card(this, "语音", voiceCard)))

        // ---------------- 设备能力 ----------------
        val deviceCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        deviceCard.addView(toggle("启用 android_* 设备工具", "关掉后模型看不到任何手机能力（截屏/手势/文件/通知）", settings.deviceToolsEnabled) {
            settings.deviceToolsEnabled = it
            (application as FusionApplication).refreshToolManifest()
        })
        deviceCard.addView(toggle("危险操作二次确认", "写文件、改动系统设置这类工具在 UI 上会先确认", settings.confirmDangerous) {
            settings.confirmDangerous = it
        })
        FusionRuntime.toolHostOrNull()?.statusLines()?.forEach { line ->
            deviceCard.addView(UiKit.text(this, line, 12f, UiKit.TEXT_DIM).apply {
                setPadding(0, UiKit.dp(this@SettingsActivity, 4f), 0, 0)
            })
        }
        val perms = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        perms.addView(UiKit.pill(this, "无障碍设置") { openSystem(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = UiKit.dp(this@SettingsActivity, 6f) })
        perms.addView(UiKit.pill(this, "通知使用权") { openSystem("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS") },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = UiKit.dp(this@SettingsActivity, 6f) })
        perms.addView(UiKit.pill(this, "Shizuku 授权") {
            val ok = FusionRuntime.toolHostOrNull()?.requestShizuku() == true
            Toast.makeText(this, if (ok) "Shizuku 已授权" else "Shizuku 不可用或未授权", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        deviceCard.addView(UiKit.spacer(this, 10f))
        deviceCard.addView(perms)
        column.addView(spaced(UiKit.card(this, "设备能力", deviceCard)))

        // ---------------- 下载与内核 ----------------
        val netCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        netCard.addView(UiKit.row(this, "npm 源", settings.npmRegistry, UiKit.pill(this, "切换") { switchRegistry() }))
        netCard.addView(UiKit.row(
            this,
            "Ubuntu rootfs",
            if (FusionRuntime.provisioner().ubuntu.isReady()) "已就位（apt / gcc / python 可用）" else "未装配（约 30 MB 下载）",
            UiKit.pill(this, "装配") { provisionRootfs() },
        ))
        netCard.addView(UiKit.row(
            this,
            "dsh 内核",
            FusionRuntime.provisioner().dsh.installedVersion()?.let { "当前 v$it" } ?: "未安装",
            UiKit.pill(this, "检查更新") { checkKernel() },
        ))
        column.addView(spaced(UiKit.card(this, "下载与内核", netCard)))

        // ---------------- 插件 ----------------
        column.addView(spaced(UiKit.card(this, "插件", buildPluginBox())))

        // ---------------- 关于 ----------------
        val about = UiKit.text(
            this,
            "包名 io.dsh.fusion · 与其它 DSH 安卓版可并排安装（独立签名）\n" +
                "数据全部在 ${FusionRuntime.paths().root.absolutePath}，卸载即净\n" +
                "引擎只监听 127.0.0.1，不出设备",
            12f, UiKit.TEXT_DIM,
        )
        column.addView(spaced(UiKit.card(this, "关于", about)))

        setContentView(UiKit.scroll(this, column))
        container = column
    }

    // ------------------------------------------------------------------ 插件

    /**
     * 插件卡片。
     *
     * 这里刻意把「装上」和「挂载」分开显示：dsh 的插件要两处同时成立才真正生效，
     * 只做完 npm install 的话，插件是装了但不会跑 —— 用户会以为装失败了。
     * 界面上直接把这个中间状态点出来。
     */
    private fun buildPluginBox(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val entries = runCatching { FusionRuntime.provisioner().plugins.list() }.getOrDefault(emptyList())
        val effective = entries.count { it.effective }

        box.addView(UiKit.text(
            this,
            "已装 ${entries.size} 个，其中 ${effective} 个正在生效。" +
                "官方 npm 包、GitHub tarball 都能装 —— 走的就是 npm 那条路。",
            12f, UiKit.TEXT_DIM,
        ))

        if (entries.isEmpty()) {
            box.addView(UiKit.text(this, "还没有装任何插件。", 12.5f, UiKit.TEXT_DIM)
                .apply { setPadding(0, UiKit.dp(this@SettingsActivity, 8f), 0, 0) })
        }

        entries.forEach { entry ->
            val status = when {
                entry.effective -> "生效中"
                entry.installed -> "已装上但未挂载"
                entry.bundled -> "已挂载但没装上"
                else -> "未生效"
            }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(UiKit.pill(this, if (entry.bundled) "停用" else "启用") {
                FusionRuntime.provisioner().plugins.mount(entry.name, "web", !entry.bundled)
                Toast.makeText(this, "已${if (entry.bundled) "停用" else "启用"} ${entry.name}，重启引擎后生效", Toast.LENGTH_SHORT).show()
                render()
            })
            actions.addView(UiKit.pill(this, "移除") { removePlugin(entry.name) })

            box.addView(
                UiKit.row(
                    this,
                    entry.name,
                    "${entry.specifier} · $status · ${entry.kind}",
                    actions,
                    if (entry.effective) UiKit.ACCENT else UiKit.WARN,
                ),
            )
        }

        box.addView(UiKit.spacer(this, 12f))
        box.addView(UiKit.text(this, "安装新插件", 13f, UiKit.TEXT, true))
        val input = EditText(this).apply {
            hint = "包名，如 dsh-meme 或 @scope/pkg@1.2.3"
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.TEXT_DIM)
            textSize = 13f
            setPadding(0, UiKit.dp(this@SettingsActivity, 6f), 0, UiKit.dp(this@SettingsActivity, 6f))
        }
        box.addView(input)
        box.addView(UiKit.pill(this, "安装并挂载", primary = true) {
            installPlugin(input.text.toString())
        })
        box.addView(UiKit.text(
            this,
            "注意：需要现场编译原生模块的插件（node-gyp 那一类）在「原生直跑」后端下装不上，\n" +
                "请先切到「Ubuntu 容器」后端再装；指向电脑本地路径的 file:/link: 插件在手机上无意义。",
            11.5f, UiKit.TEXT_DIM,
        ).apply { setPadding(0, UiKit.dp(this@SettingsActivity, 8f), 0, 0) })
        return box
    }

    private fun installPlugin(spec: String) {
        if (spec.isBlank()) {
            Toast.makeText(this, "请填写包名", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            Toast.makeText(this@SettingsActivity, "开始安装 $spec，进度看控制台…", Toast.LENGTH_LONG).show()
            val outcome = FusionRuntime.provisioner().plugins.add(spec, "web") { msg -> LogBus.i("设置", msg) }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(if (outcome.ok) "安装完成" else "安装失败")
                .setMessage(outcome.message + (if (outcome.ok) "" else "\n\n到「控制台」页能看到 npm 的完整输出。"))
                .setPositiveButton("重启引擎并刷新") { _, _ ->
                    if (outcome.ok) {
                        io.dsh.fusion.service.EngineService.start(this@SettingsActivity, io.dsh.fusion.service.EngineService.ACTION_RESTART)
                    }
                    render()
                }
                .setNegativeButton("稍后", null)
                .show()
        }
    }

    private fun removePlugin(name: String) {
        AlertDialog.Builder(this)
            .setTitle("移除 $name")
            .setMessage("会从 profile 卸载并取消挂载。该插件产生的数据不会被删除。")
            .setPositiveButton("移除") { _, _ ->
                lifecycleScope.launch {
                    val outcome = FusionRuntime.provisioner().plugins.remove(name, "web") { msg -> LogBus.i("设置", msg) }
                    Toast.makeText(this@SettingsActivity, outcome.message, Toast.LENGTH_LONG).show()
                    render()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------ 组件

    private fun toggle(title: String, desc: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        var value = initial
        var button: TextView? = null

        // 先声明、后创建：pill 的回调不带参数，所以按钮自己要能被回调看见
        fun paint(on: Boolean) {
            button?.let { b ->
                b.text = if (on) "已开启" else "已关闭"
                b.background = UiKit.rounded(
                    if (on) UiKit.ACCENT else UiKit.SURFACE_HIGH, 12f, this,
                    if (on) 0 else UiKit.STROKE,
                )
                b.setTextColor(if (on) 0xFF06110C.toInt() else UiKit.TEXT)
            }
        }

        button = UiKit.pill(this, if (value) "已开启" else "已关闭", primary = value) {
            value = !value
            paint(value)
            onChange(value)
        }
        return UiKit.row(this, title, desc, button)
    }

    private fun stepper(title: String, valueText: String, desc: String, onDelta: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, UiKit.dp(this@SettingsActivity, 8f), 0, UiKit.dp(this@SettingsActivity, 8f)) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(UiKit.text(this, title, 14f, UiKit.TEXT, true))
        left.addView(UiKit.text(this, "$valueText · $desc", 12f, UiKit.TEXT_DIM))
        row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(UiKit.pill(this, "−128") { onDelta(-128) })
        row.addView(UiKit.pill(this, "+128") { onDelta(128) })
        return row
    }

    private fun spaced(view: View): View = view.apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = UiKit.dp(this@SettingsActivity, 12f) }
    }

    private fun buildBackendChooser(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        EngineBackend.entries.forEach { backend ->
            val selected = settings.backend == backend
            val row = UiKit.row(
                this,
                backend.label + if (selected) "  ✓" else "",
                backend.hint,
                UiKit.pill(this, if (selected) "使用中" else "切换", primary = selected) {
                    if (settings.backend == backend) return@pill
                    switchBackend(backend)
                },
                if (selected) UiKit.ACCENT else UiKit.TEXT,
            )
            box.addView(row)
        }
        return box
    }

    // ------------------------------------------------------------------ 动作

    private fun switchBackend(backend: EngineBackend) {
        lifecycleScope.launch {
            Toast.makeText(this@SettingsActivity, "正在切到「${backend.label}」…", Toast.LENGTH_SHORT).show()
            val status = FusionRuntime.supervisor().switchBackend(backend)
            Toast.makeText(this@SettingsActivity, "${backend.label}：${status.state} ${status.detail}", Toast.LENGTH_LONG).show()
            render()
        }
    }

    private fun editWakePhrase() {
        val input = EditText(this).apply { setText(settings.wakePhrase); setSelection(text.length) }
        AlertDialog.Builder(this)
            .setTitle("唤醒词")
            .setMessage("选一个平时不会说到的词。中文小型模型对叠词（如「小深小深」）识别最稳。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                settings.wakePhrase = input.text.toString().ifBlank { "小深小深" }
                if (settings.wakeEnabled) WakeWordService.restart(this)
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadModel() {
        lifecycleScope.launch {
            Toast.makeText(this@SettingsActivity, "开始下载语音模型…", Toast.LENGTH_SHORT).show()
            val result = withContext(Dispatchers.IO) {
                VoiceModels.ensure(FusionRuntime.paths(), VoiceModels.CHINESE_SMALL, { msg -> LogBus.i("设置", msg) }, null)
            }
            Toast.makeText(this@SettingsActivity, if (result != null) "模型已就位" else "下载失败，看控制台", Toast.LENGTH_LONG).show()
            render()
        }
    }

    private fun provisionRootfs() {
        AlertDialog.Builder(this)
            .setTitle("装配 Ubuntu rootfs")
            .setMessage("会下载约 30 MB 的官方 ubuntu-base 并解包到应用私有目录（展开约 300 MB）。装好后「Ubuntu 容器」后端才能用，apt / gcc / python 都在里面。")
            .setPositiveButton("开始") { _, _ ->
                lifecycleScope.launch {
                    Toast.makeText(this@SettingsActivity, "开始装配，进度看控制台", Toast.LENGTH_SHORT).show()
                    val ok = FusionRuntime.provisioner().provisionRootfs({ msg -> LogBus.i("设置", msg) }, null)
                    Toast.makeText(this@SettingsActivity, if (ok) "rootfs 装配完成" else "装配失败", Toast.LENGTH_LONG).show()
                    render()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun checkKernel() {
        lifecycleScope.launch {
            val versions = withContext(Dispatchers.IO) { FusionRuntime.provisioner().dsh.resolveVersion() }
            val items = arrayOf("安装/升级到 ${versions.version}（来自 ${versions.source}）", "重新装配 profile（修复白屏/插件 pending）")
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("内核更新")
                .setItems(items) { _, which ->
                    lifecycleScope.launch {
                        Toast.makeText(this@SettingsActivity, "开始…耗时几分钟，进度看控制台", Toast.LENGTH_LONG).show()
                        val ok = FusionRuntime.provisioner().installKernel(versions.version) { msg ->
                            LogBus.i("设置", msg)
                        }
                        Toast.makeText(this@SettingsActivity, if (ok) "内核已更新到 ${versions.version}" else "更新失败", Toast.LENGTH_LONG).show()
                        EngineService.start(this@SettingsActivity, EngineService.ACTION_RESTART)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun switchRegistry() {
        val options = arrayOf(
            "npmmirror（国内，默认）" to "https://registry.npmmirror.com",
            "npm 官方" to "https://registry.npmjs.org",
        )
        AlertDialog.Builder(this)
            .setTitle("npm 源")
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                settings.npmRegistry = options[which].second
                Toast.makeText(this, "已切到 ${options[which].second}", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openSystem(action: String) {
        runCatching { startActivity(Intent(action)) }
            .onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                Toast.makeText(this, "跳转失败，请手动到系统设置里找", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            settings.wakeEnabled = granted
            if (granted) WakeWordService.start(this)
            Toast.makeText(
                this,
                if (granted) "麦克风已授权，唤醒服务启动中" else "没有麦克风权限，离线唤醒无法工作",
                Toast.LENGTH_LONG,
            ).show()
            render()
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val REQ_MIC = 202
    }
}
