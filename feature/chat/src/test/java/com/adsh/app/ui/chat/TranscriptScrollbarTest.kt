package com.adsh.app.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 右侧贴边滚动条的 thumb 几何：比例直接取滚动容器的真实可滚距离（浏览器 scrollHeight 语义），
 * 所以内容没变时长度恒定；视口装得下内容时不画，长度有下限。
 */
class TranscriptScrollbarTest {

    private fun thumb(
        viewportPx: Int,
        maxScrollPx: Int,
        scrollValuePx: Int = 0,
        minThumbPx: Float = 24f,
        trackInsetPx: Int = 0,
    ) = scrollbarThumb(
        viewportPx = viewportPx,
        maxScrollPx = maxScrollPx,
        scrollValuePx = scrollValuePx,
        minThumbPx = minThumbPx,
        trackInsetPx = trackInsetPx,
    )

    @Test
    fun noThumbWhenTheContentFits() {
        assertNull(thumb(viewportPx = 400, maxScrollPx = 0))
        assertNull(thumb(viewportPx = 0, maxScrollPx = 400))
        assertNull(thumb(viewportPx = 400, maxScrollPx = -10))
    }

    @Test
    fun topOfTheListPutsTheThumbAtTheTrackStart() {
        val t = thumb(viewportPx = 200, maxScrollPx = 400)!!
        assertEquals(0f, t.topPx, 0.01f)
        // 内容 = 视口 + 可滚 = 600；长度 = 200² / 600
        assertEquals(200f * 200f / 600f, t.lengthPx, 0.01f)
    }

    @Test
    fun lengthOnlyDependsOnContentViewportRatio() {
        // 同样的内容比例，滚到哪儿长度都一样（dsh 的 scrollHeight 语义）。
        val atTop = thumb(viewportPx = 200, maxScrollPx = 400, scrollValuePx = 0)!!
        val inMiddle = thumb(viewportPx = 200, maxScrollPx = 400, scrollValuePx = 200)!!
        assertEquals(atTop.lengthPx, inMiddle.lengthPx, 0.01f)
    }

    @Test
    fun middlePositionIsProportionalToTheScrolledDistance() {
        val t = thumb(viewportPx = 200, maxScrollPx = 400, scrollValuePx = 200)!!
        val length = 200f * 200f / 600f
        val travel = 200f - length
        assertEquals(0.5f * travel, t.topPx, 0.01f)
    }

    @Test
    fun bottomIsClampedToTheTrackEnd() {
        val t = thumb(viewportPx = 200, maxScrollPx = 400, scrollValuePx = 10_000)!!
        assertEquals(200f - t.lengthPx, t.topPx, 0.01f)
        assertTrue(t.topPx + t.lengthPx <= 200.01f)
    }

    @Test
    fun thumbNeverShorterThanTheMinimum() {
        // 内容比视口长 1000 倍：原始长度 0.2px，被最短 24px 抬起。
        val t = thumb(viewportPx = 500, maxScrollPx = 500_000, scrollValuePx = 250_000)!!
        assertEquals(24f, t.lengthPx, 0.01f)
        val travel = 500f - 24f
        assertEquals(0.5f * travel, t.topPx, 0.01f)
    }

    @Test
    fun trackInsetShrinksTheThumbAndItsTravel() {
        // dsh 的轨道上下各内缩 2dp：thumb 长度与行程按内缩后的轨道算。
        val t = thumb(viewportPx = 200, maxScrollPx = 400, trackInsetPx = 2)!!
        val track = 200f - 4f
        assertEquals(track * 200f / 600f, t.lengthPx, 0.01f)
        val middle = thumb(viewportPx = 200, maxScrollPx = 400, scrollValuePx = 200, trackInsetPx = 2)!!
        assertEquals(0.5f * (track - middle.lengthPx), middle.topPx, 0.01f)
    }

    @Test
    fun maxScrollIsExposedForDragConversion() {
        val t = thumb(viewportPx = 200, maxScrollPx = 400)!!
        assertEquals(400f, t.maxScrollPx, 0.01f)
    }

    @Test
    fun dragDeltaKeepsTheThumbRatio() {
        // 轨道可动 100px、内容可滚 400px：手指拖 10px ⇒ 列表滚 40px。
        assertEquals(40f, thumbDragScrollDelta(10f, 100f, 400f), 0.01f)
        assertEquals(0f, thumbDragScrollDelta(10f, 0f, 400f), 0f)
    }

    @Test
    fun onlyTheThumbBandGrabsTheGesture() {
        // 视口宽 1000、命中区 24、thumb 在 y=300..420：只有右缘 24dp × thumb 段算命中。
        val width = 1000f
        assertTrue(isOnScrollbarThumb(990f, 360f, width, 24f, 300f, 120f))
        assertFalse("左移出命中区", isOnScrollbarThumb(970f, 360f, width, 24f, 300f, 120f))
        assertFalse("thumb 上方", isOnScrollbarThumb(990f, 200f, width, 24f, 300f, 120f))
        assertFalse("thumb 下方", isOnScrollbarThumb(990f, 500f, width, 24f, 300f, 120f))
    }

    @Test
    fun edgeGuardExemptsOnlyTheThumbHitRegion() {
        val hit = Rect(left = 0f, top = 100f, right = 24f, bottom = 300f)
        assertTrue(isExemptFromEdgeGuard(Offset(10f, 150f), hit))
        assertFalse(isExemptFromEdgeGuard(Offset(10f, 50f), hit))
        assertFalse("滚动条不可拖（命中区为空）时不豁免", isExemptFromEdgeGuard(Offset(10f, 150f), null))
    }
}
