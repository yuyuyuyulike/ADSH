package com.adsh.app.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹层「点别处即关」的登记处：所有 `DshPopup` 打开时登记自己，根布局一次按下就关掉
 * 「触发按钮不在这根手指下」的那些。手势本身要在真机上验（Initial 阶段看一眼按下、不吃掉抬起），
 * 这里验登记、关闭语义与锚点判定。
 */
class OverlayDismissTest {

    @Test
    fun `登记之后才处于打开状态`() {
        val registry = OverlayDismissRegistry()
        assertFalse(registry.active)
        val key = Any()
        registry.register(key, dismiss = {})
        assertTrue(registry.active)
        registry.unregister(key)
        assertFalse(registry.active)
    }

    @Test
    fun `dismissAll 关掉所有登记过的弹层`() {
        val registry = OverlayDismissRegistry()
        val closed = ArrayList<String>()
        registry.register("permission", dismiss = { closed += "permission" })
        registry.register("model", dismiss = { closed += "model" })
        registry.dismissAll()
        // stateMap 的遍历顺序不是插入顺序（它是哈希序），所以只比集合
        assertEquals(listOf("model", "permission"), closed.sorted())
    }

    @Test
    fun `关闭过程中注销自己不会打断遍历`() {
        val registry = OverlayDismissRegistry()
        val closed = ArrayList<String>()
        // 第一个弹层的关闭回调顺手注销自己（真实场景：状态一变 DisposableEffect 就注销）
        val first = Any()
        registry.register(first, dismiss = {
            closed += "first"
            registry.unregister(first)
        })
        registry.register("second", dismiss = { closed += "second" })
        registry.dismissAll()
        assertEquals(listOf("first", "second"), closed.sorted())
        // 第二个弹层没有注销自己（真实场景由 DisposableEffect 在状态变化时注销），
        // 所以登记表里还留着它 —— 这里只断言「遍历没被打断」
        assertTrue(registry.active)
    }

    @Test
    fun `没有弹层时 dismissAll 是空操作`() {
        val registry = OverlayDismissRegistry()
        registry.dismissAll()
        assertFalse(registry.active)
    }

    /** 第 199 轮的口径：按在自己的触发按钮上 = 「再点一次同一个按钮」，由按钮自己收起，登记处不碰 */
    @Test
    fun `按下落在锚点里时这个弹层不关`() {
        val registry = OverlayDismissRegistry()
        val closed = ArrayList<String>()
        val anchor = AnchorRect().apply { value = IntRect(IntOffset(100, 200), IntOffset(300, 260)) }
        registry.register("menu", dismiss = { closed += "menu" }, anchor = { anchor.value })
        registry.dismissOutside(150f, 230f)
        assertEquals(emptyList<String>(), closed)
    }

    /** 按在别处（含**另一个**胶囊的按钮）：关掉它，并且这次点击照常发出去（此处只验「关了」） */
    @Test
    fun `按下落在锚点之外就关`() {
        val registry = OverlayDismissRegistry()
        val closed = ArrayList<String>()
        registry.register(
            "menu",
            dismiss = { closed += "menu" },
            anchor = { IntRect(IntOffset(100, 200), IntOffset(300, 260)) },
        )
        // 另一个按钮的位置（不在上面那个矩形里）
        registry.dismissOutside(400f, 230f)
        // 矩形边界：右下角是**开区间**（contains 不含右下边）
        registry.dismissOutside(300f, 260f)
        assertEquals(listOf("menu", "menu"), closed)
    }
}
