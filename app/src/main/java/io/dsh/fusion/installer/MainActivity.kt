package io.dsh.fusion.installer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 安装器主界面。
 *
 * 整个应用的职责就三件事，界面也照这三步排：
 *   ① 探测这台机器是什么
 *   ② 决定该装哪一个（并说明为什么其他的不行）
 *   ③ 断点续传拉下来、校验、交给系统安装
 *
 * 「说明为什么其他不行」不是锦上添花 —— 用户遇到「装不上」时，
 * 一个只写着「无可用版本」的界面等于没给任何信息。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var profile: DeviceProfile
    private lateinit var catalog: ArtifactCatalog
    private lateinit var container: LinearLayout
    private val downloader by lazy { ResumableDownloader(catalog.channels) { log(it) } }

    /**
     * 正在下载的产物 id（null = 空闲）。
     *
     * 没有这个守卫时，连点两次「下载」会起**两个协程写同一个 `.part`** ——
     * 它们各自 `seek` 到自己的位置、各写各的，结果是一个交错在一起的坏文件。
     * 而 sha256 只能告诉你「坏了」，说不出为什么。
     */
    private var downloadingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        profile = DeviceProbe.probe(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0B0E14.toInt())
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        scroll.addView(container)
        setContentView(scroll)

        lifecycleScope.launch {
            catalog = loadCatalog()
            render()
        }
    }

    // ------------------------------------------------------------------ 清单

    /**
     * 优先从网络拉清单，失败退到随包的副本。
     *
     * 这样**加一个新平台不需要重新发安装器** —— 改仓库根的
     * `catalog/artifacts.json` 就够了。随包那份只是「第一次打开时没网」的兜底。
     */
    private suspend fun loadCatalog(): ArtifactCatalog = withContext(Dispatchers.IO) {
        val remote = CATALOG_URLS.firstNotNullOfOrNull { url ->
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 12000
                    setRequestProperty("User-Agent", "DSH-Fusion-Installer/0.1")
                }
                if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().readText() else null
            }.getOrNull()
        }
        // 拉到了不等于能用 —— 内容可能是半截 JSON，也可能是被劫持的 HTML
        // （公共 Wi-Fi 的强制门户就是这么干的）。
        // 原来直接 parse，抛异常会把整个启动协程打断 → **界面永远不渲染**。
        // 兜底只在「拉取失败」时生效、不在「内容非法」时生效，等于没有兜底。
        if (remote != null) {
            val parsed = runCatching { ArtifactCatalog.parse(remote) }.getOrNull()
            if (parsed != null) {
                log("清单已从网络更新")
                return@withContext parsed
            }
            log("远端清单内容非法，改用随包副本")
        } else {
            log("网络清单不可用，使用随包副本")
        }
        runCatching { ArtifactCatalog.parse(assets.open("artifacts.json").bufferedReader().readText()) }
            .getOrElse {
                // 随包副本也坏 = 安装包本身有问题。这时**不能静默**。
                log("随包清单也读不出来：${it.message}")
                ArtifactCatalog.parse("{\"schema\":1,\"channels\":[],\"artifacts\":[]}")
            }
    }

    // ------------------------------------------------------------------ 渲染

    private fun render() {
        container.removeAllViews()

        container.addView(title("DSH Fusion 安装器"))
        container.addView(dim("先看清这台机器，再决定装什么。"))

        // ── 设备信息 ──
        container.addView(section("这台设备"))
        container.addView(card(
            listOf(
                "型号" to "${profile.manufacturer} ${profile.model}",
                "架构" to profile.abis.joinToString(", "),
                "系统" to "Android API ${profile.apiLevel}",
                "内存" to "${"%.1f".format(profile.ramGb)} GB",
                "可用存储" to if (profile.freeStorageMb >= 0) "${profile.freeStorageMb / 1024} GB" else "读不到",
                "屏幕" to "${profile.screenWidth}×${profile.screenHeight}",
                "系统 WebView" to if (profile.webViewMajor > 0) "v${profile.webViewMajor}" else "厂商自带/未知",
            ),
        ))

        // ── 风险提示（提前说，别让用户下完再失败）──
        DeviceProbe.ptraceRisk(profile)?.let {
            container.addView(warn(it))
        }
        if (profile.suspiciousEmulator) {
            container.addView(warn(
                "检测到的环境线索：${profile.emulatorEvidence.joinToString("；")}\n" +
                    "如果你是在模拟器里跑，建议直接用 x86_64 版的产物（已自动为你选好）。",
            ))
        }

        // ── 推荐产物 ──
        val best = catalog.best(profile)
        if (best == null) {
            container.addView(section("没有可用的产物"))
            container.addView(warn(
                "这台设备没有任何匹配的构建。下面是每个产物不匹配的具体原因 —— " +
                    "如果你认为这是误判，把这份信息发到项目 Issue 里。",
            ))
        } else {
            container.addView(section("推荐安装"))
            val a = best.artifact
            val installed = DeviceProbe.installedVersion(this, TARGET_PKG)
            container.addView(card(
                listOf(
                    "版本" to "v${a.version}",
                    "渠道" to (a.branch ?: "—"),
                    "大小" to humanBytes(a.bytes),
                    "来源分支" to (a.branch ?: "—"),
                    "说明" to a.note,
                ),
            ))
            val btn = primaryButton(
                when {
                    installed != null -> "更新（当前已装 v$installed）"
                    else -> "下载并安装"
                },
            ) { startDownload(a) }
            container.addView(btn)
        }

        // ── 其他产物（含外部）──
        container.addView(section("全部产物"))
        catalog.matchAll(profile).forEach { m ->
            val a = m.artifact
            if (a.isExternal) {
                container.addView(card(
                    listOf(
                        a.name to "",
                        "平台" to a.platform,
                        "说明" to a.note,
                    ),
                ))
                a.externalUrl?.let { url ->
                    container.addView(secondaryButton("去社区项目页面") { open(url) })
                }
            } else {
                val mark = if (m.ok) "✔ 可用" else "✘ 不适用"
                container.addView(card(
                    buildList {
                        add("$mark  ${a.name}" to "")
                        if (m.ok) {
                            add("版本" to "v${a.version}")
                            add("大小" to humanBytes(a.bytes))
                        } else {
                            m.reasons.forEach { add("原因" to it) }
                        }
                    },
                ))
                if (m.ok && m.artifact.id != best?.artifact?.id) {
                    container.addView(secondaryButton("改为安装这个") { startDownload(a) })
                }
            }
        }

        // ── 日志 ──
        container.addView(section("过程日志"))
        logView = TextView(this).apply {
            setTextColor(0xFF8FA3BF.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFF111823.toInt())
        }
        container.addView(logView)
        logs.forEach { logView!!.append(it + "\n") }
    }

    private var logView: TextView? = null
    private val logs = ArrayDeque<String>()

    private fun log(line: String) {
        logs.addLast(line)
        while (logs.size > 200) logs.removeFirst()
        runOnUiThread { logView?.append(line + "\n") }
    }

    // ------------------------------------------------------------------ 下载

    private fun startDownload(a: Artifact) {
        // 并发守卫：同一个产物只允许一个下载在跑
        val running = downloadingId
        if (running != null) {
            Toast.makeText(
                this,
                if (running == a.id) "这个产物正在下载中，别急" else "还有另一个产物的下载没结束",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (!a.hasPayload) {
            AlertDialog.Builder(this)
                .setTitle("这个产物还没发布")
                .setMessage("清单里没有它的大小与校验值，说明构建还没上传。\n\n请到项目 Releases 页面确认，或用 Issue 反馈。")
                .setPositiveButton("好", null).show()
            return
        }

        val dir = File(getExternalFilesDir(null) ?: filesDir, "downloads")
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(8)).also { it.topMargin = dp(12) }
        }
        val label = TextView(this).apply {
            setTextColor(0xFFB9C7DB.toInt()); textSize = 12f
            setPadding(0, dp(6), 0, 0)
        }
        container.addView(label)
        container.addView(bar)

        downloadingId = a.id
        lifecycleScope.launch {
            val result = try {
                downloader.download(catalog, a, dir) { p ->
                runOnUiThread {
                    bar.progress = p.percent
                    val speed = if (p.bps > 0) " · ${humanBytes(p.bps)}/s" else ""
                    label.text = "${p.channel} · ${p.percent}%（${humanBytes(p.received)} / ${humanBytes(p.total)}）$speed"
                }
            }
        } finally {
                // 无论成功、失败还是取消都要清位，否则一次异常会把按钮永久锁死
                downloadingId = null
            }
            when (result) {
                is ResumableDownloader.Result.Ok -> {
                    label.text = "下载完成 · 来源 ${result.channel}"
                    install(result.file)
                }
                is ResumableDownloader.Result.Failed -> {
                    label.text = "下载失败"
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("下载失败")
                        .setMessage(
                            "${result.message}\n\n已尝试渠道：${result.triedChannels.joinToString("、")}\n\n" +
                                "已经下好的部分**保留着**，再点一次会接着下，不会从 0 重来。",
                        )
                        .setPositiveButton("再试一次") { _, _ -> startDownload(a) }
                        .setNegativeButton("取消", null).show()
                }
                else -> {}
            }
        }
    }

    /** 把下载好的 APK 交给系统安装器。 */
    private fun install(apk: File) {
        // Android 8.0 起安装未知来源应用需要用户授权，没授权时系统会静默拒绝 —— 
        // 所以要显式引导，而不是让用户对着「点了没反应」发呆。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(this)
                .setTitle("需要先允许安装")
                .setMessage("系统要求你为「DSH Fusion 安装器」打开「安装未知应用」权限，然后回来再点一次。")
                .setPositiveButton("去设置") { _, _ ->
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    }
                }
                .setNegativeButton("取消", null).show()
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure {
            Toast.makeText(this, "调起安装器失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    // ------------------------------------------------------------------ 小工具

    private fun title(t: String) = TextView(this).apply {
        text = t; setTextColor(0xFFF0F5FF.toInt()); textSize = 22f
        setPadding(0, 0, 0, dp(4))
    }

    private fun dim(t: String) = TextView(this).apply {
        text = t; setTextColor(0xFF8FA3BF.toInt()); textSize = 13f
        setPadding(0, 0, 0, dp(8))
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; setTextColor(0xFF6EA8FF.toInt()); textSize = 12f
        letterSpacing = 0.1f
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun card(rows: List<Pair<String, String>>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF131A26.toInt())
        setPadding(dp(16), dp(14), dp(16), dp(14))
        rows.forEach { (k, v) ->
            if (v.isEmpty()) {
                addView(TextView(this@MainActivity).apply {
                    text = k; setTextColor(0xFFE6EEFB.toInt()); textSize = 14f
                })
            } else {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(TextView(this@MainActivity).apply {
                        text = k; setTextColor(0xFF8FA3BF.toInt()); textSize = 12.5f
                        layoutParams = LinearLayout.LayoutParams(dp(76), WRAP)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = v; setTextColor(0xFFD7E3F4.toInt()); textSize = 12.5f
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                    })
                })
            }
        }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = dp(10) }
    }

    private fun warn(t: String) = TextView(this).apply {
        text = t
        setTextColor(0xFFF5D08A.toInt())
        textSize = 12.5f
        setBackgroundColor(0xFF231B0C.toInt())
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = dp(10) }
    }

    private fun primaryButton(t: String, f: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(6) }
    }

    private fun secondaryButton(t: String, f: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        alpha = 0.85f
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = dp(8) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 人类可读的体积。用十进制（1 MB = 1e6），与下载对话框、系统存储显示同口径 —— 
     *  用二进制会比用户预期的数字大 5%，而「说好的 100 MB 怎么显示 105」是个会被问的问题。 */
    private fun humanBytes(b: Long): String = when {
        b <= 0 -> "未知"
        b >= 1_000_000_000 -> "%.2f GB".format(b / 1e9)
        b >= 1_000_000 -> "%.1f MB".format(b / 1e6)
        b >= 1_000 -> "%.0f KB".format(b / 1e3)
        else -> "$b B"
    }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        const val TARGET_PKG = "io.dsh.fusion"

        /** 清单的远端位置：raw.githubusercontent 快，jsDelivr 作兜底。 */
        val CATALOG_URLS = listOf(
            "https://raw.githubusercontent.com/yuchen1017918/dsh-fusion/main/catalog/artifacts.json",
            "https://cdn.jsdelivr.net/gh/yuchen1017918/dsh-fusion@main/catalog/artifacts.json",
        )
    }
}
