package io.dsh.fusion.engine.desktop

import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.dsh.fusion.engine.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 把桌面宿主载体（carrier）注入官方 Web UI，并**用金丝雀守着它**。
 *
 * ── 它为什么存在 ────────────────────────────────────────────────
 * 官方客户端有两条宿主分支，只看一个全局对象：
 *
 *     const carrier = globalThis.dshDesktop
 *     const desktop = carrier?.protocolVersion === 1 ? carrier.browser : undefined
 *     if (desktop === undefined) installFrames(...)        // 侧边栏浏览器 → iframe 降级
 *     else                        createElectronPage(...)  // 侧边栏浏览器 → 真 WebView
 *
 * 挂上 `dshDesktop` 就能拿到后者，**官方客户端一行都不用改**。
 *
 * ── 注入用的是什么（这决定了风险有多大）───────────────────────
 * `WebViewCompat.addDocumentStartJavaScript` —— **官方支持的**
 * 「在文档开始处执行脚本」API（androidx.webkit 1.9+，本项目用的 1.11.0）。
 *
 * 它比两条常见做法都干净：
 *   · 不起本地代理、不新增监听端口 —— 链路里没有第二个可能出错的环节
 *   · 不改文档 origin —— 文档与 API 仍在引擎端口，同源，后端的同源闸天然通过
 *     （闸的两条：`Sec-Fetch-Site: cross-site` 拒；带 Origin 时要求
 *      `origin.host === host.host`。同源请求两条都过。）
 *   · 不拦响应 —— SSE / WebSocket / 下载全部走 WebView 原生路径，不经我方代码
 *
 * 之前的实验分支用「本地反向代理 + 剥 Origin」达到同样效果，那是**保险做法**：
 * 它假定同源请求可能带一个不匹配的 Origin。但其实只需要「注入」这一件事，
 * 而注入有官方 API 可用。**少一个进程，就少一分白屏风险。**
 *
 * ── 金丝雀守什么 ────────────────────────────────────────────────
 * carrier 依赖的是官方客户端的**内部契约**（那个全局对象的名字、版本号、字段），
 * 它不在任何公开文档里，官方一次重构就可能失效。而失效**不会报错** ——
 * 表现只是「浏览器面板悄悄退回 iframe」，是典型的最难发现的故障。
 *
 * 所以 carrier 里放了 getter 陷阱，记录官方到底读了哪一层；这里读那份报告并决定去留：
 * 没进桌面分支就**卸掉注入并让页面重载** —— 回到今天这个已知可用的形态，
 * 而不是把一个可能已经失效的补丁继续挂在别人的界面上。
 */
class DesktopCarrier(private val context: Context) {

    /** 金丝雀判定结果。 */
    enum class Verdict {
        /** 官方读了 browser —— 真的进了桌面分支，功能生效 */
        ENTERED_DESKTOP,

        /** 官方压根没读 dshDesktop —— 契约变了（这个全局不再被认） */
        INERT_NOT_READ,

        /** 读了 dshDesktop 但没读 browser —— 协议版本或字段名变了 */
        INERT_NO_BROWSER,

        /** carrier 自己抛错了 */
        CARRIER_THREW,

        /** 页面根本没起来（官方界面没渲染出来），可能是注入导致的 */
        SHELL_MISSING,

        /** 观察窗口没到，还判断不了 */
        PENDING,
    }

    data class Report(
        val verdict: Verdict,
        val detail: String,
        val raw: String = "",
    ) {
        val shouldUninstall: Boolean
            get() = verdict in setOf(
                Verdict.INERT_NOT_READ, Verdict.INERT_NO_BROWSER,
                Verdict.CARRIER_THREW, Verdict.SHELL_MISSING,
            )
    }

    /**
     * 本次会话挂上的句柄。
     *
     * androidx.webkit 的设计很干净：`addDocumentStartJavaScript` 返回一个
     * `ScriptHandler`，它只有一个 `remove()` 方法 —— 撤销就是它自己，
     * 不需要另外记 scriptId 再去找一个反注册 API。
     * 这一点对金丝雀很关键：**能撤销**才谈得上优雅退化。
     */
    private var handler: androidx.webkit.ScriptHandler? = null
    private var installed = false

    val isInstalled: Boolean get() = installed

    private val prefs = context.getSharedPreferences("desktop_carrier", Context.MODE_PRIVATE)

    /**
     * 这次启动该不该尝试注入。
     *
     * **按应用版本记账**：在某个版本上金丝雀判定契约失效后，就没必要每次开屏
     * 都再试一遍、再重载一遍 —— 那只会让用户觉得应用闪。但**应用升级后要重试**，
     * 因为升级可能把官方客户端也带上去了。
     */
    fun shouldAttempt(): Boolean {
        val trippedAt = prefs.getString(KEY_TRIPPED_VERSION, null) ?: return true
        val current = appVersion()
        if (trippedAt == current) {
            LogBus.i(TAG, "金丝雀在 v$current 上已判定失效，本次不注入（升级后会自动重试）")
            return false
        }
        LogBus.i(TAG, "应用已从 v$trippedAt 升到 v$current，重新尝试注入")
        prefs.edit().remove(KEY_TRIPPED_VERSION).apply()
        return true
    }

    /** 运行期是否具备能力。不具备时**不报错、不注入**，静默回落到 iframe 降级。 */
    fun isSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }.getOrDefault(false)

    /**
     * 挂上 carrier。返回是否成功。
     *
     * 失败一律返回 false 而不是抛 —— 这个功能的定位是「锦上添花」，
     * 它拿不到就应该是今天的行为（iframe 降级），绝不能因此让应用起不来。
     */
    fun install(webView: WebView): Boolean {
        if (!shouldAttempt()) return false
        if (!isSupported()) {
            LogBus.w(TAG, "当前 WebView 内核不支持 DOCUMENT_START_SCRIPT，浏览器面板保持 iframe 降级")
            return false
        }
        val script = readCarrierAsset() ?: return false
        return runCatching {
            handler = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
            installed = true
            LogBus.i(TAG, "桌面宿主载体已注入（文档开始处，所有 origin）")
            true
        }.getOrElse {
            LogBus.e(TAG, "注入失败：${it.message}")
            false
        }
    }

    /**
     * 摘掉 carrier。
     *
     * 这是金丝雀能「优雅退化」的关键：因为注入用的是官方 API，
     * 所以可以**干净地撤销** —— 而不是只能靠重装应用来摆脱一个失效的补丁。
     */
    fun uninstall(@Suppress("UNUSED_PARAMETER") webView: WebView): Boolean {
        val h = handler ?: return false
        return runCatching {
            // 从这一刻起新加载的文档不再注入；已加载的页面要重载才生效。
            h.remove()
            installed = false
            handler = null
            LogBus.i(TAG, "已摘掉桌面宿主载体（下次加载起不再注入）")
            true
        }.getOrElse {
            LogBus.w(TAG, "摘除失败：${it.message}")
            false
        }
    }

    fun markTripped(reason: String) {
        prefs.edit().putString(KEY_TRIPPED_VERSION, appVersion()).apply()
        LogBus.w(TAG, "金丝雀判定失效并记账（v${appVersion()}）：$reason")
    }

    /** 清掉记账（设置页给用户一个「再试一次」的入口）。 */
    fun resetTrip() {
        prefs.edit().remove(KEY_TRIPPED_VERSION).apply()
        LogBus.i(TAG, "已清除金丝雀记账，下次开屏会重新尝试")
    }

    // ------------------------------------------------------------------ 观察

    /**
     * 等观察窗口过后读金丝雀报告。
     *
     * 为什么要等：官方客户端的 boot 是异步的，注入后立刻读必然读到「还没人读」，
     * 会把成功误判成失效。窗口取值要覆盖一次冷启动 —— 太短会误杀，
     * 太长会让失效的用户多等一次重载。
     */
    suspend fun observe(webView: WebView, shellAlive: () -> Boolean): Report {
        delay(WINDOW_MS)
        val report = readCanary(webView)
            ?: return Report(Verdict.PENDING, "读不到金丝雀报告（可能是页面已跳转或内核不支持）")

        // carrier 自己抛了错，最快也最确定的失败信号
        val error = runCatching {
            evaluate(webView, "String(globalThis.__dshFusionCanaryError || '')")
        }.getOrNull()?.trim('"', ' ')
        if (!error.isNullOrBlank()) {
            return Report(Verdict.CARRIER_THREW, "carrier 抛错：$error", report.toString())
        }

        return when {
            // 页面都没起来 → 先怀疑自己：注入失败的表现就是白屏
            !shellAlive() -> Report(Verdict.SHELL_MISSING, "官方界面没渲染出来（注入后白屏）", report.toString())

            report.optBoolean("browserRead", false) -> {
                val used = report.optBoolean("acquired", false)
                Report(
                    Verdict.ENTERED_DESKTOP,
                    if (used) "已进入桌面分支，且用户已使用浏览器面板" else "已进入桌面分支（浏览器面板待使用）",
                    report.toString(),
                )
            }

            !report.optBoolean("read", false) ->
                Report(Verdict.INERT_NOT_READ, "官方客户端没有读 dshDesktop —— 契约变了", report.toString())

            else ->
                Report(Verdict.INERT_NO_BROWSER, "官方读了 dshDesktop 但没读 browser —— 协议版本或字段变了", report.toString())
        }
    }

    private suspend fun readCanary(webView: WebView): JSONObject? = withContext(Dispatchers.Main) {
        runCatching {
            val raw = evaluate(webView, "globalThis.__dshFusionCanary ? __dshFusionCanary() : 'null'")
            if (raw.isNullOrBlank() || raw == "null") return@runCatching null
            // evaluateJavascript 回来的是 JSON 字符串再套一层引号
            val unquoted = runCatching { JSONObject("{\"v\":$raw}").getString("v") }.getOrElse { raw }
            JSONObject(unquoted)
        }.getOrElse {
            LogBus.t(TAG, "读金丝雀失败：${it.message}")
            null
        }
    }

    /** evaluateJavascript 是回调式的，包成 suspend。 */
    private suspend fun evaluate(webView: WebView, js: String): String? =
        withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                if (!cont.isActive) return@suspendCancellableCoroutine
                runCatching {
                    webView.evaluateJavascript(js) { value ->
                        if (cont.isActive) cont.resume(value)
                    }
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    private fun readCarrierAsset(): String? = runCatching {
        context.assets.open(ASSET).use { it.readBytes().decodeToString() }
    }.getOrElse {
        LogBus.e(TAG, "读不到 $ASSET：${it.message}")
        null
    }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    }.getOrDefault("0")

    companion object {
        private const val TAG = "桌面载体"
        const val ASSET = "host/desktop-carrier.js"

        /**
         * 观察窗口。
         *
         * 15 秒是权衡出来的：要覆盖一次官方客户端冷启动（实测它在手机上
         * 一般 2–6 秒起来），又不能长到让契约失效的用户多等一次重载。
         * 取 15 秒留了足够余量 —— **误杀比晚发现更糟**，因为它会把这个功能
         * 从本来能用的用户手里拿掉。
         */
        const val WINDOW_MS = 15_000L

        private const val KEY_TRIPPED_VERSION = "tripped_at_version"
    }
}
