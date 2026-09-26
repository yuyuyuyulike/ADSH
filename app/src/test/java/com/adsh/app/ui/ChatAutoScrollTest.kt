package com.adsh.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「展开之后向下展示」的滚动量（第 77 轮）：展开是向下长的，读者点的那一行又常常贴着视口底边，
 * 于是新长出来的一块整个落在视口下面 —— 这里只滚**必要的那一点**把它带回视口。
 *
 * 视口取 1000（0..1000）方便看数。规则：把这一行的底边带进视口，但绝不把行首推过视口顶边。
 */
class ChatAutoScrollTest {

    @Test
    fun `行整个在视口里时不滚`() {
        assertEquals(0, revealScrollDelta(itemOffset = 100, itemSize = 200, viewportStart = 0, viewportEnd = 1000))
        // 底边正好压在视口底边上：也不算超出
        assertEquals(0, revealScrollDelta(itemOffset = 800, itemSize = 200, viewportStart = 0, viewportEnd = 1000))
    }

    @Test
    fun `底边超出视口时只补超出量`() {
        // 行占 800..1200，视口到 1000 ⇒ 补 200（补完正好整行可见）
        assertEquals(200, revealScrollDelta(itemOffset = 800, itemSize = 400, viewportStart = 0, viewportEnd = 1000))
        // 展开体很长（900）但仍比视口矮：底边超 200、行首还有 300 的余量 ⇒ 补 200
        assertEquals(200, revealScrollDelta(itemOffset = 300, itemSize = 900, viewportStart = 0, viewportEnd = 1000))
    }

    @Test
    fun `行比视口高时滚到行首贴住视口顶边`() {
        // 行首已经贴在顶边：一点余量都没有 ⇒ 不滚
        assertEquals(0, revealScrollDelta(itemOffset = 0, itemSize = 1200, viewportStart = 0, viewportEnd = 1000))
        // 行首在 400、整行 1300 高：需要 700 才能露出底边，但只允许滚 400（再多行首就出视口了）
        assertEquals(400, revealScrollDelta(itemOffset = 400, itemSize = 1300, viewportStart = 0, viewportEnd = 1000))
    }

    @Test
    fun `视口带内边距时按可用区域算`() {
        // 可用区域 40..960：行占 700..1100 ⇒ 超 140、行首余量 660 ⇒ 补 140
        assertEquals(140, revealScrollDelta(itemOffset = 700, itemSize = 400, viewportStart = 40, viewportEnd = 960))
        // 行首贴在可用区域顶边（40）：没有余量 ⇒ 不滚
        assertEquals(0, revealScrollDelta(itemOffset = 40, itemSize = 950, viewportStart = 40, viewportEnd = 960))
    }
}
