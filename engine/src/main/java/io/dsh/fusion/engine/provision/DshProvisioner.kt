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
    }

    // ---------------------------------------------------------------- 安装

    /** 安装/升级启动器 + profile 依赖。返回是否成功。 */
    suspend fun install(
        version: String,
        profileName: String = "web",
        onStep: ((String) -> Unit)? = null,
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
        installBridgePlugin(profileName)

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
    fun installBridgePlugin(profileName: String) {
        val dir = File(paths.profileDir(profileName), "plugins/$BRIDGE_PKG")
        dir.mkdirs()
        val assets = listOf("package.json", "index.js", "cordis.patch.yml", "README.md")
        var copied = 0
        for (name in assets) {
            runCatching {
                val bytes = ASSET_READER?.invoke("bridge-plugin/$name") ?: return@runCatching
                File(dir, name).writeBytes(bytes)
                copied++
            }
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

        /** 由 app 模块注入 assets 读取器，避免 engine 依赖 Android Context。 */
        var ASSET_READER: ((String) -> ByteArray?)? = null
    }
}
