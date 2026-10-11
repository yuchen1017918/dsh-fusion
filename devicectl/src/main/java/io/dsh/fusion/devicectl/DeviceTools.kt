package io.dsh.fusion.devicectl

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import io.dsh.fusion.engine.FusionPaths
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.Tool
import io.dsh.fusion.engine.ToolBus
import io.dsh.fusion.engine.ToolChannel
import io.dsh.fusion.engine.ToolParam
import io.dsh.fusion.engine.ToolSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 手机能力 → `android_*` 工具。
 *
 * 命名沿用社区约定（Chiyang001/DSHAN、dphmoblie 都用这套前缀），
 * 这样已有的提示词与技能可以直接复用，不必重新调教模型。
 *
 * 每个工具都标了自己走哪条通道（无障碍 / Shizuku / 应用自身），
 * 到手机上就能一眼看出「要不要装 Shizuku」「要不要开无障碍」。
 */
object DeviceTools {

    private const val TAG = "设备"

    fun all(context: Context, paths: FusionPaths, shell: ShellRouter): List<Tool> = listOf(
        deviceInfo(context, shell),
        shellTool(shell),
        screenshot(context, paths, shell),
        tap(),
        swipe(),
        longPress(),
        key(),
        textInput(shell),
        uiTree(),
        foregroundApp(shell),
        launchApp(shell),
        listApps(context),
        fileList(),
        fileRead(),
        fileWrite(),
        fileSearch(),
        clipboardGet(context),
        clipboardSet(context),
        screenPower(shell),
        volume(shell),
        brightness(shell),
        notifications(),
        openUrl(context),
        notify(context, paths),
    )

    // ---------------------------------------------------------------- 基础设施

    private fun ok(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        put("ok", true)
        pairs.forEach { (k, v) -> put(k, v) }
    }

    private fun fail(message: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", message)
    }

    private fun str(args: JSONObject, key: String, default: String? = null): String? =
        if (args.has(key) && !args.isNull(key)) args.get(key).toString() else default

    private fun float(args: JSONObject, key: String): Float? = str(args, key)?.toFloatOrNull()
    private fun int(args: JSONObject, key: String): Int? = str(args, key)?.toFloatOrNull()?.toInt()

    // ---------------------------------------------------------------- 工具

    private fun deviceInfo(context: Context, shell: ShellRouter): Tool {
        val spec = ToolSpec(
            id = "android_device_info",
            title = "设备信息",
            description = "返回机型、Android 版本、ABI、屏幕分辨率与像素密度，以及当前可用的设备控制通道。",
            channel = ToolChannel.APP,
        )
        return Tool(spec) {
            val metrics = context.resources.displayMetrics
            ok(
                "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "android" to Build.VERSION.RELEASE,
                "sdk" to Build.VERSION.SDK_INT,
                "abis" to JSONArray().also { arr -> Build.SUPPORTED_ABIS.forEach { arr.put(it) } },
                "screen" to JSONObject().apply {
                    put("width", metrics.widthPixels)
                    put("height", metrics.heightPixels)
                    put("density", metrics.density)
                },
                "channels" to JSONObject().apply {
                    put("accessibility", AccessibilityBridge.connected)
                    put("shizuku", shell.shizuku.available())
                    put("root", shell.root.available())
                    put("notifications", FusionNotificationListener.connected)
                },
            )
        }
    }

    private fun shellTool(shell: ShellRouter): Tool {
        val spec = ToolSpec(
            id = "android_shell",
            title = "执行 shell 命令",
            description = "在当前可用的最高权限通道上执行一条 shell 命令。Shizuku 或 Root 可用时是系统权限，否则只有应用自身权限。",
            channel = ToolChannel.SHIZUKU,
            dangerous = true,
            params = listOf(ToolParam("command", "string", true, "要执行的命令")),
        )
        return Tool(spec) { args ->
            val command = str(args, "command") ?: return@Tool fail("缺少 command")
            val backend = shell.best()
            when (val r = backend.exec(command)) {
                is ShellResult.Ok -> ok(
                    "channel" to backend.id,
                    "code" to r.code,
                    "stdout" to r.stdout.take(200_000),
                    "stderr" to r.stderr.take(20_000),
                )
                is ShellResult.Failure -> fail(r.message)
            }
        }
    }

    private fun screenshot(context: Context, paths: FusionPaths, shell: ShellRouter): Tool {
        val spec = ToolSpec(
            id = "android_screenshot",
            title = "截屏",
            description = "抓取当前屏幕。优先走无障碍（无需任何额外授权），失败时退回 shell 的 screencap。图片会同时落盘，返回路径可直接交给读图工具。",
            channel = ToolChannel.ACCESSIBILITY,
        )
        return Tool(spec) {
            var bytes = AccessibilityBridge.screenshot()
            var via = "accessibility"
            if (bytes == null) {
                val backend = shell.privileged() ?: shell.best()
                bytes = backend.execBytes("screencap -p")
                via = backend.id
            }
            if (bytes == null || bytes.isEmpty()) return@Tool fail("截屏失败：无障碍与 shell 都没拿到画面")

            val dir = File(paths.root, "shots").apply { mkdirs() }
            val file = File(dir, "shot-${System.currentTimeMillis()}.png")
            file.writeBytes(bytes)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

            ok(
                "channel" to via,
                "path" to file.absolutePath,
                "bytes" to bytes.size,
                "width" to bounds.outWidth,
                "height" to bounds.outHeight,
                "base64" to Base64.encodeToString(bytes, Base64.NO_WRAP),
            )
        }
    }

    private fun tap(): Tool {
        val spec = ToolSpec(
            id = "android_tap",
            title = "点击屏幕",
            description = "在屏幕坐标 (x, y) 上点一下。坐标是物理像素，左上角为原点。",
            channel = ToolChannel.ACCESSIBILITY,
            params = listOf(
                ToolParam("x", "number", true, "横坐标"),
                ToolParam("y", "number", true, "纵坐标"),
            ),
        )
        return Tool(spec) { args ->
            val x = float(args, "x") ?: return@Tool fail("缺少 x")
            val y = float(args, "y") ?: return@Tool fail("缺少 y")
            if (AccessibilityBridge.tap(x, y)) ok("x" to x, "y" to y, "channel" to "accessibility")
            else fail("点击失败：无障碍服务未开启")
        }
    }

    private fun swipe(): Tool {
        val spec = ToolSpec(
            id = "android_swipe",
            title = "滑动屏幕",
            description = "从 (x1, y1) 滑到 (x2, y2)，duration 毫秒决定快慢（默认 320）。",
            channel = ToolChannel.ACCESSIBILITY,
            params = listOf(
                ToolParam("x1", "number", true, "起点 x"),
                ToolParam("y1", "number", true, "起点 y"),
                ToolParam("x2", "number", true, "终点 x"),
                ToolParam("y2", "number", true, "终点 y"),
                ToolParam("duration", "number", false, "毫秒", "320"),
            ),
        )
        return Tool(spec) { args ->
            val x1 = float(args, "x1") ?: return@Tool fail("缺少 x1")
            val y1 = float(args, "y1") ?: return@Tool fail("缺少 y1")
            val x2 = float(args, "x2") ?: return@Tool fail("缺少 x2")
            val y2 = float(args, "y2") ?: return@Tool fail("缺少 y2")
            val d = int(args, "duration")?.toLong() ?: 320L
            if (AccessibilityBridge.swipe(x1, y1, x2, y2, d)) ok("channel" to "accessibility")
            else fail("滑动失败：无障碍服务未开启")
        }
    }

    private fun longPress(): Tool {
        val spec = ToolSpec(
            id = "android_long_press",
            title = "长按屏幕",
            description = "在 (x, y) 长按，默认 700 毫秒。",
            channel = ToolChannel.ACCESSIBILITY,
            params = listOf(
                ToolParam("x", "number", true, "横坐标"),
                ToolParam("y", "number", true, "纵坐标"),
                ToolParam("duration", "number", false, "毫秒", "700"),
            ),
        )
        return Tool(spec) { args ->
            val x = float(args, "x") ?: return@Tool fail("缺少 x")
            val y = float(args, "y") ?: return@Tool fail("缺少 y")
            val d = int(args, "duration")?.toLong() ?: 700L
            if (AccessibilityBridge.longPress(x, y, d)) ok("channel" to "accessibility")
            else fail("长按失败：无障碍服务未开启")
        }
    }

    private fun key(): Tool {
        val spec = ToolSpec(
            id = "android_key",
            title = "按键 / 全局动作",
            description = "命名动作：back / home / recents / notifications / lock / power；其它值当作 Android keyevent 编码发送。",
            channel = ToolChannel.ACCESSIBILITY,
            params = listOf(ToolParam("name", "string", true, "动作名或 keyevent 编码")),
        )
        return Tool(spec) { args ->
            val name = str(args, "name")?.lowercase() ?: return@Tool fail("缺少 name")
            val handled = when (name) {
                "back" -> AccessibilityBridge.back()
                "home" -> AccessibilityBridge.home()
                "recents", "recent" -> AccessibilityBridge.recents()
                "notifications", "notification" -> AccessibilityBridge.notifications()
                "lock" -> AccessibilityBridge.lockScreen()
                else -> false
            }
            if (handled) return@Tool ok("action" to name, "channel" to "accessibility")

            val code = name.toIntOrNull() ?: when (name) {
                "power" -> 26
                "volume_up" -> 24
                "volume_down" -> 25
                "enter" -> 66
                "delete", "backspace" -> 67
                "home_key" -> 3
                else -> return@Tool fail("不认识的动作：$name")
            }
            fail("无障碍未开启，且 '$name' 需要 shell 通道（keyevent $code）")
        }
    }

    private fun textInput(shell: ShellRouter): Tool {
        val spec = ToolSpec(
            id = "android_text_input",
            title = "输入文本",
            description = "把文本写进当前获得焦点的输入框。优先走无障碍的 SET_TEXT（支持中文），退回 shell 的 input text（仅 ASCII）。",
            channel = ToolChannel.ACCESSIBILITY,
            params = listOf(ToolParam("text", "string", true, "要输入的文本")),
        )
        return Tool(spec) { args ->
            val text = str(args, "text") ?: return@Tool fail("缺少 text")
            if (AccessibilityBridge.setText(text)) return@Tool ok("channel" to "accessibility", "length" to text.length)
            val escaped = text.replace(" ", "%s")
            if (escaped.any { it.code > 127 }) {
                return@Tool fail("无障碍不可用，且 shell 的 input text 无法输入非 ASCII 文本")
            }
            when (val r = shell.exec("input text '$escaped'")) {
                is ShellResult.Ok -> ok("channel" to shell.best().id, "code" to r.code)
                is ShellResult.Failure -> fail(r.message)
            }
        }
    }

    private fun uiTree(): Tool {
        val spec = ToolSpec(
            id = "android_ui_tree",
            title = "读取界面结构",
            description = "导出当前界面的无障碍节点树（文本、可点击、坐标）。模型据此决定点哪里，比盲点靠谱得多。",
            channel = ToolChannel.ACCESSIBILITY,
            params = listOf(ToolParam("max_nodes", "number", false, "最多返回多少个节点", "600")),
        )
        return Tool(spec) { args -> AccessibilityBridge.dumpTree(int(args, "max_nodes") ?: 600) }
    }

    private fun foregroundApp(shell: ShellRouter): Tool {
        val spec = ToolSpec(
            id = "android_foreground_app",
            title = "当前前台应用",
            description = "返回正在前台的应用包名。",
            channel = ToolChannel.ACCESSIBILITY,
        )
        return Tool(spec) {
            AccessibilityBridge.currentPackageName()?.let { return@Tool ok("package" to it, "channel" to "accessibility") }
            when (val r = shell.exec("dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp'")) {
                is ShellResult.Ok -> ok("raw" to r.trimmed, "channel" to shell.best().id)
                is ShellResult.Failure -> fail(r.message)
            }
        }
    }

    private fun launchApp(shell: ShellRouter): Tool {
        val spec = ToolSpec(
            id = "android_launch_app",
            title = "启动应用",
            description = "按包名启动应用（monkey 方式拉起默认 Activity）。",
            channel = ToolChannel.SHIZUKU,
            params = listOf(ToolParam("package", "string", true, "应用包名")),
        )
        return Tool(spec) { args ->
            val pkg = str(args, "package") ?: return@Tool fail("缺少 package")
            when (val r = shell.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1")) {
                is ShellResult.Ok ->
                    if (r.stdout.contains("No activities found") || r.stderr.contains("monkey aborted")) fail("启动失败：$pkg 不存在或没有可启动的界面")
                    else ok("package" to pkg, "channel" to shell.best().id)
                is ShellResult.Failure -> fail(r.message)
            }
        }
    }

    private fun listApps(context: Context): Tool {
        val spec = ToolSpec(
            id = "android_list_apps",
            title = "列出已安装应用",
            description = "列出设备上已安装的应用（包名 + 应用名 + 是否系统应用）。",
            channel = ToolChannel.APP,
            params = listOf(ToolParam("filter", "string", false, "按包名或应用名模糊过滤")),
        )
        return Tool(spec) { args ->
            val filter = str(args, "filter")?.lowercase()
            val pm = context.packageManager
            val arr = JSONArray()
            runCatching {
                val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledApplications(android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(0)
                }
                apps.forEach { info ->
                    val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                    if (filter == null || info.packageName.lowercase().contains(filter) || label.lowercase().contains(filter)) {
                        arr.put(JSONObject().apply {
                            put("package", info.packageName)
                            put("label", label)
                            put("system", (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                        })
                    }
                }
            }.onFailure { LogBus.w(TAG, "枚举应用失败：${it.message}") }
            ok("count" to arr.length(), "apps" to arr)
        }
    }

    private fun fileList(): Tool {
        val spec = ToolSpec(
            id = "android_file_list",
            title = "列目录",
            description = "列出目录内容。路径是设备真实路径；受 Android 沙箱限制，只有应用能访问的位置才读得到。",
            channel = ToolChannel.APP,
            params = listOf(ToolParam("path", "string", true, "目录绝对路径")),
        )
        return Tool(spec) { args ->
            val path = str(args, "path") ?: return@Tool fail("缺少 path")
            val dir = File(path)
            if (!dir.isDirectory) return@Tool fail("不是目录或无权访问：$path")
            val arr = JSONArray()
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                arr.put(JSONObject().apply {
                    put("name", f.name)
                    put("dir", f.isDirectory)
                    put("size", if (f.isFile) f.length() else 0)
                    put("readable", f.canRead())
                })
            }
            ok("path" to dir.absolutePath, "entries" to arr)
        }
    }

    private fun fileRead(): Tool {
        val spec = ToolSpec(
            id = "android_file_read",
            title = "读文件",
            description = "读取文本文件内容（默认最多 256 KB）。",
            channel = ToolChannel.APP,
            params = listOf(
                ToolParam("path", "string", true, "文件绝对路径"),
                ToolParam("max_bytes", "number", false, "最多读取字节数", "262144"),
            ),
        )
        return Tool(spec) { args ->
            val path = str(args, "path") ?: return@Tool fail("缺少 path")
            val max = int(args, "max_bytes") ?: 262_144
            val f = File(path)
            if (!f.isFile) return@Tool fail("不是文件或无权访问：$path")
            val bytes = f.inputStream().use { input -> input.readBytes().take(max) }
            ok("path" to f.absolutePath, "size" to f.length(), "text" to String(bytes.toByteArray(), Charsets.UTF_8))
        }
    }

    private fun fileWrite(): Tool {
        val spec = ToolSpec(
            id = "android_file_write",
            title = "写文件",
            description = "写入文本文件（覆盖），自动创建父目录。",
            channel = ToolChannel.APP,
            dangerous = true,
            params = listOf(
                ToolParam("path", "string", true, "文件绝对路径"),
                ToolParam("content", "string", true, "文本内容"),
                ToolParam("append", "boolean", false, "是否追加", "false"),
            ),
        )
        return Tool(spec) { args ->
            val path = str(args, "path") ?: return@Tool fail("缺少 path")
            val content = str(args, "content") ?: ""
            val append = str(args, "append")?.toBoolean() ?: false
            val f = File(path)
            f.parentFile?.mkdirs()
            runCatching { if (append) f.appendText(content) else f.writeText(content) }
                .fold(
                    onSuccess = { ok("path" to f.absolutePath, "bytes" to f.length()) },
                    onFailure = { fail("写入失败：${it.message}") },
                )
        }
    }

    private fun fileSearch(): Tool {
        val spec = ToolSpec(
            id = "android_file_search",
            title = "搜索文件名",
            description = "在指定根目录下按名称片段递归搜索（限深度与结果数），找文件用。",
            channel = ToolChannel.APP,
            params = listOf(
                ToolParam("root", "string", true, "起始目录"),
                ToolParam("keyword", "string", true, "文件名包含的关键字"),
                ToolParam("max_depth", "number", false, "递归深度", "6"),
                ToolParam("limit", "number", false, "最多返回条数", "120"),
            ),
        )
        return Tool(spec) { args ->
            val rootPath = str(args, "root") ?: return@Tool fail("缺少 root")
            val keyword = str(args, "keyword")?.lowercase() ?: return@Tool fail("缺少 keyword")
            val maxDepth = int(args, "max_depth") ?: 6
            val limit = int(args, "limit") ?: 120
            val root = File(rootPath)
            if (!root.isDirectory) return@Tool fail("不是目录或无权访问：$rootPath")
            val hits = JSONArray()

            fun walk(dir: File, depth: Int) {
                if (depth > maxDepth || hits.length() >= limit) return
                val children = runCatching { dir.listFiles() }.getOrNull() ?: return
                for (f in children) {
                    if (hits.length() >= limit) return
                    if (f.name.lowercase().contains(keyword)) {
                        hits.put(JSONObject().apply {
                            put("path", f.absolutePath)
                            put("dir", f.isDirectory)
                            put("size", if (f.isFile) f.length() else 0)
                        })
                    }
                    if (f.isDirectory && !f.name.startsWith(".")) walk(f, depth + 1)
                }
            }

            walk(root, 1)
            ok("root" to root.absolutePath, "count" to hits.length(), "hits" to hits)
        }
    }

    private fun clipboardGet(context: Context): Tool {
        val spec = ToolSpec(
            id = "android_clipboard_get",
            title = "读剪贴板",
            description = "读取系统剪贴板里的文本。",
            channel = ToolChannel.APP,
        )
        return Tool(spec) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
            ok("text" to text, "empty" to text.isEmpty())
        }
    }

    private fun clipboardSet(context: Context): Tool {
        val spec = ToolSpec(
            id = "android_clipboard_set",
            title = "写剪贴板",
            description = "把文本放进系统剪贴板，用户随即可以粘贴。",
            channel = ToolChannel.APP,
            params = listOf(ToolParam("text", "string", true, "要放入剪贴板的文本")),
        )
        return Tool(spec) { args ->
            val text = str(args, "text") ?: return@Tool fail("缺少 text")
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("dsh-fusion", text))
            ok("length" to text.length)
        }
    }

    private fun screenPower(shell: ShellRouter): Tool {
        val spec = ToolSpec(
            id = "android_screen_power",
            title = "电源键 / 息屏",
            description = "toggle=开关屏；sleep=直接息屏；wake=唤醒。",
            channel = ToolChannel.SHIZUKU,
            params = listOf(ToolParam("action", "string", false, "toggle | sleep | wake", "toggle")),
        )
        return Tool(spec) { args ->
            val action = str(args, "action", "toggle")!!.lowercase()
            val command = when (action) {
                "sleep", "off" -> "input keyevent 26"
                "wake", "on" -> "input keyevent 224"
                else -> "input keyevent 26"
            }
            when (val r = shell.exec(command)) {
                is ShellResult.Ok -> ok("action" to action, "channel" to shell.best().id)
                is ShellResult.Failure -> fail(r.message)
            }
        }
    }

    private fun volume(shell: ShellRouter): Tool {
        val spec = ToolSpec(
            id = "android_volume",
            title = "音量",
            description = "设置媒体音量（0-15）或静音/最大。",
            channel = ToolChannel.SHIZUKU,
            params = listOf(ToolParam("level", "string", true, "0-15 / mute / max")),
        )
        return Tool(spec) { args ->
            val level = str(args, "level") ?: return@Tool fail("缺少 level")
            val value = when (level) {
                "mute" -> 0
                "max" -> 15
                else -> level.toIntOrNull()?.coerceIn(0, 15) ?: return@Tool fail("level 需要是 0-15 / mute / max")
            }
            when (val r = shell.exec("media volume --stream 3 --set $value")) {
                is ShellResult.Ok -> ok("level" to value, "channel" to shell.best().id)
                is ShellResult.Failure -> fail(r.message)
            }
        }
    }

    private fun brightness(shell: ShellRouter): Tool {
        val spec = ToolSpec(
            id = "android_brightness",
            title = "屏幕亮度",
            description = "设置屏幕亮度（0-255），同时关掉自动亮度以免被改回去。",
            channel = ToolChannel.SHIZUKU,
            params = listOf(ToolParam("value", "number", true, "0-255")),
        )
        return Tool(spec) { args ->
            val value = int(args, "value")?.coerceIn(0, 255) ?: return@Tool fail("缺少 value")
            val command = "settings put system screen_brightness_mode 0; settings put system screen_brightness $value"
            when (val r = shell.exec(command)) {
                is ShellResult.Ok -> ok("value" to value, "channel" to shell.best().id)
                is ShellResult.Failure -> fail(r.message)
            }
        }
    }

    private fun notifications(): Tool {
        val spec = ToolSpec(
            id = "android_notifications",
            title = "读取通知",
            description = "返回最近收到的通知（需用户开启「通知使用权」）。",
            channel = ToolChannel.APP,
            params = listOf(
                ToolParam("limit", "number", false, "返回条数", "20"),
                ToolParam("clear", "boolean", false, "读取后是否清空", "false"),
            ),
        )
        return Tool(spec) { args ->
            if (!FusionNotificationListener.connected) {
                return@Tool fail("通知读取未授权：请在系统设置里开启「通知使用权」")
            }
            val limit = int(args, "limit") ?: 20
            val list = FusionNotificationListener.recent(limit)
            if (str(args, "clear")?.toBoolean() == true) FusionNotificationListener.clear()
            ok("count" to list.length(), "notifications" to list)
        }
    }

    private fun openUrl(context: Context): Tool {
        val spec = ToolSpec(
            id = "android_open_url",
            title = "打开链接",
            description = "用系统默认浏览器（或对应应用）打开一个 URL。",
            channel = ToolChannel.APP,
            params = listOf(ToolParam("url", "string", true, "http(s) 链接")),
        )
        return Tool(spec) { args ->
            val url = str(args, "url") ?: return@Tool fail("缺少 url")
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.fold(onSuccess = { ok("url" to url) }, onFailure = { fail("打开失败：${it.message}") })
        }
    }

    private fun notify(context: Context, paths: FusionPaths): Tool {
        val spec = ToolSpec(
            id = "android_notify",
            title = "发一条系统通知",
            description = "把消息推到系统通知栏 —— 模型想在应用外跟用户说话时用它（配合流体云胶囊使用）。",
            channel = ToolChannel.APP,
            params = listOf(
                ToolParam("title", "string", true, "标题"),
                ToolParam("text", "string", true, "正文"),
            ),
        )
        return Tool(spec) { args ->
            val title = str(args, "title") ?: "DSH"
            val text = str(args, "text") ?: ""
            runCatching { io.dsh.fusion.engine.notify(context, paths, title, text) }
                .fold(onSuccess = { ok("posted" to true) }, onFailure = { fail("通知失败：${it.message}") })
        }
    }

    /** 便捷：把无障碍里的常量暴露给上层做状态展示。 */
    fun accessibilityActions(): List<String> = listOf("back", "home", "recents", "notifications", "lock")

    @Suppress("unused")
    private val globalActionConstants = listOf(
        AccessibilityService.GLOBAL_ACTION_BACK,
        AccessibilityService.GLOBAL_ACTION_HOME,
    )

    @Suppress("unused")
    private val settingsHints = listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS)
}
