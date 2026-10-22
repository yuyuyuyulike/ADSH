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
 *
 * 这个过滤器只在**旧 TextToolbar 路径**上生效：Compose 1.12 起 Android 默认启用新的 context-menu 路径
 * （`ComposeFoundationFlags.isNewContextMenuEnabled`），它不经过 [LocalTextToolbar]。所以 `:app` 的
 * `AdshApp.onCreate` 把该 flag 关掉 —— 两处是一对，改一处要同时改另一处。
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
        if (!shouldShowSelectionToolbar(onCopyRequested, onPasteRequested, onCutRequested)) return
        delegate.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun hide() {
        delegate.hide()
    }
}

/**
 * 这一次 showMenu 值不值得弹：三样能证明「确实选中了内容」的动作（复制 / 剪切 / 粘贴）一个都没有，
 * 就只剩「全选」——只读正文里那没有用处，弹出就是一瞬即逝的空栏。
 */
internal fun shouldShowSelectionToolbar(
    onCopyRequested: (() -> Unit)?,
    onPasteRequested: (() -> Unit)?,
    onCutRequested: (() -> Unit)?,
): Boolean = onCopyRequested != null || onPasteRequested != null || onCutRequested != null

/** 把 [NoEmptySelectionToolbar] 装进 [LocalTextToolbar]（在 App 根上包一层） */
@Composable
fun AdshSelectionToolbar(content: @Composable () -> Unit) {
    val platform = LocalTextToolbar.current
    val toolbar = remember(platform) { NoEmptySelectionToolbar(platform) }
    CompositionLocalProvider(LocalTextToolbar provides toolbar) { content() }
}
