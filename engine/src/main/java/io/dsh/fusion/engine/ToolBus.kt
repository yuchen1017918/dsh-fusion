package io.dsh.fusion.engine

import org.json.JSONArray
import org.json.JSONObject

/** 一个能力由谁执行 —— 决定它在设备上要不要额外授权。 */
enum class ToolChannel { APP, SHIZUKU, ACCESSIBILITY, ROOT }

data class ToolParam(
    val name: String,
    val type: String,
    val required: Boolean = false,
    val description: String = "",
    val default: String? = null,
)

data class ToolSpec(
    val id: String,
    val title: String,
    val description: String,
    val channel: ToolChannel,
    val dangerous: Boolean = false,
    val params: List<ToolParam> = emptyList(),
) {
    /** 输出为 dsh 侧插件注册工具时用的 JSON Schema 片段。 */
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", id)
        put("title", title)
        put("description", description)
        put("channel", channel.name)
        put("dangerous", dangerous)
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                params.forEach { p ->
                    put(p.name, JSONObject().apply {
                        put("type", p.type)
                        put("description", p.description)
                        if (p.default != null) put("default", p.default)
                    })
                }
            })
            put("required", JSONArray().apply { params.filter { it.required }.forEach { put(it.name) } })
        })
    }
}

/** 工具实现：收 JSON 参数，回 JSON 结果；抛异常即失败。 */
fun interface ToolHandler {
    suspend fun invoke(args: JSONObject): JSONObject
}

class Tool(val spec: ToolSpec, val handler: ToolHandler)

/**
 * 全局能力总线 —— 融合体的中枢。
 *
 * 每个域（设备控制 / 语音 / 悬浮球 / 应用自身）把自己的能力注册进来，
 * 桥接服务把它翻译成 dsh 侧的 `android_*` 工具。新增域不需要改桥。
 */
object ToolBus {

    private val tools = LinkedHashMap<String, Tool>()
    private val listeners = ArrayList<() -> Unit>()

    @Synchronized
    fun register(tool: Tool) {
        tools[tool.spec.id] = tool
        notifyChanged()
    }

    @Synchronized
    fun registerAll(list: List<Tool>) {
        list.forEach { tools[it.spec.id] = it }
        notifyChanged()
    }

    @Synchronized
    fun unregister(id: String) {
        if (tools.remove(id) != null) notifyChanged()
    }

    @Synchronized
    fun specs(): List<ToolSpec> = tools.values.map { it.spec }

    @Synchronized
    fun find(id: String): Tool? = tools[id]

    @Synchronized
    fun addListener(l: () -> Unit) { listeners.add(l) }

    private fun notifyChanged() {
        val copy = synchronized(this) { ArrayList(listeners) }
        copy.forEach { runCatching { it() } }
    }

    fun describe(): JSONArray = JSONArray().also { arr -> specs().forEach { arr.put(it.toJson()) } }
}
