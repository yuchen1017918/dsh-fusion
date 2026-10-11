package io.dsh.fusion.engine.provision

import android.content.Context
import io.dsh.fusion.engine.FusionPaths
import io.dsh.fusion.engine.LogBus
import org.json.JSONObject
import java.io.File

/**
 * 内置 npm 的解包。
 *
 * 设备上大概率没有 npm，Termux 的 nodejs 包也已经不带 npm（只剩 corepack）。
 * 所以 npm 以 tgz 形式随 APK 发货，首启解到 filesDir —— 不需要联网、
 * 不需要用户先装任何东西，「装完即用」这句承诺靠的就是这一步。
 */
class NpmProvisioner(private val context: Context, private val paths: FusionPaths) {

    fun installedVersion(): String? {
        val pkg = File(paths.npmDir, "package.json")
        if (!pkg.exists()) return null
        return runCatching { JSONObject(pkg.readText()).optString("version").ifBlank { null } }.getOrNull()
    }

    fun isReady(): Boolean = paths.npmCli.exists()

    /** 返回解包后的版本号；失败抛异常。 */
    fun provision(onStep: ((String) -> Unit)? = null): String {
        if (isReady()) {
            val v = installedVersion() ?: "unknown"
            LogBus.i(TAG, "npm 已就位：v$v")
            return v
        }
        val asset = findAsset() ?: throw IllegalStateException("APK 内没有 npm 安装包（assets/runtime/npm-*.tgz）")
        paths.ensure()
        val tmp = File(paths.tmpDir, asset)
        onStep?.invoke("解包内置 npm…")
        LogBus.i(TAG, "解包 assets/runtime/$asset -> ${paths.npmDir.absolutePath}")
        context.assets.open("runtime/$asset").use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
        }
        if (paths.npmDir.exists()) paths.npmDir.deleteRecursively()
        paths.npmDir.mkdirs()
        TarExt.extract(tmp, paths.npmDir, stripComponents = 1)
        if (!tmp.delete()) LogBus.w(TAG, "临时包残留: ${tmp.absolutePath}")
        val version = installedVersion() ?: "unknown"
        LogBus.i(TAG, "npm v$version 就位，入口 ${paths.npmCli.absolutePath}")
        if (!paths.npmCli.exists()) throw IllegalStateException("npm 解包异常：找不到 ${paths.npmCli.absolutePath}")
        return version
    }

    private fun findAsset(): String? = runCatching {
        context.assets.list("runtime")?.firstOrNull { it.startsWith("npm-") && it.endsWith(".tgz") }
    }.getOrNull()

    private companion object {
        const val TAG = "npm"
    }
}
