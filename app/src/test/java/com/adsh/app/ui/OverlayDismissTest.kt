package com.adsh.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹层「点别处即关」的登记处：所有 `DshPopup` 打开时登记自己，根布局一次按下就全关掉。
 * 手势本身要在真机上验（Initial 阶段看一眼按下、只吃掉那一次抬起），这里验登记与关闭语义。
 */
class OverlayDismissTest {

    @Test
    fun `登记之后才处于打开状态`() {
        val registry = OverlayDismissRegistry()
        assertFalse(registry.active)
        val key = Any()
        registry.register(key) { }
        assertTrue(registry.active)
        registry.unregister(key)
        assertFalse(registry.active)
    }

    @Test
    fun `dismissAll 关掉所有登记过的弹层`() {
        val registry = OverlayDismissRegistry()
        val closed = ArrayList<String>()
        registry.register("permission") { closed += "permission" }
        registry.register("model") { closed += "model" }
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
        registry.register(first) {
            closed += "first"
            registry.unregister(first)
        }
        registry.register("second") { closed += "second" }
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
}
