package io.dsh.fusion.engine.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * 端口选择。
 *
 * 这三条行为分别对应三种真实场景，其中第三条曾经写错过：
 * 扫不到就返回首选端口 → 把一个**已知被占用**的端口交给引擎 → 注定失败，
 * 而失败信息只有「启动超时」，用户完全无从判断。实测确认过：内核遇到被占用的
 * 端口不会自己退让，直接 `EADDRINUSE` 崩掉。
 */
class PortPickerTest {

    /** 占住一个端口，返回它。 */
    private fun occupy(): ServerSocket =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    @Test
    fun `preferred 为 0 时交给操作系统 —— 这是零竞态的首选路径`() {
        assertEquals(PortPicker.OS_ASSIGNED, PortPicker.pick(0))
        assertEquals(PortPicker.OS_ASSIGNED, PortPicker.pick(-1))
    }

    @Test
    fun `首选端口空闲时就用它 —— 尊重用户显式指定的端口`() {
        val port = freePort()
        assertEquals(port, PortPicker.pick(port))
    }

    @Test
    fun `首选端口被占用时顺延到下一个空闲端口`() {
        occupy().use { busy ->
            val picked = PortPicker.pick(busy.localPort)
            assertNotEquals("不能返回被占用的端口", busy.localPort, picked)
            assertTrue("应当顺延到 busy+1", picked == busy.localPort + 1)
        }
    }

    @Test
    fun `整段窗口都被占用时退回系统分配，而不是硬闯一个已知被占用的端口 —— 回归测试`() {
        // 占用 SCAN_WINDOW 个连续端口。用 1024 附近的窗口，避开系统常用端口。
        val start = freePort()
        val held = mutableListOf<ServerSocket>()
        try {
            var port = start
            while (held.size < PortPicker.SCAN_WINDOW && port < 65535) {
                try {
                    held += ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
                    port++
                } catch (e: Exception) {
                    // 这个端口恰好不可用，跳过继续（不断言完美连续）
                    port++
                }
            }
            val picked = PortPicker.pick(start)
            // 要么这一段真的被我们占满了 → 必须返回 0；
            // 要么中间有漏网的 → 返回那个空端口。两种都不允许返回被占用的端口。
            val busy = held.map { it.localPort }.toSet()
            assertTrue(
                "返回的端口不能被占用：picked=$picked busy=${busy.contains(picked)}",
                picked == PortPicker.OS_ASSIGNED || !busy.contains(picked),
            )
        } finally {
            held.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `isFree 与真实占用状态一致`() {
        val port = freePort()
        assertTrue("刚释放的端口应当空闲", PortPicker.isFree(port))
        occupyAt(port).use {
            assertTrue("被占用后不该报空闲", !PortPicker.isFree(port))
        }
    }

    private fun occupyAt(port: Int): ServerSocket =
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
}
