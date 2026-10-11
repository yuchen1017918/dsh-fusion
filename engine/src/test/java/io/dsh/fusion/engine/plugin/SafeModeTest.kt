package io.dsh.fusion.engine.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 安全模式的行为验证。
 *
 * 这是一个**恢复工具**，所以它自己的正确性要求比别的模块更高：
 * 用户点它的时候，界面往往已经是坏的 —— 如果它再毁一次数据，就没有下一步了。
 * 因此这里逐条钉住三件事：
 *
 *  1. **不删任何东西**（全部是改名/副本）
 *  2. 禁用后 bundles 恰好等于官方列表
 *  3. 还原能回到禁用前的状态（往返一致）
 */
class SafeModeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var profile: File

    private fun setup(thirdParty: List<String> = listOf("dsh-mnemon", "dsh-mermaid", "dsh-meme")) {
        profile = tmp.newFolder("web")
        val bundles = JSONArray((SafeMode.OFFICIAL_BUNDLES + thirdParty).toList())
        val manifest = JSONObject().apply {
            put("name", "dsh-profile-web")
            put("private", true)
            put("dsh", JSONObject().apply {
                put("profile", JSONObject().apply { put("bundles", bundles) })
            })
        }
        File(profile, "package.json").writeText(manifest.toString(2))
        File(profile, SafeMode.PATCH_FILENAME).writeText("# 十万字节的插件补丁\nplugins:\n  - dsh-mnemon\n")
    }

    private fun bundles(): List<String> {
        val obj = JSONObject(File(profile, "package.json").readText())
        val arr = obj.getJSONObject("dsh").getJSONObject("profile").getJSONArray("bundles")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    @Test
    fun `诊断能分清官方与第三方`() {
        setup()
        val diag = SafeMode(profile).diagnose()
        assertEquals(5, diag.totalBundles)
        assertEquals(2, diag.officialBundles.size)
        assertEquals(3, diag.thirdPartyCount)
        assertTrue(diag.thirdPartyBundles.contains("dsh-mnemon"))
        assertFalse(diag.isClean)
    }

    @Test
    fun `全是官方时 isClean 为真`() {
        setup(thirdParty = emptyList())
        assertTrue(SafeMode(profile).diagnose().isClean)
    }

    @Test
    fun `禁用后 bundles 恰好等于官方列表`() {
        setup()
        val result = SafeMode(profile).disableAllPlugins()
        assertTrue(result.message, result.ok)
        assertEquals(SafeMode.OFFICIAL_BUNDLES, bundles())
        assertTrue(SafeMode(profile).diagnose().isClean)
    }

    @Test
    fun `禁用不删除任何文件 —— 全部是改名或副本`() {
        setup()
        val patchBefore = File(profile, SafeMode.PATCH_FILENAME)
        val patchBytes = patchBefore.length()
        assertTrue(patchBefore.exists())

        SafeMode(profile).disableAllPlugins()

        assertFalse("补丁文件应已被改名", patchBefore.exists())
        val recovered = profile.listFiles()
            ?.filter { it.name.startsWith(SafeMode.PATCH_FILENAME + ".bak-safe-") }
            ?.firstOrNull()
        assertNotNull("必须留下补丁备份", recovered)
        assertEquals("备份内容必须与原文一致", patchBytes, recovered!!.length())
        assertTrue(
            "必须留下清单备份",
            profile.listFiles()!!.any { it.name.startsWith("package.json.bak-safe-") },
        )
    }

    @Test
    fun `没有补丁文件时也能安全完成`() {
        setup()
        File(profile, SafeMode.PATCH_FILENAME).delete()
        val result = SafeMode(profile).disableAllPlugins()
        assertTrue(result.ok)
        assertEquals(SafeMode.OFFICIAL_BUNDLES, bundles())
    }

    @Test
    fun `清单不可解析时只做补丁备份，并如实告知`() {
        setup()
        File(profile, "package.json").writeText("{ 这不是 JSON")
        val result = SafeMode(profile).disableAllPlugins()
        assertTrue(result.ok)
        assertTrue(result.message.contains("未改动"))
    }

    @Test
    fun `重复执行不会互相覆盖备份`() {
        setup()
        val safe = SafeMode(profile)
        safe.disableAllPlugins()
        File(profile, SafeMode.PATCH_FILENAME).writeText("second")
        safe.disableAllPlugins()
        val patches = profile.listFiles()!!.count { it.name.startsWith(SafeMode.PATCH_FILENAME + ".bak-safe-") }
        assertEquals("两次禁用应留下两份补丁备份", 2, patches)
    }

    @Test
    fun `还原清单后 bundles 回到禁用前的状态`() {
        setup()
        val before = bundles()
        val safe = SafeMode(profile)
        safe.disableAllPlugins()
        assertEquals(SafeMode.OFFICIAL_BUNDLES, bundles())

        val manifestBackup = safe.listBackups().first { it.name.startsWith("package.json.bak-safe-") }
        val restored = safe.restore(manifestBackup)
        assertTrue(restored.message, restored.ok)
        assertEquals("还原后必须与禁用前完全一致", before, bundles())
    }

    @Test
    fun `还原补丁文件`() {
        setup()
        val safe = SafeMode(profile)
        val original = File(profile, SafeMode.PATCH_FILENAME).readText()
        safe.disableAllPlugins()
        assertFalse(File(profile, SafeMode.PATCH_FILENAME).exists())

        val patchBackup = safe.listBackups().first { it.name.startsWith(SafeMode.PATCH_FILENAME + ".bak-safe-") }
        assertTrue(safe.restore(patchBackup).ok)
        assertEquals("补丁内容必须逐字节一致", original, File(profile, SafeMode.PATCH_FILENAME).readText())
    }

    @Test
    fun `还原前会把当前状态也留一份 —— 还原错了还能回头`() {
        setup()
        val safe = SafeMode(profile)
        safe.disableAllPlugins()
        val backup = safe.listBackups().first { it.name.startsWith("package.json.bak-safe-") }
        safe.restore(backup)
        assertTrue(
            "还原前必须先保留当前状态",
            profile.listFiles()!!.any { it.name.startsWith("package.json.bak-beforeRestore-") },
        )
    }

    @Test
    fun `备份清单按时间倒序，最新的在最前`() {
        setup()
        val safe = SafeMode(profile)
        safe.disableAllPlugins()
        Thread.sleep(1100)
        File(profile, SafeMode.PATCH_FILENAME).writeText("another")
        safe.disableAllPlugins()
        val patches = safe.listBackups().filter { it.kind == SafeMode.Backup.Kind.PATCH }
        assertTrue(patches.size >= 2)
        assertTrue("新的应排在前面", patches[0].atMillis >= patches[1].atMillis)
    }

    @Test
    fun `备份类型识别正确`() {
        setup()
        SafeMode(profile).disableAllPlugins()
        val kinds = SafeMode(profile).listBackups().associate { it.name to it.kind }
        assertTrue(kinds.any { it.key.startsWith(SafeMode.PATCH_FILENAME) && it.value == SafeMode.Backup.Kind.PATCH })
        assertTrue(kinds.any { it.key.startsWith("package.json") && it.value == SafeMode.Backup.Kind.MANIFEST })
    }

    @Test
    fun `还原不存在的备份会明确失败而不是静默`() {
        setup()
        val bogus = SafeMode.Backup(
            file = File(profile, "nope.bak"), name = "nope.bak",
            kind = SafeMode.Backup.Kind.PATCH, sizeBytes = 0, atMillis = 0,
        )
        val result = SafeMode(profile).restore(bogus)
        assertFalse(result.ok)
        assertTrue(result.message.contains("不存在"))
    }
}
