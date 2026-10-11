package com.adsh.app.ui

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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
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

/**
 * 「点别处即关闭」的全局登记处。
 *
 * 为什么需要它：dsh 的菜单是**非焦点**浮层（打开菜单时键盘必须留在输入框上，抢焦点会让
 * 输入法收起、拼音组合区丢失），而 Compose 的 `Popup(focusable = false)` 根本收不到
 * 「外部点击」——`dismissOnClickOutside` 只在可获焦的弹窗上生效。所以在此之前每一处弹层都得
 * 自己铺一层拦截层，结果就是有的地方铺了（消息区）、有的地方忘了（轮尾的用量 / 用时、
 * 设置页的四个下拉），用户点到别处弹窗不关。
 *
 * 现在的规则只有一条，照 dsh 的 `useDismissOnOutsidePointer`（第 199 轮）：
 * **弹层打开期间，一次「按下」关掉所有「触发按钮不在这根手指下」的弹层，且不吃掉这次点击**。
 *  - 按下落在**自己的触发按钮**上（[AnchorRect]）→ 不关：那一下是「再点一次同一个按钮 = 收起」，
 *    归按钮自己处理；
 *  - 按下落在**别处**（含另一个胶囊的按钮）→ 关掉当前这张卡，并且**让这次点击照常发出去** ——
 *    于是「点另一个胶囊」直接切过去，而不是先关一次、再点第二次（用户第 199 轮的报点）；
 *  - 按下落在弹层面板里 → 收不到（弹窗是独立窗口），与 dsh 的 `portal.contains` 等价。
 *
 * 登记：`DshPopup` 一组合就登记自己（弹窗只在打开时才在组合里），调用处一行都不用写；
 * 观察：根布局（[AppRoot]）挂一个 [dismissOverlaysOnPress]，在 Initial 阶段看一眼按下事件。
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

    /** 这一次「按下」落在 ([x], [y])：关掉所有触发按钮不在这根手指下的弹层（见类注释）。 */
    fun dismissOutside(x: Float, y: Float) {
        if (handlers.isEmpty()) return
        val at = IntOffset(x.roundToInt(), y.roundToInt())
        handlers.values.toList().forEach { entry ->
            if (entry.anchor()?.contains(at) == true) return@forEach
            runCatching { entry.dismiss() }
        }
    }
}

/** 没有 [OverlayDismissHost] 时（预览 / 单测）拿到 null：弹层照常工作，只是不参与全局关闭 */
val LocalOverlayDismiss = staticCompositionLocalOf<OverlayDismissRegistry?> { null }

/**
 * 根布局上挂的一次手势观察：详见 [OverlayDismissRegistry]。
 *
 * 只看「按下」这一下（Initial 阶段，不消费它），随后**立刻放手**：抬起事件照常落到被点中的
 * 控件上 —— dsh 的 outside-pointer 关闭只关浮层、不吃点击，少了这一条就回到「必须先关掉卡片
 * 才能点开另一个胶囊」。
 */
fun Modifier.dismissOverlaysOnPress(registry: OverlayDismissRegistry): Modifier =
    pointerInput(registry) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
            // 这一下在更外层就已经被消费掉了（边缘防误触摘掉的手指）：它不是一次「点击」，
            // 弹层不该跟着关（不然贴着边缘蹭一下就把菜单收了）
            if (down.isConsumed) return@awaitEachGesture
            registry.dismissOutside(down.position.x, down.position.y)
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
