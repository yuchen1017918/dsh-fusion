package io.dsh.fusion.engine

import android.content.Context
import io.dsh.fusion.engine.provision.DshProvisioner
import io.dsh.fusion.engine.provision.KernelBundleProvisioner
import io.dsh.fusion.engine.provision.NpmProvisioner
import io.dsh.fusion.engine.provision.PluginManager
import io.dsh.fusion.engine.provision.UbuntuProvisioner
import io.dsh.fusion.engine.runtime.ProcessRunner
import io.dsh.fusion.engine.runtime.RuntimeEnv
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

    /** profile 插件管理（安装 / 卸载 / 挂载开关）。 */
    val plugins = PluginManager(paths, settings, runner)

    /** 内置内核包（随 APK 发货的那份）。 */
    val kernelBundle = KernelBundleProvisioner(context, paths)

    fun survey(): List<Step> = listOf(
        Step("runtime", "内置运行时（bionic Node 24 + PRoot）", paths.runtimeSeeded(),
            if (paths.runtimeSeeded()) "来自 APK，无需下载" else "APK 内未找到 libnode.so／libproot.so"),
        Step("npm", "内置 npm", npm.isReady(), npm.installedVersion()?.let { "v$it" } ?: "待解包"),
        Step("kernel", "dsh 内核", paths.dshInstalled(), dsh.installedVersion()?.let { "v$it" } ?: "未安装"),
        Step("profile", "web profile", java.io.File(paths.profileDir("web"), "package.json").exists(), ""),
        Step("rootfs", "Ubuntu rootfs（可选）", ubuntu.isReady(),
            if (ubuntu.isReady()) "完整发行版已就位" else "首次使用时按需下载约 30 MB"),
    )

    /**
     * 读出桥插件文件（assets → 字节）。
     *
     * 整个 engine 模块里只有这一处碰 Android 的 assets ——
     * 内核装配器拿到的就是一份普通 Map，它可以被单元测试、可以被替换。
     */
    fun bridgePluginFiles(): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        DshProvisioner.BRIDGE_FILES.forEach { name ->
            runCatching {
                context.assets.open("bridge-plugin/$name").use { out[name] = it.readBytes() }
            }.onFailure { LogBus.w(TAG, "assets 里读不到 bridge-plugin/$name：${it.message}") }
        }
        if (out.isEmpty()) LogBus.e(TAG, "桥插件文件一个都没读到，设备工具与流体云都不会生效")
        return out
    }

    /**
     * 内核安装的唯一入口。
     *
     * 之所以收口到 Provisioner：只有它手里有 Context 能读 assets。
     * 早先内核安装可以被任意调用方直接触发，而桥插件文件靠一个全局变量兜着 ——
     * 那条路线上「谁负责把插件铺进去」是没有答案的。
     */
    suspend fun installKernel(version: String, onStep: ((String) -> Unit)? = null): Boolean =
        withContext(Dispatchers.IO) {
            supervisorMarkProvisioning("正在安装 dsh 内核 $version")
            try {
                dsh.install(version, "web", onStep, bridgePluginFiles())
            } finally {
                supervisorClearProvisioning()
            }
        }

    private fun supervisorMarkProvisioning(detail: String) {
        runCatching { FusionRuntime.supervisor().markProvisioning(detail) }
    }

    private fun supervisorClearProvisioning() {
        runCatching { FusionRuntime.supervisor().clearProvisioning() }
    }

    /**
     * 把宿主脚本从 assets 铺到私有目录。
     *
     * 这个脚本是让官方插件安装页在 Android 上能用的关键：官方 CLI 启动 profile 时
     * 不传 packageManager，脚本补上这一传。见 assets/host/fusion-host.mjs 的注释。
     */
    fun installHostScript(): Boolean = runCatching {
        val bytes = context.assets.open("host/fusion-host.mjs").use { it.readBytes() }
        paths.hostDir.mkdirs()
        paths.hostScript.writeBytes(bytes)
        LogBus.i(TAG, "宿主脚本已铺好（${bytes.size} 字节）：${paths.hostScript.absolutePath}")
        true
    }.getOrElse {
        LogBus.e(TAG, "铺宿主脚本失败：${it.message}（引擎会退回官方 CLI，插件安装页将不可用）")
        false
    }

    /**
     * 装 pnpm。
     *
     * 官方插件管理器底层跑的就是 pnpm（`execa('pnpm', [...])`），
     * 而设备上不可能有它。装进我们自己的 prefix 之后，宿主脚本把它作为
     * `packageManager.args` 传给 dsh，官方那一页就能正常工作了。
     */
    suspend fun installPnpm(onStep: ((String) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        if (paths.pnpmReady()) {
            LogBus.i(TAG, "pnpm 已就位：${paths.pnpmEntry.absolutePath}")
            return@withContext true
        }
        val npm = paths.npmCli
        if (!npm.exists()) {
            onStep?.invoke("内置 npm 未就位，跳过 pnpm")
            return@withContext false
        }
        onStep?.invoke("安装 pnpm（官方插件安装页依赖它，约 10 MB）…")
        val env = RuntimeEnv.base(paths, settings.npmRegistry, settings.heapMb)
        val code = runner.run(
            listOf(
                paths.nodeBin.absolutePath, npm.absolutePath,
                "install", "-g",
                "--prefix", paths.prefixDir.absolutePath,
                "--registry", settings.npmRegistry,
                "--no-fund", "--no-audit", "--loglevel", "info",
                PNPM_SPEC,
            ),
            env,
            paths.root,
        )
        val ok = code == 0 && paths.pnpmReady()
        if (ok) {
            LogBus.i(TAG, "pnpm 装好了：${paths.pnpmEntry.absolutePath}")
            onStep?.invoke("pnpm 就绪，官方插件安装页可用")
        } else {
            LogBus.w(TAG, "pnpm 安装失败（退出码 $code）—— 官方插件页将不可用，可用应用内的备选入口")
            onStep?.invoke("pnpm 安装失败，稍后可在设置里重试")
        }
        ok
    }

    /**
     * 首启核心装配。
     *
     * 两条路径，优先走第一条：
     *
     *  **A · 内置内核包（APK 自带，零联网）**
     *     解压 114 MB 的预装内核树即可。这是「装完即用」的实现方式：
     *     不需要 npm、不需要 registry、不需要等 538 个包解析，
     *     飞行模式下也能完成。内核包由 tools/build-kernel-bundle.py 产出。
     *
     *  **B · 在线安装（回退）**
     *     没有内置包时（比如 x86_64 预览版刻意不带），走原来的 npm 流程。
     */
    suspend fun provisionCore(
        onStep: (String) -> Unit,
        onProgress: ((KernelBundleProvisioner.Progress) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        paths.ensure()
        if (!paths.runtimeSeeded()) {
            onStep("运行时缺失：APK 里没有本机架构的 libnode.so")
            LogBus.e(TAG, "运行时缺失，装配中止")
            return@withContext false
        }

        // ── 路径 A ──
        val bundle = kernelBundle.find()
        if (bundle != null) {
            onStep("正在初始化本地环境（离线，约 ${bundle.uncompressedBytes / 1048576} MB）…")
            supervisorMarkProvisioning("正在解压内置内核包")
            val outcome = try {
                kernelBundle.install(bundle, onProgress)
            } finally {
                supervisorClearProvisioning()
            }
            when (outcome) {
                is KernelBundleProvisioner.Outcome.Ready -> {
                    installHostScript()
                    // 内置包里是**官方**内核，没有我们的能力桥插件 ——
                    // 不铺进去的话设备工具与流体云全是死的。这一步不能省。
                    onStep("装配设备能力桥…")
                    dsh.installBridgePlugin(FusionPaths.PROFILE_NAME, bridgePluginFiles())
                    onStep("本地环境已就绪（内核 ${outcome.version}，全程未联网）")
                    LogBus.i(TAG, "内置内核包装配完成：${outcome.version}")
                    return@withContext true
                }
                is KernelBundleProvisioner.Outcome.NotEnoughSpace -> {
                    val msg = "存储空间不足：需要约 ${outcome.needBytes / 1048576} MB，可用 ${outcome.freeBytes / 1048576} MB"
                    LogBus.e(TAG, msg)
                    onStep(msg)
                    return@withContext false
                }
                is KernelBundleProvisioner.Outcome.Failed -> {
                    LogBus.w(TAG, "内置内核包解压失败（${outcome.message}），回退到在线安装")
                    onStep("内置包解压失败，改用在线安装…")
                }
                KernelBundleProvisioner.Outcome.Absent -> Unit
                else -> Unit
            }
        } else {
            LogBus.i(TAG, "APK 内没有内核包（可能是精简版或 x86_64 预览版），走在线安装")
        }

        // ── 路径 B ──
        onStep("展开内置 npm…")
        val npmVersion = runCatching { npm.provision(onStep) }.getOrElse {
            LogBus.e(TAG, "npm 解包失败：${it.message}")
            onStep("npm 解包失败：${it.message}")
            return@withContext false
        }
        LogBus.i(TAG, "npm v$npmVersion 就绪")

        onStep("铺设宿主入口…")
        installHostScript()

        installPnpm(onStep)

        onStep("查询官方内核版本…")
        val version = dsh.resolveVersion()
        onStep("安装 dsh 内核 ${version.version}（来自 ${version.source}）…")
        val ok = installKernel(version.version, onStep)
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

        /**
         * pnpm 的版本。
         *
         * dsh 仓库根声明的是 pnpm@11.7.0，但 11 太新；10.x 是当前稳定线，
         * 生成的 lockfile 与 workspace 配置格式都被 11 兼容读取。
         * 要升版本改这一处即可。
         */
        const val PNPM_SPEC = "pnpm@10"
    }
}
