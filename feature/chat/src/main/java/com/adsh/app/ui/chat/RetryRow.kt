package com.adsh.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.blocks.DshTextShimmer
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.kit.dshInteraction
import com.adsh.app.ui.theme.LocalDshPalette

/**
 * dsh 的 model-retry 行（MessageItem.tsx 的 RetryNodeView + MessageItem.module.css）：
 * **会话流里的一行**，不是输入框边上的连接条。
 *
 * - summary：一行状态文本（等待中带流光），13sp / 行高 20sp / label-tertiary，
 *   行尾 6dp 的 chevron（1.5dp 描边、-45°→45°、120ms），点一下展开 / 收起；
 * - 展开体：两行「重试延迟：{n}毫秒」「失败原因：{message}」，13sp / 18sp、标签 label-secondary；
 * - 等待中显示**实时倒计时**（锚定首次渲染的墙钟，250ms 轮询、下限 1 秒），其余态显示静态秒数；
 * - 默认折叠；展开 / 收起是读者动作（交出自动跟随）。
 */
@Composable
internal fun RetryRow(
    info: RetryInfo,
    turnClosed: Boolean,
    modifier: Modifier = Modifier,
    onReaderAction: (Boolean) -> Unit = {},
) {
    val palette = LocalDshPalette.current
    var expanded by remember { mutableStateOf(false) }
    val phase = retryPhase(info, turnClosed)
    val active = phase == RetryPhase.Active
    // dsh 的倒计时：deadline = **这一行首次渲染**的墙钟 + delayMs（不是事件时间），
    // 250ms 轮询、到 1 秒停表；非 active 态用静态 ceil(delayMs)。
    var seconds by remember(info.retry, info.delayMs, active) {
        mutableIntStateOf(if (active) retrySeconds(info.delayMs) else retryStaticSeconds(info))
    }
    if (active) {
        LaunchedEffect(info.retry, info.delayMs) {
            val deadline = System.currentTimeMillis() + info.delayMs
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                seconds = retrySeconds(remaining)
                if (remaining <= 1000L) break
                kotlinx.coroutines.delay(250L)
            }
        }
    }
    val status = retryStatusText(info, phase, seconds)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .dshClickable(
                    interactionSource = dshInteraction(),
                    onClickLabel = if (expanded) "收起重试详情" else "展开重试详情",
                ) {
                    expanded = !expanded
                    onReaderAction(expanded)
                }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            DshTextShimmer(active = active) {
                Text(
                    text = status,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = palette.labelTertiary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            RetryChevron(expanded)
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 14.dp, top = 3.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                RetryDetailLine(retryDelayText(info))
                RetryDetailLine(retryFailureText(info))
            }
        }
    }
}

/** dsh 的 details 展开体一行：标签 label-secondary、值 label-tertiary（同一行） */
@Composable
private fun RetryDetailLine(line: String) {
    val palette = LocalDshPalette.current
    Text(
        text = retryDetailText(line, palette.labelSecondary, palette.labelTertiary),
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
}

/** 「重试延迟：2500毫秒」这类整行 → 标签着色、值随整行颜色（dsh 的 span + 文本同形） */
private fun retryDetailText(line: String, labelColor: Color, valueColor: Color) = buildAnnotatedString {
    val separator = line.indexOf('：')
    if (separator < 0) {
        withStyle(SpanStyle(color = valueColor)) { append(line) }
    } else {
        withStyle(SpanStyle(color = labelColor)) { append(line.substring(0, separator + 1)) }
        withStyle(SpanStyle(color = valueColor)) { append(line.substring(separator + 1)) }
    }
}

/** dsh 的 summary::after：6×6、右边 + 下边各 1.5px、-45°（收）/ 45°（开） */
@Composable
private fun RetryChevron(expanded: Boolean) {
    val palette = LocalDshPalette.current
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else -45f,
        animationSpec = tween(120),
        label = "retryChevron",
    )
    Box(Modifier.padding(end = 2.dp)) {
        Canvas(Modifier.size(6.dp).rotate(rotation)) {
            val stroke = 1.5.dp.toPx()
            val color = palette.labelTertiary
            drawLine(
                color = color,
                start = Offset(size.width - stroke / 2f, 0f),
                end = Offset(size.width - stroke / 2f, size.height - stroke / 2f),
                strokeWidth = stroke,
            )
            drawLine(
                color = color,
                start = Offset(0f, size.height - stroke / 2f),
                end = Offset(size.width, size.height - stroke / 2f),
                strokeWidth = stroke,
            )
        }
    }
}
