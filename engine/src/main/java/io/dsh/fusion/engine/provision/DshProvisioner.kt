package io.dsh.fusion.engine.provision

import io.dsh.fusion.engine.FusionPaths
import io.dsh.fusion.engine.FusionSettings
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.ToolBus
import io.dsh.fusion.engine.runtime.ProcessRunner
import io.dsh.fusion.engine.runtime.RuntimeEnv
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 安装 / 升级 dsh 内核本体。
 *
 * 两个关键事实（踩过才知道）：
 *  1. `@deepseek-ai/dsh-base` 与 `@deepseek-ai/dsh-web-app` 的 npm `latest`
 *     标签指向一个 0.0.1-rc.1 的占位版本，真正能用的版本在 `next` 或具体版本号上。
 *     所以这里把三个包**锁死成同一个版本**，绝不各装各的。
 *  2. profile 是 pnpm 味儿的，但设备上没有 pnpm；实测 `npm install`
 *     装同一份 package.json 完全可行，于是免掉了 corepack 拉 pnpm 那一圈。
 */
class DshProvisioner(
    private val paths: FusionPaths,
    private val settings: FusionSettings,
    private val runner: ProcessRunner,
) {

    data class KernelVersion(val version: String, val source: String)

    // ---------------------------------------------------------------- 版本发现

    /** 从 registry 读 dist-tags。优先 next（社区实测更可用），退回 latest。 */
    fun resolveVersion(registry: String = settings.npmRegistry): KernelVersion {
        val url = "${registry.trimEnd('/')}/@deepseek-ai%2Fdsh"
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/vnd.npm.install-v1+json")
            }
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tags = json.optJSONObject("dist-tags") ?: JSONObject()
            val latest = tags.optString("latest").ifBlank { null }
            val next = tags.optString("next").ifBlank { null }
            val chosen = next ?: latest ?: throw IllegalStateException("registry 未返回任何 dist-tag")
            LogBus.i(TAG, "registry 版本：latest=$latest next=$next -> 采用 $chosen")
            KernelVersion(chosen, if (next != null) "next" else "latest")
        }.getOrElse {
            LogBus.w(TAG, "查询 registry 失败（${it.message}），回退到内置默认版本 $FALLBACK")
            KernelVersion(FALLBACK, "fallback")
        }
    }

    fun installedVersion(): String? {
        val pkg = File(paths.prefixDir, "lib/node_modules/@deepseek-ai/dsh/package.json")
        if (!pkg.exists()) return null
        return runCatching { JSONObject(pkg.readText()).optString("version").ifBlank { null } }.getOrNull()
    }

    // ---------------------------------------------------------------- profile

    /** 写出 `$DSH_HOME/profiles/web/package.json`：三个包同版本 + 自带的能力桥插件。 */
    fun writeProfile(name: String, version: String) {
        val dir = paths.profileDir(name)
        dir.mkdirs()
        File(dir, "plugins").mkdirs()

        val pkg = JSONObject().apply {
            put("name", "dsh-profile-$name")
            put("private", true)
            put("dependencies", JSONObject().apply {
                put("@deepseek-ai/dsh-base", version)
                put("@deepseek-ai/dsh-web-app", version)
                put(BRIDGE_PKG, "file:./plugins/$BRIDGE_PKG")
            })
            put("dsh", JSONObject().apply {
                put("profile", JSONObject().apply {
                    put("bundles", JSONArray().apply {
                        put("@deepseek-ai/dsh-base")
                        put("@deepseek-ai/dsh-web-app")
                        put(BRIDGE_PKG)
                    })
                })
            })
        }
        val file = File(dir, "package.json")
        file.writeText(pkg.toString(2))
        LogBus.i(TAG, "已写出 profile 清单 ${file.absolutePath}（内核 $version）")

        /**
         * pnpm 的工作区配置。
         *
         * 官方插件管理器会读它（见 plugin-manager/src/build-approval.ts），
         * 缺了它官方那条路径的行为与「标准安装」不一致。
         * `nodeLinker: hoisted` 让 pnpm 产出扁平 node_modules —— 与 npm 装出来的
         * 形态一致，于是官方 pnpm 路径与应用内 npm 兜底路径可以混用而不打架。
         */
        File(dir, "pnpm-workspace.yaml").writeText(
            """
            |packages:
            |  - .
            |
            |nodeLinker: hoisted
            |autoInstallPeers: false
            |
            |# 这些包需要现场编译原生模块，Android 上做不到，忽略才是对的。
            |# 显式写出来，pnpm 就不会把「按设计忽略」当成错误（否则退出码是 1）。
            |ignoredBuiltDependencies:
            |  - '@deepseek-ai/dsh-subprocess-local'
            |  - '@google/genai'
            |  - koffi
            |  - node-pty
            |  - protobufjs
            """.trimMargin() + "\n"
        )

        // 让两条安装路径（官方 pnpm / 应用内兜底）都走同一个国内镜像
        File(dir, ".npmrc").writeText("registry=${settings.npmRegistry}\n")
        LogBus.i(TAG, "已写出 pnpm-workspace.yaml 与 .npmrc（registry=${settings.npmRegistry}）")
    }

    // ---------------------------------------------------------------- 安装

    /**
     * 安装/升级启动器 + profile 依赖。返回是否成功。
     *
     * @param bridgeFiles 桥插件文件（相对路径 -> 内容）。由调用方从 assets 读好传进来 ——
     *   早先这里挂着一个可变的全局 `ASSET_READER`，谁都能改、生命周期说不清，
     *   现在改成显式参数：文件从哪来，看调用点就知道。
     */
    suspend fun install(
        version: String,
        profileName: String = "web",
        onStep: ((String) -> Unit)? = null,
        bridgeFiles: Map<String, ByteArray> = emptyMap(),
    ): Boolean {
        paths.ensure()
        val npm = paths.npmCli
        if (!npm.exists()) {
            LogBus.e(TAG, "npm 未就位，无法安装内核")
            return false
        }
        val env = RuntimeEnv.base(paths, settings.npmRegistry, settings.heapMb)

        onStep?.invoke("安装 dsh 启动器 @$version …")
        LogBus.i(TAG, "安装启动器 @deepseek-ai/dsh@$version -> ${paths.prefixDir.absolutePath}")
        val launcherCode = runner.run(
            listOf(
                paths.nodeBin.absolutePath, npm.absolutePath,
                "install", "-g",
                "--prefix", paths.prefixDir.absolutePath,
                "--registry", settings.npmRegistry,
                "--no-fund", "--no-audit", "--loglevel", "info",
                "@deepseek-ai/dsh@$version",
            ),
            env,
            paths.root,
        )
        if (launcherCode != 0 || !paths.dshEntry.exists()) {
            LogBus.e(TAG, "启动器安装失败（退出码 $launcherCode）")
            return false
        }

        onStep?.invoke("装配 web profile（bundle + 能力桥插件）…")
        writeProfile(profileName, version)
        installBridgePlugin(profileName, bridgeFiles)

        LogBus.i(TAG, "安装 profile 依赖（这一步会拉几百 MB，耐心等）")
        val profileCode = runner.run(
            listOf(
                paths.nodeBin.absolutePath, npm.absolutePath,
                "install",
                "--prefix", paths.profileDir(profileName).absolutePath,
                "--registry", settings.npmRegistry,
                "--no-fund", "--no-audit", "--loglevel", "info",
            ),
            env,
            paths.profileDir(profileName),
        )
        if (profileCode != 0) {
            LogBus.w(TAG, "profile 依赖安装退出码 $profileCode —— 引擎仍会尝试启动，缺什么它会自己报")
        }
        return true
    }

    /**
     * 把 APK 内自带的能力桥插件铺进 profile 的 plugins/，
     * 并把**当前能力清单**写成 tools.json。
     *
     * 这一步是整个融合的接缝：手机端知道有哪些能力，模型端需要一张静态清单才能在
     * 同步的 apply() 里把它们注册成工具。中间不加任何构建期耦合。
     */
    fun installBridgePlugin(profileName: String, files: Map<String, ByteArray>) {
        val dir = File(paths.profileDir(profileName), "plugins/$BRIDGE_PKG")
        dir.mkdirs()
        var copied = 0
        files.forEach { (name, bytes) ->
            runCatching {
                File(dir, name).writeBytes(bytes)
                copied++
            }.onFailure { LogBus.w(TAG, "写入桥插件文件 $name 失败：${it.message}") }
        }
        if (copied == 0) {
            LogBus.w(TAG, "没有拿到任何桥插件文件 —— 设备工具与流体云都不会生效，请检查 assets/bridge-plugin")
        }
        writeToolManifest(dir)
        LogBus.i(TAG, "能力桥插件铺好：${dir.absolutePath}（$copied 个文件 + tools.json）")
    }

    /** 把能力总线上的工具导出成插件可同步读取的清单。 */
    fun writeToolManifest(dir: File = File(paths.profileDir("web"), "plugins/$BRIDGE_PKG")): Int {
        dir.mkdirs()
        val specs = ToolBus.specs()
        val manifest = JSONObject().apply {
            put("generatedAt", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).format(java.util.Date()))
            put("bridge", JSONObject().apply {
                put("transport", "http-loopback")
                put("invoke", "POST /invoke")
                put("auth", "X-Fusion-Token")
            })
            put("tools", org.json.JSONArray().also { arr -> specs.forEach { arr.put(it.toJson()) } })
        }
        File(dir, "tools.json").writeText(manifest.toString(2))
        LogBus.i(TAG, "已导出能力清单：${specs.size} 个工具 -> ${File(dir, "tools.json").absolutePath}")
        return specs.size
    }

    companion object {
        private const val TAG = "内核"
        const val BRIDGE_PKG = "dsh-fusion-android-bridge"
        const val FALLBACK = "0.2.0-rc.2"

        /** 桥插件需要铺进 profile 的文件清单。 */
        val BRIDGE_FILES = listOf("package.json", "index.js", "cordis.patch.yml", "README.md")
    }
}
