package com.adsh.app.ui.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * 长按选中文本后的浮动操作栏（复制 / 全选）——**拦掉「没有选中任何内容」的那一排**：Compose 在选中被
 * 清掉后还会再发一次 showMenu，那时只有「全选」可点，于是冒出一个一瞬即逝的栏。ADSH 的可选中区域都是
 * 只读文本，「全选」没有用处，所以直接不弹。
 */
private class NoEmptySelectionToolbar(private val delegate: TextToolbar) : TextToolbar {

    override val status: TextToolbarStatus get() = delegate.status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        // 三样都没有 = 这一次按下没有选中任何内容：不弹（见文件注释）
        if (onCopyRequested == null && onCutRequested == null && onPasteRequested == null) return
        delegate.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun hide() {
        delegate.hide()
    }
}

/** 把 [NoEmptySelectionToolbar] 装进 [LocalTextToolbar]（在 App 根上包一层） */
@Composable
fun AdshSelectionToolbar(content: @Composable () -> Unit) {
    val platform = LocalTextToolbar.current
    val toolbar = remember(platform) { NoEmptySelectionToolbar(platform) }
    CompositionLocalProvider(LocalTextToolbar provides toolbar) { content() }
}
