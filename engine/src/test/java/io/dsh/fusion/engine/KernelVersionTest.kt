package io.dsh.fusion.engine

import io.dsh.fusion.engine.update.KernelUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较。
 *
 * 这段逻辑决定「内核更新」页把哪个版本排在最前面 —— 排错了用户会升到一个更旧的版本，
 * 而且升级过程要重装几百 MB，代价很大。所以它值得被钉住。
 */
class KernelVersionTest {

    @Test
    fun `数字段逐位比较`() {
        assertTrue(KernelUpdater.compareVersions("0.2.0", "0.1.9") > 0)
        assertTrue(KernelUpdater.compareVersions("0.1.9", "0.2.0") < 0)
        assertEquals(0, KernelUpdater.compareVersions("0.2.0", "0.2.0"))
    }

    @Test
    fun `位数不同时缺位按 0 处理`() {
        assertEquals(0, KernelUpdater.compareVersions("0.2", "0.2.0"))
        assertTrue(KernelUpdater.compareVersions("0.2.1", "0.2") > 0)
        assertTrue(KernelUpdater.compareVersions("1", "0.9.9.9") > 0)
    }

    @Test
    fun `预发布版小于正式版（semver 规则）`() {
        assertTrue(
            "0.2.0-rc.2 应当小于 0.2.0",
            KernelUpdater.compareVersions("0.2.0-rc.2", "0.2.0") < 0,
        )
        assertTrue(KernelUpdater.compareVersions("0.2.0", "0.2.0-rc.2") > 0)
        // 结果必须稳定，否则升级页的排序会抖
        assertEquals(
            KernelUpdater.compareVersions("0.2.0-rc.2", "0.2.0"),
            KernelUpdater.compareVersions("0.2.0-rc.2", "0.2.0"),
        )
    }

    @Test
    fun `两个预发布版之间按标识比较`() {
        assertTrue(KernelUpdater.compareVersions("0.2.0-rc.2", "0.2.0-rc.1") > 0)
        assertEquals(0, KernelUpdater.compareVersions("0.2.0-rc.2", "0.2.0-rc.2"))
    }

    @Test
    fun `数字段相同时不同位数视为同一版本 —— 回归测试`() {
        // 曾经这里返回 -2：数字段比完后错误地退回了字符串比较。
        // 单测第一次运行就抓到了它。
        assertEquals("0.2 与 0.2.0 是同一个版本", 0, KernelUpdater.compareVersions("0.2", "0.2.0"))
        assertEquals(0, KernelUpdater.compareVersions("0.2.0", "0.2"))
        assertEquals(0, KernelUpdater.compareVersions("1", "1.0.0"))
    }

    @Test
    fun `两位数十位版本号不被字符串序误导`() {
        // 字符串比较会认为 "0.9.0" > "0.10.0"，数字比较才是对的
        assertTrue(KernelUpdater.compareVersions("0.10.0", "0.9.0") > 0)
    }
}
