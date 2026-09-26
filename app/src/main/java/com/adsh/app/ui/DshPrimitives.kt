package com.adsh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.adsh.app.ui.theme.LocalDshPalette

/**
 * dsh 的基础组件（client/ui-primitives + Modal.module.css）。
 *
 * 之前这些确认框都走 Material 的 AlertDialog：灰底、直角按钮、标题左对齐，和 dsh 的
 * 「24 圆角白卡 + 右上角 X + 右下角两个胶囊按钮」不是一个东西。这里按 dsh 的
 * Modal / Button / RiskConfirmation 原样复刻，聊天侧与设置侧共用。
 */

/** dsh 的 Button 变体（.primary / .outline / .ghost） */
enum class DshButtonKind { Primary, Outline, Ghost }

/**
 * dsh 的 Button：
 *  - .button{border-radius:18px;font-size:14px;line-height:22px;padding:0 14px}，md 高 36
 *  - .sm{height:28px;font-size:12px;line-height:18px;padding:0 10px;border-radius:14px}
 *  - .primary 底色 button-primary-fill（= label-primary）、文字 label-primary-foreground
 *  - .outline border .5px border-l3、悬停 interactive-bg-hover
 *  - disabled{opacity:.4}
 */
@Composable
fun DshButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: DshButtonKind = DshButtonKind.Outline,
    small: Boolean = false,
    enabled: Boolean = true,
    minWidth: Dp = 0.dp,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(if (small) 14.dp else 18.dp)
    val fill = when {
        !enabled && kind == DshButtonKind.Primary -> palette.labelPrimary.copy(alpha = 0.4f)
        kind == DshButtonKind.Primary -> palette.labelPrimary
        kind == DshButtonKind.Ghost && pressed -> palette.hover
        kind == DshButtonKind.Outline && pressed -> palette.hover
        else -> Color.Transparent
    }
    val label = when {
        !enabled -> if (kind == DshButtonKind.Primary) palette.onPrimary.copy(alpha = 0.6f) else palette.labelPrimary.copy(alpha = 0.4f)
        kind == DshButtonKind.Primary -> palette.onPrimary
        else -> palette.labelPrimary
    }
    Box(
        modifier
            .heightIn(min = if (small) 28.dp else 36.dp)
            .height(if (small) 28.dp else 36.dp)
            .widthIn(min = minWidth)
            .clip(shape)
            .background(fill)
            .then(if (kind == DshButtonKind.Outline) Modifier.border(0.5.dp, palette.borderL3, shape) else Modifier)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = if (small) 10.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = if (small) 12.sp else 14.sp,
            lineHeight = if (small) 18.sp else 22.sp,
            color = label,
            maxLines = 1,
        )
    }
}

/**
 * dsh 的圆形图标按钮：
 *  - GoalBar / TodoPanel 的 .iconBtn：28x28、圆角 999、三级色
 *  - QuestionComposer 的 .iconButton：24x24、圆角 999、三级色
 *  - ModelListEditor 的 .iconButton：28x28、圆角 6
 */
@Composable
fun DshIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    size: Dp = 28.dp,
    iconSize: Dp = 14.dp,
    radius: Dp = 999.dp,
    enabled: Boolean = true,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(if (pressed && enabled) palette.hover else Color.Transparent)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = (tint ?: palette.labelTertiary).copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** dsh 的 .checkbox：14x14 圆角 4、border .5px border-l4；勾选后底色换成 label-primary 并画对勾 */
@Composable
fun DshCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true, size: Dp = 16.dp) {
    val palette = LocalDshPalette.current
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) palette.labelPrimary else Color.Transparent)
            .border(0.5.dp, if (checked) palette.labelPrimary else palette.borderL4, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = DshSettingIcons.Check,
                contentDescription = null,
                tint = palette.onPrimary,
                modifier = Modifier.size(size * 0.75f),
            )
        }
    }
}

/**
 * dsh 的 Modal（primitives/Modal.module.css）：
 *  - .root{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;padding:24px}
 *  - .mask{background:bg-mask-1;backdrop-filter:blur}
 *  - .dialog{flex column;gap:20px;width:min(380px,100%);padding:0 0 24px;border-radius:24px;
 *    background:bg-layer-2;box-shadow:elevation-prominent}
 *  - .header{padding:22px 14px 12px 24px} .title{16/24 500} .close{28x28 圆角 8}
 *  - .description{padding:0 24px;14/22} .body{margin-top:20px;padding:0 24px}
 *  - .footer{justify-content:flex-end;gap:8px;padding:0 24px}
 */
@Composable
fun DshModal(
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    width: Dp = 380.dp,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalDshPalette.current
    val view = LocalView.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Compose 的 Dialog 自带一层系统 dim，这里自己画 dsh 的 mask，所以把它压掉
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(palette.mask)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier
                    .widthIn(max = width)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(palette.bgLayer2)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 22.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.labelPrimary,
                        )
                        DshIconButton(
                            icon = DshSettingIcons.Close,
                            description = "关闭",
                            onClick = onDismiss,
                            size = 28.dp,
                            radius = 8.dp,
                            iconSize = 14.dp,
                            tint = palette.labelSecondary,
                        )
                    }
                    if (!description.isNullOrEmpty()) {
                        Text(
                            text = description,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = palette.labelPrimary,
                        )
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .padding(top = 20.dp, start = 24.dp, end = 24.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        content()
                    }
                }
                if (footer != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        footer()
                    }
                }
            }
        }
    }
}

/**
 * dsh 的 RiskConfirmation（切换「完全权限」时的风险确认）。
 *
 * 与之前那个 AlertDialog 的差别就在勾选框：dsh 里「我已了解风险，并愿意继续」是**复选框的文案**，
 * 右下角的确认按钮是「启用完全权限」，没勾选时是禁用的（.confirmAction{min-width:136px}）。
 */
@Composable
fun RiskConfirmationDialog(
    title: String,
    description: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    acknowledgeLabel: String = "我已了解风险，并愿意继续",
    cancelLabel: String = "取消",
    confirmLabel: String = "启用完全权限",
) {
    val palette = LocalDshPalette.current
    var acknowledged by remember { mutableStateOf(false) }
    DshModal(
        onDismiss = onCancel,
        title = title,
        width = 440.dp,
        footer = {
            DshButton(text = cancelLabel, onClick = onCancel, minWidth = 72.dp)
            DshButton(
                text = confirmLabel,
                onClick = onConfirm,
                kind = DshButtonKind.Primary,
                enabled = acknowledged,
                minWidth = 136.dp,
            )
        },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = DshDockIcons.Warning,
                contentDescription = null,
                tint = palette.errorLabel,
                modifier = Modifier.padding(top = 2.dp).size(18.dp),
            )
            Text(
                text = description,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = palette.labelSecondary,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { acknowledged = !acknowledged },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // dsh 的 .acknowledgement input{margin:3px 0 0}
            Box(Modifier.padding(top = 3.dp)) {
                DshCheckbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
            }
            Text(
                text = acknowledgeLabel,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = palette.labelPrimary,
            )
        }
    }
}
