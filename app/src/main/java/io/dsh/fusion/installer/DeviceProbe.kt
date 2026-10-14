package io.dsh.fusion.installer

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.util.DisplayMetrics
import android.webkit.WebView
import java.io.File

/**
 * 设备探测 —— 安装器做任何决定之前必须先看清这台机器。
 *
 * ── 为什么由安装器自己测，而不是让用户选 ─────────────────────────
 * 让用户选 ABI 是个陷阱：绝大多数人不知道自己的手机是 arm64 还是 x86_64，
 * 选错的后果是装上就崩（`UnsatisfiedLinkError`），而错误信息不会告诉他
 * 「你选错了架构」。所以**能测的一律测**，用户只在真有选择时才被问到。
 *
 * ── 一个容易被忽略的维度：跑在兼容层里 ──────────────────────────
 * 我们这个应用的核心是 **PRoot**，它依赖 Linux 的 `ptrace`。
 * 在 VirtualBox 里的安卓（应用宝电脑版）、或者某些厂商的兼容层中，
 * `ptrace` 可能被限制 —— 那样引擎根本起不来。
 * 这种事**必须在下载之前就告诉用户**，而不是让他下完 100 MB 再崩。
 */
data class DeviceProfile(
    /** 主 ABI，例如 arm64-v8a */
    val abi: String,
    /** 全部支持的 ABI，按优先级 */
    val abis: List<String>,
    val apiLevel: Int,
    val model: String,
    val manufacturer: String,
    /** 整机内存（GB，按 1024 进制算，四舍五入到 0.1） */
    val ramGb: Double,
    /** 可用存储（MB） */
    val freeStorageMb: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val webViewMajor: Int,
    /** 是否疑似运行在模拟器 / 兼容层里 */
    val suspiciousEmulator: Boolean,
    /** 判定为模拟器/兼容层的依据（给人看的） */
    val emulatorEvidence: List<String>,
    /** 系统是否 64 位 */
    val is64Bit: Boolean,
) {
    /** 一句话摘要，直接显示给用户 */
    fun summary(): String =
        "$manufacturer $model · $abi · Android API $apiLevel · " +
            "${"%.1f".format(ramGb)} GB 内存 · 可用 ${freeStorageMb / 1024} GB"
}

object DeviceProbe {

    fun probe(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramGb = Math.round(mi.totalMem / 1073741824.0 * 10) / 10.0

        val free = runCatching {
            StatFs(context.filesDir.absolutePath).availableBytes / 1048576
        }.getOrDefault(-1L)

        val dm = context.resources.displayMetrics
        val evidence = mutableListOf<String>()

        // ── 模拟器 / 兼容层判定 ──
        // 这些字段在真机上是厂商写死的值，模拟器往往懒得改或改得不全。
        // 单看一条会误判，所以**收集多条证据**再下结论。
        val fp = Build.FINGERPRINT ?: ""
        val model = Build.MODEL ?: ""
        val product = Build.PRODUCT ?: ""
        val hardware = Build.HARDWARE ?: ""
        val brand = Build.BRAND ?: ""
        val device = Build.DEVICE ?: ""

        if (fp.startsWith("generic") || fp.contains("vbox") || fp.contains("test-keys")) {
            evidence += "fingerprint=$fp"
        }
        if (model.contains("sdk", true) || model.contains("emulator", true) ||
            model.contains("Android SDK", true)
        ) {
            evidence += "model=$model"
        }
        if (product.contains("sdk", true) || product.contains("vbox", true) ||
            product.contains("emulator", true)
        ) {
            evidence += "product=$product"
        }
        if (hardware.contains("goldfish", true) || hardware.contains("ranchu", true) ||
            hardware.contains("vbox", true) || hardware.contains("qemu", true)
        ) {
            evidence += "hardware=$hardware"
        }
        if (brand.startsWith("generic") || device.contains("generic", true)) {
            evidence += "brand/device=$brand/$device"
        }
        // qemu 的痕迹文件（真机上不会有）
        for (p in listOf("/dev/socket/qemud", "/dev/qemu_pipe", "/system/lib/libc_malloc_debug_qemu.so")) {
            if (File(p).exists()) evidence += "存在 $p"
        }
        // 常见模拟器厂商
        for (k in listOf("ro.kernel.qemu", "ro.boot.qemu", "ro.product.cpu.abi")) {
            val v = runCatching {
                val c = Class.forName("android.os.SystemProperties")
                val m = c.getMethod("get", String::class.java)
                m.invoke(null, k) as? String
            }.getOrNull()
            if (k.contains("qemu") && v == "1") evidence += "$k=1"
        }

        val abis = Build.SUPPORTED_ABIS?.toList() ?: listOf(Build.CPU_ABI)
        val primaryAbi = abis.firstOrNull() ?: "unknown"

        return DeviceProfile(
            abi = primaryAbi,
            abis = abis,
            apiLevel = Build.VERSION.SDK_INT,
            model = model,
            manufacturer = Build.MANUFACTURER ?: "?",
            ramGb = ramGb,
            freeStorageMb = free,
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            webViewMajor = webViewMajor(context),
            suspiciousEmulator = evidence.isNotEmpty(),
            emulatorEvidence = evidence,
            is64Bit = abis.any { it.contains("64") },
        )
    }

    /** 系统 WebView 大版本。取不到返回 0（厂商自带内核的情况）。 */
    private fun webViewMajor(context: Context): Int = runCatching {
        val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()
        } else {
            null
        }
        pkg?.versionName?.split('.')?.firstOrNull()?.toIntOrNull() ?: 0
    }.getOrDefault(0)

    /** 系统里是否已经装了目标应用（决定按钮文案是「安装」还是「更新」）。 */
    fun installedVersion(context: Context, pkg: String): String? = runCatching {
        context.packageManager.getPackageInfo(pkg, 0).versionName
    }.getOrNull()

    /**
     * 应用是否已经在**兼容层/虚拟机**里跑着。
     *
     * 这一条影响很大：我们的引擎要跑 PRoot（依赖 `ptrace`），
     * 而 VirtualBox 里的安卓未必放行。宁可提前警告，也不要让用户下完再失败。
     */
    fun ptraceRisk(profile: DeviceProfile): String? = when {
        profile.suspiciousEmulator ->
            "检测到疑似模拟器/兼容层环境（${profile.emulatorEvidence.take(2).joinToString("；")}）。" +
                "本应用的核心引擎依赖 PRoot（Linux ptrace），在这类环境里可能无法启动。"
        !profile.is64Bit ->
            "这台设备只有 32 位 ABI（${profile.abi}）。本应用只提供 64 位运行时，装不上。"
        else -> null
    }
}
