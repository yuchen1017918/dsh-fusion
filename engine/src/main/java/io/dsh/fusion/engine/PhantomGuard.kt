package io.dsh.fusion.engine

/**
 * 安卓 12–14 的「幻影进程杀手」。
 *
 * 系统会统计每个应用的子进程数，超过阈值（通常 32）就把整个进程组杀掉 ——
 * 不报错、不给理由，表现就是「Node 跑着跑着没了」。这是 DSH 这类
 * 带子进程的运行时在手机上的头号暗杀者。
 *
 * 三条应对：
 *  1. 自查当前开关状态（能查就查）；
 *  2. 有 shell 通道时提示并可一键关掉监控（settings put global …）；
 *  3. 没有 shell 就地降级：把并发压到个位数 + 记录子进程数，让用户看得见。
 */
object PhantomGuard {

    private const val KEY = "settings_enable_monitor_phantom_procs"

    data class State(
        val monitorEnabled: Boolean?,
        val childProcessCount: Int,
        val advice: String,
    )

    /** 当前进程的直接子进程数（/proc 能读多少算多少）。 */
    fun childProcessCount(): Int = runCatching {
        val pid = android.os.Process.myPid()
        val tasks = java.io.File("/proc/$pid/task")
        var count = 0
        tasks.listFiles()?.forEach { task ->
            runCatching {
                val children = java.io.File(task, "children").readText().trim()
                if (children.isNotEmpty()) count += children.split(' ').size
            }
        }
        count
    }.getOrDefault(0)

    fun query(shellCommand: (String) -> String?): Boolean? {
        val out = shellCommand("settings get global $KEY") ?: return null
        return when (out.trim().lowercase()) {
            "1", "true" -> true
            "0", "false", "null" -> false
            else -> null
        }
    }

    fun disableCommand(): String = "settings put global $KEY false"

    fun describe(monitorEnabled: Boolean?, children: Int): String = when {
        monitorEnabled == true ->
            "监控开启中（子进程 $children 个）——超过约 32 个会被系统静默杀掉，建议关掉监控"
        monitorEnabled == false ->
            "监控已关闭（子进程 $children 个）——引擎可以安心拉子进程"
        else ->
            "无法确定监控状态（需要 Shizuku/Root 才能查），当前子进程 $children 个；超过 32 个有被清理的风险"
    }
}
