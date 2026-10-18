package com.adsh.app.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * 「无涟漪点击」：每处可点区都要重复三件事（无涟漪、一个 interaction source、可选的 enabled）。
 *
 * 为什么要包一层（不只是少写几行）：
 *  - **可发现**：项目约定「可点区都不带涟漪」，入口只有这一份，新写的组件用它；
 *  - **可修正**：涟漪、按下态、无障碍语义要动就只动这一处；
 *  - **挡住一类写法错误**：把 `interactionSource = remember { MutableInteractionSource() }` 写在
 *    调用参数里时，那个 remember 的调用点其实在**被调用函数**那一层，而 clickable 是普通函数，
 *    于是同一个调用点在不同组合位置会各拿一个 source —— 与「组件自己持有 source」不是一回事。
 *    改走 [rememberDshInteraction] / [dshInteraction] 这两个入口，建 source 就只能是 remember 出来的。
 *
 * 参数顺序与默认值与 `Modifier.clickable` **逐字一致**（enabled / onClickLabel / role 同口径）：
 * 视觉与行为都不该变。
 */
fun Modifier.dshClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = clickable(
    enabled = enabled,
    onClickLabel = onClickLabel,
    role = role,
    interactionSource = interactionSource,
    indication = null,
    onClick = onClick,
)

/** 组件自己持有一个 interaction source（要读按下态 `collectIsPressedAsState`，或要在多处共用时选它） */
@Composable
fun rememberDshInteraction(): MutableInteractionSource = remember { MutableInteractionSource() }

/** 纯点击区、不关心按下态的一次性来源（等价于把 `remember { MutableInteractionSource() }` 写在调用点） */
@Composable
fun dshInteraction(): MutableInteractionSource = rememberDshInteraction()
