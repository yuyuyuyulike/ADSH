package com.adsh.app.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette
import com.adsh.app.ui.kit.DshSpacing
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.kit.dshInteraction

/*
 * bash 的展开体 —— 对齐 dsh 的 TerminalBlock（ui-primitives/src/TerminalBlock.tsx，聊天行的 .terminalBody
 * 变体：小一号代码字体 / 行高 18 / 输出区上限 224px）。横幅 = 30dp 沟槽里的运行状态点 + `$` 提示符 +
 * **单行省略**的命令 + 退出状态胶囊 + 复制按钮，下面 0.5px 分隔线；其余全是输出区（可滚、上限 224dp）。
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

/** 横幅与正文的左侧沟槽（dsh 的 --dsl-terminal-gutter 默认值：聊天行 30px） */
private val TERMINAL_GUTTER = 30.dp

/** 任务面板的沟槽：状态点已经在行上，卡片把它收回到普通内容内缩（dsh 的 .panel 覆盖 12px） */
val TERMINAL_GUTTER_PANEL = 12.dp

/** 任务面板的输出区上限（dsh 的 .panel 把 --dsl-terminal-output-max-height 覆写成 288px） */
val TERMINAL_OUTPUT_MAX_HEIGHT_PANEL = 288.dp

/**
 * 终端卡（dsh 的 TerminalBlock）：横幅（提示符 + 命令 + 收尾胶囊 + 复制）+ 输出区。
 *
 * 两个使用点共用它，差别全靠参数（与 dsh 用 CSS 变量改同几个旋钮是同一套做法）：
 *  - **聊天行**（bash 工具行）：30px 沟槽、跑动状态点、收尾胶囊、输出上限 224px、复制输出原文；
 *  - **任务面板**（[JobOutputPanel]）：12px 沟槽、不画状态点、不画收尾胶囊、输出上限 288px、
 *    复制命令行（dsh 的 `copyText={job.label}`）、没输出时写「（无输出）」（dsh 的 terminal.noOutput）。
 *
 * @param command 命令原文（横幅里并成单行、超出省略）
 * @param output 输出正文（含 renderBash 的状态标记行；跑动中可能还没有）
 */
@Composable
fun RailTerminalBlock(
    command: String,
    output: String?,
    error: Boolean,
    running: Boolean,
    modifier: Modifier = Modifier,
    /** 左侧沟槽（横幅与正文共用） */
    gutter: Dp = TERMINAL_GUTTER,
    /** 横幅左侧的运行状态点（dsh 的 runStateDot） */
    runStateDot: Boolean = true,
    /** 输出区上限（dsh 的 --dsl-terminal-output-max-height） */
    outputMaxHeight: Dp = TERMINAL_OUTPUT_MAX_HEIGHT,
    /** 收尾胶囊（没有 exit facts 的面板传 false） */
    settlePill: Boolean = true,
    /** 复制按钮复制的内容（null = 输出原文） */
    copyText: String? = null,
    /** 收尾后没有输出时的占位文案 */
    noOutputText: String = "无输出",
) {
    val palette = LocalDshPalette.current
    val context = LocalContext.current
    val outputScroll = rememberScrollState()
    val settle = remember(output, running, error) { terminalSettleOf(output, running, error) }
    val outputBlank = output.isNullOrBlank()
    // dsh 的 body 判据：跑动且还没打印 → 只有横幅；收尾后没输出 → 画「无输出」占位
    val bodyVisible = !running || !outputBlank
    var copied by rememberCopiedFlag()
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.codeBlock)
            .border(DshSpacing.Hairline, palette.borderL1, RoundedCornerShape(16.dp)),
    ) {
        // 横幅（dsh 的 .header：padding 9px 14px 9px 30px 沟槽，点在最左 8px 处）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = gutter, end = DshSpacing.Section, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (runStateDot) {
                Box(Modifier.size(14.dp), contentAlignment = Alignment.CenterStart) {
                    DshStateDot(if (running) "ongoing" else if (settle.failed || error) "error" else "done")
                }
                // 沟槽凑满：8(卡内边距) + 14(点槽) + 8 = 提示符起点
                Spacer(Modifier.width(8.dp))
            }
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
            if (settlePill && settle.pill != null) {
                Spacer(Modifier.width(12.dp))
                // dsh 的 Pill(.status)：胶囊、bg-layer-2 底、错误色文字。
                // dsh 把高度钉在提示符行高（18px）；这里让文字自己撑高 —— 会话内容字号是
                // 跟着设置缩放的（contentDensity），钉死 18dp 会在大字号下裁掉胶囊文字。
                Text(
                    text = settle.pill,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.bgLayer2)
                        .padding(horizontal = DshSpacing.Xl),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = palette.errorLabel,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            // dsh 的复制按钮：收尾之后出现。复制 `copyText`（任务面板传命令行，聊天行不传 = 输出原文）
            if (!running && (copyText != null || !outputBlank)) {
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .dshClickable(interactionSource = dshInteraction()) {
                            copyToClipboard(context, copyText ?: output.orEmpty())
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
            HorizontalDivider(thickness = DshSpacing.Hairline, color = palette.borderL2)
            if (outputBlank) {
                Text(
                    text = noOutputText,
                    modifier = Modifier.padding(start = gutter, end = DshSpacing.Section, top = DshSpacing.Xxxl, bottom = DshSpacing.Xxxl),
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
                        .heightIn(max = outputMaxHeight)
                        .dshVerticalScrollbar(outputScroll, elevated = true, bottomInset = 8.dp)
                        .verticalScroll(outputScroll)
                        .padding(start = gutter, end = DshSpacing.Section, top = DshSpacing.Xxxl, bottom = DshSpacing.Xxxl),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = if (error) palette.errorLabel else palette.labelSecondary,
                )
            }
        }
    }
}
