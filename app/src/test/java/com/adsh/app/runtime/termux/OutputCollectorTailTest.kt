package com.adsh.app.runtime.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * OutputCollector 的保留方向：**尾部**。
 *
 * 这一条以前是反的（填满就不再往后写 = 保留头部），与工具说明里写的
 * "Long output is truncated to its tail" 正好相反：5 万行日志时最后那段异常被丢掉了，
 * 而报错与最终结果恰恰都聚在末尾（dsh-subprocess-local 的原话：
 * "errors and final results cluster at the end of command output"）。
 */
class OutputCollectorTailTest {

    private fun collect(text: String, maxBytes: Int): Pair<String, Boolean> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val collector = OutputCollector(ByteArrayInputStream(bytes), maxBytes)
        collector.join(5_000)
        return collector.text() to collector.truncated
    }

    @Test
    fun keepsTheTailWhenOutputExceedsTheCap() {
        // 24 字节，上限 16 ⇒ 丢掉的必须是开头那 8 字节
        val (text, truncated) = collect("L_1\nL_2\nL_3\nL_4\nL_50000\n", 16)
        assertTrue("超上限必须标记截断", truncated)
        assertEquals("L_3\nL_4\nL_50000\n", text)
        assertFalse("被丢掉的是头部：" + text, text.contains("L_1"))
        assertTrue("结尾那条必须还在：" + text, text.endsWith("L_50000\n"))
    }

    @Test
    fun keepsEverythingWhenUnderTheCap() {
        val (text, truncated) = collect("hello\nworld\n", 1024)
        assertFalse(truncated)
        assertEquals("hello\nworld\n", text)
    }

    /** 头部被切掉时第一个字节可能落在多字节字符中间：不能解码出 U+FFFD */
    @Test
    fun doesNotEmitAReplacementCharacterAtTheCut() {
        val (text, truncated) = collect("中文中文中文中文", 13)
        assertTrue(truncated)
        assertFalse("截断处不该出现替换字符：" + text, text.contains('\uFFFD'))
        assertTrue("留下的应该是结尾那几个字：" + text, "中文".endsWith(text) || text.endsWith("中文"))
    }

    @Test
    fun singleChunkLargerThanTheCapKeepsItsOwnTail() {
        val big = "a".repeat(100) + "TAIL"
        val (text, truncated) = collect(big, 4)
        assertTrue(truncated)
        assertEquals("TAIL", text)
    }
}
