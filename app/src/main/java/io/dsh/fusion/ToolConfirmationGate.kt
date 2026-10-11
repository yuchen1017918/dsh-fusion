package io.dsh.fusion

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import io.dsh.fusion.engine.Channels
import io.dsh.fusion.engine.EventBus
import io.dsh.fusion.engine.FusionEvent
import io.dsh.fusion.engine.FusionRuntime
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.ensureNotificationChannels
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 危险工具的人工确认闸。
 *
 * 为什么要做成「通知 + 广播」而不是应用内对话框：
 * 模型调用 `android_shell` 的时候，用户很可能正在别的应用里 ——
 * 弹一个只有回到本应用才能看到的对话框，等于把这次调用直接拖到超时。
 * 通知栏的「允许 / 拒绝」两个按钮，用户在任何界面都能按到。
 *
 * 超时一律按**拒绝**处理：拿不准的时候，不执行才是安全的默认值。
 */
class ToolConfirmationGate(private val context: Context) : FusionRuntime.ToolConfirmation {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private var receiver: ConfirmationReceiver? = null

    val awaitingCount: Int get() = pending.size

    fun register() {
        if (receiver != null) return
        val r = ConfirmationReceiver()
        val filter = IntentFilter(ACTION_CONFIRM)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(r, filter)
            }
            receiver = r
            LogBus.i(TAG, "确认闸已挂载")
        }.onFailure { LogBus.e(TAG, "确认闸挂载失败：${it.message}") }
    }

    fun unregister() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    override suspend fun confirm(toolId: String, title: String, args: JSONObject): Boolean {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = deferred

        val preview = args.toString().let { if (it.length > 220) it.take(220) + "…" else it }
        LogBus.w(TAG, "等待确认：$toolId $preview")
        EventBus.emit(FusionEvent.Message("confirm", "等待确认：$title"))

        post(id, title, toolId, preview)

        val answer = withTimeoutOrNull(TIMEOUT_MS) { deferred.await() }
        pending.remove(id)
        dismiss(id)

        val allowed = answer == true
        if (answer == null) LogBus.w(TAG, "确认超时（${TIMEOUT_MS / 1000}s），按拒绝处理：$toolId")
        return allowed
    }

    /** 由广播接收器回调。 */
    fun resolve(id: String, allow: Boolean) {
        pending.remove(id)?.complete(allow)
    }

    // ------------------------------------------------------------------ 通知

    private fun post(id: String, title: String, toolId: String, preview: String) {
        ensureNotificationChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, Channels.CONFIRM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("DSH 请求执行：$title")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$toolId\n$preview"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .addAction(0, "拒绝", action(id, false, id.hashCode()))
            .addAction(0, "允许", action(id, true, id.hashCode() + 1))
            .build()

        runCatching { nm.notify(id.hashCode(), notification) }
            .onFailure { LogBus.e(TAG, "发确认通知失败（可能没有通知权限）：${it.message}") }
    }

    private fun action(id: String, allow: Boolean, requestCode: Int): PendingIntent {
        val intent = Intent(ACTION_CONFIRM)
            .setPackage(context.packageName)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_ALLOW, allow)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 危险操作确认属于「必须在极短时间内送达」，不允许系统延后
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun dismiss(id: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.cancel(id.hashCode()) }
    }

    companion object {
        private const val TAG = "确认闸"
        const val ACTION_CONFIRM = "io.dsh.fusion.action.CONFIRM"
        const val EXTRA_ID = "id"
        const val EXTRA_ALLOW = "allow"

        /** 60 秒。通知栏操作足够宽裕，又不会让模型端等到怀疑人生。 */
        const val TIMEOUT_MS = 60_000L
    }
}

/** 通知按钮的回调入口。 */
class ConfirmationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ToolConfirmationGate.ACTION_CONFIRM) return
        val id = intent.getStringExtra(ToolConfirmationGate.EXTRA_ID) ?: return
        val allow = intent.getBooleanExtra(ToolConfirmationGate.EXTRA_ALLOW, false)
        val app = context.applicationContext as? FusionApplication ?: return
        app.confirmationGate.resolve(id, allow)
    }
}
