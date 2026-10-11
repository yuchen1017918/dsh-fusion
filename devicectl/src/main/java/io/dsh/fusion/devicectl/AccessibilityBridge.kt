package io.dsh.fusion.devicectl

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import io.dsh.fusion.engine.LogBus
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 无障碍通道 —— 免 root 操作手机的另一半。
 *
 * Shizuku 给你 shell（强，但要用户装 Shizuku 并授权），
 * 无障碍给你「真正的手」（手势、读屏、输入文本，只用系统自带的开关）。
 * 两者互补：能用无障碍完成的事就不必惊动 shell。
 */
object AccessibilityBridge {

    private const val TAG = "无障碍"

    val service: FusionAccessibilityService? get() = FusionAccessibilityService.instance

    val connected: Boolean get() = service != null

    fun describe(): String =
        if (connected) "已连接（当前前台 ${currentPackageName() ?: "?"}）"
        else "未开启"

    // ------------------------------------------------------------ 手势

    fun tap(x: Float, y: Float): Boolean = gesture { path -> path.moveTo(x, y) }?.let { true } ?: false

    fun longPress(x: Float, y: Float, durationMs: Long = 700L): Boolean {
        val svc = service ?: return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(200L, 8000L))
        return svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 320L): Boolean {
        val svc = service ?: return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(50L, 8000L))
        return svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun gesture(build: (Path) -> Unit): Boolean? {
        val svc = service ?: return null
        val path = Path().apply(build)
        val stroke = GestureDescription.StrokeDescription(path, 0L, 70L)
        return svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // ------------------------------------------------------------ 全局动作

    fun global(action: Int): Boolean = service?.performGlobalAction(action) ?: false

    fun back(): Boolean = global(AccessibilityService.GLOBAL_ACTION_BACK)
    fun home(): Boolean = global(AccessibilityService.GLOBAL_ACTION_HOME)
    fun recents(): Boolean = global(AccessibilityService.GLOBAL_ACTION_RECENTS)
    fun notifications(): Boolean = global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    fun lockScreen(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) else false

    // ------------------------------------------------------------ 文本

    fun setText(text: String): Boolean {
        val svc = service ?: return false
        val root = svc.rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) LogBus.w(TAG, "目标节点不接受 SET_TEXT，可能需要先点一下输入框")
        return ok
    }

    fun clickNodeByText(text: String): Boolean {
        val svc = service ?: return false
        val root = svc.rootInActiveWindow ?: return false
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val label = node.text?.toString() ?: node.contentDescription?.toString()
            if (!label.isNullOrBlank() && label.contains(text)) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }
        return false
    }

    fun currentPackageName(): String? = runCatching { service?.rootInActiveWindow?.packageName?.toString() }.getOrNull()

    // ------------------------------------------------------------ 读屏

    fun dumpTree(maxNodes: Int = 600): JSONObject {
        val svc = service ?: return JSONObject().put("ok", false).put("error", "无障碍服务未连接")
        val root = svc.rootInActiveWindow
            ?: return JSONObject().put("ok", false).put("error", "当前没有活动窗口")
        val arr = JSONArray()
        var count = 0

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count >= maxNodes || depth > 40) return
            count++
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            arr.put(JSONObject().apply {
                put("depth", depth)
                put("text", node.text?.toString() ?: "")
                put("desc", node.contentDescription?.toString() ?: "")
                put("class", node.className?.toString() ?: "")
                put("id", node.viewIdResourceName ?: "")
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("scrollable", node.isScrollable)
                put("checked", node.isChecked)
                put("enabled", node.isEnabled)
                put("bounds", JSONArray().apply {
                    put(rect.left); put(rect.top); put(rect.right); put(rect.bottom)
                })
            })
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }

        walk(root, 0)
        return JSONObject().apply {
            put("ok", true)
            put("package", root.packageName?.toString() ?: "")
            put("nodes", arr)
            put("count", count)
            put("truncated", count >= maxNodes)
        }
    }

    // ------------------------------------------------------------ 截图

    /** 无障碍截图：API 30+ 原生支持，不需要 shell，也不需要任何权限。 */
    fun screenshot(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val svc = service ?: return null
        val latch = CountDownLatch(1)
        var bytes: ByteArray? = null
        runCatching {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, Executor { it.run() }, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    runCatching {
                        val buffer = result.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        val soft = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()
                        if (soft != null) {
                            val out = ByteArrayOutputStream()
                            soft.compress(Bitmap.CompressFormat.PNG, 100, out)
                            bytes = out.toByteArray()
                            soft.recycle()
                        }
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    LogBus.w(TAG, "无障碍截图失败，errorCode=$errorCode")
                    latch.countDown()
                }
            })
        }.onFailure { LogBus.w(TAG, "takeScreenshot 异常：${it.message}") }
        latch.await(6, TimeUnit.SECONDS)
        return bytes
    }
}
