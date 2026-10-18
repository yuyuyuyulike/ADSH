package com.adsh.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * glob / grep 的失败文案形状（dsh 的 search-core classifyRunFailure）。
 *
 * 三条契约：pattern/glob 被 ripgrep 拒绝与其它失败分开报；非零退出带退出码与 stderr 摘要；
 * **工具名必须对得上**（glob 的失败不能写成 grep 的错）。正文里不出现中英混排。
 */
class SearchErrorTextTest {

    @Test
    fun patternRejectionNamesTheToolAndKeepsTheRipgrepStderr() {
        assertEquals(
            "grep pattern rejected by ripgrep: regex parse error: unclosed group",
            ripgrepFailure("grep", 2, "regex parse error: unclosed group\n"),
        )
        assertEquals(
            "glob pattern rejected by ripgrep: error parsing glob 'x[': unclosed character class",
            ripgrepFailure("glob", 2, "error parsing glob 'x[': unclosed character class"),
        )
    }

    @Test
    fun otherNonZeroExitsReportTheCodeAndTheStderrSummary() {
        assertEquals("glob search failed (exit 2)", ripgrepFailure("glob", 2, "  \n"))
        assertEquals(
            "grep search failed (exit 2): Permission denied (os error 13)",
            ripgrepFailure("grep", 2, "Permission denied (os error 13)\n"),
        )
    }

    @Test
    fun launchFailureIsItsOwnShapeWithTheToolName() {
        assertEquals(
            "glob could not start its search command (ripgrep launch failed)",
            searchLaunchFailed("glob"),
        )
    }
}
