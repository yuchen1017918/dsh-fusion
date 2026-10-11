package io.dsh.fusion.engine.plugin

import io.dsh.fusion.engine.LogBus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 安全模式 —— 插件把界面搞死之后的自救通道。
 *
 * ── 为什么需要它 ────────────────────────────────────────────────
 * 插件跑在引擎进程里，一个坏插件能让整个 Web UI 起不来。
 * 而**恢复入口恰恰不能画在那个 UI 里** —— 页面挂了它一起挂。
 * 桌面版为此做了原生弹窗（`DesktopFatalRecovery` + `project-manager.disableAllPlugins`）；
 * 网页版没有这个能力（网页管不了自己的宿主）。
 *
 * 我们的壳本身就是宿主，所以能直接做，而且能做得比桌面版更完整：
 * 桌面版只在**启动失败**时弹一次；这里做成常驻入口，并且**能看到、能还原备份**。
 *
 * ── 它到底改什么（对齐官方 sanitizeProfile 的语义）──────────────
 * 官方 `packages/boot/app-boot/src/profile-sanitize.ts` 就做两件事：
 *   1. 把 `cordis.patch.yml` 改名为带时间戳的备份 → 等价于「禁用全部插件补丁」
 *   2. 把 `package.json` 的 `dsh.profile.bundles` 重置为**官方列表**
 *
 * 官方列表只有两个包：`@deepseek-ai/dsh-base` 与 `@deepseek-ai/dsh-web-app`。
 * 其余一切都是第三方 —— 这就是「禁用所有第三方插件」的定义。
 *
 * ── 我们不删任何东西 ────────────────────────────────────────────
 * 全部是**改名**，不是删除。所以任何一步都能原样还原。
 * 这一点是刻意的：恢复工具本身如果再毁数据，那还不如不做。
 */
class SafeMode(
    /**
     * profile 目录。
     *
     * 刻意只依赖一个目录而不是整个 FusionPaths —— 这样它是**纯 JVM 可测的**，
     * 而「改名 + 重写清单」这段逻辑恰恰最需要被钉住：它是恢复工具，
     * 自己再毁数据就不可原谅了。
     */
    private val profileDir: File,
) {

    /** 一个可还原的备份。 */
    data class Backup(
        val file: File,
        val name: String,
        val kind: Kind,
        val sizeBytes: Long,
        val atMillis: Long,
    ) {
        enum class Kind(val label: String) {
            PATCH("插件补丁"),
            MANIFEST("依赖清单"),
            OTHER("其它"),
        }

        val prettySize: String get() = when {
            sizeBytes > 1024 * 1024 -> "%.1f MB".format(sizeBytes / 1048576.0)
            sizeBytes > 1024 -> "%.0f KB".format(sizeBytes / 1024.0)
            else -> "$sizeBytes B"
        }
    }

    /** 当前 profile 的体检结论，给界面用。 */
    data class Diagnosis(
        val totalBundles: Int,
        val thirdPartyBundles: List<String>,
        val officialBundles: List<String>,
        val patchFileBytes: Long,
        val backups: Int,
    ) {
        val thirdPartyCount: Int get() = thirdPartyBundles.size
        val isClean: Boolean get() = thirdPartyBundles.isEmpty()
    }

    data class Result(val ok: Boolean, val message: String, val detail: String? = null)

    private val patchFile: File get() = File(profileDir, PATCH_FILENAME)
    private val manifestFile: File get() = File(profileDir, "package.json")

    // ---------------------------------------------------------------- 诊断

    fun diagnose(): Diagnosis {
        val manifest = readManifest()
        val bundles = manifest?.optJSONObject("dsh")?.optJSONObject("profile")
            ?.optJSONArray("bundles")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).ifBlank { null } }
            } ?: emptyList()

        val thirdParty = bundles.filterNot { it in OFFICIAL_BUNDLES }
        val official = bundles.filter { it in OFFICIAL_BUNDLES }

        return Diagnosis(
            totalBundles = bundles.size,
            thirdPartyBundles = thirdParty,
            officialBundles = official,
            patchFileBytes = if (patchFile.exists()) patchFile.length() else 0L,
            backups = listBackups().size,
        )
    }

    // ---------------------------------------------------------------- 备份清单

    /** 列出所有可还原的备份（补丁与清单），新的在前。 */
    fun listBackups(): List<Backup> {
        if (!profileDir.isDirectory) return emptyList()
        val files = profileDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.contains(".bak") }
            .map { file ->
                Backup(
                    file = file,
                    name = file.name,
                    kind = when {
                        file.name.startsWith("$PATCH_FILENAME.") -> Backup.Kind.PATCH
                        file.name.startsWith("package.json.") -> Backup.Kind.MANIFEST
                        else -> Backup.Kind.OTHER
                    },
                    sizeBytes = file.length(),
                    atMillis = file.lastModified(),
                )
            }
            .sortedByDescending { it.atMillis }
    }

    // ---------------------------------------------------------------- 安全模式

    /**
     * 禁用所有第三方插件。
     *
     * **调用方必须先停掉引擎** —— 引擎正在跑时改 bundles 与补丁文件，
     * 轻则改了不生效，重则让 cordis 读到半截补丁。所以这里不自己停，
     * 由上层（设置页 / 启动失败流程）保证顺序。
     */
    fun disableAllPlugins(): Result {
        if (!profileDir.isDirectory) {
            return Result(false, "profile 目录不存在：${profileDir.absolutePath}")
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val moved = mutableListOf<String>()

        // ── 1. 补丁文件改名（等价于禁用全部插件补丁）──
        if (patchFile.exists()) {
            val target = uniqueBackupName("$PATCH_FILENAME.bak-safe-$stamp")
            if (!patchFile.renameTo(target)) {
                return Result(false, "无法备份 ${patchFile.name}（改名失败）")
            }
            moved += "$PATCH_FILENAME → ${target.name}"
            LogBus.i(TAG, "已备份插件补丁：${target.name}（${target.length()} 字节）")
        } else {
            LogBus.i(TAG, "没有 $PATCH_FILENAME，跳过补丁备份")
        }

        // ── 2. bundles 重置为官方列表 ──
        val manifest = readManifest()
        if (manifest == null) {
            LogBus.w(TAG, "读不到 package.json，只做了补丁备份")
            return Result(
                true,
                "已禁用插件补丁（未能读取 package.json，依赖清单未改动）",
                moved.joinToString("\n"),
            )
        }

        val before = manifest.optJSONObject("dsh")?.optJSONObject("profile")
            ?.optJSONArray("bundles")?.length() ?: 0

        // 清单也留一份备份 —— 这是可还原的前提
        if (manifestFile.exists()) {
            val target = uniqueBackupName("package.json.bak-safe-$stamp")
            if (manifestFile.copyTo(target, overwrite = false).exists()) {
                moved += "package.json → ${target.name}"
            }
        }

        val profile = manifest.optJSONObject("dsh")?.optJSONObject("profile") ?: JSONObject()
        profile.put("bundles", JSONArray(OFFICIAL_BUNDLES.toList()))
        val dsh = manifest.optJSONObject("dsh") ?: JSONObject()
        dsh.put("profile", profile)
        manifest.put("dsh", dsh)

        if (!writeManifestAtomically(manifest)) {
            return Result(false, "写入 package.json 失败")
        }

        val after = OFFICIAL_BUNDLES.size
        LogBus.w(TAG, "安全模式：bundles 从 $before 项重置为 $after 项（官方）")
        return Result(
            true,
            "已禁用 ${(before - after).coerceAtLeast(0)} 个第三方插件，仅保留官方内核",
            moved.joinToString("\n"),
        )
    }

    /**
     * 从备份还原。
     *
     * 按**文件名**决定还原到哪个目标 —— 因为备份是原文件的改名/副本，
     * 名字前缀本身就是目标名。这比让调用方传一个「目标路径」更不容易错。
     */
    fun restore(backup: Backup): Result {
        if (!backup.file.exists()) return Result(false, "备份不存在：${backup.name}")

        val targetName = when (backup.kind) {
            Backup.Kind.PATCH -> PATCH_FILENAME
            Backup.Kind.MANIFEST -> "package.json"
            Backup.Kind.OTHER -> return Result(false, "不认识这个备份的类型：${backup.name}")
        }
        val target = File(profileDir, targetName)

        // 还之前先把当前状态也留一份 —— 万一用户还原错了还能回头
        if (target.exists()) {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val safety = uniqueBackupName("$targetName.bak-beforeRestore-$stamp")
            target.copyTo(safety, overwrite = false)
            LogBus.i(TAG, "还原前已保留当前状态：${safety.name}")
        }

        return runCatching {
            backup.file.copyTo(target, overwrite = true)
            LogBus.w(TAG, "已从 ${backup.name} 还原 $targetName")
            Result(true, "已还原 $targetName（来源：${backup.name}）")
        }.getOrElse {
            Result(false, "还原失败：${it.message}")
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun readManifest(): JSONObject? = runCatching {
        if (!manifestFile.exists()) null else JSONObject(manifestFile.readText())
    }.getOrElse {
        LogBus.e(TAG, "package.json 解析失败：${it.message}")
        null
    }

    /**
     * 原子写清单。
     *
     * 先写临时文件再改名 —— 直接覆盖的话，写到一半断电会留下一个
     * **无法解析的 package.json**，那比插件坏掉更严重：引擎彻底起不来。
     */
    private fun writeManifestAtomically(manifest: JSONObject): Boolean = runCatching {
        val tmp = File(profileDir, "package.json.fusion-tmp")
        tmp.writeText(manifest.toString(2) + "\n")
        if (manifestFile.exists() && !manifestFile.delete()) {
            tmp.delete()
            return@runCatching false
        }
        if (!tmp.renameTo(manifestFile)) {
            tmp.delete()
            return@runCatching false
        }
        true
    }.getOrElse {
        LogBus.e(TAG, "原子写 package.json 失败：${it.message}")
        false
    }

    /** 避免覆盖已有备份（同名就加序号）。 */
    private fun uniqueBackupName(base: String): File {
        var candidate = File(profileDir, base)
        var ordinal = 1
        while (candidate.exists()) {
            candidate = File(profileDir, "$base-$ordinal")
            ordinal++
        }
        return candidate
    }

    companion object {
        private const val TAG = "安全模式"

        /** 官方补丁文件名（与 packages/boot/app-boot 的 PROFILE_PATCH_FILENAME 一致）。 */
        const val PATCH_FILENAME = "cordis.patch.yml"

        /**
         * 官方 web profile 的 bundles。
         *
         * 来源：官方 profile 模板（`PROFILE_TEMPLATES.web`）。
         * **不在这个列表里的都是第三方插件** —— 「禁用所有插件」就是把它重置成这两个。
         */
        val OFFICIAL_BUNDLES = listOf(
            "@deepseek-ai/dsh-base",
            "@deepseek-ai/dsh-web-app",
        )
    }
}
