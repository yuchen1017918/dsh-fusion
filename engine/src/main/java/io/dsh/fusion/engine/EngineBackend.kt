package io.dsh.fusion.engine

/**
 * 引擎的两种跑法，对应社区里两条互不相让的路线，这里让它们共存：
 *
 *  - [BIONIC_NATIVE]：bionic Node 直接跑官方 dsh。冷启动快、内存低，
 *    代价是子进程只有 Android 的 toybox userland（Soodok / dsh-mobile 路线）。
 *  - [UBUNTU_PROOT]：同一个 bionic Node 塞进 PRoot 的 Ubuntu rootfs 里跑，
 *    apt / gcc / python 全都在（DSHA / DSHBox 路线），代价是 proot 的 syscall 翻译开销。
 *
 * 两者共用同一份 Node 与同一份 dsh 安装，切换不重装。
 */
enum class EngineBackend(val id: String, val label: String, val hint: String) {
    BIONIC_NATIVE(
        "bionic",
        "原生直跑",
        "bionic Node 直接运行官方内核：启动最快、内存占用最低，适合日常对话与插件生态。",
    ),
    UBUNTU_PROOT(
        "proot",
        "Ubuntu 容器",
        "同一份内核跑在完整 Ubuntu rootfs 里：apt 可用、需要编译的原生模块能装，适合重活。",
    );

    companion object {
        fun from(id: String?): EngineBackend = entries.firstOrNull { it.id == id } ?: BIONIC_NATIVE
    }
}

enum class EngineState { IDLE, PROVISIONING, STARTING, RUNNING, DEGRADED, STOPPING, FAILED }

data class EngineStatus(
    val state: EngineState,
    val backend: EngineBackend,
    val port: Int? = null,
    val url: String? = null,
    val pid: Long? = null,
    val since: Long = 0L,
    val restarts: Int = 0,
    val detail: String = "",
) {
    val running: Boolean get() = state == EngineState.RUNNING && port != null
}
