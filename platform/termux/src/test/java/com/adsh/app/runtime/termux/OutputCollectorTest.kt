package com.adsh.app.runtime.termux

import com.adsh.app.seam.exec.Spill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * 输出收集器的截断策略：**只留尾部 + 全文 spill 到文件**（按 dsh）。
 *
 * 只留尾部的前提是**另有 spill 文件兜住头部**：全文不落盘时，`apt install chromium` 开头那条
 * `E: Unable to correct problems…` 会被尾部 `Setting up …` 的噪音挤掉。
 * 出口是正文里那行 `[output truncated; full output: <路径>]`。
 */
class OutputCollectorTest {

    private fun collected(text: String, maxBytes: Int, spill: Spill? = null): Collected {
        val collector = OutputCollector(
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
            maxBytes,
            spill,
        )
        collector.join(5_000)
        return collector.finalize()
    }

    private fun spillDir(): File = Files.createTempDirectory("adsh-spill-test").toFile()

    @Test
    fun keepsTheTailWhenOutputExceedsTheCap() {
        val out = collected("L_1\nL_2\nL_3\nL_4\nL_50000\n", 16)
        assertTrue("超上限必须标记截断", out.truncated)
        assertTrue("结尾那条必须还在：" + out.text, out.text.endsWith("L_50000\n"))
        assertFalse("只留尾部：开头不该再出现在正文里", out.text.contains("L_1\n"))
    }

    @Test
    fun keepsEverythingWhenUnderTheCap() {
        val out = collected("hello\nworld\n", 1024)
        assertFalse(out.truncated)
        assertEquals("hello\nworld\n", out.text)
    }

    @Test
    fun theFullOutputLandsInTheSpillFile() {
        val payload = "HEAD-" + "a".repeat(5_000) + "-TAIL\n"
        val dir = spillDir()
        val out = collected(payload, 64, Spill(dir, "stdout"))
        assertTrue(out.truncated)
        assertEquals("正文是最后 64 字节", "-TAIL\n", out.text.takeLast(6))
        val path = out.spillPath
        assertTrue("丢过字节就必须给出 spill 路径", path != null)
        val file = File(path!!)
        assertTrue("spill 文件必须真的存在", file.isFile)
        assertEquals("全文（含开头那句）必须原样落盘", payload, file.readText())
    }

    @Test
    fun aStreamThatStaysUnderTheCapNeverOpensASpill() {
        val dir = spillDir()
        val out = collected("short\n", 64, Spill(dir, "stdout"))
        assertFalse(out.truncated)
        assertNull("没越过内存上限就不该落盘", out.spillPath)
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun aStreamOverTheSpillCapDropsTheSpillAndKeepsTheTail() {
        // spill 上限 32 字节：越过就丢弃文件（dsh 的 discardSpill），只剩内存尾部
        val dir = spillDir()
        val out = collected("y".repeat(1_000), 16, Spill(dir, "stdout", maxBytes = 32))
        assertTrue(out.truncated)
        assertNull("超过 spill 上限就不该再宣称路径", out.spillPath)
        assertEquals("丢弃时要把文件删掉，别留半个", 0, dir.listFiles()?.size ?: 0)
    }

    /** 切点落在多字节字符中间时不能解码出 U+FFFD */
    @Test
    fun doesNotSplitAMultiByteCharacterAtTheCut() {
        val out = collected("中文中文中文中文中文中文", 24)
        assertTrue(out.truncated)
        assertFalse("截断处不该出现替换字符：" + out.text, out.text.contains('\uFFFD'))
        assertTrue("结尾那几个字还在：" + out.text, out.text.endsWith("中文"))
    }

    @Test
    fun theTailIsExactlyTheCap() {
        val out = collected("A".repeat(50) + "B".repeat(50), 20)
        assertEquals("B".repeat(20), out.text)
    }

    /** 孙进程继承了写端时管道永远不 EOF：收尾必须还能安全读出内存尾部，且最终只收一次 */
    @Test
    fun `收尾不被永不关闭的管道卡住（读线程还阻塞着）`() {
        val writer = java.io.PipedOutputStream()
        val piped = java.io.PipedInputStream(writer)
        val collector = OutputCollector(piped, 8)
        writer.write("0123456789".toByteArray())
        writer.flush()
        // 给读线程一点时间吃完这 10 个字节（之后它会一直阻塞在 read 上）
        Thread.sleep(100)
        val out = collector.finalize()
        assertEquals("只留最后 8 字节", "23456789", out.text)
        assertTrue("10 字节 > 8 字节上限：确实丢过字节", out.truncated)
        writer.close()
    }

    @Test
    fun `totalBytes 记的是整条流而不是保留下来的尾巴`() {
        val collector = OutputCollector(ByteArrayInputStream("0123456789".toByteArray()), 4)
        collector.join(5_000)
        assertEquals(10L, collector.totalBytes)
        assertEquals("6789", collector.finalize().text)
    }

    @Test
    fun `finalize 可以重复调用`() {
        val collector = OutputCollector(ByteArrayInputStream("hello".toByteArray()), 1024)
        collector.join(5_000)
        assertEquals(collector.finalize(), collector.finalize())
    }
}
