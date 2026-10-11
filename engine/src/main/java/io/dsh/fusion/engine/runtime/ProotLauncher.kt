package io.dsh.fusion.engine.runtime

import io.dsh.fusion.engine.FusionPaths
import java.io.File

/**
 * PRoot 命令构造器 —— 「完整 Ubuntu 环境」这一半的实现。
 *
 * 关键手法（和 DSHA 的 proroot 同源）：不把 Node 装进 rootfs，而是把
 * nativeLibraryDir 绑进客人视角的 /opt/bionic，于是**同一份 Node、同一份
 * dsh 安装**，两个后端共享，切换后端不需要重装任何东西。
 */
class ProotLauncher(private val paths: FusionPaths) {

    /** 客人视角里的挂载点。 */
    object Guest {
        const val BIONIC = "/opt/bionic"
        const val DSH = "/opt/dsh"
        const val HOME = "/root"
        const val DSH_HOME = "/root/.dsh"
        const val TMP = "/tmp"
        const val CACHE = "/opt/cache"
        const val HOST = "/opt/host"

        /** 本地大模型运行时（llama.cpp）。按需下载，不随 APK 发货。 */
        const val LLM = "/opt/llm"
    }

    fun command(guestArgv: List<String>, workDir: String = Guest.HOME): List<String> {
        val args = mutableListOf<String>()
        args += paths.prootBin.absolutePath
        args += "--link2symlink"          // Android 文件系统对硬链接不友好，交给 proot 兜底
        args += "-0"                      // 伪 root：apt 才不会因为 uid 不是 0 直接拒跑
        args += "-r"; args += paths.rootfsDir.absolutePath
        args += "-w"; args += workDir

        // 客人的 /dev /proc /sys 必须来自真机，否则 apt 与 python 都会炸
        listOf("/dev", "/proc", "/sys", "/system", "/apex", "/vendor", "/data/misc").forEach { p ->
            bind(args, p)
        }
        bind(args, paths.nativeDir.absolutePath, Guest.BIONIC)
        bind(args, paths.prefixDir.absolutePath, Guest.DSH)
        bind(args, paths.dshHome.absolutePath, Guest.DSH_HOME)
        bind(args, paths.tmpDir.absolutePath, Guest.TMP)
        bind(args, paths.cacheDir.absolutePath, Guest.CACHE)
        // 宿主脚本与 pnpm 的家目录也要可见，否则容器后端下插件安装无处可写
        bind(args, paths.hostDir.absolutePath, Guest.HOST)
        // 本地大模型运行时也绑进去。**必须用 proot 跑它**：Android 10+ 禁止
        // execve 应用数据目录里的文件，而 proot 用 loader 映射绕过这一点 ——
        // 这正是它无法像 Node 那样塞进 jniLibs 的原因（下载的东西进不了只读的
        // nativeLibraryDir）。
        bind(args, File(paths.root, "llm").absolutePath, Guest.LLM)
        bind(args, paths.pnpmHome.absolutePath, "${Guest.CACHE}/pnpm")

        args += guestArgv
        return args
    }

    private fun bind(args: MutableList<String>, src: String, dst: String? = null) {
        args += "-b"
        args += if (dst == null) src else "$src:$dst"
    }

    /** rootfs 是否已经落盘可用。 */
    fun rootfsReady(): Boolean =
        java.io.File(paths.rootfsDir, "etc/os-release").exists() ||
            java.io.File(paths.rootfsDir, "bin/sh").exists()

    /** 在 rootfs 里跑一条命令（客人视角的路径）。 */
    fun shellCommand(script: String): List<String> = command(listOf("/bin/sh", "-lc", script))

    /** 不做伪 root 的版本：某些包会检测 uid==0 就拒绝安装，用它规避。 */
    fun shellCommandNoFakeRoot(script: String): List<String> {
        val cmd = command(listOf("/bin/sh", "-lc", script)).toMutableList()
        val idx = cmd.indexOf("-0")
        if (idx >= 0) cmd.removeAt(idx)
        return cmd
    }
}
