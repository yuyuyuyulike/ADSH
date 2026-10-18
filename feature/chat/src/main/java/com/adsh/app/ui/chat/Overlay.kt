package com.adsh.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.adsh.app.ui.blocks.AnchorRect
import com.adsh.app.ui.blocks.RegisterOverlayDismiss
import com.adsh.app.ui.blocks.toAnchorRect
import com.adsh.app.ui.blocks.unionAnchor
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * 浮层（dsh 的 Popup + useAnchoredPosition）：同层宿主与独立窗口两条路共用一套定位规则。
 *
 * 宿主存在时浮层画在内容层之上（不占布局、随 IME/导航栏变矮）；没有宿主（Preview / 单测 /
 * :app 的独立调用点）退回窗口 Popup。两条路的分支在 [DshPopup]，定位规则在
 * [popupPanelMaxHeight] / [overlayPosition] / [LegacyDshPopup] 的 calculatePosition 三处。
 */

/**
 * 把浮层从布局里**摘出去**：自己只报宽度、高度报 0，整块画在锚点上方（y 从 -height 到 0）。
 *
 * dsh 的触发菜单就是这样一个节点（MenuView.module.css 的 .menu：`position:absolute;
 * bottom:calc(100% + 4px)`）—— 绝对定位不占父容器的布局。Compose 里没有 position:absolute，
 * 唯一等价的就是「报一个小的尺寸、把内容画到外面」。
 *
 * 为什么必须摘出去（而不是让输入框那一摞整体上移）：输入框那一摞的高度是被父层**限死**的
 * （键盘弹起时只剩一百多 dp），面板几百 dp 挤进来就会把卡片自己的高度吃光 —— 卡片里最后
 * 那一行按钮、以及卡片下方的胶囊会被压成 0 高直接消失。dsh 那边不存在这个问题，正是因为它
 * 不占布局：无论面板多高，输入卡片量到的都是它原本的高度。
 */
internal fun Modifier.outOfFlowAbove(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
}

/**
 * 弹层**窗口**的高度上限：由「锚点上方实际可用的空间」决定（模型菜单与触发菜单共用）。
 *
 * 为什么必须按上方空间夹：DshPopup 的定位规则是「内容底边 = 锚点顶边 - gap」
 * （对应 dsh 的 `bottom: calc(100% + 8px)`，见 DshPopup.calculatePosition）。
 * 只要内容不高于锚点上方剩下的空间，底边就恒定不动，多出来的内容只能往上长；
 * 一旦内容比上方空间还高，定位里的 `coerceAtLeast(gap)` 就会把整张卡片往下推 ——
 * 底边跟着往下跑，最后盖住输入框，这正是要修的病。
 * 所以这里把高度夹进「锚点上方空间」，让那个 coerce 永远不生效；超出的部分交给卡片自己的
 * verticalScroll 在内部消化：底边不动、卡片不变形，因此也不需要任何动画/动效。
 * （dsh 的 DOM 版是同一件事：把 max-height 夹到输入框上方的空间，见 MenuView.tsx:59-62。）
 *
 * @param anchorTopPx 锚点顶边在 root 里的 y（px，来自 onGloballyPositioned）；0 = 还没测量到
 * @param safeTopPx   卡片顶边要避开的高度（状态栏高度，px）
 * @param screenHeight 屏幕高度（锚点还没测量时的回退用）
 * @param maxHeight   设计稿的高度上限（dsh 的 max-height）
 * @param minHeight   再挤也不低于这个高度
 * @param gap         卡片底边与锚点顶边之间的间距
 * @param topMargin   卡片顶边至少要避开的距离
 */
internal fun popupPanelMaxHeight(
    density: Density,
    anchorTopPx: Float,
    safeTopPx: Int,
    screenHeight: Dp,
    maxHeight: Dp,
    minHeight: Dp,
    gap: Dp,
    topMargin: Dp,
): Dp {
    // 还没测量到触发按钮（菜单在首帧就被展开）：退回「屏幕高度 - 96dp」公式
    if (anchorTopPx <= 0f) return minOf(maxHeight, screenHeight - 96.dp)
    return with(density) {
        val gapPx = gap.roundToPx()
        val safe = maxOf(safeTopPx, topMargin.roundToPx())
        // 再让出 1px：dp -> px 来回取整可能把高度放大不到 1px，留出这点余量后，
        // 定位里的 coerceAtLeast(gap) 才是真的永远不插手（它一插手，底边就是在动）
        (floor(anchorTopPx - gapPx - safe).toInt() - 1)
            .coerceAtLeast(minHeight.roundToPx())
            .coerceAtMost(maxHeight.roundToPx())
            .toDp()
    }
}

// --------------------------------------------------------------------- 弹层基元

/**
 * dsh 的浮层定位：贴在锚点上方 [gap]（默认 8px，对应 dsh 的 `bottom: calc(100% + 8px)`），
 * 右（或左）边缘对齐，并夹在窗口内。
 *
 * focusable 默认 false：可获焦的 Popup 会成为一个新的焦点窗口，系统会因此把输入法收起来
 * （光标消失、输入框掉回底部）。菜单本身不需要键盘输入，所以一律不抢焦点；
 * 「点空白处关闭」由根布局的 [dismissOverlaysOnPress] 统一负责 —— 这个弹层一组合就
 * 登记进 [LocalOverlayDismiss]，所以每个调用处都不必再自己铺拦截层。
 */
@Composable
private fun LegacyDshPopup(
    onDismiss: () -> Unit,
    alignStart: Boolean = false,
    /** true = 弹在锚点下方（顶部按钮用），false = 弹在上方（输入框按钮用） */
    below: Boolean = false,
    focusable: Boolean = false,
    /**
     * 再往上抬多少（输入框里的按钮用）：菜单要贴在**整个输入框上沿**之上，
     * 而不是贴在按钮上方 —— 否则会盖住输入框本身。
     */
    liftBottom: androidx.compose.ui.unit.Dp = 0.dp,
    /** 与锚点之间的间距（dsh 的触发菜单是 4px，其余浮层是 8px） */
    gap: androidx.compose.ui.unit.Dp = 8.dp,
    /**
     * 与**窗口边**之间至少留出的距离（dsh 的 useAnchoredPosition margin，默认等于 [gap]）。
     *
     * dsh 的浮层定位是「先按锚点摆，再夹进视口」：左边界 = 锚点左边（[alignStart]）或右边界减浮层
     * 宽度，然后夹进 [margin, 窗口宽 - 浮层宽 - margin]；上下同理，上方浮层 = 锚点上边减间距减高度。
     * 统计 / 用量 / 上下文那几个面板取 12dp —— 它们贴着屏幕底边弹，夹边距决定气泡不顶到屏幕边缘。
     */
    margin: androidx.compose.ui.unit.Dp = gap,
    content: @Composable () -> Unit,
) {
    // 这个弹层锚在哪个**触发按钮**上（定位时写进来）：手指按在它里面时那一下不关弹层 ——
    // 「再点一次同一个按钮 = 收起」归按钮自己的 onClick（口径见 OverlayDismiss.kt）
    val anchor = remember { AnchorRect() }
    RegisterOverlayDismiss(onDismiss, anchor = { anchor.value })
    val density = LocalDensity.current
    val lift = with(density) { liftBottom.roundToPx() }
    val shadowPad = POPUP_SHADOW_PAD
    val padPx = with(density) { shadowPad.roundToPx() }
    val provider = remember(density, alignStart, below, lift, gap, margin, padPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                // 触发按钮的矩形（窗口坐标）——「点另一个胶囊直接切过去」要用它
                anchor.value = anchorBounds
                val gapPx = with(density) { gap.roundToPx() }
                val marginPx = with(density) { margin.roundToPx() }
                // 内容外面还包了 [POPUP_SHADOW_PAD] 的一圈透明留白（给 dsh 那两层淡光晕留绘制空间，
                // 弹窗窗口按内容尺寸裁剪）。定位算的是**卡片**那一块，所以先把留白扣掉、
                // 最后再把整块内容平移回去 —— 卡片落点与没有留白时逐像素相同。
                val width = (popupContentSize.width - padPx * 2).coerceAtLeast(0)
                val height = (popupContentSize.height - padPx * 2).coerceAtLeast(0)
                val x = if (alignStart) anchorBounds.left else anchorBounds.right - width
                val y = if (below) {
                    anchorBounds.bottom + gapPx
                } else {
                    anchorBounds.top - height - gapPx - lift
                }
                // 宽 / 高还没测出来（=0）时不夹 —— 与 dsh 的 if (width > 0) 同一条
                val clampedX = if (width > 0) minOf(maxOf(x, marginPx), windowSize.width - width - marginPx) else x
                val clampedY = if (height > 0) minOf(maxOf(y, marginPx), windowSize.height - height - marginPx) else y
                return IntOffset(clampedX - padPx, clampedY - padPx)
            }
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        // 不抢焦点（否则输入法会掉）之后，Popup 自己也会监听「外部触摸」来关闭：
        // 打开菜单的那一下点击正好在弹窗外面，会被判定成外部触摸，菜单一闪就没。
        // 所以两种自动关闭都关掉，「点空白关闭」由根布局统一负责（见 OverlayDismiss.kt）。
        properties = PopupProperties(
            focusable = focusable,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            // 不夹在父窗口里：默认的弹窗窗口**不含导航栏那一条**（真机 1280x2568，屏是 1280x2772），
            // 而输入框下方那一排胶囊正好落在被切掉的那一段里 —— 定位算法按 dsh 的
            // useAnchoredPosition 把浮层「夹进视口」时，用的就是这个小窗口，于是气泡被整整顶上去
            // 33dp（实测 anchor.top 2636、窗口高 2568 → 只能摆到 2009）。关掉裁剪之后窗口就是整屏，
            // 夹取才等于 dsh 里的 window.innerWidth/innerHeight。
            clippingEnabled = false,
        ),
    ) {
        // 弹窗窗口正好按内容尺寸裁剪，而 dsh 的卡片带着两层淡光晕（--dsw-elevation-prominent）：
        // 不留这一圈，光晕整条都在窗口外、等于没画。定位那边的补偿见 provider 里的注释。
        Box(Modifier.padding(shadowPad)) { content() }
    }
}

/** 弹层内容四周给 dsh 那两层淡光晕留的透明留白（见 [LegacyDshPopup] 与 [dshProminentShadow]） */
private val POPUP_SHADOW_PAD = 12.dp

// --------------------------------------------------------------------- 同层浮层（in-tree overlay）

/** 浮层锚点：触发节点的窗口坐标。写它是 [Modifier.overlayAnchor]，读它的是定位与「点空白关闭」。 */
@Stable
class OverlayAnchorState {
    var windowRect: IntRect? by mutableStateOf(null)
        internal set
}

/** 标在**触发节点**（不是 DshPopup）上：把它的窗口矩形写进 [state]。 */
internal fun Modifier.overlayAnchor(state: OverlayAnchorState): Modifier =
    onGloballyPositioned { state.windowRect = it.boundsInWindow().toAnchorRect() }

/** 锚点窗口坐标 → 宿主局部坐标 */
internal fun overlayAnchorInHost(anchor: IntRect?, hostWindow: IntOffset): IntRect? = anchor?.let {
    IntRect(it.left - hostWindow.x, it.top - hostWindow.y, it.right - hostWindow.x, it.bottom - hostWindow.y)
}

/**
 * dsh 的 useAnchoredPosition：先按锚点摆，再夹进宿主；宽/高为 0（还没量到）时不夹。
 * 与 [LegacyDshPopup] 的 `calculatePosition` 是同一套规则（定位有两个实现，改一处要改另一处）；
 * 差异只有一处：宿主不裁剪，不需要 POPUP_SHADOW_PAD。
 */
internal fun overlayPosition(
    anchor: IntRect?,
    host: IntSize,
    panel: IntSize,
    alignStart: Boolean,
    below: Boolean,
    gapPx: Int,
    liftPx: Int,
    marginPx: Int,
): IntOffset {
    if (anchor == null) return IntOffset(marginPx, marginPx)
    val x = if (alignStart) anchor.left else anchor.right - panel.width
    val y = if (below) anchor.bottom + gapPx else anchor.top - panel.height - gapPx - liftPx
    val clampedX = if (panel.width > 0) minOf(maxOf(x, marginPx), host.width - panel.width - marginPx) else x
    val clampedY = if (panel.height > 0) minOf(maxOf(y, marginPx), host.height - panel.height - marginPx) else y
    return IntOffset(clampedX, clampedY)
}

/** 一个打开中的浮层：定位参数 + 锚点 + 内容；[panelWindowRect] 由宿主布局时写回（关闭命中用） */
internal class OverlayEntry(
    val key: Any,
    val onDismiss: () -> Unit,
    val alignStart: Boolean,
    val below: Boolean,
    val liftPx: Int,
    val gapPx: Int,
    val marginPx: Int,
    val anchor: OverlayAnchorState,
    val freeze: Boolean,
    val content: @Composable () -> Unit,
) {
    var panelWindowRect: IntRect? = null
}

/** 同层浮层宿主：业务上同一时刻只有一个浮层开着；同 key 重复提交按更新处理。 */
@Stable
internal class DshOverlayHostState {
    internal val entries = mutableStateListOf<OverlayEntry>()

    internal fun put(entry: OverlayEntry) {
        val index = entries.indexOfFirst { it.key == entry.key }
        if (index >= 0) entries[index] = entry else entries += entry
    }

    internal fun remove(key: Any) {
        entries.removeAll { it.key == key }
    }
}

/** 没有宿主（Preview / 单测 / :app 里的调用点）时拿到 null → [DshPopup] 退回独立窗口 Popup */
internal val LocalOverlayHost = staticCompositionLocalOf<DshOverlayHostState?> { null }

/** 宿主可视区（dp，浮层定位的夹取边界）；Zero = 还没测量到 */
internal val LocalOverlayHostSize = staticCompositionLocalOf { DpSize.Zero }

/**
 * 宿主图层：铺满 ChatScreen 的内容区（不占布局），只把打开中的浮层画在内容层之上。
 * 放在根 Box 的最后一个孩子：能盖住顶栏 / 消息流 / 输入区，并随 IME 与导航栏一起变矮。
 */
@Composable
internal fun DshOverlayLayer(host: DshOverlayHostState) {
    var hostWindow by remember { mutableStateOf(IntOffset.Zero) }
    var hostSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                hostSize = it.size
                val origin = it.localToWindow(Offset.Zero)
                hostWindow = IntOffset(origin.x.roundToInt(), origin.y.roundToInt())
            },
    ) {
        if (hostSize.width > 0 && hostSize.height > 0) {
            CompositionLocalProvider(
                LocalOverlayHostSize provides with(LocalDensity.current) {
                    DpSize(hostSize.width.toDp(), hostSize.height.toDp())
                },
            ) {
                host.entries.forEach { entry ->
                    key(entry.key) { OverlayPanel(entry, hostWindow, hostSize) }
                }
            }
        }
    }
}

/** 一个浮层：量内容（约束 = 宿主减两侧 margin）→ 按锚点算位置 → place；不参与父布局测量。 */
@Composable
private fun OverlayPanel(entry: OverlayEntry, hostWindow: IntOffset, hostSize: IntSize) {
    // 轮尾两处：打开那一帧冻结矩形，之后**列表滚动**时不再跟随。
    // 但宿主尺寸 / 窗口位置一变（键盘弹起、收起）就要重新取一次：冻结是为了不跟滚动，
    // 不是为了让卡片跟指着它的那一行分家 —— 键盘一收消息整体下移，旧矩形会让卡片留在原地。
    val frozenAnchor = remember(entry.key, hostWindow, hostSize) {
        if (entry.freeze) entry.anchor.windowRect else null
    }
    SubcomposeLayout { constraints ->
        val anchorWindow = if (entry.freeze) frozenAnchor else entry.anchor.windowRect
        val maxWidth = (hostSize.width - entry.marginPx * 2).coerceAtLeast(0)
        val maxHeight = (hostSize.height - entry.marginPx * 2).coerceAtLeast(0)
        val panel = subcompose(Unit) { entry.content() }
            .firstOrNull()
            ?.measure(Constraints(maxWidth = maxWidth, maxHeight = maxHeight))
        if (panel == null) {
            layout(constraints.maxWidth, constraints.maxHeight) {}
        } else {
            val position = overlayPosition(
                anchor = overlayAnchorInHost(anchorWindow, hostWindow),
                host = IntSize(constraints.maxWidth, constraints.maxHeight),
                panel = IntSize(panel.width, panel.height),
                alignStart = entry.alignStart,
                below = entry.below,
                gapPx = entry.gapPx,
                liftPx = entry.liftPx,
                marginPx = entry.marginPx,
            )
            entry.panelWindowRect = IntRect(
                position.x + hostWindow.x,
                position.y + hostWindow.y,
                position.x + hostWindow.x + panel.width,
                position.y + hostWindow.y + panel.height,
            )
            layout(constraints.maxWidth, constraints.maxHeight) { panel.place(position.x, position.y) }
        }
    }
}

/**
 * dsh 的浮层入口。ChatScreen 根部的同层宿主存在时画成 in-tree overlay（[DshMenuCard] 铺在内容层
 * 之上）；否则退回独立窗口 Popup（:app 里的调用点 / Preview / 单测）。
 *
 * 锚点由调用方在触发节点上用 [Modifier.overlayAnchor] 标出；[freezeAnchor]=true 时打开那一帧
 * 冻结矩形（轮尾两处：滚动时面板不跟着锚点跑）。
 * focusable 只在独立窗口 Popup 路径有意义：in-tree 浮层拿不到独立窗口焦点，输入法不会被收起。
 */
@Composable
fun DshPopup(
    onDismiss: () -> Unit,
    alignStart: Boolean = false,
    below: Boolean = false,
    focusable: Boolean = false,
    liftBottom: Dp = 0.dp,
    gap: Dp = 8.dp,
    margin: Dp = gap,
    anchor: OverlayAnchorState? = null,
    freezeAnchor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val host = LocalOverlayHost.current
    if (host == null || anchor == null) {
        LegacyDshPopup(onDismiss, alignStart, below, focusable, liftBottom, gap, margin, content)
        return
    }
    val density = LocalDensity.current
    val key = remember { Any() }
    val entry = OverlayEntry(
        key = key,
        onDismiss = onDismiss,
        alignStart = alignStart,
        below = below,
        liftPx = with(density) { liftBottom.roundToPx() },
        gapPx = with(density) { gap.roundToPx() },
        marginPx = with(density) { margin.roundToPx() },
        anchor = anchor,
        freeze = freezeAnchor,
        content = content,
    )
    SideEffect { host.put(entry) }
    // 关闭语义不变：仍在 ui:blocks 的全局登记处登记；命中区 = 触发节点 ∪ 浮层卡片本身
    RegisterOverlayDismiss(onDismiss, anchor = { unionAnchor(anchor.windowRect, entry.panelWindowRect) })
    DisposableEffect(key) { onDispose { host.remove(key) } }
}
