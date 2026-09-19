package com.adsh.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dsh 的 parseSearchArgs 判定顺序：先判条数上限、再判每条非空、最后精确去重。
 *
 * 以前这里还有两条 schema 里根本不存在的旁路（query 单数、maxResults）——
 * 「文档没写的参数实测能生效」和「文档写了的参数实测不生效」一样糟。
 */
class WebSearchArgsTest {

    private fun accepted(raw: List<String>): List<String> {
        val parsed = parseSearchQueries(raw, 4)
        assertTrue(parsed.toString(), parsed is SearchArgs.Accepted)
        return (parsed as SearchArgs.Accepted).queries
    }

    private fun rejected(raw: List<String>): String {
        val parsed = parseSearchQueries(raw, 4)
        assertTrue(parsed.toString(), parsed is SearchArgs.Rejected)
        return (parsed as SearchArgs.Rejected).message
    }

    @Test
    fun emptyListIsRejected() {
        assertEquals("queries must contain at least one query", rejected(emptyList()))
    }

    @Test
    fun tooManyQueriesAreRejectedBeforeTheOthers() {
        assertEquals("queries must contain at most 4 queries", rejected(listOf("a", "b", "c", "d", "e")))
    }

    @Test
    fun blankQueryIsRejected() {
        assertEquals("each query must be a non-empty string", rejected(listOf("ok", "   ")))
    }

    @Test
    fun exactDuplicatesCollapseButWhitespaceIsKept() {
        // dsh 用的是 [...new Set(queries)]：只去重，不 trim（trim 只用于非空判定）
        assertEquals(listOf("a", " a "), accepted(listOf("a", " a ", "a")))
    }

    @Test
    fun theBoundIsCheckedOnTheRawCountNotTheDeduplicatedOne() {
        assertEquals("queries must contain at most 4 queries", rejected(listOf("a", "a", "a", "a", "a")))
    }
}
