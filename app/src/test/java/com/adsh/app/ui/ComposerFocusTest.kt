package com.adsh.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息区手势结束时的「要不要收起输入状态」判据（第 85 轮修的真机 bug）。
 *
 * 事故链（logcat 实测）：长按正文 → SelectionContainer `requestFocus()` 接管焦点
 * （选中高亮 / 手柄 / 复制菜单都挂在这条焦点上）→ 抬手 → 这里 clearFocus →
 * **4ms 后** SelectionManager 的 onFocusChanged 看到 `!hasFocus` 就 `onRelease()`：
 * 选中当场被清空，用户看到的就是「长按选不中、复制不了」。
 *
 * 所以只有「短按」（点击）才收键盘。系统长按阈值这里是 500ms（真机同值）。
 */
class ComposerFocusTest {

    private val longPress = 500L

    @Test
    fun `短按收键盘`() {
        assertTrue(shouldClearComposerFocus(dragged = false, heldMillis = 80, longPressTimeoutMillis = longPress))
        // 正好卡在阈值上：已经算长按（Compose 的 awaitLongPressOrCancellation 也是 >= 阈值即触发）
        assertFalse(shouldClearComposerFocus(dragged = false, heldMillis = 500, longPressTimeoutMillis = longPress))
    }

    @Test
    fun `长按不收键盘（否则选中会被 onRelease 清掉）`() {
        assertFalse(shouldClearComposerFocus(dragged = false, heldMillis = 501, longPressTimeoutMillis = longPress))
        assertFalse(shouldClearComposerFocus(dragged = false, heldMillis = 3000, longPressTimeoutMillis = longPress))
    }

    @Test
    fun `滑动不收键盘`() {
        // 滑历史时键盘不该自己收起来；长距离慢滑（很久）同样不算短按
        assertFalse(shouldClearComposerFocus(dragged = true, heldMillis = 60, longPressTimeoutMillis = longPress))
        assertFalse(shouldClearComposerFocus(dragged = true, heldMillis = 900, longPressTimeoutMillis = longPress))
    }
}
