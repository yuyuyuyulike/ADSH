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
    fun `待办浮岛与输入卡同宽、圆角 16、body 上下 4 左右 12、header 32`() {
        // 折叠态总高 4 + 32 + 4 = 40dp；横向留白取输入卡的侧留白，折叠态与输入框等宽
        assertEquals(ComposerSpec.SideClearance, TodoDockPageInset)
        assertEquals(16.dp, TodoDockRadius)
        assertEquals(4.dp, TodoDockBodyPaddingVertical)
        assertEquals(12.dp, TodoDockBodyPaddingHorizontal)
        assertEquals(32.dp, TodoDockHeaderHeight)
        // 折叠态总高：上下内边距 + 标题行
        assertEquals(40.dp, TodoDockBodyPaddingVertical * 2 + TodoDockHeaderHeight)
    }

    @Test
    fun `轮尾动作行左移 6、两粒统计间距 2`() {
        // TurnTailNodeView.module.css:1-12 .actions{margin-top:4; margin-left:-6}
        assertEquals((-6).dp, TurnActionsLeftMargin)
        // TurnUsagePanel.module.css:12-14 .root+.root{margin-left:-6}，外层 gap 8 ⇒ 2
        assertEquals(2.dp, TurnStatPillGap)
    }
}
