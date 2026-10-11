package io.dsh.fusion.engine.bridge

import io.dsh.fusion.engine.EventBus
import io.dsh.fusion.engine.FusionEvent
import io.dsh.fusion.engine.FusionPaths
import io.dsh.fusion.engine.FusionRuntime
import io.dsh.fusion.engine.FusionSettings
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.PhantomGuard
import io.dsh.fusion.engine.ToolBus
import org.json.JSONObject

/**
 * 桥的路由表。
 *
 * 四个端点，刻意保持极小：
 *   GET  /health        探活（引擎侧和人肉排查都用它）
 *   GET  /capabilities  能力清单（排查「为什么模型看不到某个工具」）
 *   POST /invoke        模型调手机能力
 *   POST /event         手机反向往上推（模型回复 → 流体云胶囊）
 *
 * 前三个是「模型 → 手机」，第四个是「模型 → 手机」之外唯一的一条回程。
 * 没有回程的话，屏幕顶部的胶囊就永远不会有东西可显示 —— 那正是它之前的状态。
 */
class BridgeRouter(
    private val paths: FusionPaths,
    private val settings: FusionSettings,
    private val startedAt: Long = System.currentTimeMillis(),
) {

    /** 已受理的事件数，`/health` 里能看到，方便排查「胶囊为什么不动」。 */
    @Volatile
    private var eventsAccepted: Int = 0

    @Volatile
    private var lastEventAt: Long = 0L

    suspend fun route(
        method: String,
        path: String,
        query: Map<String, String>,
        body: JSONObject?,
    ): JSONObject = when {
        path == "/health" && method == "GET" -> health()

        path == "/capabilities" && method == "GET" -> JSONObject().apply {
            put("ok", true)
            put("count", ToolBus.specs().size)
            put("tools", ToolBus.describe())
        }

        path == "/invoke" && method == "POST" -> invoke(body)

        path == "/event" && method == "POST" -> acceptEvent(body)

        else -> JSONObject().apply {
            put("ok", false)
            put("error", "未知端点 $method $path")
        }
    }

    // ---------------------------------------------------------------- 探活

    private fun health(): JSONObject = JSONObject().apply {
        put("ok", true)
        put("uptimeMs", System.currentTimeMillis() - startedAt)
        put("tools", ToolBus.specs().size)
        put("root", paths.root.absolutePath)
        put("backend", settings.backend.id)
        put("children", PhantomGuard.childProcessCount() ?: JSONObject.NULL)
        put("eventsAccepted", eventsAccepted)
        put("lastEventAgeMs", if (lastEventAt == 0L) JSONObject.NULL else System.currentTimeMillis() - lastEventAt)
        put("confirmationGate", settings.confirmDangerous && FusionRuntime.confirmation() != null)
    }

    // ---------------------------------------------------------------- 调用

    private suspend fun invoke(body: JSONObject?): JSONObject {
        if (body == null) {
            return fail("请求体不是合法 JSON")
        }
        val id = body.optString("id").ifBlank { return fail("缺少 id") }
        val args = body.optJSONObject("args") ?: JSONObject()
        val tool = ToolBus.find(id) ?: return fail("没有注册名为 $id 的能力")

        // 危险工具过人工确认闸。
        // 在此之前 `confirmDangerous` 只是个存在但不被任何代码读取的开关 ——
        // 对安全类开关来说，「假的」比「没有」更糟：用户以为它拦得住。
        if (tool.spec.dangerous && settings.confirmDangerous) {
            val gate = FusionRuntime.confirmation()
            if (gate == null) {
                LogBus.w(TAG, "$id 是危险工具，但确认闸未挂载 —— 按拒绝处理")
                return fail("$id 需要人工确认，但确认通道不可用；请检查通知权限")
            }
            LogBus.w(TAG, "$id 是危险工具，等待人工确认…")
            val allowed = runCatching { gate.confirm(id, tool.spec.title, args) }.getOrDefault(false)
            if (!allowed) {
                LogBus.w(TAG, "$id 被拒绝（超时按拒绝处理）")
                EventBus.emit(FusionEvent.ToolInvoked(id, false))
                return fail("用户未批准执行 $id")
            }
            LogBus.i(TAG, "$id 已获人工批准")
        }

        val started = System.currentTimeMillis()
        LogBus.i(TAG, "→ $id ${args.toString().take(200)}")
        return try {
            val result = tool.handler.invoke(args)
            val elapsed = System.currentTimeMillis() - started
            LogBus.i(TAG, "← $id 成功（${elapsed}ms）")
            EventBus.emit(FusionEvent.ToolInvoked(id, true))
            if (result.optString("channel").isBlank()) {
                result.put("channel", tool.spec.channel.name.lowercase())
            }
            result.put("ok", result.optBoolean("ok", true))
            result.put("elapsedMs", elapsed)
            result
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - started
            LogBus.e(TAG, "← $id 失败（${elapsed}ms）：${e.message}")
            EventBus.emit(FusionEvent.ToolInvoked(id, false))
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: e.javaClass.simpleName)
                put("elapsedMs", elapsed)
            }
        }
    }

    // ---------------------------------------------------------------- 事件上行

    /**
     * 接收 node 侧插件推上来的会话事件。
     *
     * 这是流体云回复卡唯一的真实数据来源。之前 `FusionEvent.Reply` 只有消费者、
     * 没有生产者，卡片永远不会出现 —— 现在插件订阅 dsh 的 `session/event`，
     * 把 `assistant/message` 的正文推到这里。
     */
    private fun acceptEvent(body: JSONObject?): JSONObject {
        if (body == null) return fail("事件体不是合法 JSON")
        val kind = body.optString("kind").ifBlank { return fail("缺少 kind") }

        return when (kind) {
            "reply" -> {
                val text = body.optString("text").trim()
                if (text.isEmpty()) {
                    return JSONObject().apply { put("ok", true); put("delivered", false) }
                }
                val interrupted = body.optBoolean("interrupted", false)
                val session = body.optString("session").ifBlank { null }
                eventsAccepted++
                lastEventAt = System.currentTimeMillis()
                LogBus.i(
                    TAG,
                    "← 模型回复 ${text.length} 字${if (interrupted) "（被打断）" else ""}" +
                        (session?.let { " [${it.take(8)}]" } ?: ""),
                )
                EventBus.emit(FusionEvent.Reply(text, done = !interrupted))
                JSONObject().apply {
                    put("ok", true)
                    put("delivered", true)
                    put("accepted", eventsAccepted)
                }
            }

            "failure" -> {
                val text = body.optString("text").ifBlank { "未知错误" }
                eventsAccepted++
                lastEventAt = System.currentTimeMillis()
                LogBus.w(TAG, "← 失败事件：$text")
                EventBus.emit(FusionEvent.Failure(text.take(400)))
                JSONObject().apply { put("ok", true); put("delivered", true) }
            }

            else -> fail("未知事件类型 $kind")
        }
    }

    private fun fail(message: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", message)
    }

    private companion object {
        const val TAG = "桥"
    }
}
