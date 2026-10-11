package io.dsh.fusion.engine.update

import io.dsh.fusion.engine.FusionPaths
import io.dsh.fusion.engine.FusionSettings
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.Provisioner
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内核在线更新。
 *
 * 社区里几个项目都去啃 GitHub Releases，但那条路在国内基本是抽奖。
 * 这里改用 npm registry 作为唯一事实来源（dsh 本来就只从 npm 发布），
 * 于是「切换内核版本」退化成一次幂等的 reinstall —— 会话与设置全都不动。
 */
class KernelUpdater(
    private val paths: FusionPaths,
    private val settings: FusionSettings,
    private val provisioner: Provisioner,
) {

    data class Release(val version: String, val publishedAt: String, val prerelease: Boolean)

    fun currentVersion(): String? = provisioner.dsh.installedVersion()

    fun available(limit: Int = 40): List<Release> = runCatching {
        val url = "${settings.npmRegistry.trimEnd('/')}/@deepseek-ai%2Fdsh"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Accept", "application/json")
        }
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        val time = json.optJSONObject("time") ?: JSONObject()
        val versions = json.optJSONObject("versions") ?: JSONObject()
        val list = versions.keys().asSequence().map { v ->
            Release(
                version = v,
                publishedAt = time.optString(v).take(10),
                prerelease = v.contains('-'),
            )
        }.toList()
        // 稳定版在前，组内按版本号倒序
        list.sortedWith(
            compareByDescending<Release> { !it.prerelease }
                .thenComparator { a, b -> compareVersions(b.version, a.version) }
        ).take(limit)
    }.getOrElse {
        LogBus.w(TAG, "拉取版本列表失败：${it.message}")
        emptyList()
    }

    /** 幂等切换到指定版本：只重装 npm 侧的东西，DSH_HOME 里的会话与插件一律保留。 */
    suspend fun switchTo(version: String, onStep: ((String) -> Unit)? = null): Boolean {
        LogBus.i(TAG, "切换内核 -> $version（当前 ${currentVersion() ?: "无"}）")
        // 走 Provisioner 而不是 DshProvisioner：只有这样桥插件文件才会被一起铺进去
        val ok = provisioner.installKernel(version, onStep)
        if (ok) LogBus.i(TAG, "内核已切到 $version")
        else LogBus.e(TAG, "内核切换失败：$version")
        return ok
    }

    companion object {
        private const val TAG = "更新"

        /**
         * 语义化版本比较。
         *
         * 三个坑，都是单测逼出来的：
         *  1. **缺位要按 0 补**：`0.2` 与 `0.2.0` 是同一个版本，必须返回 0。
         *     早先数字段比完就退回 `a.compareTo(b)`，那会给出 -2 —— 版本排序因此会抖。
         *  2. **数字段要按数值比**：字符串序会认为 `0.9.0` > `0.10.0`。
         *  3. **预发布标识更小**（semver 规则）：`0.2.0-rc.2` < `0.2.0`。
         *     升级页据此把稳定版排在前面，排错了用户会升级到一个更旧的版本。
         */
        fun compareVersions(a: String, b: String): Int {
            fun core(v: String): String = v.split('-', '+').first()
            fun prerelease(v: String): String? =
                v.substringAfter('-', "").substringBefore('+').ifBlank { null }

            val pa = core(a).split('.').mapNotNull { it.toIntOrNull() }
            val pb = core(b).split('.').mapNotNull { it.toIntOrNull() }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x - y
            }

            val preA = prerelease(a)
            val preB = prerelease(b)
            return when {
                preA == null && preB == null -> 0
                preA == null -> 1        // 正式版 > 预发布版
                preB == null -> -1
                else -> preA.compareTo(preB)
            }
        }
    }
}
