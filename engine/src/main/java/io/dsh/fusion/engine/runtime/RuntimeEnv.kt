package io.dsh.fusion.engine.runtime

import io.dsh.fusion.engine.FusionPaths
import java.io.File

/**
 * 子进程环境变量。
 *
 * 这里每一行都对应一个真实踩过的坑：
 *  - LD_LIBRARY_PATH 必须指向 nativeLibraryDir，我们那 13 个 lib*.so 才互相找得到；
 *  - NODE_EXTRA_CA_CERTS 指向内置 cert.pem，否则 Android 没有系统 CA 目录，https 全挂；
 *  - PROOT_NO_SECCOMP=1 是 Android 上跑 PRoot 的前提（seccomp 会拦掉 ptrace 路径）；
 *  - HOME / DSH_HOME 全部落在 filesDir，卸载即净，不碰用户存储。
 */
object RuntimeEnv {

    fun base(
        paths: FusionPaths,
        registry: String,
        heapMb: Int = 512,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val env = HashMap<String, String>()
        val native = paths.nativeDir.absolutePath

        env["HOME"] = paths.dshHome.absolutePath
        env["DSH_HOME"] = paths.dshHome.absolutePath
        env["TMPDIR"] = paths.tmpDir.absolutePath
        env["TMP"] = paths.tmpDir.absolutePath
        env["TEMP"] = paths.tmpDir.absolutePath

        env["PATH"] = listOf(native, "/system/bin", "/system/xbin", paths.prefixDir.absolutePath + "/bin")
            .joinToString(":")
        env["LD_LIBRARY_PATH"] = native
        // 手机 RAM 比桌面小一个量级；堆开太大 = 被系统 OOM 直接干掉
        env["NODE_OPTIONS"] = "--max-old-space-size=$heapMb"
        env["NO_COLOR"] = "1"
        env["FORCE_COLOR"] = "0"
        env["CI"] = "1"
        env["LANG"] = "C.UTF-8"

        env["npm_config_registry"] = registry
        env["NPM_CONFIG_REGISTRY"] = registry
        env["npm_config_cache"] = File(paths.cacheDir, "npm").absolutePath
        env["npm_config_prefix"] = paths.prefixDir.absolutePath
        env["npm_config_fund"] = "false"
        env["npm_config_audit"] = "false"
        env["npm_config_progress"] = "false"
        env["npm_config_update_notifier"] = "false"
        env["npm_config_loglevel"] = "info"

        env["PROOT_TMP_DIR"] = paths.tmpDir.absolutePath
        env["PROOT_NO_SECCOMP"] = "1"
        // PRoot 的 ELF 加载器。**少了它 proot 基本是残废的**：
        // Android 禁止 execve 应用数据目录里的文件，加载器让 proot 能把客人程序
        // 直接映射起来跑，绕开那次注定失败的 execve。
        val loader = File(paths.nativeDir, "libproot-loader.so")
        if (loader.exists()) {
            env["PROOT_LOADER"] = loader.absolutePath
            env["PROOT_LOADER_32"] = File(paths.nativeDir, "libproot-loader32.so").absolutePath
        }

        /**
         * `DEEPSEEK_BASE_URL` 由 EngineSupervisor 注入（见那边的注释）。
         * 它之所以能生效，是因为官方把这类「决定进程怎么上网」的变量限定为
         * **只能由启动环境提供** —— 而这里构造的正是子进程的继承环境。
         */

        // pnpm 必须被关在应用私有目录里写东西，否则它去碰 $HOME/.local/share 会失败
        env["PNPM_HOME"] = paths.pnpmHome.absolutePath
        env["XDG_CACHE_HOME"] = paths.xdgCache.absolutePath
        env["XDG_DATA_HOME"] = paths.xdgData.absolutePath
        env["XDG_CONFIG_HOME"] = paths.xdgConfig.absolutePath

        if (paths.certFile.exists()) {
            env["NODE_EXTRA_CA_CERTS"] = paths.certFile.absolutePath
        }
        env.putAll(extra)
        return env
    }

    /** Ubuntu 后端专用的额外环境（路径都是 rootfs 内的客人视角）。 */
    fun guest(paths: FusionPaths): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to "/opt/bionic",
        "PATH" to "/opt/bionic:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "HOME" to "/root",
        "DSH_HOME" to "/root/.dsh",
        "TMPDIR" to "/tmp",
        "PROOT_TMP_DIR" to paths.tmpDir.absolutePath,
        "PROOT_NO_SECCOMP" to "1",
        "LANG" to "C.UTF-8",
    )
}
