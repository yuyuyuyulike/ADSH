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
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 「点别处即关闭」的全局登记处。
 *
 * 为什么需要它：dsh 的菜单是**非焦点**浮层（打开菜单时键盘必须留在输入框上，抢焦点会让
 * 输入法收起、拼音组合区丢失），而 Compose 的 `Popup(focusable = false)` 根本收不到
 * 「外部点击」——`dismissOnClickOutside` 只在可获焦的弹窗上生效。所以在此之前每一处弹层都得
 * 自己铺一层拦截层，结果就是有的地方铺了（消息区）、有的地方忘了（轮尾的用量 / 用时、
 * 设置页的四个下拉），用户点到别处弹窗不关。
 *
 * 现在的做法只有一条规则：**弹层打开期间，一次「按下」就关掉所有弹层**。
 *  - 登记：`DshPopup` 一组合就登记自己（弹窗只在打开时才在组合里），所以调用处一行都不用写；
 *  - 观察：根布局（[AppRoot]）挂一个 [dismissOverlaysOnPress]，在 Initial 阶段看一眼按下事件；
 *  - 不消费按下本身（否则滑动、拖拽都会被吃掉），但**吃掉那一次「抬起」**——
 *    关闭弹层的那一下不应该同时按到下面的按钮。少了这一步，「点触发按钮」会在关掉弹窗的
 *    同一帧又被它自己的 toggle 打开（看起来像没反应）；而滚动、拖拽（位移超过 touch slop）
 *    照常放行，弹层只是在这一下关闭。
 */
@Stable
class OverlayDismissRegistry {
    private val handlers = mutableStateMapOf<Any, () -> Unit>()

    /** 当前有没有弹层打开（没有的话根布局连手势都不用看） */
    val active: Boolean get() = handlers.isNotEmpty()

    fun register(key: Any, dismiss: () -> Unit) {
        handlers[key] = dismiss
    }

    fun unregister(key: Any) {
        handlers.remove(key)
    }

    /** 关闭全部弹层（先复制一份：关闭过程中会有人注销自己） */
    fun dismissAll() {
        if (handlers.isEmpty()) return
        handlers.values.toList().forEach { runCatching { it() } }
    }
}

/** 没有 [OverlayDismissHost] 时（预览 / 单测）拿到 null：弹层照常工作，只是不参与全局关闭 */
val LocalOverlayDismiss = staticCompositionLocalOf<OverlayDismissRegistry?> { null }

/**
 * 根布局上挂的一次手势观察：详见 [OverlayDismissRegistry]。
 *
 * 不消费「按下」——它只是**看**一眼，事件照常流向下面被点中的控件；只有确认这次手势是
 * 一次「点击」（位移没超过 touch slop）时，才把抬起事件吃掉，避免关闭弹层的那一下又触发
 * 下面的按钮。
 */
fun Modifier.dismissOverlaysOnPress(registry: OverlayDismissRegistry): Modifier =
    pointerInput(registry) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
            // 这一下在更外层就已经被消费掉了（边缘防误触摘掉的手指）：它不是一次「点击」，
            // 弹层不该跟着关（不然贴着边缘蹭一下就把菜单收了）
            if (down.isConsumed) return@awaitEachGesture
            if (!registry.active) return@awaitEachGesture
            registry.dismissAll()
            var dragged = false
            val slop = viewConfiguration.touchSlop * viewConfiguration.touchSlop
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!dragged) {
                    val dx: Float = change.position.x - down.position.x
                    val dy: Float = change.position.y - down.position.y
                    if (dx * dx + dy * dy > slop) dragged = true
                }
                if (change.changedToUpIgnoreConsumed()) {
                    if (!dragged) change.consume()
                    break
                }
                if (!change.pressed) break
            }
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
 */
@Composable
fun RegisterOverlayDismiss(onDismiss: () -> Unit) {
    val registry = LocalOverlayDismiss.current ?: return
    val current by rememberUpdatedState(onDismiss)
    val key = remember { Any() }
    DisposableEffect(registry, key) {
        registry.register(key) { current() }
        onDispose { registry.unregister(key) }
    }
}
