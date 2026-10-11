package io.dsh.fusion.devicectl

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.dsh.fusion.engine.LogBus
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知读取：模型想「知道手机刚才弹了什么」时用得上。
 * 这是独立的一次系统授权（通知使用权），不点开就完全不采集。
 */
class FusionNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        LogBus.i(TAG, "通知读取已连接")
    }

    override fun onListenerDisconnected() {
        connected = false
        LogBus.w(TAG, "通知读取已断开")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val title = n.notification.extras?.getString("android.title") ?: ""
        val text = n.notification.extras?.getString("android.text") ?: ""
        if (title.isBlank() && text.isBlank()) return
        synchronized(ring) {
            ring.addLast(
                JSONObject().apply {
                    put("package", n.packageName)
                    put("title", title)
                    put("text", text)
                    put("time", n.postTime)
                    put("ongoing", n.isOngoing)
                }
            )
            while (ring.size > CAPACITY) ring.removeFirst()
        }
    }

    companion object {
        private const val TAG = "通知"
        private const val CAPACITY = 80

        @Volatile
        var connected: Boolean = false
            private set

        private val ring = ArrayDeque<JSONObject>()

        fun recent(limit: Int = 20): JSONArray = synchronized(ring) {
            JSONArray().also { arr -> ring.toList().takeLast(limit).forEach { arr.put(it) } }
        }

        fun clear() = synchronized(ring) { ring.clear() }
    }
}
