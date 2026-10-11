package io.dsh.fusion.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力清单的 JSON 形状。
 *
 * 这份 JSON 会被写进 tools.json，由 node 侧插件读出来注册成 dsh 工具。
 * 形状错了的表现是「插件静默不注册任何工具」—— 最糟的一种失败：
 * 不报错、没日志、用户只觉得「模型好像看不见我的手机」。
 * 所以这里把契约钉死。
 */
class ToolSpecJsonTest {

    private val spec = ToolSpec(
        id = "android_tap",
        title = "点击屏幕",
        description = "在坐标上点一下",
        channel = ToolChannel.ACCESSIBILITY,
        dangerous = false,
        params = listOf(
            ToolParam("x", "number", required = true, description = "横坐标"),
            ToolParam("y", "number", required = true, description = "纵坐标"),
            ToolParam("duration", "number", required = false, description = "毫秒", default = "70"),
        ),
    )

    @Test
    fun `顶层字段齐全且名字与插件端一致`() {
        val json = spec.toJson()
        assertEquals("android_tap", json.getString("name"))
        assertEquals("点击屏幕", json.getString("title"))
        assertEquals("ACCESSIBILITY", json.getString("channel"))
        assertFalse(json.getBoolean("dangerous"))
    }

    @Test
    fun `required 数组只含必填项且顺序稳定`() {
        val json = spec.toJson().getJSONObject("parameters")
        assertEquals("object", json.getString("type"))
        val required = json.getJSONArray("required")
        assertEquals(2, required.length())
        assertEquals("x", required.getString(0))
        assertEquals("y", required.getString(1))
        assertFalse(
            "可选参数不该出现在 required 里",
            (0 until required.length()).any { required.getString(it) == "duration" },
        )
    }

    @Test
    fun `可选参数带 default，必填参数不带`() {
        val props = spec.toJson().getJSONObject("parameters").getJSONObject("properties")
        assertTrue(props.getJSONObject("duration").has("default"))
        assertEquals("70", props.getJSONObject("duration").getString("default"))
        assertFalse(props.getJSONObject("x").has("default"))
        assertEquals("number", props.getJSONObject("x").getString("type"))
    }

    @Test
    fun `无参数工具输出空的 properties 而不是缺字段`() {
        val bare = ToolSpec(id = "android_device_info", title = "设备信息", description = "", channel = ToolChannel.APP)
        val json = bare.toJson().getJSONObject("parameters")
        assertEquals(0, json.getJSONObject("properties").length())
        assertEquals(0, json.getJSONArray("required").length())
    }

    @Test
    fun `危险工具会被标记（插件据此在描述里加提醒）`() {
        val dangerous = ToolSpec(
            id = "android_shell", title = "执行命令", description = "", channel = ToolChannel.SHIZUKU, dangerous = true,
        )
        assertTrue(dangerous.toJson().getBoolean("dangerous"))
    }

    @Test
    fun `能力总线注册后可枚举且描述是合法 JSON 数组`() {
        ToolBus.register(Tool(spec) { JSONObject().put("ok", true) })
        val describe = ToolBus.describe()
        assertTrue(describe.length() >= 1)
        val found = (0 until describe.length()).map { describe.getJSONObject(it).getString("name") }
        assertTrue("注册过的工具应当出现在清单里", found.contains("android_tap"))
    }
}
