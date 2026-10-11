package io.dsh.fusion.engine.runtime

import io.dsh.fusion.engine.LogBus
import java.net.InetAddress
import java.net.ServerSocket

/**
 * 引擎监听端口的选择。
 *
 * 单独成类有两个原因：它有三条值得被钉住的行为（见 [pick] 的注释），
 * 而 `EngineSupervisor` 需要 Context，在纯 JVM 单测里构造不出来。
 *
 * ── 为什么默认是 0 ────────────────────────────────────────────────
 * 内核明确支持 `--port 0`（`dsh-host-webserver` 的注释原话：
 * *"the OS-assigned value when config.port is 0"*），并且在 listen 成功后才
 * 记下 `server.address().port`；`dsh-web-app` 打印 URL 时读的正是这个值。
 * 所以传 0 之后：
 *
 *   · 端口由操作系统在 listen 的那一刻**原子地**分配 —— 没有「探测到空闲、
 *     启动时却被别人抢走」的窗口（也就是没有 TOCTOU 竞态）；
 *   · 我们照旧能从 stdout 解析出**真实**地址，因为内核打印的是真实端口而不是 0。
 *
 * 而先探测再启动的做法天然有竞态：`isFree()` 关闭探针 socket 之后、引擎真正
 * listen 之前，别的进程完全可能占走那个端口。概率不高，但一旦发生，
 * 用户看到的是一句笼统的「启动超时」，完全联想不到端口冲突。
 */
object PortPicker {

    /** 备选端口扫描窗口。 */
    const val SCAN_WINDOW = 128

    /** 传 0 表示「让操作系统分配」。 */
    const val OS_ASSIGNED = 0

    fun isFree(port: Int): Boolean = runCatching {
        // 必须绑在 127.0.0.1 上探测：引擎只监听回环，绑 0.0.0.0 探测会得到
        // 不同的结论（别的进程可能只占了回环那一份）
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { true }
    }.getOrDefault(false)

    /**
     * 挑一个端口。三条行为：
     *
     *  1. `preferred <= 0` → 返回 0，交给操作系统（**首选路径**，零竞态）
     *  2. `preferred > 0`  → 从它起扫 [SCAN_WINDOW] 个，返回第一个空闲的
     *  3. 全都占用        → **返回 0**，而不是返回一个已知被占用的端口
     *
     * 第 3 条是这里最容易写错、也最值得写错的地方：早先的实现扫不到就返回
     * `preferred`，于是把一个**已知被占用**的端口交给引擎，注定了失败 ——
     * 而失败信息里只有「启动超时」，用户根本无从判断。
     */
    fun pick(preferred: Int): Int {
        if (preferred <= 0) return OS_ASSIGNED
        for (offset in 0 until SCAN_WINDOW) {
            val candidate = preferred + offset
            if (candidate > 65535) break
            if (isFree(candidate)) return candidate
        }
        LogBus.w(
            TAG,
            "首选端口 $preferred 起 $SCAN_WINDOW 个候选都被占用 —— 改用系统分配端口（传 0），" +
                "而不是硬闯一个已知被占用的端口",
        )
        return OS_ASSIGNED
    }

    private const val TAG = "端口"
}
