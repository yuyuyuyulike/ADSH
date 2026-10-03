package com.adsh.app.ui

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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.tools.TodoItem
import com.adsh.app.core.tools.TodoStore
import com.adsh.app.ui.theme.LocalDshPalette

// ------------------------------------------------------------------ 任务 dock（dsh 的 TodoPanel）

/** dsh 的 CompletedGlyph 里的对勾（figma 14x14 画板） */
private const val TODO_CHECK_PATH =
    "M10.9631 5.71411L7.70154 8.97571C7.48011 9.19714 7.27736 9.40099 7.09229 9.54993C6.89742 9.70669 " +
        "6.66314 9.85279 6.3634 9.90027C6.2049 9.92534 6.04339 9.92534 5.88489 9.90027C5.58515 9.85279 " +
        "5.35087 9.70669 5.15601 9.54993C4.97093 9.40099 4.76818 9.19714 4.54675 8.97571L3.03516 7.46411L3.96313 " +
        "6.53613L5.47473 8.04773C5.7169 8.28989 5.86196 8.43389 5.97888 8.52795C6.08597 8.61409 6.10875 " +
        "8.60701 6.08997 8.604C6.11259 8.60758 6.13571 8.60758 6.15833 8.604C6.13954 8.60701 6.16232 " +
        "8.61409 6.26941 8.52795C6.38633 8.43389 6.53139 8.28989 6.77356 8.04773L10.0352 4.78613L10.9631 5.71411Z"

/**
 * 输入框上方的任务横窗，逐项对齐 dsh 的 TodoPanel（dsh-client-ui-conversation 的 skeleton/TodoPanel）：
 *
 *  - .root{border .5px border-l1;background:--dsw-specific-tip;border-radius:12px;overflow:hidden}
 *  - .body{flex column;gap:8px;padding:6px 12px}
 *  - .header{height:36px;padding:4px 12px;gap:10px}：清单图标 + 「任务」13/500/24 + 进度 13/400 三级色 + 折叠箭头
 *  - 进度是「N 已完成 / N 进行中 / N 待处理」中非零项用「 · 」连接（dsh 的 progressLabel）
 *  - .list{max-height:180px;gap:8px}：14x14 状态字形 + 13/20 正文，单行省略
 *  - 默认收起（dsh 的 collapsed 初值就是 true）
 *
 * 比 dsh 多一个「清除」按钮（用户要求）：dsh 的任务面板只能等模型下一次 todo 调用才消失。
 *
 * 清单是**会话级**的（dsh 的 session projection「todos」，见 [TodoStore]）：它只在自己的会话里
 * 显示 —— 旧实现是全局单例，换个会话还能看到上一个会话的任务。
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.Xxl)
            .padding(bottom = DshSpacing.Lg)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.tip)
            .border(0.5.dp, palette.borderL1, RoundedCornerShape(12.dp))
            // 收起时整条与目标横窗一样高（36dp）：dsh 的 body 上下各 6px 内边距，
            // 手机上比目标条高一截，所以纵向内边距去掉，展开时再给列表补 6dp
            .padding(horizontal = DshSpacing.Xxxl),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .dshClickable(interactionSource = dshInteraction()) { expanded = !expanded }
                    .padding(horizontal = DshSpacing.Xxxl, vertical = DshSpacing.Md),
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
                    .padding(bottom = DshSpacing.Lg)
                    .heightIn(max = 180.dp)
                    // 卡片滚到头之后剩下的位移/惯性留在卡片里（第 115 轮，见 ScrollEdgeEater）
                    .nestedScroll(ScrollEdgeEater)
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

