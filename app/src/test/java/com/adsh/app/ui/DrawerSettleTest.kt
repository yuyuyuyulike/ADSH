package com.adsh.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉手势松手后该开还是该关（[drawerOpensAfterRelease]）—— 第 83 轮。
 *
 * 这一层以前埋在 `Modifier.draggable` 的 `onDragStopped` 里（composable 内部），一条用例都
 * 够不着。它现在只决定**方向**：速度不进动画，动画是 AppRoot 里那条定长缓动 —— 第 82 轮曾把
 * 手势速度当弹簧初速度接上去，用户实测「动画末尾抖动、很花哨」，所以退回「直来直去」。
 */
class DrawerSettleTest {

    @Test
    fun 往右甩开往左甩关() {
        assertTrue(drawerOpensAfterRelease(progress = 0.2f, velocityPxPerS = 800f))
        assertFalse(drawerOpensAfterRelease(progress = 0.8f, velocityPxPerS = -800f))
    }

    @Test
    fun 慢慢松手按过没过半定方向() {
        assertTrue(drawerOpensAfterRelease(0.6f, 100f))
        assertFalse(drawerOpensAfterRelease(0.4f, -100f))
        // 正好一半算关（与旧实现逐字一致：progress > 0.5）
        assertFalse(drawerOpensAfterRelease(0.5f, 0f))
    }

    /** 阈值那一档：正好 250 不算甩，比它快一点才算 —— 两条边界两个方向都钉住 */
    @Test
    fun 阈值正好等于250不算甩() {
        assertFalse(drawerOpensAfterRelease(progress = 0.2f, velocityPxPerS = 250f))
        assertTrue(drawerOpensAfterRelease(progress = 0.2f, velocityPxPerS = 250.5f))
        assertTrue(drawerOpensAfterRelease(progress = 0.8f, velocityPxPerS = -250f))
        assertFalse(drawerOpensAfterRelease(progress = 0.8f, velocityPxPerS = -250.5f))
    }
}
