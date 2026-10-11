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
        val childProcessCount: Int?,
        val advice: String,
    )

    /**
     * 当前进程的直接子进程数。
     *
     * 返回 null 表示**读不到**，而不是 0。
     * 之前这里吞掉异常返回 0，界面就显示「子进程 0 个」—— 一个看起来正常、
     * 实际上毫无信息量的数字。Android 上 `/proc/<pid>/task/<tid>/children`
     * 未必可读，把「未知」伪装成「零」比报错更糟。
     */
    fun childProcessCount(): Int? {
        val pid = android.os.Process.myPid()
        val tasks = java.io.File("/proc/$pid/task")
        val taskDirs = tasks.listFiles() ?: return null
        if (taskDirs.isEmpty()) return null

        var count = 0
        var readAny = false
        taskDirs.forEach { task ->
            val file = java.io.File(task, "children")
            if (!file.canRead()) return@forEach
            val text = runCatching { file.readText().trim() }.getOrNull() ?: return@forEach
            readAny = true
            if (text.isNotEmpty()) count += text.split(' ').count { it.isNotBlank() }
        }
        return if (readAny) count else null
    }

    fun query(shellCommand: (String) -> String?): Boolean? {
        val out = shellCommand("settings get global $KEY") ?: return null
        return when (out.trim().lowercase()) {
            "1", "true" -> true
            "0", "false", "null" -> false
            else -> null
        }
    }

    fun disableCommand(): String = "settings put global $KEY false"

    fun describe(monitorEnabled: Boolean?, children: Int?): String {
        val childText = children?.let { "$it 个" } ?: "读不到（本机 /proc 未开放该字段）"
        return when {
            monitorEnabled == true ->
                "监控开启中（子进程 $childText）——超过约 32 个会被系统静默杀掉，建议关掉监控"
            monitorEnabled == false ->
                "监控已关闭（子进程 $childText）——引擎可以安心拉子进程"
            else ->
                "无法确定监控状态（需要 Shizuku/Root 才能查），当前子进程 $childText；超过 32 个有被清理的风险"
        }
    }
}
