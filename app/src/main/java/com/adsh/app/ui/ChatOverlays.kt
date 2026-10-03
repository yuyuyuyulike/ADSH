package com.adsh.app.ui

import androidx.compose.runtime.saveable.listSaver

/**
 * 会话页那几层浮层的**开关与互斥规则**（纯逻辑，表在 ChatOverlaysTest）。
 *
 * 为什么单独一份：这些规则原来长在 ChatScreen 的四个 remember 里、散在六个写入点 ——
 * 「统计浮窗与输入框弹层互斥」和「dsh 的 dismissed」（关掉触发菜单之后，同一个 token +
 * 同一份查询不再自动召回）都只能靠真机目视。搬出来之后每一条都有断言钉着。
 *
 * 状态仍由 ChatScreen 持有（它得跟着会话生命周期与重组走，见那边采样一段的注释），
 * 这里只放「当前状态 + 这一次动作 → 下一个状态」的纯判定。
 */
internal data class ChatOverlays(
    /** 「+」打开的全量菜单记下按下那一刻的草稿；草稿一变就交回「键入 /」那条路径（dsh 的 track） */
    val launcherDraft: String? = null,
    /** 用户关掉的那一份草稿（dsh 的 dismissed）：输入新查询或再按一次「+」才重新武装 */
    val paletteMuted: String? = null,
    /** 输入框弹出的三个层之一（"permission" / "model" / "context"）；null = 没开 */
    val composerMenu: String? = null,
    /** 顶栏的会话统计浮窗 */
    val statsOpen: Boolean = false,
) {

    /** 「+」那条路径还挂着吗：只有草稿与按下那一刻**完全一致**时才算 */
    fun launcherOpen(draft: String): Boolean = launcherDraft != null && launcherDraft == draft

    /**
     * 「键入 /」那条路径还挂着吗：有活的查询、没有精确命中、且这一份草稿没被关过
     * （前两条由 [slashQueryOf] / [paletteQueryComplete] 在调用处算好传进来）。
     */
    fun typedOpen(query: String?, complete: Boolean, draft: String): Boolean =
        query != null && !complete && paletteMuted != draft

    /** 点顶栏的统计图标：开关自己，并且**总是**把输入框弹层收掉（互斥） */
    fun statsToggled(): ChatOverlays = copy(statsOpen = !statsOpen, composerMenu = null)

    /**
     * 输入框弹层改开成 [menu]（null = 关掉）：开的时候统计浮窗收掉，触发菜单按
     * 「这一份草稿已被忽略」收掉（与 [paletteDismissed] 同一套语义）。
     */
    fun menuChanged(menu: String?, draft: String): ChatOverlays =
        if (menu == null) {
            copy(composerMenu = null)
        } else {
            copy(composerMenu = menu, statsOpen = false, launcherDraft = null, paletteMuted = draft)
        }

    /** 「+」/「键入 /」自己报上来的可见性变化（dsh 的 toggleCommandMenu） */
    fun paletteVisibility(want: Boolean, draft: String): ChatOverlays =
        copy(launcherDraft = if (want) draft else null, paletteMuted = if (want) null else draft)

    /** 点空白处 / 返回键关掉触发菜单：记下这份草稿已被忽略 */
    fun paletteDismissed(draft: String): ChatOverlays =
        copy(launcherDraft = null, paletteMuted = draft)

    /** 发出去一条消息：两种触发菜单的状态都归零（弹层与统计不受影响） */
    fun sent(): ChatOverlays = copy(launcherDraft = null, paletteMuted = null)
}

/**
 * 只把「统计浮窗开着没有」存过进程重建 —— 与搬出来之前**逐字一致**：触发菜单与输入框弹层
 * 本来就不跨进程恢复（它们绑在这一屏的草稿上），别顺手把它们也存了。
 */
internal val ChatOverlaysSaver = listSaver<ChatOverlays, Boolean>(
    save = { listOf(it.statsOpen) },
    restore = { ChatOverlays(statsOpen = it[0]) },
)
