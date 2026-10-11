package io.dsh.fusion.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/** 通知渠道常量 —— 引擎、语音、桌宠各走各的，用户可以单独静音。 */
object Channels {
    const val ENGINE = "fusion-engine"
    const val VOICE = "fusion-voice"
    const val PET = "fusion-pet"
    const val CLOUD = "fusion-cloud"
}

fun ensureNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    listOf(
        Triple(Channels.ENGINE, "引擎运行", NotificationManager.IMPORTANCE_LOW),
        Triple(Channels.VOICE, "语音唤醒", NotificationManager.IMPORTANCE_LOW),
        Triple(Channels.PET, "悬浮桌宠", NotificationManager.IMPORTANCE_MIN),
        Triple(Channels.CLOUD, "流体云回复", NotificationManager.IMPORTANCE_DEFAULT),
    ).forEach { (id, name, importance) ->
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }
}

/**
 * 从应用外部（通知栏）也能说话的最小通道。
 * 模型用 `android_notify` 工具调它 —— AI 想在应用外戳你一下时走的就是这里。
 */
fun notify(context: Context, paths: FusionPaths, title: String, text: String, channelId: String = Channels.CLOUD) {
    ensureNotificationChannels(context)
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    val pending = launch?.let {
        PendingIntent.getActivity(
            context,
            (System.currentTimeMillis() and 0xFFFF).toInt(),
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .apply { pending?.let { setContentIntent(it) } }
        .build()
    nm.notify((System.currentTimeMillis() and 0x7FFFFFFF).toInt(), notification)
    LogBus.i("通知", "已推送：$title")
}

/** 常驻前台服务通知（引擎保活用）。 */
fun buildForegroundNotification(
    context: Context,
    channelId: String,
    title: String,
    text: String,
): android.app.Notification {
    ensureNotificationChannels(context)
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    val pending = launch?.let {
        PendingIntent.getActivity(
            context,
            1001,
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    return NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .apply { pending?.let { setContentIntent(it) } }
        .build()
}
