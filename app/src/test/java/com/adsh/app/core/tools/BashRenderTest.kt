package com.adsh.app.core.tools

import com.adsh.app.runtime.termux.ExecResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bash 结果正文的渲染（[bashStatusMarkers] / [renderBash]）与它的另一端 [bashStatusMarker]。
 *
 * 这是模型真正读到的正文；而三种状态标记的互斥关系原先只有一行注释保证、还有一个读者
 * （[RunCodeTool] 的摘要）从正文里把它们读回去 —— 写读两端必须对得上。
 */
class BashRenderTest {

    private fun result(exitCode: Int, timedOut: Boolean = false, timeoutMs: Long = 0) = ExecResult(
        exitCode = exitCode,
        output = "",
        timedOut = timedOut,
        truncated = false,
        durationMs = 1,
        timeoutMs = timeoutMs,
    )

    // ------------------------------------------------------------------ 标记

    @Test
    fun `正常退出：一个标记都不写`() {
        assertEquals(emptyList<String>(), bashStatusMarkers(false, 120_000, null, 0))
    }

    @Test
    fun `非零退出：写退出码`() {
        assertEquals(listOf("[exit code: 7]"), bashStatusMarkers(false, 120_000, null, 7))
    }

    @Test
    fun `超时：只写超时那行，不补退出码`() {
        assertEquals(listOf("[timed out after 3000ms]"), bashStatusMarkers(true, 3000, null, 143))
    }

    @Test
    fun `被人停掉：只写停止那行，不补退出码`() {
        assertEquals(listOf("[stopped: user pressed stop]"), bashStatusMarkers(false, 120_000, "user pressed stop", 143))
    }

    /** 超时转后台之后人又 kill 了它：两条都在，超时在前；仍然**没有**退出码 */
    @Test
    fun `超时 + 被停止：两条都有，仍然不补退出码`() {
        assertEquals(
            listOf("[timed out after 120000ms]", "[stopped: killed by user]"),
            bashStatusMarkers(true, 120_000, "killed by user", 137),
        )
    }

    @Test
    fun `超时且退出码是 0：也只写超时`() {
        assertEquals(listOf("[timed out after 5ms]"), bashStatusMarkers(true, 5, null, 0))
    }

    // ------------------------------------------------------------------ 正文

    @Test
    fun `只有 stdout、没有标记：正文原样`() {
        assertEquals("hello\n", renderBash(result(0), "hello\n", ""))
        assertEquals("hello", renderBash(result(0), "hello", ""))
    }

    @Test
    fun `stderr 段：stdout 没以换行结尾时先补一个`() {
        assertEquals("out\n[stderr]\nbad", renderBash(result(0), "out", "bad"))
        assertEquals("out\n[stderr]\nbad", renderBash(result(0), "out\n", "bad"))
    }

    @Test
    fun `只有 stderr：正文直接从 stderr 段开始，没有前导空行`() {
        assertEquals("[stderr]\nbad", renderBash(result(0), "", "bad"))
    }

    @Test
    fun `什么都没有：no output`() {
        assertEquals("(no output)", renderBash(result(0), "", ""))
    }

    @Test
    fun `标记自成一行：正文末尾有换行就不重复补`() {
        assertEquals("out\n[exit code: 1]", renderBash(result(1), "out\n", ""))
        assertEquals("out\n[exit code: 1]", renderBash(result(1), "out", ""))
    }

    @Test
    fun `空正文 + 标记：no output 之后换行再接标记`() {
        assertEquals("(no output)\n[exit code: 1]", renderBash(result(1), "", ""))
    }

    @Test
    fun `多条标记各占一行`() {
        assertEquals(
            "out\n[timed out after 10ms]\n[stopped: x]",
            renderBash(result(143, timedOut = true, timeoutMs = 10), "out", "", "x"),
        )
    }

    @Test
    fun `renderBash：把结果与停止原因拼起来`() {
        assertEquals("out\n[exit code: 2]", renderBash(result(2), "out", ""))
        assertEquals("out\n[stopped: user]", renderBash(result(0), "out", "", "user"))
        assertEquals("out\n[timed out after 3000ms]", renderBash(result(0, timedOut = true, timeoutMs = 3000), "out", ""))
    }

    // ------------------------------------------------------------------ 读回

    @Test
    fun `读回：拿最后一条匹配的行`() {
        assertEquals("[exit code: 3]", bashStatusMarker("a\n[exit code: 1]\nb\n[exit code: 3]"))
    }

    @Test
    fun `读回：行首有空白也认`() {
        assertEquals("[timed out after 10ms]", bashStatusMarker("  [timed out after 10ms]  "))
    }

    /** 停止是**用户动作**，不是命令自己的退出状态：摘要里报它会让模型以为命令自己停了 */
    @Test
    fun `读回：不认 stopped`() {
        assertNull(bashStatusMarker("out\n[stopped: user]"))
    }

    @Test
    fun `读回：认不出就 null`() {
        assertNull(bashStatusMarker(""))
        assertNull(bashStatusMarker("out\nexit code: 1"))
        assertNull(bashStatusMarker("(no output)"))
    }

    /** 两端一致性：渲染出来的正文，读回函数必须认得（这是这个文件存在的理由） */
    @Test
    fun `写读两端一致`() {
        val body = renderBash(result(9), "out", "err")
        assertEquals("[exit code: 9]", bashStatusMarker(body))
        val timed = renderBash(result(0, timedOut = true, timeoutMs = 42), "out", "")
        assertEquals("[timed out after 42ms]", bashStatusMarker(timed))
        assertTrue(bashStatusMarker(timed)!!.startsWith(TIMED_OUT_PREFIX))
    }
}
