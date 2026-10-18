package com.adsh.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * grep 的 include 校验（dsh grep.ts 的 validateInclude）：必须是**一个**正向 glob，
 * 逗号分隔的列表要在 {} 里写择一。文案逐字用 dsh 原文，程序可见。
 */
class GrepIncludeTest {

    @Test
    fun onePositiveGlobIsAccepted() {
        assertNull(includeProblem("*.kt"))
        assertNull(includeProblem("src/**/*.kt"))
        assertNull(includeProblem("*.{ts,tsx}"))
        assertNull(includeProblem("*.{a,{b,c}}"))
        // 多余的右括号只是把深度夹到 0（dsh 的 Math.max(0, …)），本身不报错
        assertNull(includeProblem("a}b"))
    }

    @Test
    fun topLevelCommasAreRejectedWithTheDshWording() {
        val expected = "include must be one glob, not a comma-separated list (use {a,b} alternation instead)"
        assertEquals(expected, includeProblem("*.ts,*.js"))
        assertEquals(expected, includeProblem("a,b{c,d}"))
        assertEquals(expected, includeProblem("{a,b},c"))
        assertEquals(expected, includeProblem("a,{b,c}"))
    }

    @Test
    fun blankAndNegatedKeepTheirOwnMessages() {
        assertEquals("include must be a non-empty glob when given", includeProblem("  "))
        assertEquals(
            "include must be a positive glob filter; negated patterns (\"!…\") are not supported",
            includeProblem("!*.kt"),
        )
    }
}
