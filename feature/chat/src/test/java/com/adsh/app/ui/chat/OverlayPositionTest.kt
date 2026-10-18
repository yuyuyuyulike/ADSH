package com.adsh.app.ui.chat

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 同层浮层的纯函数：定位（dsh 的 useAnchoredPosition）、窗口→宿主坐标换算。
 * UI 层（SubcomposeLayout）只在真机上验。
 */
class OverlayPositionTest {

    private val host = IntSize(1080, 2000)

    private fun pos(
        anchor: IntRect?,
        panel: IntSize = IntSize(300, 400),
        alignStart: Boolean = false,
        below: Boolean = false,
        gap: Int = 8,
        lift: Int = 0,
        margin: Int = 12,
    ): IntOffset = overlayPosition(anchor, host, panel, alignStart, below, gap, lift, margin)

    @Test
    fun `above puts the panel bottom at anchor top minus gap`() {
        val anchor = IntRect(100, 1000, 400, 1100)
        assertEquals(IntOffset(100, 1000 - 400 - 8), pos(anchor, alignStart = true))
    }

    @Test
    fun `below puts the panel top at anchor bottom plus gap`() {
        val anchor = IntRect(100, 1000, 400, 1100)
        assertEquals(IntOffset(100, 1100 + 8), pos(anchor, alignStart = true, below = true))
    }

    @Test
    fun `alignEnd puts the panel right edge at the anchor right edge`() {
        val anchor = IntRect(100, 1000, 400, 1100)
        assertEquals(IntOffset(400 - 300, 1000 - 400 - 8), pos(anchor))
    }

    @Test
    fun `liftBottom raises the panel further`() {
        val anchor = IntRect(100, 1000, 400, 1100)
        assertEquals(IntOffset(100, 1000 - 400 - 8 - 40), pos(anchor, alignStart = true, lift = 40))
    }

    @Test
    fun `clamped into the host on all four sides`() {
        val bottomRight = IntRect(1000, 1990, 1070, 1999)
        assertEquals(IntOffset(1080 - 300 - 12, 1990 - 400 - 8), pos(bottomRight))

        val top = IntRect(100, 20, 200, 60)
        assertEquals(IntOffset(100, 12), pos(top, alignStart = true))

        val left = IntRect(2, 1000, 100, 1100)
        assertEquals(IntOffset(12, 1000 - 400 - 8), pos(left, alignStart = true))
    }

    @Test
    fun `zero sized panel is not clamped`() {
        val anchor = IntRect(2, 20, 100, 60)
        assertEquals(IntOffset(2, 20 - 8), pos(anchor, panel = IntSize.Zero, alignStart = true))
    }

    @Test
    fun `null anchor falls back to the margin corner`() {
        assertEquals(IntOffset(12, 12), pos(null))
    }

    @Test
    fun `anchor converts from window to host coordinates`() {
        assertEquals(
            IntRect(10, 90, 110, 130),
            overlayAnchorInHost(IntRect(100, 1000, 200, 1040), IntOffset(90, 910)),
        )
        assertNull(overlayAnchorInHost(null, IntOffset(90, 910)))
    }

}
