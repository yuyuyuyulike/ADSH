package com.adsh.app.runtime.termux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * OutputCollector 的截断策略：**头 + 尾**（第 105 轮）。
 *
 * 两条历史：
 *  - 最早是「填满就不再往后写」（= 只留头部），与工具说明里的 "truncated to its tail" 相反：
 *    5 万行日志时最后那段异常被丢掉了；
 *  - 第 93 轮改成**只留尾部**（照 dsh 的 dsh-subprocess-local 抄），但 dsh 那条理由的前提是
 *    「另有 spill 文件兜住头部」，ADSH 没有落盘 —— 于是头部真的丢了。测试 agent 的实测代价：
 *    `apt install chromium` 的 `E: Unable to correct problems…` 在最前面、被丢掉，返回值尾部
 *    全是 `Setting up …` 的正常噪音，差点把「装失败」读成「装成功」。
 *
 * 现在两头都留，中间掐掉的那一段在正文里用一行标记说明（丢的是哪一段也不再靠猜）。
 */
class OutputCollectorTest {

    private fun collect(text: String, maxBytes: Int): Pair<String, Boolean> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val collector = OutputCollector(ByteArrayInputStream(bytes), maxBytes)
        collector.join(5_000)
        return collector.text() to collector.truncated
    }

    @Test
    fun keepsBothEndsWhenOutputExceedsTheCap() {
        // 24 字节，上限 16 ⇒ 头部 4 字节 + 尾部 12 字节，中间 8 字节被掐掉
        val (text, truncated) = collect("L_1\nL_2\nL_3\nL_4\nL_50000\n", 16)
        assertTrue("超上限必须标记截断", truncated)
        assertTrue("开头那份必须还在（apt / make 的错误都在最前面）：" + text, text.startsWith("L_1\n"))
        assertTrue("结尾那条必须还在：" + text, text.endsWith("L_50000\n"))
        assertTrue("中间掐掉的那一段要就地标出来：" + text, text.contains("bytes elided in the middle"))
        assertFalse("被掐掉的中间部分不该原样出现", text.contains("L_2\nL_3\nL_4"))
    }

    @Test
    fun keepsEverythingWhenUnderTheCap() {
        val (text, truncated) = collect("hello\nworld\n", 1024)
        assertFalse(truncated)
        assertTrue(text == "hello\nworld\n")
    }

    /** 两头被切时都不得落在多字节字符中间：不能解码出 U+FFFD */
    @Test
    fun doesNotEmitAReplacementCharacterAtEitherCut() {
        // 36 字节，上限 24 ⇒ 头 6 字节（正好两个字）、尾 18 字节，切点都落在字符边界之间的概率很低
        val (text, truncated) = collect("中文中文中文中文中文中文", 24)
        assertTrue(truncated)
        assertFalse("截断处不该出现替换字符：" + text, text.contains('\uFFFD'))
        assertTrue("开头那几个字还在：" + text, text.startsWith("中文"))
        assertTrue("结尾那几个字还在：" + text, text.endsWith("中文"))
    }

    @Test
    fun aSingleChunkLargerThanTheCapKeepsItsOwnHeadAndTail() {
        val big = "HEAD" + "a".repeat(100) + "TAIL"
        val (text, truncated) = collect(big, 16)
        assertTrue(truncated)
        assertTrue("头还在：" + text, text.startsWith("HEAD"))
        assertTrue("尾还在：" + text, text.endsWith("TAIL"))
        assertTrue(text.contains("bytes elided in the middle"))
    }

    /** 掐掉的字节数要如实报出来（不然「丢了多少」又要靠猜） */
    @Test
    fun reportsHowMuchWasElided() {
        val (text, _) = collect("a".repeat(100), 20)
        // 头 5 + 尾 15 = 20，丢 80
        assertTrue("要报出掐掉的字节数：" + text, text.contains("80 bytes elided"))
    }
}
