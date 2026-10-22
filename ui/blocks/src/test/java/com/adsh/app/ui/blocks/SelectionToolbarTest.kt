package com.adsh.app.ui.blocks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选择工具栏的弹出判据（[shouldShowSelectionToolbar]）：只有「复制 / 剪切 / 粘贴」这类证明有选中内容的
 * 动作为空时才是空栏 —— 空栏只剩「全选」，在只读正文里没有用处，弹出就是一瞬即逝的闪烁。
 *
 * 回归：Compose 1.12 的新 context-menu 路径绕开 LocalTextToolbar 之后，这条判据一度整体失效。
 */
class SelectionToolbarTest {

    @Test
    fun `有复制动作时弹出`() {
        assertTrue(shouldShowSelectionToolbar(onCopyRequested = {}, onPasteRequested = null, onCutRequested = null))
    }

    @Test
    fun `只有粘贴或剪切也弹出`() {
        assertTrue(shouldShowSelectionToolbar(onCopyRequested = null, onPasteRequested = {}, onCutRequested = null))
        assertTrue(shouldShowSelectionToolbar(onCopyRequested = null, onPasteRequested = null, onCutRequested = {}))
    }

    @Test
    fun `没有选中内容的空栏不弹出`() {
        assertFalse(shouldShowSelectionToolbar(onCopyRequested = null, onPasteRequested = null, onCutRequested = null))
    }
}
