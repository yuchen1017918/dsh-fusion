package io.dsh.fusion.engine.llm

import android.content.Context
import io.dsh.fusion.engine.FusionPaths
import io.dsh.fusion.engine.LogBus
import io.dsh.fusion.engine.provision.HttpDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 本地大模型运行时的按需安装。
 *
 * ── 它装什么 ────────────────────────────────────────────────────
 * llama.cpp 的 `llama-server`（提供 **OpenAI 兼容** 的 `/v1/chat/completions`）
 * 及它的整条 `.so` 依赖闭包。包清单在 `assets/llm/runtime-manifest.json`，
 * 由 `tools/build-llm-manifest.py` 从真实的 DT_NEEDED 算出来 —— 不含任何二进制。
 *
 * **不进 APK**：清单只有 3.8 KB，二进制（约 11 MB 下载 / 36 MB 安装后）在
 * 设备上按需下载。这是刻意的 —— 不用本地模型的人不该为此多背 11 MB。
 *
 * ── 为什么不用 patchelf ─────────────────────────────────────────
 * Node 运行时必须做 SONAME 归一化（Android 只解压 `lib*.so`），因为那些库要放进
 * jniLibs 伪装成 so 才能执行。**但 LLM 运行时是用 proot 跑的** ——
 * proot 下文件可以叫任何名字，动态链接器照原样找得到。
 * 所以这里保留 `libcrypto.so.3` 这种真名，构建期一把 patchelf 都不需要。
 */
class LlmRuntimeInstaller(
    private val context: Context,
    private val paths: FusionPaths,
) {

    data class Progress(val phase: String, val done: Long, val total: Long, val ratio: Float)

    sealed class Outcome {
        data class Ready(val serverBin: File, val libDir: File) : Outcome()
        data class Failed(val message: String, val cause: Throwable? = null) : Outcome()
        data object NotEnoughSpace : Outcome()
    }

    /** 运行时根目录：`<root>/llm`。 */
    val root: File get() = File(paths.root, "llm")
    val usrDir: File get() = File(root, "usr")
    val serverBin: File get() = File(usrDir, "bin/llama-server")
    val libDir: File get() = File(usrDir, "lib")
    private val manifestCache: File get() = File(root, "manifest.json")

    fun isInstalled(): Boolean = serverBin.exists() && libDir.isDirectory

    fun installedBytes(): Long {
        if (!usrDir.exists()) return 0
        var total = 0L
        usrDir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    /** 读随包的清单（只有 URL 与校验值）。 */
    fun manifest(): JSONObject? = runCatching {
        context.assets.open("llm/runtime-manifest.json").use { JSONObject(it.readBytes().decodeToString()) }
    }.getOrElse {
        LogBus.e(TAG, "读不到 assets/llm/runtime-manifest.json：${it.message}")
        null
    }

    fun downloadBytes(): Long = manifest()?.optLong("downloadBytes", 0) ?: 0L
    fun installedKb(): Long = manifest()?.optLong("installedKB", 0) ?: 0L

    /**
     * 下载并安装。
     *
     * 顺序刻意是「先全部下载校验，再逐个解包」：解包到一半发现某个包下载失败，
     * 会留下一个装了一半、看起来却像装好了的运行时 —— 而启动失败时报的错
     * 只会是「找不到某个 .so」，很难定位到是下载环节出的问题。
     */
    suspend fun install(onProgress: ((Progress) -> Unit)? = null): Outcome = withContext(Dispatchers.IO) {
        val manifest = manifest() ?: return@withContext Outcome.Failed("缺少 assets/llm/runtime-manifest.json")
        val packages = manifest.optJSONArray("packages") ?: return@withContext Outcome.Failed("清单里没有 packages")

        val needBytes = (manifest.optLong("installedKB", 0) + 65536) * 1024
        val free = runCatching { android.os.StatFs(paths.root.absolutePath).availableBytes }.getOrDefault(-1L)
        if (free in 0 until needBytes) {
            LogBus.e(TAG, "空间不足：需要约 ${needBytes / 1048576} MB，可用 ${free / 1048576} MB")
            return@withContext Outcome.NotEnoughSpace
        }

        root.mkdirs()
        val debs = File(root, "debs").apply { mkdirs() }
        val totalBytes = manifest.optLong("downloadBytes", 0)
        var doneBytes = 0L

        // 主镜像 + 备用镜像。设备在国内，主源用清华（实测比官方源快两个数量级），
        // 但它是第三方基础设施 —— 挂了的时候用户不该连模型都装不上。
        val mirrors = buildList {
            manifest.optString("mirror").takeIf { it.isNotBlank() }?.let { add(it) }
            manifest.optJSONArray("fallbackMirrors")?.let { arr ->
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
        }
        if (mirrors.isEmpty()) return@withContext Outcome.Failed("清单里没有可用的镜像地址")
        val downloader = HttpDownloader(mirrors)

        // ── 阶段一：下载 + 校验 ──
        val staged = mutableListOf<Pair<String, File>>()
        for (i in 0 until packages.length()) {
            val p = packages.getJSONObject(i)
            val name = p.getString("name")
            val relativePath = p.getString("path")
            val url = p.optString("url")
            val sha = p.optString("sha256").ifBlank { null }
            val expect = p.optLong("bytes", 0)
            val dest = File(debs, relativePath.substringAfterLast('/'))

            if (dest.exists() && expect > 0 && dest.length() == expect) {
                doneBytes += expect
                staged += name to dest
                onProgress?.invoke(Progress("已缓存 $name", doneBytes, totalBytes, ratio(doneBytes, totalBytes)))
                continue
            }

            onProgress?.invoke(Progress("下载 $name", doneBytes, totalBytes, ratio(doneBytes, totalBytes)))
            try {
                downloader.to(dest, relativePath, sha, name) { prog ->
                    val base = doneBytes
                    onProgress?.invoke(
                        Progress("下载 $name", base + prog.received, totalBytes, ratio(base + prog.received, totalBytes)),
                    )
                }
            } catch (e: Exception) {
                LogBus.e(TAG, "下载 $name 失败：${e.message}")
                return@withContext Outcome.Failed("下载 $name 失败：${e.message}（$url）", e)
            }
            doneBytes += expect.coerceAtLeast(dest.length())
            staged += name to dest
        }

        // ── 阶段二：解包 ──
        val staging = File(root, "staging")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        staged.forEachIndexed { index, (name, deb) ->
            onProgress?.invoke(
                Progress("解包 $name", index.toLong(), staged.size.toLong(), index.toFloat() / staged.size.coerceAtLeast(1)),
            )
            runCatching {
                DebExtractor.extractData(deb, staging, DebExtractor.TERMUX_USR_PREFIX)
            }.onFailure {
                LogBus.e(TAG, "解包 $name 失败：${it.message}")
                staging.deleteRecursively()
                return@withContext Outcome.Failed("解包 $name 失败：${it.message}", it)
            }
        }

        // ── 阶段三：原子就位 ──
        if (!File(staging, "bin/llama-server").exists()) {
            staging.deleteRecursively()
            return@withContext Outcome.Failed("解包结果里没有 bin/llama-server")
        }
        val backup = File(root, "previous")
        if (backup.exists()) backup.deleteRecursively()
        if (usrDir.exists() && !usrDir.renameTo(backup)) {
            staging.deleteRecursively()
            return@withContext Outcome.Failed("无法移走旧的运行时")
        }
        if (!staging.renameTo(usrDir)) {
            if (backup.exists()) backup.renameTo(usrDir)
            staging.deleteRecursively()
            return@withContext Outcome.Failed("运行时就位失败（rename 跨文件系统？）")
        }
        if (backup.exists()) backup.deleteRecursively()

        // 清单留一份副本，便于以后诊断「装的是哪一版」
        runCatching { manifestCache.writeText(manifest.toString(2)) }

        runCatching { serverBin.setExecutable(true, false) }
        LogBus.i(TAG, "LLM 运行时已就位：${serverBin.absolutePath}（${installedBytes() / 1048576} MB）")
        onProgress?.invoke(Progress("完成", 1, 1, 1f))
        Outcome.Ready(serverBin, libDir)
    }

    fun remove(): Boolean = runCatching {
        root.deleteRecursively()
    }.getOrDefault(false)

    private fun ratio(done: Long, total: Long): Float =
        if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    private companion object {
        const val TAG = "本地模型"
    }
}
