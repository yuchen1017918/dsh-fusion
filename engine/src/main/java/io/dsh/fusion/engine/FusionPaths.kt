package io.dsh.fusion.engine

import android.content.Context
import java.io.File

/**
 * 融合体在设备上的全部落盘位置。
 *
 * 设计约束（决定了目录长什么样）：
 *  - Android 10+ 禁止 exec 应用私有目录里的文件，所以真正被执行的
 *    node / proot 只能待在 `nativeLibraryDir`（安装时由包管理器解压，带 x 位）。
 *    其余一切数据都在 filesDir 下，卸载即清空。
 *  - `$DSH_HOME` 必须可写且唯一，否则 dsh 的 profile 会互相踩。
 */
class FusionPaths(context: Context) {

    private val app: Context = context.applicationContext

    /** 数据根：filesDir/fusion */
    val root = File(app.filesDir, "fusion")

    /** $DSH_HOME —— dsh 的会话、profile、插件全在这里 */
    val dshHome = File(root, "home")

    /** dsh profile 根目录（web profile 即 $DSH_HOME/profiles/web） */
    val profilesDir = File(dshHome, "profiles")

    /** npm 的解包位置（内置 npm 10.x，不依赖设备上有没有 npm） */
    val runtimeDir = File(root, "runtime")
    val npmDir = File(runtimeDir, "npm")

    /** npm --prefix：全局安装的 @deepseek-ai/dsh 启动器 */
    val prefixDir = File(root, "prefix")

    /** Ubuntu rootfs（完整发行版，apt / gcc / python 都在里面） */
    val rootfsDir = File(root, "rootfs")

    val logsDir = File(root, "logs")
    val tmpDir = File(root, "tmp")
    val cacheDir = File(app.cacheDir, "fusion")

    /** 运行时可执行文件所在目录 */
    val nativeDir = File(app.applicationInfo.nativeLibraryDir)

    /** bionic Node.js 24（Termux 编译，伪装成 lib*.so 以换取可执行权限） */
    val nodeBin: File get() = File(nativeDir, "libnode.so")

    /** PRoot 5.1（同样是伪装成 .so 的可执行文件） */
    val prootBin: File get() = File(nativeDir, "libproot.so")

    /** dsh 启动器入口 */
    val dshEntry: File get() = File(prefixDir, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")

    /** npm CLI 入口 */
    val npmCli: File get() = File(npmDir, "bin/npm-cli.js")

    /** CA 证书包（内置 121 个根证书，https 才能用） */
    val certFile: File get() = File(runtimeDir, "cert.pem")

    /** 引擎日志文件 */
    val engineLog: File get() = File(logsDir, "engine.log")

    /** 环境快照文件，方便用户把日志整包导出 */
    val envReport: File get() = File(logsDir, "env.txt")

    /** 幂等建目录 */
    fun ensure() {
        listOf(root, dshHome, profilesDir, runtimeDir, prefixDir, rootfsDir, logsDir, tmpDir, cacheDir)
            .forEach { if (!it.exists()) it.mkdirs() }
    }

    /** 运行时是否已就位（内置资源，装机即有） */
    fun runtimeSeeded(): Boolean = nodeBin.exists() && prootBin.exists()

    /** dsh 启动器是否已安装 */
    fun dshInstalled(): Boolean = dshEntry.exists()

    fun profileDir(name: String): File = File(profilesDir, name)

    fun describe(): Map<String, String> = linkedMapOf(
        "root" to root.absolutePath,
        "DSH_HOME" to dshHome.absolutePath,
        "nativeDir" to nativeDir.absolutePath,
        "node" to "${nodeBin.absolutePath} (exists=${nodeBin.exists()}, size=${if (nodeBin.exists()) nodeBin.length() else 0})",
        "proot" to "${prootBin.absolutePath} (exists=${prootBin.exists()})",
        "dshEntry" to "${dshEntry.absolutePath} (exists=${dshEntry.exists()})",
        "rootfs" to "${rootfsDir.absolutePath} (ready=${File(rootfsDir, "etc/os-release").exists()})",
    )
}
