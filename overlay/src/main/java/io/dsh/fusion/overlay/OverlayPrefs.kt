package io.dsh.fusion.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** 悬浮窗权限与服务的开关，UI 只跟这个门面打交道。 */
object OverlayPrefs {

    fun canDrawOverlays(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true

    fun requestPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun enable(context: Context): Boolean {
        if (!canDrawOverlays(context)) {
            requestPermission(context)
            return false
        }
        FloatingBallService.start(context)
        return true
    }

    fun disable(context: Context) = FloatingBallService.stop(context)
}
