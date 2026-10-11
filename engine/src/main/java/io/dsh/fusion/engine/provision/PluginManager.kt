package io.dsh.fusion.engine.provision

import io.dsh.fusion.engine.FusionPaths
import io.dsh.fusion.engine.FusionSettings
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.runtime.ProcessRunner
import io.dsh.fusion.engine.runtime.RuntimeEnv
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * profile 插件管理。
 *
 * dsh 的插件要真正生效，需要**两处同时成立**（这是读 profile 的 cordis.yml 得到的结论，
 * 不是猜的：`cordis.yml` 是一个空的 entry 列表，装配树由
 * 「package.json 的 dsh.profile.bundles → cordis.patch.yml → --patch 覆盖」依次叠出来）：
 *
 *   1. `dependencies` 里有这个包（= 装上了）
 *   2. `dsh.profile.bundles` 里有这个名字（= 挂载了）
 *
 * 官方 CLI 的 `dsh plugin add` 只做第 1 步（它把参数透传给 pnpm），第 2 步得自己改。
 * 手机上两步都做了，用户才能真的把插件用起来。
 */
class PluginManager(
    private val paths: FusionPaths,
    private val settings: FusionSettings,
    private val runner: ProcessRunner,
) {

    data class PluginEntry(
        val name: String,
        val specifier: String,
        val bundled: Boolean,
        val installed: Boolean,
    ) {
        /** 装了但没挂载 = 半生效，界面上要点出来。 */
        val effective: Boolean get() = bundled && installed

        val kind: String
            get() = when {
                specifier.startsWith("file:") || specifier.startsWith("link:") -> "本地路径"
                specifier.startsWith("http") || specifier.contains("github") -> "远端包"
                else -> "npm"
            }
    }

    data class Outcome(val ok: Boolean, val message: String)

    private fun profileJson(profile: String): File = File(paths.profileDir(profile), "package.json")

    /**
     * 用哪个包管理器。
     *
     * 优先 pnpm —— 官方插件安装页跑的就是它，两条路径用同一个工具才不会
     * 把 node_modules 和 lockfile 搞成两种形态。没有 pnpm 时退回 npm
     * （`add` / `remove` 是两者的共同子命令，registry 参数也通用）。
     */
    /**
     * pnpm 在「按设计忽略了构建脚本」时会以退出码 1 退出（ERR_PNPM_IGNORED_BUILDS）。
     * 在 Android 上那些原生模块本来就不可能编译，所以这不算失败。
     * 不识别它的话，插件明明装好了却被报成失败 —— 踩过一次。
     */
    private fun isTolerableFailure(code: Int, output: String): Boolean =
        code != 0 && (output.contains("ERR_PNPM_IGNORED_BUILDS") || output.contains("Ignored build scripts"))

    private data class PkgTool(val exe: List<String>, val label: String, val commonFlags: List<String>)

    private fun tool(registry: String): PkgTool {
        val registryFlag = "--registry=$registry"
        return if (paths.pnpmReady()) {
            PkgTool(
                exe = listOf(paths.nodeBin.absolutePath, paths.pnpmEntry.absolutePath),
                label = "pnpm（与官方插件页同款）",
                commonFlags = listOf(registryFlag, "--reporter=append-only"),
            )
        } else {
            PkgTool(
                exe = listOf(paths.nodeBin.absolutePath, paths.npmCli.absolutePath),
                label = "npm（兜底；官方插件页需要 pnpm）",
                commonFlags = listOf(registryFlag, "--no-fund", "--no-audit", "--loglevel=info"),
            )
        }
    }

    // ---------------------------------------------------------------- 读

    fun list(profile: String = "web"): List<PluginEntry> {
        val file = profileJson(profile)
        if (!file.exists()) return emptyList()
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyList()
        val deps = root.optJSONObject("dependencies") ?: JSONObject()
        val bundles = bundlesOf(root)

        val names = LinkedHashMap<String, PluginEntry>()
        deps.keys().forEach { name ->
            val spec = deps.optString(name)
            names[name] = PluginEntry(
                name = name,
                specifier = spec,
                bundled = name in bundles,
                installed = isInstalled(profile, name),
            )
        }
        // 只挂在 bundles 里、却没写进 dependencies 的（手工编辑过 profile 的情况）
        bundles.filter { it !in names }.forEach { name ->
            names[name] = PluginEntry(name, "(未在 dependencies 中)", bundled = true, installed = false)
        }
        return names.values.sortedBy { it.name }
    }

    private fun bundlesOf(root: JSONObject): Set<String> {
        val arr = root.optJSONObject("dsh")
            ?.optJSONObject("profile")
            ?.optJSONArray("bundles")
            ?: return emptySet()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
    }

    private fun isInstalled(profile: String, name: String): Boolean =
        File(File(paths.profileDir(profile), "node_modules"), name).exists()

    // ---------------------------------------------------------------- 写

    /**
     * 安装一个插件。
     *
     * @param spec 包名，或 `包名@版本`。官方 npm 包、GitHub tarball 都可以 —— 走的是 npm。
     */
    suspend fun add(spec: String, profile: String = "web", onStep: ((String) -> Unit)? = null): Outcome {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) return Outcome(false, "请填写包名")

        if (!paths.npmCli.exists()) return Outcome(false, "内置 npm 未就位，先完成首次装配")

        val name = packageNameOf(trimmed)
        val env = RuntimeEnv.base(paths, settings.npmRegistry, settings.heapMb)
        val dir = paths.profileDir(profile)
        val pkgTool = tool(settings.npmRegistry)

        onStep?.invoke("正在用 ${pkgTool.label} 安装 $trimmed …")
        LogBus.i(TAG, "${pkgTool.label} add $trimmed（profile=$profile，cwd=$dir）")
        // 不传 --prefix：靠 cwd 定位 profile，pnpm 与 npm 都认这个约定
        val transcript = StringBuilder()
        val code = runner.run(
            pkgTool.exe + listOf("add", trimmed) + pkgTool.commonFlags,
            env,
            dir,
        ) { line -> synchronized(transcript) { transcript.appendLine(line) } }
        if (code != 0 && !isTolerableFailure(code, transcript.toString())) {
            return Outcome(false, "安装失败（退出码 $code）：$trimmed —— 到控制台看完整输出")
        }
        if (code != 0) {
            LogBus.w(TAG, "退出码 $code 可容忍（忽略了需要编译的原生模块，Android 上属正常）")
        }

        // 第二步：挂载。少了它插件就是「装了但不生效」，用户会以为装失败了。
        onStep?.invoke("正在挂载到 profile …")
        return if (mount(name, profile, true)) {
            LogBus.i(TAG, "插件已就绪：$name")
            Outcome(true, "已安装并挂载：$name（重启引擎后生效）")
        } else {
            Outcome(false, "已安装，但写入 profile 的 bundles 失败：$name")
        }
    }

    suspend fun remove(name: String, profile: String = "web", onStep: ((String) -> Unit)? = null): Outcome {
        if (!paths.npmCli.exists()) return Outcome(false, "内置 npm 未就位")
        val env = RuntimeEnv.base(paths, settings.npmRegistry, settings.heapMb)
        val dir = paths.profileDir(profile)
        val pkgTool = tool(settings.npmRegistry)

        mount(name, profile, false)
        onStep?.invoke("正在用 ${pkgTool.label} 卸载 $name …")
        // remove 同样是 pnpm 与 npm 的共同子命令
        val code = runner.run(pkgTool.exe + listOf("remove", name) + pkgTool.commonFlags, env, dir)
        return if (code == 0) {
            LogBus.i(TAG, "插件已移除：$name")
            Outcome(true, "已移除：$name（重启引擎后生效）")
        } else {
            Outcome(false, "卸载失败（退出码 $code）：$name")
        }
    }

    /** 只改挂载状态，不动物理安装 —— 用来快速启用/停用一个已装插件。 */
    fun mount(name: String, profile: String = "web", enabled: Boolean): Boolean {
        val file = profileJson(profile)
        if (!file.exists()) return false
        return runCatching {
            val root = JSONObject(file.readText())
            val dsh = root.optJSONObject("dsh") ?: JSONObject().also { root.put("dsh", it) }
            val prof = dsh.optJSONObject("profile") ?: JSONObject().also { dsh.put("profile", it) }
            val current = prof.optJSONArray("bundles") ?: JSONArray().also { prof.put("bundles", it) }

            val kept = (0 until current.length())
                .mapNotNull { current.optString(it).takeIf { s -> s.isNotBlank() } }
                .filter { it != name }
                .toMutableList()
            if (enabled) kept.add(name)

            prof.put("bundles", JSONArray().also { arr -> kept.forEach { arr.put(it) } })
            // 原子写：先落临时文件再改名，避免写一半断电留下坏 profile
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(root.toString(2))
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(2))
                tmp.delete()
            }
            LogBus.i(TAG, "挂载${if (enabled) "启用" else "停用"}：$name")
            true
        }.getOrElse {
            LogBus.e(TAG, "改写 profile 失败：${it.message}")
            false
        }
    }

    /** `@scope/pkg@1.2.3` → `@scope/pkg`；`pkg@1.2.3` → `pkg`。 */
    private fun packageNameOf(spec: String): String {
        val at = spec.lastIndexOf('@')
        return if (at <= 0) spec else spec.substring(0, at)
    }

    private companion object {
        const val TAG = "插件"
    }
}
