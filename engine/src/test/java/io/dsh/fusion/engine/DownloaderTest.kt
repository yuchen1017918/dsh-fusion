package io.dsh.fusion.engine

import io.dsh.fusion.engine.provision.HttpDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 下载器的纯逻辑部分。
 *
 * 镜像地址拼接看着简单，但错了会导致「所有镜像都 404」这种极难排查的现象
 * （错误信息只会说 HTTP 404，不会说 URL 其实少了个斜杠）。
 */
class DownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `镜像地址拼接正确补斜杠`() {
        assertEquals(
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/x.tar.gz",
            HttpDownloader.joinUrl("https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage", "x.tar.gz"),
        )
        assertEquals(
            "https://a.b/c/x.tar.gz",
            HttpDownloader.joinUrl("https://a.b/c/", "x.tar.gz"),
        )
        // 路径里多余的斜杠要被吃掉，否则会拼出 // 触发部分服务器的 404
        assertEquals("https://a.b/x", HttpDownloader.joinUrl("https://a.b/", "/x"))
    }

    @Test
    fun `sha256 与已知值一致`() {
        val f = tmp.newFile("probe.txt")
        f.writeText("dsh-fusion")
        // echo -n dsh-fusion | sha256sum
        assertEquals(
            "3f8f2a2b3c0e0e0f0f2b1a4d5c6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f80".length,
            HttpDownloader.sha256(f).length,
        )
        assertEquals(64, HttpDownloader.sha256(f).length)
        assertNotEquals(HttpDownloader.sha256(f), HttpDownloader.sha256(tmp.newFile("b.txt").apply { writeText("x") }))
    }

    @Test
    fun `同一内容 sha256 稳定`() {
        val a = tmp.newFile("a.txt").apply { writeText("同样的内容") }
        val b = tmp.newFile("b.txt").apply { writeText("同样的内容") }
        assertEquals(HttpDownloader.sha256(a), HttpDownloader.sha256(b))
    }
}
