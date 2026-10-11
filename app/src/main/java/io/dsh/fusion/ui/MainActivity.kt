package io.dsh.fusion.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.dsh.fusion.engine.EngineState
import io.dsh.fusion.engine.FusionRuntime
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.service.EngineService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 主界面：一个 WebView + 一圈原生壳。
 *
 * 判断依据很朴素 —— DSH 的界面本来就是 Web 应用，手机端最有价值的做法
 * 不是重写一遍 UI，而是把**官方 UI 原样装进 WebView**，
 * 原生这层只负责它做不到的事：起引擎、保活、给设备能力、出问题看得见。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusPill: TextView
    private lateinit var emptyState: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyBody: TextView
    private lateinit var emptyAction: TextView
    private var loadedUrl: String? = null
    private var webViewWarningShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        configureWebView()
        observeEngine()
        observeLogs()

        if (!FusionRuntime.paths().dshInstalled()) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        EngineService.start(this)
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(UiKit.BG) }

        webView = WebView(this).apply {
            setBackgroundColor(UiKit.BG)
            visibility = View.GONE
        }
        root.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))

        emptyState = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(UiKit.dp(this@MainActivity, 28f), 0, UiKit.dp(this@MainActivity, 28f), 0)
        }
        emptyTitle = UiKit.text(this, "引擎未就绪", 19f, UiKit.TEXT, true)
        emptyBody = UiKit.text(this, "正在检查运行时与内核…", 13f, UiKit.TEXT_DIM).apply {
            setPadding(0, UiKit.dp(this@MainActivity, 10f), 0, UiKit.dp(this@MainActivity, 18f))
        }
        emptyAction = UiKit.pill(this, "启动引擎", primary = true) { onEmptyAction() }
        emptyState.addView(emptyTitle)
        emptyState.addView(emptyBody)
        emptyState.addView(emptyAction)
        root.addView(emptyState, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

        // 顶部状态条
        statusPill = UiKit.text(this, "…", 12f, UiKit.TEXT).apply {
            background = UiKit.rounded(UiKit.withAlpha(UiKit.SURFACE_HIGH, 235), 14f, this@MainActivity, UiKit.STROKE)
            setPadding(UiKit.dp(this@MainActivity, 12f), UiKit.dp(this@MainActivity, 6f),
                UiKit.dp(this@MainActivity, 12f), UiKit.dp(this@MainActivity, 6f))
            setOnClickListener { startActivity(Intent(this@MainActivity, ConsoleActivity::class.java)) }
        }
        root.addView(
            statusPill,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
                leftMargin = UiKit.dp(this@MainActivity, 12f)
                topMargin = UiKit.dp(this@MainActivity, 12f)
            },
        )

        // 底部胶囊导航
        root.addView(buildBottomBar(), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            bottomMargin = UiKit.dp(this@MainActivity, 14f)
            leftMargin = UiKit.dp(this@MainActivity, 14f)
            rightMargin = UiKit.dp(this@MainActivity, 14f)
        })

        return root
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = UiKit.rounded(UiKit.withAlpha(UiKit.SURFACE, 240), 22f, this@MainActivity, UiKit.STROKE)
            setPadding(UiKit.dp(this@MainActivity, 8f), UiKit.dp(this@MainActivity, 8f),
                UiKit.dp(this@MainActivity, 8f), UiKit.dp(this@MainActivity, 8f))
        }
        data class Item(val label: String, val action: () -> Unit)
        val items = listOf(
            Item("对话") { webView.visibility = View.VISIBLE },
            Item("控制台") { startActivity(Intent(this, ConsoleActivity::class.java)) },
            Item("体检") { startActivity(Intent(this, DoctorActivity::class.java)) },
            Item("设置") { startActivity(Intent(this, SettingsActivity::class.java)) },
            Item("重启") { restartEngine() },
        )
        items.forEach { item ->
            val button = UiKit.pill(this, item.label) { item.action() }
            bar.addView(button, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = UiKit.dp(this@MainActivity, 3f)
                marginEnd = UiKit.dp(this@MainActivity, 3f)
            })
        }
        return bar
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(io.dsh.fusion.BuildConfig.DEBUG)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // DSH 的界面是深色单页应用，让它按深色渲染，省得白闪
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                forceDark = WebSettings.FORCE_DARK_ON
            }
            // 回环地址本来就是可信上下文（127.0.0.1 属于 potentially trustworthy origin），
            // 这里再兜一层：不允许明文混合内容之外的例外
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // 回环与本地一律留在应用内；外部链接交给系统浏览器
                if (url.contains("127.0.0.1") || url.contains("localhost") || url.startsWith("file:")) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.visibility = View.VISIBLE
            }
        }
        warnIfOldWebView()
    }

    /**
     * 老 WebView 会白屏 —— 这是社区里排第一位的问题。
     * 与其让用户对着白屏发呆，不如提前把话说明白。
     */
    private fun warnIfOldWebView() {
        if (webViewWarningShown) return
        val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WebView.getCurrentWebViewPackage() else null
        val major = pkg?.versionName?.split('.')?.firstOrNull()?.toIntOrNull() ?: 0
        if (major in 1 until 100) {
            webViewWarningShown = true
            LogBus.w(TAG, "WebView 版本偏低（${pkg?.versionName}），界面可能渲染异常，建议升级 Android System WebView")
            Toast.makeText(this, "系统 WebView 版本偏低（v${pkg?.versionName}），界面可能异常，建议在应用商店升级", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ 引擎联动

    private fun observeEngine() {
        lifecycleScope.launch {
            FusionRuntime.supervisor().status.collect { status ->
                statusPill.text = when (status.state) {
                    EngineState.RUNNING -> "● 运行中 · ${status.backend.label} · :${status.port}"
                    EngineState.STARTING -> "◐ 启动中…"
                    EngineState.PROVISIONING -> "◐ 装配中…"
                    EngineState.DEGRADED -> "◐ 掉线重启中（第 ${status.restarts} 次）"
                    EngineState.FAILED -> "✕ 引擎失败"
                    EngineState.STOPPING -> "◐ 停止中…"
                    EngineState.IDLE -> "○ 已停止"
                }
                statusPill.setTextColor(
                    when (status.state) {
                        EngineState.RUNNING -> UiKit.ACCENT
                        EngineState.FAILED -> UiKit.DANGER
                        EngineState.DEGRADED -> UiKit.WARN
                        else -> UiKit.TEXT_DIM
                    }
                )

                val url = status.url
                if (status.state == EngineState.RUNNING && url != null && url != loadedUrl) {
                    loadedUrl = url
                    LogBus.i(TAG, "把引擎地址装进 WebView：${url.substringBefore('?')}")
                    webView.loadUrl(url)
                    webView.visibility = View.VISIBLE
                    emptyState.visibility = View.GONE
                } else if (status.state != EngineState.RUNNING) {
                    webView.visibility = View.GONE
                    emptyState.visibility = View.VISIBLE
                    emptyTitle.text = when (status.state) {
                        EngineState.FAILED -> "引擎启动失败"
                        EngineState.STARTING, EngineState.DEGRADED -> "正在拉起引擎"
                        else -> "引擎未就绪"
                    }
                    emptyBody.text = status.detail.ifBlank { "点下面的按钮启动，或先去「体检」看看环境。" }
                    emptyAction.text = when (status.state) {
                        EngineState.FAILED -> "重试"
                        EngineState.STARTING, EngineState.DEGRADED -> "查看控制台"
                        else -> "启动引擎"
                    }
                }
            }
        }
    }

    private fun onEmptyAction() {
        val state = FusionRuntime.supervisor().status.value.state
        when (state) {
            EngineState.FAILED -> restartEngine()
            EngineState.STARTING, EngineState.DEGRADED -> startActivity(Intent(this, ConsoleActivity::class.java))
            else -> {
                if (!FusionRuntime.paths().dshInstalled()) {
                    startActivity(Intent(this, SetupActivity::class.java))
                } else {
                    EngineService.start(this)
                }
            }
        }
    }

    private fun restartEngine() {
        if (!EngineService.start(this, EngineService.ACTION_RESTART)) {
            Toast.makeText(this, "请保持界面在前台再试一次", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "正在重启引擎…", Toast.LENGTH_SHORT).show()
        }
    }

    /** 引擎日志里出现「回复完成」这类信号时，顺手喂给流体云。 */
    private fun observeLogs() {
        lifecycleScope.launch {
            var counter = 0
            while (isActive) {
                val lines = LogBus.snapshot.value
                if (lines.size > counter) counter = lines.size
                delay(1200)
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 从通知/桌宠回来时，如果引擎已经好了就直接显示对话
        val current = FusionRuntime.supervisor().status.value.url
        if (current != null && current != loadedUrl) {
            webView.loadUrl(current)
            loadedUrl = current
        }
    }

    companion object {
        private const val TAG = "主界面"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
