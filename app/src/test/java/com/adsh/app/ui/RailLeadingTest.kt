package com.adsh.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 行首图标格画什么（第 101 轮）。
 *
 * 用户实测的 bug：run_code 及其子调用在**流式期间**失败时，行首那一格整个是空的 ——
 * 摘要已经变红（说明 `error = true`），左边却既没有图标也没有红点；等这一轮结束、界面改从库里
 * 渲染才出现。
 *
 * 旧实现的判定藏在三个 `animateFloatAsState` 的中间值里（三个 `if (alpha > 0f)`），
 * 动画值一旦没推进就三样都不画。现在判定是纯函数，这条规矩可以直接钉住。
 */
class RailLeadingTest {

    @Test
    fun failedAlwaysDrawsTheDot() {
        // 失败 → 状态点，且**与展开与否无关**（展开的行失败了也是红点）
        assertEquals(RailLeadingState.DOT, railLeadingState(open = false, failed = true))
        assertEquals(RailLeadingState.DOT, railLeadingState(open = true, failed = true))
    }

    @Test
    fun otherwiseOpenMeansChevronAndClosedMeansTheIcon() {
        assertEquals(RailLeadingState.CHEVRON, railLeadingState(open = true, failed = false))
        assertEquals(RailLeadingState.ICON, railLeadingState(open = false, failed = false))
    }

    @Test
    fun everyCombinationIsCovered() {
        // 四种组合都有确定的答案（没有「什么都不画」这一档）：失败两种 → 红点，
        // 收起 → 图标，展开（没失败）→ 倒角
        val all = listOf(false, true).flatMap { open ->
            listOf(false, true).map { failed -> railLeadingState(open, failed) }
        }
        assertEquals(4, all.size)
        assertEquals(2, all.count { it == RailLeadingState.DOT })
        assertEquals(1, all.count { it == RailLeadingState.CHEVRON })
        assertEquals(1, all.count { it == RailLeadingState.ICON })
    }
}
