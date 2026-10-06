package com.adsh.app.ui

import androidx.compose.runtime.saveable.listSaver

/**
 * 会话页那几层浮层的**开关与互斥规则**（纯逻辑，表在 ChatOverlaysTest）。
 *
 * 为什么单独一份：这些规则原来长在 ChatScreen 的四个 remember 里、散在六个写入点 ——
 * 「统计浮窗与输入框弹层互斥」和「dsh 的 dismissed」（关掉触发菜单之后，同一个 token +
 * 同一份查询不再自动召回）都只能靠真机目视。搬出来之后每一条都有断言钉着。
 *
 * **一次只开一个**（第 185 轮起）：输入框的三个弹层与输入框下方那一排胶囊共用 [open] 一个槽。
 * dsh 就是这个形状 —— 统计 / 用量两个胶囊共用一个 openPill，上下文那个用自己那一个 open；
 * 两处同时开在界面上也没有意义（两个气泡会叠在一起）。用「一个槽」表达之后，互斥不再是一条
 * 需要维护的规则，而是**结构上没有第二个位置可以开**：写进去的那个 id 就是唯一开着的浮层。
 */
internal data class ChatOverlays(
    /** 「+」打开的全量菜单记下按下那一刻的草稿；草稿一变就交回「键入 /」那条路径（dsh 的 track） */
    val launcherDraft: String? = null,
    /** 用户关掉的那一份草稿（dsh 的 dismissed）：输入新查询或再按一次「+」才重新武装 */
    val paletteMuted: String? = null,
    /** 当前打开的那一个浮层（取值见 OPEN_*）；null = 全关 */
    val open: String? = null,
) {

    /** 「+」那条路径还挂着吗：只有草稿与按下那一刻**完全一致**时才算 */
    fun launcherOpen(draft: String): Boolean = launcherDraft != null && launcherDraft == draft

    /**
     * 「键入 /」那条路径还挂着吗：有活的查询、没有精确命中、且这一份草稿没被关过
     * （前两条由 [slashQueryOf] / [paletteQueryComplete] 在调用处算好传进来）。
     */
    fun typedOpen(query: String?, complete: Boolean, draft: String): Boolean =
        query != null && !complete && paletteMuted != draft

    /** 某个触发器的开关：点一下同一个 id 就是关掉它 */
    fun toggled(id: String): ChatOverlays = copy(open = if (open == id) null else id)

    /**
     * 输入框弹层改开成 [menu]（null = 关掉）：开的时候触发菜单按「这一份草稿已被忽略」收掉
     * （与 [paletteDismissed] 同一套语义）。
     */
    fun menuChanged(menu: String?, draft: String): ChatOverlays =
        if (menu == null) {
            copy(open = null)
        } else {
            copy(open = menu, launcherDraft = null, paletteMuted = draft)
        }

    /** 「+」/「键入 /」自己报上来的可见性变化（dsh 的 toggleCommandMenu） */
    fun paletteVisibility(want: Boolean, draft: String): ChatOverlays =
        copy(launcherDraft = if (want) draft else null, paletteMuted = if (want) null else draft)

    /** 点空白处 / 返回键关掉触发菜单：记下这份草稿已被忽略 */
    fun paletteDismissed(draft: String): ChatOverlays =
        copy(launcherDraft = null, paletteMuted = draft)

    /** 发出去一条消息：两种触发菜单的状态都归零（浮层不受影响） */
    fun sent(): ChatOverlays = copy(launcherDraft = null, paletteMuted = null)
}

/** [ChatOverlays.open] 的取值：输入框里的三个弹层（dsh 的输入栏触发器） */
internal const val OPEN_PERMISSION = "permission"
internal const val OPEN_MODEL = "model"
internal const val OPEN_WORKSPACE = "workspace"

/** [ChatOverlays.open] 的取值：输入框下方那一排（dsh 的 ContextMeter + composer dock） */
internal const val OPEN_CONTEXT = "context"
internal const val OPEN_STATS = "stats"
internal const val OPEN_USAGE = "usage"

/**
 * 只把「哪个浮层开着」存过进程重建。草稿那两份状态不存 —— 它们绑在这一屏的草稿上
 * （与搬出来之前一致：原来也只存了统计那一个布尔）。
 */
internal val ChatOverlaysSaver = listSaver<ChatOverlays, String>(
    save = { listOf(it.open.orEmpty()) },
    restore = { ChatOverlays(open = it[0].ifEmpty { null }) },
)
