package io.dsh.fusion.engine.provision

import io.dsh.fusion.engine.FusionPaths
import io.dsh.fusion.engine.FusionSettings
import io.dsh.fusion.engine.LogBus
import java.io.File

/**
 * 完整 Ubuntu rootfs 的按需装配。
 *
 * 不随 APK 发货（一个发行版塞进安装包不现实），但**装机后的第一次使用是一键的**：
 * 多镜像下载 → sha256 校验 → 解包（含软链/硬链/可执行位）→ 写 DNS 与 apt 源。
 *
 * 为什么必须自己写 resolv.conf：Android 没有 /etc/resolv.conf，
 * rootfs 里的 apt / pip / git 都会因为解析不了域名而全灭。
 */
class UbuntuProvisioner(private val paths: FusionPaths, private val settings: FusionSettings) {

    data class Recipe(
        val fileName: String,
        val sha256: String?,
        val label: String,
    )

    fun isReady(): Boolean =
        File(paths.rootfsDir, "etc/os-release").exists() || File(paths.rootfsDir, "bin/sh").exists()

    fun provision(onStep: ((String) -> Unit)?, onProgress: ((HttpDownloader.Progress) -> Unit)?): Boolean {
        if (isReady()) {
            LogBus.i(TAG, "Ubuntu rootfs 已就绪：${paths.rootfsDir.absolutePath}")
            return true
        }
        val mirrors = settings.mirrors
        val downloader = HttpDownloader(mirrors)
        val recipe = Recipe(
            fileName = ROOTFS_FILE,
            // 三级兜底：单文件 .sha256 → SHA256SUMS → 内置已知值（实测校验过）
            sha256 = fetchSha256(mirrors, "$ROOTFS_FILE.sha256")
                ?: fetchFromSums(mirrors)
                ?: KNOWN_SHA256,
            label = "Ubuntu 22.04 base (arm64)",
        )

        onStep?.invoke("下载 Ubuntu rootfs（约 30 MB，可断点续传）…")
        val tarball = File(paths.tmpDir, recipe.fileName)
        try {
            downloader.to(tarball, recipe.fileName, recipe.sha256, recipe.label, onProgress)
        } catch (e: Exception) {
            LogBus.e(TAG, "rootfs 下载失败：${e.message}")
            return false
        }

        onStep?.invoke("解包 rootfs（约 300 MB 展开）…")
        if (paths.rootfsDir.exists()) paths.rootfsDir.deleteRecursively()
        paths.rootfsDir.mkdirs()
        try {
            TarExt.extract(tarball, paths.rootfsDir, 0) { info -> onStep?.invoke(info) }
        } catch (e: Exception) {
            LogBus.e(TAG, "rootfs 解包失败：${e.message}")
            return false
        }
        if (!tarball.delete()) LogBus.w(TAG, "rootfs 压缩包残留：${tarball.absolutePath}")

        onStep?.invoke("写入 DNS 与 apt 源…")
        bakeNetworkConfig()
        LogBus.i(TAG, "Ubuntu rootfs 装配完成")
        return true
    }

    /** rootfs 内的域名解析与软件源。 */
    private fun bakeNetworkConfig() {
        val resolv = File(paths.rootfsDir, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        resolv.writeText(
            """
            # DSH Fusion: Android 不提供 resolv.conf，这里固定可用的公共 DNS
            nameserver 223.5.5.5
            nameserver 119.29.29.29
            nameserver 8.8.8.8
            """.trimIndent() + "\n"
        )

        val sources = File(paths.rootfsDir, "etc/apt/sources.list")
        sources.parentFile?.mkdirs()
        sources.writeText(APT_SOURCES)

        // rootfs 默认的 root 密码为空，proot -0 下登录无碍，但给个哨兵免得某些工具卡住
        runCatching {
            val passwd = File(paths.rootfsDir, "etc/passwd")
            if (passwd.exists()) {
                val fixed = passwd.readLines().joinToString("\n") { line ->
                    if (line.startsWith("root:") && line.contains(":/root:")) {
                        line.replaceAfter("root:", "x:").let { l ->
                            if (l.endsWith(":")) "$l/bin/sh" else l
                        }
                    } else line
                }
                passwd.writeText(fixed + "\n")
            }
        }
        LogBus.i(TAG, "已写入 ${resolv.absolutePath} 与 ${sources.absolutePath}")
    }

    /** 支持 `<file>.sha256` 单文件形式。 */
    private fun fetchSha256(mirrors: List<String>, name: String): String? = runCatching {
        val text = readText(mirrors, name) ?: return null
        Regex("[0-9a-fA-F]{64}").find(text)?.value?.lowercase()
    }.getOrNull()

    /** 退回 SHA256SUMS 汇总文件。 */
    private fun fetchFromSums(mirrors: List<String>): String? = runCatching {
        val text = readText(mirrors, "SHA256SUMS") ?: return null
        text.lineSequence()
            .firstOrNull { it.contains(ROOTFS_FILE) }
            ?.let { Regex("[0-9a-fA-F]{64}").find(it)?.value?.lowercase() }
    }.getOrNull()

    private fun readText(mirrors: List<String>, fileName: String): String? {
        for (base in mirrors) {
            runCatching {
                val url = HttpDownloader.joinUrl(base, fileName)
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 15_000
                }
                if (conn.responseCode in 200..299) return conn.inputStream.bufferedReader().readText()
            }.onFailure { LogBus.t(TAG, "读取 $fileName 失败（$base）：${it.message}") }
        }
        return null
    }

    companion object {
        private const val TAG = "Ubuntu"
        const val ROOTFS_FILE = "ubuntu-base-22.04.5-base-arm64.tar.gz"

        /** 实测自 cdimage.ubuntu.com/SHA256SUMS 的官方校验值，网络拿不到时用它兜底。 */
        const val KNOWN_SHA256 = "075d4abd2817a5023ab0a82f5cb314c5ec0aa64a9c0b40fd3154ca3bfdae979f"

        const val APT_SOURCES = """# DSH Fusion: 默认指向国内镜像，可在应用内一键切换
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ jammy main restricted universe multiverse
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ jammy-updates main restricted universe multiverse
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ jammy-backports main restricted universe multiverse
deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ jammy-security main restricted universe multiverse
"""
    }
}
