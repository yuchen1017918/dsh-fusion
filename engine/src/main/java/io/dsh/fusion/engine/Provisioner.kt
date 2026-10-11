package io.dsh.fusion.engine

import android.content.Context
import io.dsh.fusion.engine.provision.DshProvisioner
import io.dsh.fusion.engine.provision.NpmProvisioner
import io.dsh.fusion.engine.provision.UbuntuProvisioner
import io.dsh.fusion.engine.runtime.ProcessRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把三个装配步骤串成一条首启流水线。
 *
 * 顺序是有讲究的：内置 npm（离线可完成）→ 官方内核（联网）→ Ubuntu rootfs（联网、可选）。
 * 前两步做完应用已经可用，第三步失败不影响日常使用 —— 降级是设计的一部分。
 */
class Provisioner(
    private val context: Context,
    private val paths: FusionPaths,
    private val settings: FusionSettings,
) {

    data class Step(val id: String, val label: String, val done: Boolean, val detail: String = "")

    private val runner = ProcessRunner("装配")

    val npm = NpmProvisioner(context, paths)
    val dsh = DshProvisioner(paths, settings, runner)
    val ubuntu = UbuntuProvisioner(paths, settings)

    fun survey(): List<Step> = listOf(
        Step("runtime", "内置运行时（bionic Node 24 + PRoot）", paths.runtimeSeeded(),
            if (paths.runtimeSeeded()) "来自 APK，无需下载" else "APK 内未找到 libnode.so／libproot.so"),
        Step("npm", "内置 npm", npm.isReady(), npm.installedVersion()?.let { "v$it" } ?: "待解包"),
        Step("kernel", "dsh 内核", paths.dshInstalled(), dsh.installedVersion()?.let { "v$it" } ?: "未安装"),
        Step("profile", "web profile", java.io.File(paths.profileDir("web"), "package.json").exists(), ""),
        Step("rootfs", "Ubuntu rootfs（可选）", ubuntu.isReady(),
            if (ubuntu.isReady()) "完整发行版已就位" else "首次使用时按需下载约 30 MB"),
    )

    /** 首启核心装配：离线部分 + 官方内核。 */
    suspend fun provisionCore(onStep: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        paths.ensure()
        if (!paths.runtimeSeeded()) {
            onStep("运行时缺失：APK 里没有 arm64-v8a 的 libnode.so")
            LogBus.e(TAG, "运行时缺失，装配中止")
            return@withContext false
        }
        onStep("展开内置 npm…")
        val npmVersion = runCatching { npm.provision(onStep) }.getOrElse {
            LogBus.e(TAG, "npm 解包失败：${it.message}")
            onStep("npm 解包失败：${it.message}")
            return@withContext false
        }
        LogBus.i(TAG, "npm v$npmVersion 就绪")

        onStep("查询官方内核版本…")
        val version = dsh.resolveVersion()
        onStep("安装 dsh 内核 ${version.version}（来自 ${version.source}）…")
        val ok = dsh.install(version.version, "web", onStep)
        onStep(if (ok) "内核装配完成" else "内核安装失败，看控制台日志")
        ok
    }

    suspend fun provisionRootfs(onStep: (String) -> Unit, onProgress: ((io.dsh.fusion.engine.provision.HttpDownloader.Progress) -> Unit)? = null): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { ubuntu.provision(onStep, onProgress) }.getOrElse {
                LogBus.e(TAG, "rootfs 装配失败：${it.message}")
                onStep("rootfs 装配失败：${it.message}")
                false
            }
        }

    private companion object {
        const val TAG = "装配"
    }
}
