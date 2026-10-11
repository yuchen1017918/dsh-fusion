package io.dsh.fusion.engine.bridge

import io.dsh.fusion.engine.EventBus
import io.dsh.fusion.engine.FusionEvent
import io.dsh.fusion.engine.FusionPaths
import io.dsh.fusion.engine.FusionSettings
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.ToolBus
import org.json.JSONObject

/**
 * 桥的路由表。
 *
 * 只有三个端点，刻意保持极小：
 *   GET  /health        探活（引擎侧和人肉排查都用它）
 *   GET  /capabilities  能力清单（排查「为什么模型看不到某个工具」）
 *   POST /invoke        真正干活的那一个
 */
class BridgeRouter(
    private val paths: FusionPaths,
    private val settings: FusionSettings,
    private val startedAt: Long = System.currentTimeMillis(),
) {

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

        else -> JSONObject().apply {
            put("ok", false)
            put("error", "未知端点 $method $path")
        }
    }

    private fun health(): JSONObject = JSONObject().apply {
        put("ok", true)
        put("uptimeMs", System.currentTimeMillis() - startedAt)
        put("tools", ToolBus.specs().size)
        put("root", paths.root.absolutePath)
        put("backend", settings.backend.id)
        put("shell", io.dsh.fusion.engine.PhantomGuard.childProcessCount())
    }

    private suspend fun invoke(body: JSONObject?): JSONObject {
        if (body == null) {
            return JSONObject().apply { put("ok", false); put("error", "请求体不是合法 JSON") }
        }
        val id = body.optString("id").ifBlank {
            return JSONObject().apply { put("ok", false); put("error", "缺少 id") }
        }
        val args = body.optJSONObject("args") ?: JSONObject()
        val tool = ToolBus.find(id)
            ?: return JSONObject().apply { put("ok", false); put("error", "没有注册名为 $id 的能力") }

        val started = System.currentTimeMillis()
        LogBus.i("桥", "→ $id ${args.toString().take(200)}")
        return try {
            val result = tool.handler.invoke(args)
            val elapsed = System.currentTimeMillis() - started
            LogBus.i("桥", "← $id 成功（${elapsed}ms）")
            EventBus.emit(FusionEvent.ToolInvoked(id, true))
            if (result.optString("channel").isBlank()) {
                result.put("channel", tool.spec.channel.name.lowercase())
            }
            result.put("ok", result.optBoolean("ok", true))
            result.put("elapsedMs", elapsed)
            result
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - started
            LogBus.e("桥", "← $id 失败（${elapsed}ms）：${e.message}")
            EventBus.emit(FusionEvent.ToolInvoked(id, false))
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: e.javaClass.simpleName)
                put("elapsedMs", elapsed)
            }
        }
    }
}
