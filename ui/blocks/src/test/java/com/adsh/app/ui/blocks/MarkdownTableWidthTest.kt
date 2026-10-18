package com.adsh.app.ui.blocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窄表列宽（dsh 的 `.tableFill table { width: 100% }`）：整表铺满可用宽度，每列不低于
 * 100px 的最小宽度；连最小宽度都放不下时返回 null（调用方退回横向滚动）。
 */
class MarkdownTableWidthTest {

    @Test
    fun widthsSumToTheAvailableWidth() {
        val widths = fillTableWidthsPx(natural = listOf(60, 320), availablePx = 1000, minCellPx = 100)
        assertEquals(2, widths!!.size)
        assertEquals(1000, widths.sum())
        assertTrue("每列都不低于最小宽度", widths.all { it >= 100 })
    }

    @Test
    fun widerNaturalColumnsGetMoreOfTheLeftover() {
        val widths = fillTableWidthsPx(natural = listOf(100, 300), availablePx = 800, minCellPx = 100)!!
        // 最小宽度之后剩 600，按 100:300 分：第二列明显更宽
        assertTrue(widths[1] > widths[0])
        assertEquals(800, widths.sum())
    }

    @Test
    fun proportionalDistributionCoversEveryColumn() {
        val widths = fillTableWidthsPx(natural = listOf(120, 120, 120), availablePx = 900, minCellPx = 100)!!
        assertEquals(900, widths.sum())
        assertEquals("等宽自然宽度 ⇒ 等分剩余", listOf(300, 300, 300), widths)
    }

    @Test
    fun fallsBackToScrollingWhenMinimumsDoNotFit() {
        // 三列 × 100px 最小宽度 > 250px 可用宽度
        assertNull(fillTableWidthsPx(natural = listOf(100, 100, 100), availablePx = 250, minCellPx = 100))
        assertNull(fillTableWidthsPx(natural = emptyList(), availablePx = 400, minCellPx = 100))
        assertNull(fillTableWidthsPx(natural = listOf(100), availablePx = 0, minCellPx = 100))
    }
}
