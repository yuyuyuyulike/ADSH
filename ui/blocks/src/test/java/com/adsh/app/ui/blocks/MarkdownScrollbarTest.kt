package com.adsh.app.ui.blocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 宽表底部滚动条的 thumb 几何（与右侧会话滚动条同一套「视口 / 内容」比例）。 */
class MarkdownScrollbarTest {

    @Test
    fun `内容装得下时不画`() {
        assertNull(horizontalScrollbarThumb(viewportPx = 400, maxScrollPx = 0, scrollValuePx = 0, minThumbPx = 24f))
        assertNull(horizontalScrollbarThumb(viewportPx = 0, maxScrollPx = 100, scrollValuePx = 0, minThumbPx = 24f))
    }

    @Test
    fun `内容翻倍时 thumb 占视口一半`() {
        val thumb = horizontalScrollbarThumb(viewportPx = 400, maxScrollPx = 400, scrollValuePx = 0, minThumbPx = 24f)!!
        assertEquals(200f, thumb.lengthPx, 0.01f)
        assertEquals(0f, thumb.leftPx, 0.01f)
    }

    @Test
    fun `滚到底时 thumb 贴右端`() {
        val thumb = horizontalScrollbarThumb(viewportPx = 400, maxScrollPx = 1200, scrollValuePx = 1200, minThumbPx = 24f)!!
        assertEquals(400f - thumb.lengthPx, thumb.leftPx, 0.01f)
    }

    @Test
    fun `超长内容时 thumb 不小于最短长度`() {
        val thumb = horizontalScrollbarThumb(viewportPx = 400, maxScrollPx = 1_000_000, scrollValuePx = 0, minThumbPx = 24f)!!
        assertEquals(24f, thumb.lengthPx, 0.01f)
        assertTrue(thumb.lengthPx <= 400f)
    }
}
