package com.adsh.app.platform.ptc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PTC 日志的行数上限（[QuickJsRuntime.MAX_LOG_LINES]）：500 行是**平台内存约束**
 * （dsh 的 ptc-runtime-node 只按 64 MiB 字节预算，没有行上限），所以被丢弃的行不允许静默
 * —— 结果末尾必须写清楚省略了多少行。
 */
class LogTruncationTest {

    @Test
    fun logsWithinTheCapPassThroughUntouched() {
        assertEquals(listOf("a", "b"), logLinesForResult(listOf("a", "b"), 0))
    }

    @Test
    fun omittedLinesAreCountedInAnExplicitFooter() {
        val shown = (1..QuickJsRuntime.MAX_LOG_LINES).map { "line-" + it }
        val lines = logLinesForResult(shown, 137)
        assertEquals(QuickJsRuntime.MAX_LOG_LINES + 1, lines.size)
        assertEquals("line-" + QuickJsRuntime.MAX_LOG_LINES, lines[QuickJsRuntime.MAX_LOG_LINES - 1])
        assertEquals("(137 log lines omitted: showing the first 500)", lines.last())
    }

    @Test
    fun anEmptyLogGetsOnlyTheFooter() {
        assertEquals(listOf("(3 log lines omitted: showing the first 0)"), logLinesForResult(emptyList(), 3))
    }
}
