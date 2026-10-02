package com.adsh.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette

/*
 * bash 的展开体 —— 对齐 dsh 的 TerminalBlock（ui-primitives/src/TerminalBlock.tsx，
 * 聊天行的 .terminalBody 变体：小一号代码字体 / 行高 18 / 输出区上限 224px）。
 *
 * 结构（第 87 轮用户点名重做，之前是「命令原文整段 + 输出」两段都塞在一张 150dp 的卡里，
 * 命令一长输出就只剩一条缝）：
 *  - 横幅：左侧 30dp 沟槽里一枚运行状态点（StateDot：跑动=旋转弧、干净收尾=绿点、
 *    失败/非零退出=红点）+ `$` 提示符 + **单行省略**的命令 + 退出状态胶囊 + 复制按钮；
 *  - 横幅下面 0.5px 分隔线（有正文才画，dsh 的 .block[data-body] .header）；
 *  - 其余全是输出区：等宽、可滚、上限 224dp；跑动且还没输出时只有横幅（dsh 的 body 判据）。
 *
 * 与 dsh 的两处手机侧偏离：输出**折行**（dsh 是 pre + 横向滚动，竖屏上横滚既难滑又和根层的
 * 抽屉手势抢横向拖动）；命令多行时并成一行再省略（用户点名「执行的命令只有一行，多的省略掉」，
 * dsh 是一行一个提示符行）。
 */

/** dsh 聊天行终端卡的输出区上限（--dsl-terminal-output-max-height: 224px） */
private val TERMINAL_OUTPUT_MAX_HEIGHT = 224.dp

/** renderBash 追加在输出末尾的状态标记（Tools.kt）：只有**最后一行**才算数，正文里碰巧同形的文本不误判 */
private val EXIT_CODE_LINE = Regex("\\[exit code: (-?\\d+)]")
private val TIMEOUT_LINE = Regex("\\[timed out after \\d+ms]")

/** 收尾状态：胶囊文案（null = 干净收尾不画）+ 是否算失败（决定状态点红/绿） */
private data class TerminalSettle(val pill: String?, val failed: Boolean)

/**
 * 从输出文本的最后一行还原 dsh 的 statusText/runState 判据（ADSH 的工具行没有结果 meta，
 * 退出码只活在 renderBash 追加的标记里）：超时 = 「未正常退出」（dsh 的 exitCode null 档）、
 * 非零退出 = 「退出码 N」、工具级报错（isError，如沙箱拒绝）只红点不胶囊。
 */
private fun terminalSettleOf(output: String?, running: Boolean, error: Boolean): TerminalSettle {
    if (running) return TerminalSettle(null, false)
    if (error) return TerminalSettle(null, true)
    val lastLine = output?.trimEnd()?.substringAfterLast('\n')?.trim().orEmpty()
    if (TIMEOUT_LINE.matchEntire(lastLine) != null) return TerminalSettle("未正常退出", true)
    val exit = EXIT_CODE_LINE.matchEntire(lastLine)?.groupValues?.get(1)?.toIntOrNull()
    if (exit != null && exit != 0) return TerminalSettle("退出码 " + exit, true)
    return TerminalSettle(null, false)
}

/**
 * dsh 的 StateDot：实心态是 10px 槽里一枚 6px 的圆核（done 绿 / error 红），
 * ongoing 是 14px 的旋转弧（25% 透明度的整圈轨道 + 一段 72° 的弧，1.5s 线性循环）。
 */
@Composable
private fun StateDot(state: String, modifier: Modifier = Modifier) {
    val palette = LocalDshPalette.current
    if (state == "ongoing") {
        val transition = rememberInfiniteTransition(label = "stateDot")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
            label = "stateDotSpin",
        )
        Canvas(modifier.size(14.dp)) {
            val stroke = 1.2.dp.toPx()
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = palette.labelTertiary.copy(alpha = 0.25f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            rotate(angle) {
                drawArc(
                    color = palette.labelTertiary,
                    startAngle = 0f,
                    // dsh 的 stroke-dasharray 12/周长≈59.7 ≈ 72°
                    sweepAngle = 72f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    } else {
        val color = if (state == "error") palette.errorLabel else palette.success
        Box(modifier.size(10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        }
    }
}

/**
 * bash 工具行的展开卡（dsh 的 TerminalBlock，聊天行变体）。
 *
 * @param command 命令原文（横幅里并成单行、超出省略）
 * @param output 输出正文（含 renderBash 的状态标记行；跑动中可能还没有）
 */
@Composable
internal fun RailTerminalBlock(
    command: String,
    output: String?,
    error: Boolean,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDshPalette.current
    val context = LocalContext.current
    val settle = remember(output, running, error) { terminalSettleOf(output, running, error) }
    val outputBlank = output.isNullOrBlank()
    // dsh 的 body 判据：跑动且还没打印 → 只有横幅；收尾后没输出 → 画「无输出」占位
    val bodyVisible = !running || !outputBlank
    var copied by rememberCopiedFlag()
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.codeBlock)
            .border(0.5.dp, palette.borderL1, RoundedCornerShape(12.dp)),
    ) {
        // 横幅（dsh 的 .header：padding 9px 14px 9px 30px 沟槽，点在最左 8px 处）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(14.dp), contentAlignment = Alignment.CenterStart) {
                StateDot(if (running) "ongoing" else if (settle.failed || error) "error" else "done")
            }
            // 沟槽凑满 30dp：8(卡内边距) + 14(点槽) + 8 = 提示符起点
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                // 用户点名：命令只占一行，多的省略掉（多行命令并成一行再省略）
                text = command.replace('\n', ' '),
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (settle.pill != null) {
                Spacer(Modifier.width(12.dp))
                // dsh 的 Pill(.status)：胶囊、bg-layer-2 底、错误色文字。
                // dsh 把高度钉在提示符行高（18px）；这里让文字自己撑高 —— 会话内容字号是
                // 跟着设置缩放的（contentDensity），钉死 18dp 会在大字号下裁掉胶囊文字。
                Text(
                    text = settle.pill,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.bgLayer2)
                        .padding(horizontal = 8.dp),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = palette.errorLabel,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            // dsh 的复制按钮：收尾且有输出才出现，复制的是**输出原文**（横幅是 chrome 不入内）
            if (!running && !outputBlank) {
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            copyToClipboard(context, output.orEmpty())
                            copied = true
                        },
                ) {
                    Text(
                        text = if (copied) "复制成功" else "复制",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = palette.labelSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        if (bodyVisible) {
            HorizontalDivider(thickness = 0.5.dp, color = palette.borderL2)
            if (outputBlank) {
                Text(
                    text = "无输出",
                    modifier = Modifier.padding(start = 30.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = palette.labelTertiary,
                )
            } else {
                Text(
                    text = output.orEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = TERMINAL_OUTPUT_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 30.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = if (error) palette.errorLabel else palette.labelSecondary,
                )
            }
        }
    }
}
