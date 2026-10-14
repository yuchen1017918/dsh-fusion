package io.dsh.fusion.installer

import org.json.JSONArray
import org.json.JSONObject

/**
 * 产物清单 —— 整个架构的契约。
 *
 * 安装器不认识「分支」是什么，它只认识这份清单：
 *   · 每个产物声明自己需要什么设备（platform / abi / minApi / 内存 / 存储）
 *   · 声明自己从哪个分支构建、放在哪些渠道
 *   · 声明自己的大小与 sha256
 *
 * 所以「新增一个平台」不需要改安装器代码 —— 往清单里加一条就行。
 * 这也正是「main 只做安装器，实现分到各分支」这件事能成立的原因。
 */
data class Channel(
    val id: String,
    val name: String,
    val base: String,
    val priority: Int,
    val note: String,
)

data class Artifact(
    val id: String,
    val name: String,
    val branch: String?,
    val platform: String,
    val abi: String?,
    val minApi: Int?,
    val minRamGb: Double,
    val minStorageMb: Long,
    val kind: String,          // apk / external
    val version: String?,
    val fileName: String?,
    val bytes: Long,
    val sha256: String,
    val note: String,
    val externalUrl: String?,
) {
    val isExternal: Boolean get() = kind == "external"
    val hasPayload: Boolean get() = bytes > 0 && sha256.isNotBlank() && fileName != null
}

/** 一个产物的匹配结果：为什么选它，或者为什么不选。 */
data class Match(
    val artifact: Artifact,
    val ok: Boolean,
    val reasons: List<String>,
)

class ArtifactCatalog(private val root: JSONObject) {

    val channels: List<Channel> = root.optJSONArray("channels").mapJSON { o ->
        Channel(
            id = o.getString("id"),
            name = o.optString("name", o.getString("id")),
            base = o.getString("base").trimEnd('/'),
            priority = o.optInt("priority", 50),
            note = o.optString("note"),
        )
    }.sortedBy { it.priority }

    val artifacts: List<Artifact> = root.optJSONArray("artifacts").mapJSON { o ->
        Artifact(
            id = o.getString("id"),
            name = o.optString("name", o.getString("id")),
            branch = o.optString("branch").ifBlank { null },
            platform = o.getString("platform"),
            abi = o.optString("abi").ifBlank { null },
            minApi = if (o.has("minApi") && !o.isNull("minApi")) o.optInt("minApi") else null,
            minRamGb = o.optDouble("minRamGb", 0.0),
            minStorageMb = o.optLong("minStorageMb", 0),
            kind = o.optString("kind", "apk"),
            version = o.optString("version").ifBlank { null },
            fileName = o.optString("fileName").ifBlank { null },
            bytes = o.optLong("bytes", 0),
            sha256 = o.optString("sha256"),
            note = o.optString("note"),
            externalUrl = o.optString("externalUrl").ifBlank { null },
        )
    }

    /**
     * 按设备挑产物。
     *
     * 返回**全部**候选（含不匹配的），而不是只返回一个 ——
     * 因为「为什么我这台机器不能用」是用户真会遇到的问题，
     * 而只说「没有可用版本」等于什么都没说。
     */
    fun matchAll(d: DeviceProfile): List<Match> = artifacts.map { a ->
        val reasons = mutableListOf<String>()

        if (a.isExternal) {
            reasons += "外部产物（由社区提供），不通过本安装器下载"
            return@map Match(a, true, reasons)
        }

        if (a.platform != "android") reasons += "平台不匹配（产物面向 ${a.platform}）"
        if (a.abi != null && !d.abis.contains(a.abi)) {
            reasons += "架构不匹配（产物需要 ${a.abi}，本机是 ${d.abis.joinToString("/")}）"
        }
        if (a.minApi != null && d.apiLevel < a.minApi) {
            reasons += "系统版本过低（需要 API ${a.minApi}，本机 ${d.apiLevel}）"
        }
        if (d.ramGb < a.minRamGb) {
            reasons += "内存不足（需要 ${a.minRamGb} GB，本机 ${"%.1f".format(d.ramGb)} GB）"
        }
        if (d.freeStorageMb in 0 until a.minStorageMb) {
            reasons += "存储不足（需要 ${a.minStorageMb / 1024} GB，可用 ${d.freeStorageMb / 1024} GB）"
        }
        // 清单没填数字等于没发布，不能选 —— 否则下载完必然校验失败
        if (!a.hasPayload) reasons += "该产物尚未发布（清单里没有大小与校验值）"

        Match(a, reasons.isEmpty(), reasons)
    }

    /** 最适合的那一个（排除外部产物）。 */
    fun best(d: DeviceProfile): Match? =
        matchAll(d).filter { it.ok && !it.artifact.isExternal }
            .maxByOrNull { it.artifact.bytes }

    fun byId(id: String): Artifact? = artifacts.firstOrNull { it.id == id }

    /** 拼出某个产物在第 n 个渠道的完整 URL。 */
    fun urlOf(artifact: Artifact, channel: Channel): String? {
        val file = artifact.fileName ?: return null
        val tag = "v${artifact.version ?: "0.0.0"}"
        return "${channel.base}/$tag/$file"
    }

    companion object {
        fun parse(json: String): ArtifactCatalog = ArtifactCatalog(JSONObject(json))
    }
}

/** JSONArray → List 的小工具（org.json 没有直接的映射）。 */
internal inline fun <T> JSONArray?.mapJSON(f: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) out += f(getJSONObject(i))
    return out
}
