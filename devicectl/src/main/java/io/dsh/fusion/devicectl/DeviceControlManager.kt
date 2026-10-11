package io.dsh.fusion.devicectl

import android.content.Context
import android.content.pm.PackageManager
import io.dsh.fusion.engine.FusionPaths
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.ToolBus
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 设备控制域的门口。
 *
 * 负责：探测有哪些通道可用 → 把 [DeviceTools] 注册进能力总线 → 给 UI 一份人话状态。
 * Shizuku 的权限申请也在这里收口，避免散落在 Activity 里。
 */
class DeviceControlManager(
    private val context: Context,
    private val paths: FusionPaths,
) {

    val shell = ShellRouter()

    @Volatile
    private var registered = false

    private var permissionLatch: CountDownLatch? = null

    /** Shizuku 是否已安装（不代表已授权）。 */
    fun shizukuInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    fun shizukuVersion(): Int = runCatching { Shizuku.getVersion() }.getOrDefault(0)

    /** 一次性注册全部 `android_*` 工具。重复调用安全。 */
    fun registerTools() {
        if (registered) return
        registered = true
        val tools = DeviceTools.all(context, paths, shell)
        ToolBus.registerAll(tools)
        LogBus.i(TAG, "已注册 ${tools.size} 个设备工具：" + tools.joinToString(", ") { it.spec.id.removePrefix("android_") })
        LogBus.i(TAG, "可用通道：" + shell.describe().joinToString(" | "))
    }

    fun refresh() {
        registerTools()
    }

    /** 申请 Shizuku 授权（阻塞至多 20 秒）。必须在有前台 Activity 时调用。 */
    fun requestShizukuPermission(): Boolean {
        if (shell.shizuku.available()) return true
        if (!shizukuInstalled()) {
            LogBus.w(TAG, "设备上没有 Shizuku，跳过（可用无障碍与 Root 代替）")
            return false
        }
        return runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
            val latch = CountDownLatch(1)
            permissionLatch = latch
            var granted = false
            val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
                granted = grantResult == PackageManager.PERMISSION_GRANTED
                latch.countDown()
            }
            Shizuku.addRequestPermissionResultListener(listener)
            try {
                Shizuku.requestPermission(REQUEST_CODE)
                latch.await(20, TimeUnit.SECONDS)
            } finally {
                Shizuku.removeRequestPermissionResultListener(listener)
            }
            granted
        }.getOrElse {
            LogBus.e(TAG, "Shizuku 授权异常：${it.message}")
            false
        }
    }

    /** 人类可读的通道状态，UI 直接展示。 */
    fun status(): List<ChannelStatus> = listOf(
        ChannelStatus(
            id = "accessibility",
            label = "无障碍服务",
            ready = AccessibilityBridge.connected,
            detail = AccessibilityBridge.describe(),
            hint = "免 root 的手势 / 读屏 / 输入能力，只需要在系统设置里打开一次",
        ),
        ChannelStatus(
            id = "shizuku",
            label = "Shizuku",
            ready = shell.shizuku.available(),
            detail = if (shell.shizuku.available()) "已授权（API ${shizukuVersion()}）"
            else if (shizukuInstalled()) "已安装，待授权" else "未安装",
            hint = "免 root 拿到系统 shell：截屏、settings、am/pm 全靠它",
        ),
        ChannelStatus(
            id = "root",
            label = "Root",
            ready = shell.root.available(),
            detail = if (shell.root.available()) "su 可用" else "无 su",
            hint = "有 Root 时优先级最高，不再需要 Shizuku",
        ),
        ChannelStatus(
            id = "notification",
            label = "通知读取",
            ready = FusionNotificationListener.connected,
            detail = if (FusionNotificationListener.connected) "已连接" else "未授权",
            hint = "让模型知道手机弹了什么，需要单独开「通知使用权」",
        ),
    )

    data class ChannelStatus(
        val id: String,
        val label: String,
        val ready: Boolean,
        val detail: String,
        val hint: String,
    )

    /** 当前实际生效的那条 shell 通道 id。 */
    fun activeChannelId(): String = shell.best().id

    private companion object {
        const val TAG = "设备控制"
        const val REQUEST_CODE = 4701
    }
}
