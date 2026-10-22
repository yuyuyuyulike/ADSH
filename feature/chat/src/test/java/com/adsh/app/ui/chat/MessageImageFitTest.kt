package com.adsh.app.ui.chat

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 消息内单图尺寸（dsh 的 MessageImage.singleFit）：长边 240、宽高比夹在 [0.25, 4]、
 * 超高锚顶 / 超宽锚左、只缩不放，且不超过消息列宽。
 */
class MessageImageFitTest {

    @Test
    fun normalLandscapeFitsIn240LongEdge() {
        val fit = messageImageFit(4000, 3000, 300.dp)
        assertEquals(240.dp, fit.width)
        assertEquals(180.dp, fit.height)
        assertEquals(Alignment.Center, fit.alignment)
    }

    @Test
    fun tinyImageIsNotUpscaled() {
        val fit = messageImageFit(100, 100, 300.dp)
        assertEquals(100.dp, fit.width)
        assertEquals(100.dp, fit.height)
    }

    @Test
    fun veryTallImageIsClampedAndAnchoredTop() {
        val fit = messageImageFit(1000, 8000, 300.dp)
        assertEquals(60.dp, fit.width)
        assertEquals(240.dp, fit.height)
        assertEquals(Alignment.TopCenter, fit.alignment)
    }

    @Test
    fun veryWideImageIsClampedAndAnchoredLeft() {
        val fit = messageImageFit(8000, 1000, 300.dp)
        assertEquals(240.dp, fit.width)
        assertEquals(60.dp, fit.height)
        assertEquals(Alignment.CenterStart, fit.alignment)
    }

    @Test
    fun narrowColumnCapsTheWholeBox() {
        val fit = messageImageFit(4000, 3000, 120.dp)
        assertEquals(120.dp, fit.width)
        assertEquals(90.dp, fit.height)
    }

    @Test
    fun unknownDimensionsFallBackToSquareWithinColumn() {
        val fit = messageImageFit(0, 0, 300.dp)
        assertEquals(240.dp, fit.width)
        assertEquals(240.dp, fit.height)
        val narrow = messageImageFit(0, 0, 120.dp)
        assertEquals(120.dp, narrow.width)
        assertEquals(120.dp, narrow.height)
    }
}
