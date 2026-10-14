package io.dsh.fusion.engine.desktop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import io.dsh.fusion.engine.LogBus
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 桌面版架构的原生一半：把官方客户端的 `<webview>` 元素接上真的 WebView。
 *
 * 配对的是 `app/src/main/assets/host/desktop-carrier.js` 里的 polyfill：
 * 那边把 `document.createElement('webview')` 换成一个占位 div，
 * 这边负责在占位 div 的位置上摆一个真正的原生 WebView，并把导航与状态双向转发。
 *
 * ── 一个必须说清的妥协 ────────────────────────────────────────────
 * Electron 的 `<webview partition="...">` 提供**真正的存储分区隔离**。
 * **Android WebView 没有等价能力**：Cookie 与 DOM Storage 都是进程级的，
 * `WebStorage.deleteAllData()` 还是全局的。
 *
 * 所以这里退一步：**一个租约一个独立 WebView**，释放时销毁并清掉它的 Cookie。
 * 这是粗粒度隔离，不等价于 partition —— 如果将来要跑互不信任的站点，
 * 这一条必须先解决（可行路线是给浏览器页起独立进程 + 独立 data 目录）。
 */
class DesktopBrowserHost(
    private val context: Context,
    private val container: FrameLayout,
    private val evaluateInMainPage: (String) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())

    /** viewId -> 一个真实 WebView。 */
    private val views = ConcurrentHashMap<String, Entry>()

    /** lease -> partition 名。 */
    private val leases = ConcurrentHashMap<String, String>()

    /** 谁在监听「打开新页」。 */
    private val watched = ConcurrentHashMap.newKeySet<String>()

    private class Entry(
        val webView: WebView,
        val params: FrameLayout.LayoutParams,
        var lease: String,
        var partition: String,
    )

    init {
        container.clipChildren = true
        container.clipToPadding = true
    }

    // ══════════════════════════════════════════════════════════════
    // 一、DesktopBrowserBridge 的三个方法
    // ══════════════════════════════════════════════════════════════

    /**
     * 申请一个租约。
     *
     * 返回的 partition 只是个**标签** —— Android 没有真分区，
     * 但客户端会把它写进 `partition` 属性，我们留着用于日志与将来做隔离。
     */
    @JavascriptInterface
    fun acquire(workspace: String): String {
        val lease = UUID.randomUUID().toString().replace("-", "")
        val partition = "persist:fusion-" + workspace.hashCode().toString(16) + "-" + lease.take(8)
        leases[lease] = partition
        LogBus.i(TAG, "申请租约 ${lease.take(8)}（workspace=${workspace.take(60)}）")
        return JSONObject().apply {
            put("lease", lease)
            put("partition", partition)
        }.toString()
    }

    @JavascriptInterface
    fun release(lease: String) {
        leases.remove(lease)
        watched.remove(lease)
        // 该租约下的视图一并销毁
        views.entries.filter { it.value.lease == lease }.forEach { (id, _) -> destroyView(id) }
        LogBus.i(TAG, "释放租约 ${lease.take(8)}")
    }

    @JavascriptInterface
    fun watch(lease: String) {
        watched.add(lease)
    }

    @JavascriptInterface
    fun unwatch(lease: String) {
        watched.remove(lease)
    }

    // ══════════════════════════════════════════════════════════════
    // 二、<webview> 元素的生命周期
    // ══════════════════════════════════════════════════════════════

    /** 占位元素被挂到 DOM 上了 —— 建一个真 WebView。 */
    @JavascriptInterface
    fun attach(viewId: String, lease: String, partition: String) {
        main.post {
            if (views.containsKey(viewId)) return@post
            val webView = createWebView(viewId)
            val params = FrameLayout.LayoutParams(1, 1).apply { leftMargin = -10000; topMargin = -10000 }
            container.addView(webView, params)
            views[viewId] = Entry(webView, params, lease, partition)
            LogBus.i(TAG, "挂载浏览器页 $viewId（租约 ${lease.take(8)}，分区 ${partition.take(20)}）")
        }
    }

    /** 占位元素从 DOM 上摘下来了 —— 销毁。 */
    @JavascriptInterface
    fun detach(viewId: String) {
        main.post { destroyView(viewId) }
    }

    /** 跟随占位元素的位置。这是整条路线里最琐碎、也最容易出偏差的一环。 */
    @JavascriptInterface
    fun setBounds(viewId: String, left: Int, top: Int, width: Int, height: Int, visible: String) {
        val entry = views[viewId] ?: return
        main.post {
            val isVisible = visible == "1"
            entry.webView.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
            if (!isVisible) return@post
            entry.params.width = width.coerceAtLeast(1)
            entry.params.height = height.coerceAtLeast(1)
            entry.params.leftMargin = left
            entry.params.topMargin = top
            entry.webView.layoutParams = entry.params
        }
    }

    private fun destroyView(viewId: String) {
        val entry = views.remove(viewId) ?: return
        runCatching {
            container.removeView(entry.webView)
            entry.webView.stopLoading()
            entry.webView.loadUrl("about:blank")
            entry.webView.removeAllViews()
            entry.webView.destroy()
        }
        LogBus.i(TAG, "销毁浏览器页 $viewId")
    }

    fun destroyAll() {
        views.keys.toList().forEach { destroyView(it) }
    }

    // ══════════════════════════════════════════════════════════════
    // 三、导航：JS → 原生
    // ══════════════════════════════════════════════════════════════

    @JavascriptInterface
    fun loadUrl(viewId: String, url: String) {
        val entry = views[viewId] ?: return
        main.post {
            LogBus.i(TAG, "浏览器页 $viewId 导航 -> ${url.take(120)}")
            entry.webView.loadUrl(url)
        }
    }

    @JavascriptInterface
    fun goBack(viewId: String) {
        main.post { views[viewId]?.webView?.let { if (it.canGoBack()) it.goBack() } }
    }

    @JavascriptInterface
    fun goForward(viewId: String) {
        main.post { views[viewId]?.webView?.let { if (it.canGoForward()) it.goForward() } }
    }

    @JavascriptInterface
    fun reload(viewId: String) {
        main.post { views[viewId]?.webView?.reload() }
    }

    @JavascriptInterface
    fun clearHistory(viewId: String) {
        main.post { views[viewId]?.webView?.clearHistory() }
    }

    // ══════════════════════════════════════════════════════════════
    // 四、WebView 配置与事件回传
    // ══════════════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(viewId: String): WebView {
        val webView = WebView(context)
        webView.setBackgroundColor(Color.WHITE)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // 浏览器页是我们渲染「模型想给用户看的东西」的地方，
            // 但它是任意网页，所以**不允许**它读本地文件
            allowFileAccess = false
            allowContentAccess = false
            // 外链在浏览器页里就地打开（这就是浏览器页的意义），
            // 但要挡住 http 页面里的混合内容
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        webView.webViewClient = object : WebViewClient() {
          override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // 浏览器页就该留在浏览器页里
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                callJs(viewId, "start", "{}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                callJs(viewId, "stop", statusJson(viewId, url))
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                callJs(viewId, "navigate", statusJson(viewId, url))
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                val payload = JSONObject().apply {
                    put("code", error?.errorCode ?: -1)
                    put("description", error?.description?.toString() ?: "load failed")
                    put("url", request.url?.toString() ?: "")
                }.toString()
                callJs(viewId, "fail", payload)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                val payload = JSONObject().apply { put("title", title ?: "") }.toString()
                callJs(viewId, "title", payload)
            }

            /**
             * 页面想开新窗口（target=_blank 或 window.open）。
             * 这正好对应官方客户端的 onOpenRequested ——
             * 客户端据此决定是开新标签还是复用当前页。
             */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean {
                val entry = views[viewId] ?: return false
                if (entry.lease.isNotEmpty() && watched.contains(entry.lease)) {
                    // 让客户端自己决定；我们把请求丢上去，不在这里直接开窗
                    val payload = JSONObject().apply { put("url", entry.webView.url ?: "") }.toString()
                    callJs(viewId, "newwindow", payload)
                    return true
                }
                return false
            }
        }

        return webView
    }

    private fun statusJson(viewId: String, url: String?): String {
        val entry = views[viewId]
        return JSONObject().apply {
            put("url", url ?: entry?.webView?.url ?: "")
            put("canBack", entry?.webView?.canGoBack() ?: false)
            put("canForward", entry?.webView?.canGoForward() ?: false)
        }.toString()
    }

    /** 主页面 -> 回调。每次注入都重新拼 JS 源码，所以参数必须严格转义。 */
    private fun callJs(viewId: String, event: String, payloadJson: String) {
        main.post {
            val js = "window.__dshFusionWv && window.__dshFusionWv(" +
                JSONObject.quote(viewId) + "," +
                JSONObject.quote(event) + "," +
                JSONObject.quote(payloadJson) + ")"
            runCatching { evaluateInMainPage(js) }
                .onFailure { LogBus.t(TAG, "回传 $event 失败：${it.message}") }
        }
    }

    /** 原生 -> 页面：通知「用户要求打开某个链接」。 */
    fun notifyOpenRequested(lease: String, url: String) {
        val js = "window.__dshFusionOpenRequested && window.__dshFusionOpenRequested(" +
            JSONObject.quote(lease) + "," + JSONObject.quote(url) + ")"
        main.post { runCatching { evaluateInMainPage(js) } }
    }

    /** 供设置页显示状态。 */
    fun describe(): String =
        "浏览器页 ${views.size} 个 · 租约 ${leases.size} 个 · 监听 ${watched.size} 个"

    companion object {
        private const val TAG = "桌面宿主"

        /** JS 桥在页面里的名字。 */
        const val JS_INTERFACE = "__DSH_FUSION_HOST__"
    }
}
