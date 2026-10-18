package com.adsh.app.ui.chat

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * footer（输入区整摞）叠在会话列表之上的几何：列表底部内边距 = footer 高度 + 14dp
 * （dsh 的 chat .scroll 是 `padding-bottom: 16px`，ADSH 沿用列表原有的 14dp 刻度），所以静止时
 * 最后一条正好停在 footer 上沿 14dp；滚动时内容从 footer 下面经过，由 footer 顶部 36dp 的渐变淡出。
 */
class TranscriptGeometryTest {

    private val density = Density(density = 3.25f, fontScale = 1f)

    @Test
    fun `底部内边距 = footer 高度 + 14dp`() {
        val gapPx = with(density) { TRANSCRIPT_FOOTER_GAP.roundToPx() }
        assertEquals(488 + gapPx, transcriptBottomPaddingPx(footerHeightPx = 488, density = density))
        // 还没量到 footer 的极端情况：只剩 14dp
        assertEquals(gapPx, transcriptBottomPaddingPx(footerHeightPx = 0, density = density))
    }

    @Test
    fun `静止时最后一条停在 footer 上沿 16dp`() {
        val footerPx = 488
        val gapPx = with(density) { TRANSCRIPT_FOOTER_GAP.roundToPx() }
        val paddingPx = transcriptBottomPaddingPx(footerPx, density)
        // 视口下沿 = footer 下沿：静止位置 = 视口下沿 - 底部内边距 = footer 上沿 - 14dp
        val viewportEndPx = 2600
        val footerTopPx = viewportEndPx - footerPx
        assertEquals(footerTopPx - gapPx, viewportEndPx - paddingPx)
        assertEquals(gapPx, paddingPx - footerPx)
    }

    @Test
    fun `遮罩带与静止间隙`() {
        // ConversationRoot.module.css 的 composerSeat 渐变：0px -> bg-base 36px
        assertEquals(36.dp, FOOTER_FADE_HEIGHT)
        assertEquals(14.dp, TRANSCRIPT_FOOTER_GAP)
    }

    @Test
    fun `内容列与输入卡等宽：从圆角缺口透出内容`() {
        // 有意差异：dsh 的内容列比卡窄 16/侧（缺口里几乎看不到内容）；这里取 16 = 卡的侧留白，
        // 内容会从 28dp 圆角缺口透出、被圆角裁切（用户明确要的观感）。
        assertEquals(16.dp, TRANSCRIPT_HORIZONTAL_PADDING)
    }
}
