package com.adsh.app.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 输入卡片的 dsh 几何（[ComposerSpec]，取自 dsh 的 InputBar.module.css）与输入框上沿的
 * 36dp 渐变遮罩口径。这组数字决定「输入框的体积与质感」，改动会当场变红。
 */
class ComposerSpecTest {

    @Test
    fun `卡片几何逐项对齐 dsh InputBar`() {
        // .card: border-radius --dsw-radius-panel=28、padding-top 8、gap 12
        assertEquals(28.dp, ComposerSpec.CardRadius)
        assertEquals(8.dp, ComposerSpec.CardPaddingTop)
        assertEquals(12.dp, ComposerSpec.CardGap)
        // .input: min-height 36、padding 4 8 0 14（右侧 8 + .scroll 的 4 = 12）
        assertEquals(36.dp, ComposerSpec.InputMinHeight)
        assertEquals(4.dp, ComposerSpec.InputPaddingTop)
        assertEquals(14.dp, ComposerSpec.InputPaddingStart)
        assertEquals(12.dp, ComposerSpec.InputPaddingEnd)
        // .row: padding 2 8 6；窄容器（<560px）gap 8
        assertEquals(2.dp, ComposerSpec.RowPaddingTop)
        assertEquals(8.dp, ComposerSpec.RowPaddingSide)
        assertEquals(6.dp, ComposerSpec.RowPaddingBottom)
        assertEquals(8.dp, ComposerSpec.RowGap)
        // .primary: 34 圆、translateY(-2)
        assertEquals(34.dp, ComposerSpec.SendSize)
        assertEquals(2.dp, ComposerSpec.SendLift)
        // --dsh-composer-side-clearance
        assertEquals(16.dp, ComposerSpec.SideClearance)
    }

    @Test
    fun `单行空卡片高 98dp`() {
        // dsh 的堆叠：padding-top 8 + input 36 + gap 12 + row（2 + 34 + 6）
        assertEquals(
            98.dp,
            ComposerSpec.CardPaddingTop + ComposerSpec.InputMinHeight + ComposerSpec.CardGap +
                ComposerSpec.RowPaddingTop + ComposerSpec.SendSize + ComposerSpec.RowPaddingBottom,
        )
    }
}
