package com.adsh.app.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P0 表面/几何（dsh StatsPills / TodoPanel / TurnTailNodeView + TurnUsagePanel）：
 * 这几个数字就是胶囊、待办浮岛、轮尾动作行的观感来源，改动会当场变红。
 */
class SurfaceGeometryTest {

    @Test
    fun `dock 胶囊圆角对齐 StatsPills 的 radius-sm`() {
        // StatsPills.module.css:13-28 —— radius-sm=8（不是圆角 999）
        assertEquals(8.dp, StatsDockPillRadius)
    }

    @Test
    fun `待办浮岛比输入卡每侧窄 16、圆角 16、body 上下 6 左右 12、header 36`() {
        // TodoPanel.module.css:1-33：composer 侧留白 16 + dock inset 8×2
        assertEquals(32.dp, TodoDockPageInset)
        assertEquals(16.dp, TodoDockRadius)
        assertEquals(6.dp, TodoDockBodyPaddingVertical)
        assertEquals(12.dp, TodoDockBodyPaddingHorizontal)
        assertEquals(36.dp, TodoDockHeaderHeight)
    }

    @Test
    fun `轮尾动作行左移 6、两粒统计间距 2`() {
        // TurnTailNodeView.module.css:1-12 .actions{margin-top:4; margin-left:-6}
        assertEquals((-6).dp, TurnActionsLeftMargin)
        // TurnUsagePanel.module.css:12-14 .root+.root{margin-left:-6}，外层 gap 8 ⇒ 2
        assertEquals(2.dp, TurnStatPillGap)
    }
}
