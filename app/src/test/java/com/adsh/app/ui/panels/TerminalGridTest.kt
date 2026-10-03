package com.adsh.app.ui.panels

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 视口 → PTY 网格（[terminalGrid]）—— R10 从 LazyColumn 的 onSizeChanged 里提出来的纯函数。
 *
 * 这条公式决定终端里所有程序怎么排版（apt 的进度条、ls 的分栏、less 的分页、top 的整屏刷新）：
 * PTY 说自己 100 列宽、屏幕只有 45 列，输出就会在屏幕中间硬折行。它以前长在 538 行的
 * @Composable 里，一行都测不了；现在截断方向与上下限都有打表。
 */
class TerminalGridTest {

    /** 12sp 等宽在 2.75 密度下的量级：每列 ~23.4px；行高 51px —— 与真机上的取值同量级 */
    private val charWidth = 23.4f
    private val lineHeight = 51f
    private val padding = 20f

    private fun grid(width: Int, height: Int, pad: Float = padding) =
        terminalGrid(
            widthPx = width,
            heightPx = height,
            charWidthPx = charWidth,
            lineHeightPx = lineHeight,
            horizontalPaddingPx = pad,
        )

    @Test
    fun columnsAndRowsComeFromViewportDividedByFontMetrics() {
        // (1080 - 20) / 23.4 = 45.29… → 45 列（截断，不四舍五入）；2000 / 51 = 39.2 → 39 行
        val g = grid(1080, 2000)
        assertEquals(45, g.cols)
        assertEquals(39, g.rows)
    }

    @Test
    fun paddingIsSubtractedBeforeDividing() {
        // 同样 1080 宽：不扣内边距是 46 列，扣掉 100px 只剩 41 列
        assertEquals(46, grid(1080, 2000, pad = 0f).cols)
        assertEquals(41, grid(1080, 2000, pad = 100f).cols)
        // 内边距只影响列，不影响行
        assertEquals(39, grid(1080, 2000, pad = 100f).rows)
    }

    @Test
    fun tinyViewportClampsToMinimums() {
        // (100 - 20) / 23.4 = 3 列、40 / 51 = 0 行 —— 太小的值会让程序排版崩掉，按上下限夹住
        val g = grid(100, 40)
        assertEquals(20, g.cols)
        assertEquals(5, g.rows)
    }

    @Test
    fun absurdlyLargeViewportClampsToMaximums() {
        // 测量出错（或外接大屏）时不把 PTY 撑到几千列：上限 300 × 200
        val g = grid(100_000, 100_000)
        assertEquals(300, g.cols)
        assertEquals(200, g.rows)
    }

    @Test
    fun defaultSessionSizeIsAValidGrid() {
        // 还没测量出视口时先按 40 行 × 100 列起会话：这个尺寸必须是合法网格（不被上下限夹住）。
        // 这里用二进制可精确表示的刻度（每列 20px、行高 50px）量，免得踩浮点边界。
        val g = terminalGrid(
            widthPx = DEFAULT_PTY_COLS * 20 + 20,
            heightPx = DEFAULT_PTY_ROWS * 50,
            charWidthPx = 20f,
            lineHeightPx = 50f,
            horizontalPaddingPx = 20f,
        )
        assertEquals(DEFAULT_PTY_COLS, g.cols)
        assertEquals(DEFAULT_PTY_ROWS, g.rows)
    }
}
