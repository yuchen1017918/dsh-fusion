package io.dsh.fusion.devicectl

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import io.dsh.fusion.engine.FusionPaths
import io.dsh.fusion.engine.FusionSettings
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.PhantomGuard
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 设备体检 —— 把「踩过的坑」变成开机可见的红黄绿。
 *
 * 这个类里的每一条都对应一个真实会让 DSH 在手机上死掉的阻塞点：
 * W^X、FUSE 硬链接、WebView 版本、幻影进程、SELinux、前台服务限制……
 * 与其等用户撞墙，不如提前一条条告诉他「这条过了 / 这条要处理」。
 */
class CompatDoctor(
    private val context: Context,
    private val paths: FusionPaths,
    private val settings: FusionSettings,
    private val shell: ShellRouter,
) {

    enum class Level { OK, WARN, BLOCK }

    data class Finding(
        val id: String,
        val title: String,
        val level: Level,
        val detail: String,
        val fix: String? = null,
        val fixCommand: String? = null,
    )

    fun diagnose(): List<Finding> = listOf(
        abiCheck(),
        wxcCheck(),
        hardLinkCheck(),
        dshHomeCheck(),
        webViewCheck(),
        phantomCheck(),
        selinuxCheck(),
        overlayCheck(),
        accessibilityCheck(),
        notificationCheck(),
        foregroundServiceCheck(),
        storageCheck(),
        heapCheck(),
        shellChannelCheck(),
        huaweiInstallCheck(),
        harmonyCompatCheck(),
        apiLevelCheck(),
        batteryCheck(),
    )

    // ------------------------------------------------------------ 运行时

    private fun abiCheck(): Finding {
        // 内置运行时现在出两份：arm64-v8a（真机）与 x86_64（应用宝电脑版的
        // VirtualBox x86_64 安卓虚拟机、以及标准 x86 模拟器）。两个都过。
        val supported = setOf("arm64-v8a", "x86_64")
        val hit = Build.SUPPORTED_ABIS.firstOrNull { it in supported }
        return Finding(
            id = "abi",
            title = "CPU 架构",
            level = if (hit != null) Level.OK else Level.BLOCK,
            detail = if (hit != null) {
                "$hit 命中内置运行时（本机 ABI 顺序：${Build.SUPPORTED_ABIS.joinToString()}）"
            } else {
                "本机 ABI：${Build.SUPPORTED_ABIS.joinToString()} —— 内置运行时只有 arm64-v8a 与 x86_64"
            },
            fix = if (hit != null) null else "换一台 arm64 真机；或在 x86_64 模拟器/应用宝电脑版里运行",
        )
    }

    private fun wxcCheck(): Finding {
        val node = paths.nodeBin
        val exists = node.exists()
        val executable = exists && node.canExecute()
        val inNativeDir = node.absolutePath.contains("/lib/")
        val level = when {
            !exists -> Level.BLOCK
            !inNativeDir -> Level.BLOCK
            else -> Level.OK
        }
        return Finding(
            id = "wxc",
            title = "运行时可执行位（Android 10+ W^X）",
            level = level,
            detail = when {
                !exists -> "找不到 ${node.absolutePath}"
                !inNativeDir -> "运行时不在 nativeLibraryDir 里，execve 会被系统拒绝"
                executable -> "libnode.so 已落在 nativeLibraryDir，具备执行位"
                else -> "在 nativeLibraryDir 里但当前读不到执行位（真机上安装包管理器会给）"
            },
            fix = if (level == Level.OK) null else "确认 APK 的 extractNativeLibs=true（本工程已开启）",
        )
    }

    private fun shellChannelCheck(): Finding {
        val best = shell.best()
        return Finding(
            id = "shell",
            title = "系统命令通道",
            level = if (best.id == "app") Level.WARN else Level.OK,
            detail = shell.describe().joinToString("；"),
            fix = if (best.id == "app") "装 Shizuku 并授权，或给设备 Root；否则截屏/按键等只能走无障碍" else null,
        )
    }

    // ------------------------------------------------------------ 文件系统

    /** FUSE 上不支持 link()，DSH 的会话原子写会炸 —— 所以必须实测一次。 */
    private fun hardLinkCheck(): Finding {
        val probe = File(paths.tmpDir, "link-probe")
        val result = runCatching {
            paths.tmpDir.mkdirs()
            val source = File(probe, "a")
            source.parentFile?.mkdirs()
            source.writeText("probe")
            val target = File(probe, "b")
            Files.createLink(Paths.get(target.absolutePath), Paths.get(source.absolutePath))
            val ok = target.exists()
            source.delete()
            target.delete()
            probe.deleteRecursively()
            ok
        }.getOrDefault(false)

        return Finding(
            id = "hardlink",
            title = "硬链接支持（FUSE 检查）",
            level = if (result) Level.OK else Level.BLOCK,
            detail = if (result) "私有目录支持 link()，会话可以原子落盘" else "当前 DSH_HOME 所在文件系统不支持 link()",
            fix = if (result) null else "把 DSH_HOME 放到应用私有目录（ext4/f2fs），不要放 /sdcard（FUSE）",
        )
    }

    private fun dshHomeCheck(): Finding {
        val home = paths.dshHome.absolutePath
        val onExternal = home.startsWith("/sdcard") || home.contains("/emulated/")
        return Finding(
            id = "dshhome",
            title = "DSH_HOME 位置",
            level = if (onExternal) Level.BLOCK else Level.OK,
            detail = home,
            fix = if (onExternal) "改回 filesDir（FUSE 会让 dsh 的原子写失败，进程看起来活着但界面永远转圈）" else null,
        )
    }

    private fun storageCheck(): Finding {
        val stat = runCatching { StatFs(paths.root.absolutePath) }.getOrNull()
        val freeMb = stat?.let { it.availableBytes / 1024 / 1024 } ?: -1L
        val level = when {
            freeMb < 0 -> Level.WARN
            freeMb < 400 -> Level.BLOCK
            freeMb < 1200 -> Level.WARN
            else -> Level.OK
        }
        return Finding(
            id = "storage",
            title = "可用存储",
            level = level,
            detail = if (freeMb >= 0) "剩余 $freeMb MB（内核约 300 MB，Ubuntu rootfs 约 300 MB）" else "无法读取可用空间",
            fix = if (level == Level.OK) null else "至少留 1.2 GB 再装内核与 rootfs",
        )
    }

    // ------------------------------------------------------------ WebView

    private fun webViewCheck(): Finding {
        val candidates = listOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.google.android.trichromelibrary",
        )
        var version: String? = null
        var major = 0
        for (pkg in candidates) {
            val info = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull() ?: continue
            val v = info.versionName ?: continue
            val m = v.split('.').firstOrNull()?.toIntOrNull() ?: 0
            if (m > major) {
                major = m
                version = v
            }
        }
        val level = when {
            version == null -> Level.WARN
            major < 100 -> Level.WARN
            else -> Level.OK
        }
        return Finding(
            id = "webview",
            title = "系统 WebView 版本",
            level = level,
            detail = version?.let { "v$it（内核号 $major）" } ?: "没找到 WebView 包（可能是厂商自带内核）",
            fix = if (level == Level.OK) null else "去应用商店把「Android System WebView」升级到 100+，否则 DSH 网页界面可能白屏",
        )
    }

    // ------------------------------------------------------------ 系统限制

    private fun phantomCheck(): Finding {
        val children = PhantomGuard.childProcessCount()
        val monitor = PhantomGuard.query { cmd ->
            when (val r = shell.best().exec(cmd, 6000)) {
                is ShellResult.Ok -> r.trimmed.ifBlank { null }
                is ShellResult.Failure -> null
            }
        }
        val level = when {
            monitor == true -> Level.WARN
            // 读不到子进程数时不再假装是 0 —— 那种「看起来正常」的假数据会掩盖真问题
            children == null -> Level.OK
            monitor == null && children > 20 -> Level.WARN
            else -> Level.OK
        }
        return Finding(
            id = "phantom",
            title = "幻影进程杀手（Android 12–14）",
            level = level,
            detail = PhantomGuard.describe(monitor, children),
            fix = if (level == Level.OK) null else "点右侧按钮一键关闭监控（需要 Shizuku/Root）",
            fixCommand = if (level == Level.OK) null else PhantomGuard.disableCommand(),
        )
    }

    private fun selinuxCheck(): Finding {
        val enforcing = runCatching {
            when (val r = shell.best().exec("getenforce", 6000)) {
                is ShellResult.Ok -> r.trimmed
                is ShellResult.Failure -> ""
            }
        }.getOrDefault("")
        val blocking = enforcing.contains("Enforcing", true)
        return Finding(
            id = "selinux",
            title = "SELinux（PRoot 的 ptrace 依赖）",
            level = if (blocking) Level.WARN else Level.OK,
            detail = if (enforcing.isBlank()) "读不到状态（需要 shell 通道）" else "当前策略：$enforcing",
            fix = if (blocking) "PRoot 靠 ptrace 工作，部分加固 ROM 会拦。已默认加 -0（伪 root）与 PROOT_NO_SECCOMP=1；仍失败就换「原生直跑」后端" else null,
        )
    }

    private fun overlayCheck(): Finding {
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
        return Finding(
            id = "overlay",
            title = "悬浮窗权限（桌宠 / 流体云）",
            level = if (ok) Level.OK else Level.WARN,
            detail = if (ok) "已授权" else "未授权：桌宠与流体云不会出现",
            fix = if (ok) null else "设置页一键跳转授权",
        )
    }

    private fun accessibilityCheck(): Finding {
        val ok = AccessibilityBridge.connected
        return Finding(
            id = "accessibility",
            title = "无障碍服务（免 root 操作手机）",
            level = if (ok) Level.OK else Level.WARN,
            detail = if (ok) "已连接：手势 / 读屏 / 输入可用" else "未开启：android_tap / android_swipe / android_ui_tree 全部不可用",
            fix = if (ok) null else "在系统设置 → 无障碍里打开「DSH Fusion」",
        )
    }

    private fun notificationCheck(): Finding {
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        return Finding(
            id = "notification",
            title = "通知权限（前台服务可见性）",
            level = if (ok) Level.OK else Level.WARN,
            detail = if (ok) "已授权" else "Android 13+ 没有这个权限，前台服务通知不会显示，用户会以为进程偷偷在跑",
            fix = if (ok) null else "允许通知；或至少保留常驻通知让引擎可见",
        )
    }

    private fun foregroundServiceCheck(): Finding {
        val sdk = Build.VERSION.SDK_INT
        val detail = buildString {
            append("API $sdk；")
            append("引擎用 specialUse、语音用 microphone、桌宠用 specialUse。")
            if (sdk >= 31) append(" 注意：Android 12+ 只允许在界面前台时启动前台服务。")
            if (sdk >= 35) append(" Android 15 对 dataSync/mediaProcessing 有 24 小时 6 小时上限，我们没用这两类。")
        }
        return Finding(
            id = "fgs",
            title = "前台服务策略",
            level = if (sdk >= 35) Level.WARN else Level.OK,
            detail = detail,
            fix = if (sdk >= 35) "若系统提示服务超时，改用「打开应用时自动拉起」的方式续命" else null,
        )
    }

    private fun heapCheck(): Finding {
        val mb = settings.heapMb
        val deviceMb = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem / 1024 / 1024
        }.getOrDefault(4096L)
        val level = when {
            mb > deviceMb / 2 -> Level.WARN
            else -> Level.OK
        }
        return Finding(
            id = "heap",
            title = "V8 堆上限",
            level = level,
            detail = "当前 --max-old-space-size=$mb（设备总内存 ${deviceMb}MB）",
            fix = if (level == Level.OK) null else "调小到设备内存的 1/4 左右，否则容易被系统 OOM 杀",
        )
    }

    // ------------------------------------------------------------ 华为 / 鸿蒙

    /** 读系统属性：优先 getprop（准），拿不到就退回 Build 字段（弱但永远可用）。 */
    private fun prop(key: String): String? {
        val viaShell = when (val r = shell.best().exec("getprop $key", 5000)) {
            is ShellResult.Ok -> r.trimmed
            is ShellResult.Failure -> ""
        }
        if (viaShell.isNotBlank()) return viaShell
        return when (key) {
            "ro.build.version.harmonyos" -> null
            "ro.product.brand" -> Build.BRAND
            "ro.build.display.id" -> Build.DISPLAY
            "ro.build.version.emui" -> null
            else -> null
        }
    }

    private fun isHuawei(): Boolean =
        Build.MANUFACTURER.contains("HUAWEI", true) ||
            Build.BRAND.contains("huawei", true) ||
            Build.BRAND.contains("honor", true)

    /**
     * 华为的安装拦截。
     *
     * 华为（EMUI / HarmonyOS）默认开着「纯净模式」，对旁加载的 APK 会直接拒装，
     * 而且提示语非常含糊（「应用来源未知」）。这条检测的作用不是技术上的，
     * 而是**替用户把该点的开关指出来**。
     */
    private fun huaweiInstallCheck(): Finding {
        if (!isHuawei()) {
            return Finding(
                id = "huawei_install",
                title = "厂商安装限制",
                level = Level.OK,
                detail = "非华为设备（${Build.MANUFACTURER}），无需额外处理",
            )
        }
        val emui = prop("ro.build.version.emui") ?: Build.DISPLAY.takeIf { it.contains("EMUI", true) }
        return Finding(
            id = "huawei_install",
            title = "华为安装权限 / 纯净模式",
            level = Level.WARN,
            detail = buildString {
                append("检测到华为设备（${Build.MANUFACTURER} ${Build.MODEL}）")
                if (!emui.isNullOrBlank()) append("，EMUI/系统标识：$emui")
                append("。华为默认开启「纯净模式」，会拒绝安装非应用市场来源的 APK。")
            },
            fix = "若安装被拒：设置 → 系统和更新 → 纯净模式 → 退出纯净模式；" +
                "再到 设置 → 安全 → 更多安全设置 → 安装外部来源应用 → 允许「文件管理/浏览器」安装未知应用。" +
                "应用宝这类第三方分发器可能仍拒绝小众 APK，遇到时改用系统文件管理器直接安装。",
        )
    }

    /**
     * 鸿蒙的分叉口。
     *
     * HarmonyOS 4 及以下基于 AOSP，APK 直接装；
     * HarmonyOS NEXT（5.0+）去掉了 AOSP 兼容层，APK **根本装不上**，
     * 只能跑在「卓易通」这类兼容层里 —— 那种情况下 Build 字段会露出马脚。
     */
    private fun harmonyCompatCheck(): Finding {
        val harmony = prop("ro.build.version.harmonyos")
            ?: prop("hw_sc.build.platform.version")
            ?: Build.DISPLAY.takeIf { it.contains("Harmony", true) }
        val nextMarker = prop("ro.build.version.harmonyos.next")
        val underCompatLayer = Build.MODEL.contains("兼容", true) || Build.DISPLAY.contains("卓易通", true)

        if (harmony == null && !isHuawei()) {
            return Finding(id = "harmony", title = "鸿蒙兼容层", level = Level.OK, detail = "Android ${Build.VERSION.RELEASE}（AOSP 系），APK 原生可装")
        }

        val next = nextMarker != null || (harmony?.firstOrNull()?.let { it.digitToIntOrNull() ?: 0 } ?: 0) >= 5

        return Finding(
            id = "harmony",
            title = "鸿蒙兼容层",
            level = if (next && !underCompatLayer) Level.BLOCK else if (next) Level.WARN else Level.OK,
            detail = buildString {
                append("系统标识=").append(harmony ?: "未检测到")
                append("，Android 兼容层=").append(Build.VERSION.RELEASE)
                append("（API ${Build.VERSION.SDK_INT}）")
                when {
                    next && underCompatLayer -> append(" → 判定为：HarmonyOS NEXT + 兼容层运行")
                    next -> append(" → 判定为：HarmonyOS NEXT 原生环境")
                    harmony != null -> append(" → 判定为：HarmonyOS 4 及以下（AOSP 内核，APK 直接可用）")
                }
            },
            fix = when {
                !next -> null
                underCompatLayer -> "当前跑在兼容层里，功能可用但性能与系统集成会打折；关注兼容层版本的更新。" +
                    "PROOT 后端在这类环境下可能因 ptrace 被拦而失效，推荐用「原生直跑」。"
                else -> "HarmonyOS NEXT 已移除 AOSP 兼容层，APK 无法直接安装。" +
                    "需要在「卓易通」等兼容层中运行，或等待本项目的 HAP（鸿蒙原生）版本 —— 见 README「演进方向」。"
            },
        )
    }

    /** 最低版本覆盖：minSdk 26 = Android 8.0，能覆盖绝大多数存量华为与安卓机。 */
    private fun apiLevelCheck(): Finding {
        val sdk = Build.VERSION.SDK_INT
        val ok = sdk >= 26
        return Finding(
            id = "api",
            title = "Android 版本兼容性",
            level = if (ok) Level.OK else Level.BLOCK,
            detail = buildString {
                append("本机 API ").append(sdk).append("（Android ").append(Build.VERSION.RELEASE).append("）；")
                append("本 APK minSdk=26（Android 8.0）、targetSdk=34。")
                if (sdk < 26) append(" → 低于最低要求，装不上。")
                if (sdk >= 34) append(" → 命中 Android 14 的前台服务类型强制要求，已按 specialUse/microphone 分别声明。")
            },
            fix = if (ok) null else "本机版本过低；或使用面向 Android 6/7 的兼容版（需自带 GeckoView）",
        )
    }

    /**
     * 电池优化白名单。
     *
     * 这条是冲着华为来的：EMUI / HarmonyOS 的后台清理比原生 Android 激进得多，
     * 应用进了「受保护应用」之外就会被随时冻结。引擎是前台服务，理论上能扛，
     * 但华为还会额外冻结「关联启动」与子进程 —— 把应用加进电池优化白名单是
     * 唯一由用户可控、且立刻见效的一步。
     */
    private fun batteryCheck(): Finding {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val ignoring = runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
        val aggressive = isHuawei()
        return Finding(
            id = "battery",
            title = "电池优化 / 后台保活",
            level = when {
                ignoring -> Level.OK
                aggressive -> Level.WARN
                else -> Level.OK
            },
            detail = if (ignoring) {
                "已加入电池优化白名单，引擎可以长时间驻留"
            } else if (aggressive) {
                "华为设备的后台清理比原生 Android 激进得多：引擎虽是前台服务，" +
                    "但华为仍可能冻结子进程。当前**未**加入电池优化白名单。"
            } else {
                "未加入电池优化白名单（非华为设备，影响有限）"
            },
            fix = if (ignoring) null else "点下方按钮，在系统弹窗里选「允许」，把本应用加入不受限制的后台应用列表",
        )
    }

    /** 安装引导全文：既在应用内展示，也能一键复制到电脑上先读。 */
    fun installGuide(): String = """
        DSH 融合版 · 安装引导（华为 / 鸿蒙专章）

        【安装前：读这一段就够了】

        A. 如果你的手机是 HarmonyOS 4.x 或 EMUI（大多数华为机）
           1) 关闭纯净模式
              设置 → 系统和更新 → 纯净模式 → 退出纯净模式
              （纯净模式会拒绝一切应用市场之外的安装，且提示语含糊）
           2) 允许外部来源安装
              设置 → 安全 → 更多安全设置 → 安装外部来源应用 →
              把「文件管理」或你用来打开 APK 的应用设为「允许」
           3) 打开 APK 安装；出现「未经华为检测」的警告时选「继续安装」
              —— 这是华为对所有非市场上架应用的统一提示，与 APK 本身无关
           4) 若第三方分发器（应用宝等）拒绝安装，改用系统「文件管理」直装即可绕过

        B. 如果你的手机是 HarmonyOS NEXT（5.0 及以上）
           APK 无法直接运行：NEXT 已经移除 AOSP 兼容层。
           需要借助「卓易通」等兼容层运行。
           装好后可在应用内「设备体检」看到「鸿蒙兼容层」一项的状态判断。

        【首次启动：它不是卡死，是在下载】

           内置运行时（Node 24 + PRoot）已随 APK 提供，无需下载。
           但官方内核与可选组件需要联网获取，请给足时间：

             项                     体积        触发时机        在哪看进度
             官方 dsh 内核          数百 MB     点「开始装配」   首启向导 / 控制台页
             Ubuntu rootfs（可选）  约 30 MB    设置页点「装配」  设置页 / 控制台页
             离线唤醒模型（可选）   约 42 MB    设置页点「下载」  设置页 / 控制台页
             语音识别在本地跑        —          唤醒开启后       控制台页

           * 前三项都是「一次下载、之后永久离线」，装好后断网也能用。
           * 全程进度会实时打在「控制台」页；只要还在滚字，就是正常的。
           * 装配中断可重来，npm 缓存会复用，不会从零开始。

        【装好之后建议做的三件事】
           1) 电池优化白名单：设备体检 → 电池优化 → 点按钮选「允许」
              （华为会激进冻结后台，这一步最见效）
           2) 通知权限：允许通知，否则前台服务的常驻通知不显示，
              你会以为应用在偷偷跑
           3) 无障碍服务：想用截屏/手势/读屏类工具时，去设备体检点「去开启」

        【出问题看哪里】
           主界面底部「控制台」页有完整日志，右上角可一键复制，
           粘到任何地方都能看懂出问题的环节。
    """.trimIndent()

    fun summary(findings: List<Finding>): String {
        val blocks = findings.count { it.level == Level.BLOCK }
        val warns = findings.count { it.level == Level.WARN }
        return "体检完成：$blocks 项阻塞、$warns 项警告、${findings.size - blocks - warns} 项正常"
    }

    fun logAll(findings: List<Finding>) {
        LogBus.i("体检", summary(findings))
        findings.forEach { f ->
            val mark = when (f.level) {
                Level.OK -> "✔"
                Level.WARN -> "!"
                Level.BLOCK -> "✘"
            }
            LogBus.log("体检", if (f.level == Level.OK) io.dsh.fusion.engine.LogLevel.INFO else io.dsh.fusion.engine.LogLevel.WARN, "$mark ${f.title}：${f.detail}")
        }
    }

    @Suppress("unused")
    private val unusedGuard = PackageManager.PERMISSION_GRANTED
}
