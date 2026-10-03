package com.adsh.app.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette

// ------------------------------------------------------------------ 越权审批卡

/**
 * 越权审批卡，逐项对齐 dsh 的 ApprovalPanel（dsh-client-ui-approval 的 ApprovalPanel.module.css）：
 *
 *  - .root{padding:8px calc(side+16px) 12px}：贴在输入框那一条竖列上；
 *  - .card{border:1px solid state-warn-secondary;background:--dsw-specific-input-major;
 *    border-radius:20px;overflow:hidden}（窄屏 16，与计划待审卡一致）；
 *  - .strip{background:state-warn-tertiary;color:state-warn-primary;gap:8px;padding:10px 16px;13/18}
 *    + 8x8 的圆点，文案是 approval.waiting「等待审批」；
 *  - .body{padding:12px 16px 0;gap:6px;max-height;可滚动}：
 *    标题 15/24 500 label-primary = reason（缺省时是 approval.escalation「工具 {toolName} 请求越权执行」），
 *    下面一行 .command 13/20 label-tertiary 等宽 = 工具给的细节（命令行 / 路径）；
 *  - .actionRow{justify-content:flex-end;gap:8px;padding:14px 16px}：
 *    拒绝（outline，悬停是 danger）+ 允许一次（primary）。
 *
 * 手机适配：卡片占满宽度、左右各留 10dp（和计划待审卡/提问卡同一列宽），
 * 按钮沿用 DshButton 的 36dp 高（dsh 的按钮也是 36px）。
 */
@Composable
internal fun ApprovalCard(
    toolName: String,
    reason: String,
    detail: String?,
    onAllow: () -> Unit,
    onReject: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val cardShape = RoundedCornerShape(16.dp)
    val stripShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val maxBodyHeight = minOf(320, (configuration.screenHeightDp * 0.4f).toInt()).dp
    // dsh 的 answered：按过一下就地把两个按钮都置灰（客户端不再接受第二次结论，
    // 也挡掉「连点两下 = 拒绝 + 允许」这种竞态）。结论落定后卡片立刻卸载，状态不必重置。
    var answered by remember { mutableStateOf(false) }

    DshWarnCard(
        headerText = "等待审批",
        // 正文的内边距与两张卡无关（审批卡收紧在下方，计划卡更紧），所以由调用方给
        bodyModifier = Modifier.padding(start = DshSpacing.Card, end = DshSpacing.Card, top = DshSpacing.Xxxl),
        footer = {
            Row(
                Modifier.fillMaxWidth().padding(start = DshSpacing.Xxxl, end = DshSpacing.Xxxl, top = DshSpacing.Section, bottom = DshSpacing.Section),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DshButton(
                        text = "拒绝",
                        onClick = {
                            answered = true
                            onReject()
                        },
                        enabled = !answered,
                    )
                    DshButton(
                        text = "允许一次",
                        onClick = {
                            answered = true
                            onAllow()
                        },
                        kind = DshButtonKind.Primary,
                        enabled = !answered,
                    )
                }
            }
        },
    ) {
        Text(
            text = reason.ifBlank { "工具 " + toolName + " 请求越权执行" },
            fontSize = 15.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
            color = palette.labelPrimary,
        )
        detail?.takeIf { it.isNotBlank() }?.let { text ->
            Text(
                text = text,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = palette.labelTertiary,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ------------------------------------------------------------------ 计划待审（dsh 的 PlanReviewPanel）

/**
 * 计划待审卡，逐项对齐 dsh 的 PlanReviewPanel（ui-user-questions 的 PlanReviewPanel.module.css）：
 *
 *  - .card{border:1px solid state-warn-secondary;background:--dsw-specific-input-major;
 *    max-height:min(60vh,520px);border-radius:20px（窄屏 16）}
 *  - .strip{background:state-warn-tertiary;color:state-warn-primary;gap:8px;padding:10px 16px;13/18}
 *    + 8x8 的圆点，文案是 plan.header「计划待审」
 *  - .body{padding:12px 16px 4px;14/22;可滚动}：整份计划的 Markdown
 *  - .footer{padding:8px 16px 12px;justify-content:space-between}：
 *    左边 feedback（报错文案），右边 gap 8 的三个按钮 —— 去聊天里说（ghost + 编辑图标）、
 *    拒绝（outline）、确认执行（primary）
 */
@Composable
internal fun PlanReviewCard(
    plan: String,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onDiscuss: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val cardShape = RoundedCornerShape(16.dp)
    val stripShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val maxBodyHeight = minOf(520, (configuration.screenHeightDp * 0.6f).toInt()).dp

    DshWarnCard(
        headerText = "计划待审",
        // 计划卡比审批卡更紧一点（dsh 的 .body padding:12px 16px 4px 是给审批卡的，这里取 12/10/4）
        bodyModifier = Modifier.padding(start = DshSpacing.Xxxl, end = DshSpacing.Xxxl, top = DshSpacing.Xxl, bottom = DshSpacing.Md),
        footer = {
            Row(
                Modifier.fillMaxWidth().padding(start = DshSpacing.Xxxl, end = DshSpacing.Xxxl, top = DshSpacing.Xl, bottom = DshSpacing.Xxl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .dshClickable(interactionSource = interaction, onClick = onDiscuss)
                            .padding(horizontal = DshSpacing.Section),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
                    ) {
                        Icon(
                            imageVector = DshSettingIcons.Edit,
                            contentDescription = null,
                            tint = palette.labelSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text("去聊天里说", fontSize = 14.sp, lineHeight = 22.sp, color = palette.labelSecondary)
                    }
                    DshButton(text = "拒绝", onClick = onDecline)
                    DshButton(text = "确认执行", onClick = onApprove, kind = DshButtonKind.Primary)
                }
            }
        },
    ) {
        MarkdownBody(text = plan, modifier = Modifier.fillMaxWidth())
    }
}

