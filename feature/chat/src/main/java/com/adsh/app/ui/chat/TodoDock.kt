package com.adsh.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.tools.TodoItem
import com.adsh.app.core.tools.TodoStore
import com.adsh.app.ui.blocks.DshDockIcons
import com.adsh.app.ui.blocks.DshIconButton
import com.adsh.app.ui.blocks.DshSidebarIcons
import com.adsh.app.ui.blocks.dshProminentShadow
import com.adsh.app.ui.theme.LocalDshPalette
import com.adsh.app.ui.kit.DshSpacing
import com.adsh.app.ui.kit.DshSettingIcons
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.kit.dshInteraction

// ------------------------------------------------------------------ 任务 dock（dsh 的 TodoPanel）

/** dsh 的 CompletedGlyph 里的对勾（figma 14x14 画板） */
private const val TODO_CHECK_PATH =
    "M10.9631 5.71411L7.70154 8.97571C7.48011 9.19714 7.27736 9.40099 7.09229 9.54993C6.89742 9.70669 " +
        "6.66314 9.85279 6.3634 9.90027C6.2049 9.92534 6.04339 9.92534 5.88489 9.90027C5.58515 9.85279 " +
        "5.35087 9.70669 5.15601 9.54993C4.97093 9.40099 4.76818 9.19714 4.54675 8.97571L3.03516 7.46411L3.96313 " +
        "6.53613L5.47473 8.04773C5.7169 8.28989 5.86196 8.43389 5.97888 8.52795C6.08597 8.61409 6.10875 " +
        "8.60701 6.08997 8.604C6.11259 8.60758 6.13571 8.60758 6.15833 8.604C6.13954 8.60701 6.16232 " +
        "8.61409 6.26941 8.52795C6.38633 8.43389 6.53139 8.28989 6.77356 8.04773L10.0352 4.78613L10.9631 5.71411Z"

/** dsh 的 composer 侧留白 16 + dock inset 8×2：浮岛每侧比输入卡窄 16 */
internal val TodoDockPageInset = DshSpacing.Card * 2

/** dsh TodoPanel 的 --dsw-radius-lg = 16 */
internal val TodoDockRadius = 16.dp

/** dsh TodoPanel 的 .body padding 6 / 12 与 .header height 36 */
internal val TodoDockBodyPaddingVertical = 6.dp
internal val TodoDockBodyPaddingHorizontal = 12.dp
internal val TodoDockHeaderHeight = 36.dp

/**
 * 输入框上方的任务横窗，dsh 的 TodoPanel（skeleton/TodoPanel）：
 *
 *  - .root 比输入卡每侧窄 16（composer 侧留白 16 + dock inset 8×2）、--dsw-radius-lg=16、
 *    menu 面（弹层取不到身后内容 → 合成色 [com.adsh.app.ui.theme.DshPalette.menuSurface]）、
 *    border-l1 hairline + [dshProminentShadow] 光晕；
 *  - .body padding 6/12、gap 8；.header 高 36、gap 10：清单图标 + 「任务」13/500/24 + 进度 13/400 三级色 + 折叠箭头；
 *  - 进度是「N 已完成 / N 进行中 / N 待处理」中非零项用「 · 」连接（dsh 的 progressLabel）；
 *  - .list max-height 180、gap 8：状态字形 + 13/20 正文，单行省略；默认收起。
 *
 * 比 dsh 多一个「清除」按钮（用户要求）：dsh 的任务面板只能等模型下一次 todo 调用才消失。
 * 清单是**会话级**的（dsh 的 session projection「todos」，见 [TodoStore]）。
 */
@Composable
internal fun TodoDock(conversationId: Long?, todos: List<TodoItem>) {
    if (conversationId == null) return
    val palette = LocalDshPalette.current
    if (todos.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val done = todos.count { it.status == "completed" }
    val active = todos.count { it.status == "in_progress" }
    val pending = todos.size - done - active
    val progress = buildList {
        if (done > 0) add(done.toString() + " 已完成")
        if (active > 0) add(active.toString() + " 进行中")
        if (pending > 0) add(pending.toString() + " 待处理")
    }.joinToString(" · ")

    val shape = RoundedCornerShape(TodoDockRadius)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TodoDockPageInset)
            .padding(bottom = DshSpacing.Lg)
            .dshProminentShadow(shape)
            .clip(shape)
            .background(palette.menuSurface)
            .border(DshSpacing.Hairline, palette.borderL1, shape)
            .padding(horizontal = TodoDockBodyPaddingHorizontal, vertical = TodoDockBodyPaddingVertical),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(TodoDockHeaderHeight)
                    .clip(RoundedCornerShape(DshSpacing.Xl))
                    .dshClickable(interactionSource = dshInteraction()) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxl),
            ) {
                Icon(
                    imageVector = DshDockIcons.Checklist,
                    contentDescription = null,
                    tint = palette.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Text("任务", fontSize = 13.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = palette.labelPrimary)
                Text(
                    text = progress,
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = palette.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (expanded) DshSettingIcons.ChevronDown else DshSettingIcons.ChevronUp,
                    contentDescription = null,
                    tint = palette.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
            }
            DshIconButton(
                icon = DshSidebarIcons.Trash,
                description = "清除任务",
                onClick = { TodoStore.clear(conversationId) },
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
            ) {
                todos.forEach { todo ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxl),
                    ) {
                        TodoStatusGlyph(todo.status, Modifier.size(16.dp))
                        Text(
                            text = todo.content,
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = palette.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * dsh 的三种状态字形（14x14 画板，外面套 16x16 的格子）：
 *  - completed：1.2 描边的圆 + 对勾（--dsw-alias-state-success-primary）
 *  - in_progress：线性渐变（currentColor → 透明）的圆环，1s 匀速自转
 *  - pending：2.4/2.4 虚线圆（--dsw-alias-label-caption）
 */
@Composable
private fun TodoStatusGlyph(status: String, modifier: Modifier = Modifier) {
    val palette = LocalDshPalette.current
    val check = remember { PathParser().parsePathString(TODO_CHECK_PATH).toPath() }
    val transition = rememberInfiniteTransition(label = "todo-progress")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing)),
        label = "todo-progress-angle",
    )
    Canvas(modifier.padding(DshSpacing.Xxs)) {
        val unit = size.minDimension / 14f
        val center = Offset(this.center.x, this.center.y)
        val radius = 6.4f * unit
        val stroke = Stroke(width = 1.2f * unit)
        when (status) {
            "completed" -> {
                drawCircle(color = palette.success, radius = radius, center = center, style = stroke)
                withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
                    drawPath(check, color = palette.success)
                }
            }
            "in_progress" -> rotate(angle, pivot = center) {
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(palette.business, palette.business.copy(alpha = 0f)),
                        start = Offset(2.5f * unit, 12f * unit),
                        end = Offset(10.5f * unit, 3.5f * unit),
                    ),
                    radius = radius,
                    center = center,
                    style = stroke,
                )
            }
            else -> drawCircle(
                color = palette.labelCaption,
                radius = radius,
                center = center,
                style = Stroke(
                    width = 1.2f * unit,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.4f * unit, 2.4f * unit)),
                ),
            )
        }
    }
}
