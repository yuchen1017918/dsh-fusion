package io.dsh.fusion.devicectl

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import io.dsh.fusion.engine.LogBus

/**
 * 服务本体极薄 —— 所有能力都放在 [AccessibilityBridge] 里，
 * 这样即便系统把服务杀了，工具的报错也是「未连接」而不是崩溃。
 */
class FusionAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        LogBus.i(TAG, "无障碍服务已连接 —— 手势 / 读屏 / 输入能力上线")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != lastPackage) {
            lastPackage = pkg
            LogBus.t(TAG, "前台应用 -> $pkg")
        }
    }

    override fun onInterrupt() {
        LogBus.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        instance = null
        LogBus.w(TAG, "无障碍服务已断开")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "无障碍"

        @Volatile
        var instance: FusionAccessibilityService? = null
            private set

        @Volatile
        private var lastPackage: String? = null
    }
}
