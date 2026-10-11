package io.dsh.fusion.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 唤醒词的容错匹配。
 *
 * 中文小型模型对叠词识别很不稳：「小深小深」经常变成「小深深」「小婶小婶」。
 * 匹配太严 → 喊破喉咙没反应；太松 → 说句话就被唤醒。
 * 边界就在编辑距离 1 附近，值得钉住。
 */
class WakeWordMatchTest {

    @Test
    fun `编辑距离基础用例`() {
        assertEquals(0, VoskWakeWordEngine.levenshtein("小深", "小深"))
        assertEquals(1, VoskWakeWordEngine.levenshtein("小深", "小生"))
        assertEquals(2, VoskWakeWordEngine.levenshtein("小深小深", "小生小生"))
        assertEquals(0, VoskWakeWordEngine.levenshtein("", ""))
    }

    @Test
    fun `空串与非空串的距离等于长度`() {
        assertEquals(2, VoskWakeWordEngine.levenshtein("", "小深"))
        assertEquals(2, VoskWakeWordEngine.levenshtein("小深", ""))
    }

    @Test
    fun `编辑距离对称`() {
        val pairs = listOf("小深小深" to "小深深", "abcd" to "abce", "唤醒" to "换醒")
        pairs.forEach { (a, b) ->
            assertEquals(
                "距离应当对称：$a / $b",
                VoskWakeWordEngine.levenshtein(a, b),
                VoskWakeWordEngine.levenshtein(b, a),
            )
        }
    }

    @Test
    fun `近似唤醒词的距离在容错半径内`() {
        // 引擎的容错半径是 1，这里验证几种真实会出现误识别的形态
        assertTrue(VoskWakeWordEngine.levenshtein("小深小深", "小生小生") <= 2)
        assertTrue(VoskWakeWordEngine.levenshtein("小深小深", "小深深") <= 2)
    }

    @Test
    fun `完全无关的词距离显著更大`() {
        val unrelated = VoskWakeWordEngine.levenshtein("今天天气不错", "小深小深")
        assertTrue("无关短句不该被误唤醒（距离 $unrelated）", unrelated >= 3)
    }
}
