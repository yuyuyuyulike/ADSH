package com.adsh.app.ui.blocks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 弹层锚点（**触发按钮**）的矩形，由 `DshPopup` 定位时写入。
 *
 * 普通可变字段而不是 State：它只在测量/定位时写、只在「按下」那一下读，不参与重组。
 * 坐标是**窗口坐标**（Popup 的 anchorBounds 就是窗口坐标），而根布局铺满整个窗口，
 * 于是它与 [dismissOverlaysOnPress] 拿到的按下位置在同一坐标系里。
 */
class AnchorRect {
    var value: IntRect? = null
}

/** 窗口坐标的矩形（[boundsInWindow]）→ 锚点要的整数矩形：取整只影响边界那一两个像素的判定。 */
fun Rect.toAnchorRect(): IntRect =
    IntRect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

/**
 * 几块锚点区域的**并集**：只要按下落在其中任意一块里，这一次按下就不关弹层。
 *
 * 指令面板要的是「输入框那一摞 + 浮在它上面的面板」两块 —— 前者是输入框那一摞的测量矩形，
 * 后者是面板自己报的矩形（它不占布局，画在那一摞上方，因此**不在**前者的矩形里）。
 * 两块各自都是确定的测量，拼起来才既盖住面板里的行、又盖住「＋」那颗按钮。
 */
fun unionAnchor(vararg rects: IntRect?): IntRect? {
    var out: IntRect? = null
    rects.forEach { rect ->
        if (rect == null) return@forEach
        val merged = out
        out = if (merged == null) {
            rect
        } else {
            IntRect(
                left = minOf(merged.left, rect.left),
                top = minOf(merged.top, rect.top),
                right = maxOf(merged.right, rect.right),
                bottom = maxOf(merged.bottom, rect.bottom),
            )
        }
    }
    return out
}

/**
 * 「点别处即关闭」的全局登记处。dsh 的菜单是**非焦点**浮层（键盘必须留在输入框上），而 Compose 的
 * `Popup(focusable = false)` 收不到「外部点击」—— `dismissOnClickOutside` 只在可获焦的弹窗上生效。
 * 规则只有一条：**弹层打开期间，一次「按下」关掉所有「触发按钮不在这根手指下」的弹层，且不吃掉这次点击**。
 */
@Stable
class OverlayDismissRegistry {
    private class Entry(val dismiss: () -> Unit, val anchor: () -> IntRect?)

    private val handlers = mutableStateMapOf<Any, Entry>()

    /** 当前有没有弹层打开（没有的话根布局连手势都不用看） */
    val active: Boolean get() = handlers.isNotEmpty()

    fun register(key: Any, dismiss: () -> Unit, anchor: () -> IntRect? = { null }) {
        handlers[key] = Entry(dismiss, anchor)
    }

    fun unregister(key: Any) {
        handlers.remove(key)
    }

    /** 关闭全部弹层（先复制一份：关闭过程中会有人注销自己） */
    fun dismissAll() {
        if (handlers.isEmpty()) return
        handlers.values.toList().forEach { runCatching { it.dismiss() } }
    }

    /**
     * 这一次「按下」落在 ([x], [y])：关掉所有触发按钮不在这根手指下的弹层（见类注释）。
     *
     * [tolerancePx] 是手指的容差：按下点离锚点这么近也算按在触发按钮上。手指按下去是一个面而不是
     * 一个点，点小胶囊时边缘常差几个像素；没有容差就会把「想点胶囊」判成「点别处」——卡片先被关掉，
     * 紧接着又被这一下的点击打开，看着就是闪一下。
     */
    fun dismissOutside(x: Float, y: Float, tolerancePx: Float = 0f) {
        if (handlers.isEmpty()) return
        val at = IntOffset(x.roundToInt(), y.roundToInt())
        handlers.values.toList().forEach { entry ->
            if (entry.anchor()?.holds(at, tolerancePx) == true) return@forEach
            runCatching { entry.dismiss() }
        }
    }
}

/** 没有 [OverlayDismissHost] 时（预览 / 单测）拿到 null：弹层照常工作，只是不参与全局关闭 */
val LocalOverlayDismiss = staticCompositionLocalOf<OverlayDismissRegistry?> { null }

/**
 * 按下点 [at] 是否落在锚点矩形上：[tolerancePx] = 0 时就是 [IntRect.contains]（右下边开区间），
 * 否则把矩形向外扩这么多像素再判。容差的语义见 [OverlayDismissRegistry.dismissOutside]。
 */
internal fun IntRect.holds(at: IntOffset, tolerancePx: Float = 0f): Boolean =
    if (tolerancePx <= 0f) {
        contains(at)
    } else {
        at.x >= left - tolerancePx && at.x <= right + tolerancePx &&
            at.y >= top - tolerancePx && at.y <= bottom + tolerancePx
    }

/**
 * 点胶囊的容差（手指的「面」）：按下点落在这个范围里算按在胶囊上，弹层不关。
 *
 * 这个值只影响「点空白关闭」的判定，**不改胶囊的可点区域、也不改它的尺寸** ——
 * 胶囊本身仍按原来的矩形响应点击。
 */
val OVERLAY_DISMISS_TOLERANCE = 8.dp

/**
 * 根布局上挂的一次手势观察：详见 [OverlayDismissRegistry]。
 *
 * 只看「按下」这一下（Initial 阶段，不消费它），随后**立刻放手**：抬起事件照常落到被点中的
 * 控件上 —— dsh 的 outside-pointer 关闭只关浮层、不吃点击，少了这一条就回到「必须先关掉卡片
 * 才能点开另一个胶囊」。
 *
 * @param tolerancePx 手指容差（px），见 [OVERLAY_DISMISS_TOLERANCE]
 */
fun Modifier.dismissOverlaysOnPress(
    registry: OverlayDismissRegistry,
    tolerancePx: Float = 0f,
): Modifier =
    pointerInput(registry, tolerancePx) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
            // 这一下在更外层就已经被消费掉了（边缘防误触摘掉的手指）：它不是一次「点击」，
            // 弹层不该跟着关（不然贴着边缘蹭一下就把菜单收了）
            if (down.isConsumed) return@awaitEachGesture
            registry.dismissOutside(down.position.x, down.position.y, tolerancePx)
        }
    }

/**
 * 登记处宿主：把 [registry] 放进组合，并在**所有子节点之后**注册返回键处理
 * （Compose 的返回键回调是「最后登记的赢」，所以弹层优先于抽屉 / 覆盖页 / 输入面板）。
 */
@Composable
fun OverlayDismissHost(registry: OverlayDismissRegistry, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverlayDismiss provides registry) {
        content()
        BackHandler(enabled = registry.active) { registry.dismissAll() }
    }
}

/**
 * 弹层自己在打开期间登记进 [LocalOverlayDismiss]。返回的 [DisposableEffect] 不可用时是空操作。
 *
 * 用 `rememberUpdatedState` 收住回调：`onDismiss` 每次重组都是新 lambda，直接拿它当 key
 * 会每帧注销再登记。
 *
 * @param anchor 这个弹层锚在哪个触发按钮上（[AnchorRect]）；按下落在里面时这一下不关弹层。
 */
@Composable
fun RegisterOverlayDismiss(onDismiss: () -> Unit, anchor: () -> IntRect? = { null }) {
    val registry = LocalOverlayDismiss.current ?: return
    val current by rememberUpdatedState(onDismiss)
    val currentAnchor by rememberUpdatedState(anchor)
    val key = remember { Any() }
    DisposableEffect(registry, key) {
        registry.register(key, { current() }, { currentAnchor() })
        onDispose { registry.unregister(key) }
    }
}
