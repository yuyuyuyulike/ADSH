package com.adsh.app.ui.blocks

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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
import com.adsh.app.ui.kit.DshSpacing
import com.adsh.app.ui.kit.DshSettingIcons
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.kit.dshInteraction

/**
 * dsh 的基础组件（client/ui-primitives + Modal.module.css）：按 dsh 的 Modal / Button /
 * RiskConfirmation 原样复刻（24 圆角卡 + 右上角 X + 右下角两个胶囊按钮），聊天侧与设置侧共用。
 * 不是 Material 的 AlertDialog 形态（灰底、直角按钮、标题左对齐），别换回去。
 */

/**
 * dsh 的 `--dsw-elevation-prominent`（菜单卡片 / 浮层）：`0 0 0 .5px <stroke>, 0 3px 8px #0000000a,
 * 0 0 20px #0000000d`。描边由调用点用 [androidx.compose.foundation.BorderStroke] + [DshSpacing.Hairline] 画。
 *
 * 深色：单层 10dp / 4–5% 黑（与 dsh 的柔光同量级，深色下由描边负责分离）。
 * 浅色：dsh 的菜单面是半透明 + backdrop blur，安卓换成实色面之后同一份 4–5% 柔光几乎看不见；
 * 用「紧贴一层 + 大扩散一层」近似 CSS 的两层投影（Compose 的 shadow 只有一层）。
 */
@Composable
fun Modifier.dshProminentShadow(shape: Shape): Modifier {
    if (LocalDshPalette.current.dark) {
        return shadow(
            elevation = 10.dp,
            shape = shape,
            clip = false,
            ambientColor = Color(0x0D000000),
            spotColor = Color(0x0A000000),
        )
    }
    return this
        .shadow(
            elevation = 4.dp,
            shape = shape,
            clip = false,
            ambientColor = Color(0x10000000),
            spotColor = Color(0x18000000),
        )
        .shadow(
            elevation = 14.dp,
            shape = shape,
            clip = false,
            ambientColor = Color(0x0F000000),
            spotColor = Color(0x10000000),
        )
}

/**
 * dsh 的 `--dsw-elevation-soft`（输入卡片）：`0 0 0 .5px <stroke>` + `0 4px 16px rgba(0,0,0,.03)` +
 * `0 0 24px rgba(0,0,0,.03)`（gradient-shadow-text.css:38）；0.5px 描边由调用点用 BorderStroke 画。
 *
 * 深色：单层 12dp / 3% 黑。浅色：实色面叠两层（紧贴 + 大扩散），量级比 dsh 的 3% 略高一点，
 * 否则近白卡片落在近白页面上看不出离页面有多高。
 */
@Composable
fun Modifier.dshSoftShadow(shape: Shape): Modifier {
    if (LocalDshPalette.current.dark) {
        return shadow(
            elevation = 12.dp,
            shape = shape,
            clip = false,
            ambientColor = Color(0x08000000),
            spotColor = Color(0x08000000),
        )
    }
    return this
        .shadow(
            elevation = 3.dp,
            shape = shape,
            clip = false,
            ambientColor = Color(0x0F000000),
            spotColor = Color(0x14000000),
        )
        .shadow(
            elevation = 12.dp,
            shape = shape,
            clip = false,
            ambientColor = Color(0x0A000000),
            spotColor = Color(0x0C000000),
        )
}

/**
 * 浅色模式下的浮动卡片阴影：轻、软、向外扩散，把输入框 / 命令栏 / 任务栏这类浮在会话上的容器
 * 与页面底色分开。深色模式不画 —— 深色下的层级由面色与描边负责，加阴影只会发灰。
 *
 * 可与 [dshSoftShadow] / [dshProminentShadow] 叠加：那两条是 dsh 令牌的镜像（深色也有），
 * 这一条只在浅色补足「卡片离页面有多高」。
 */
@Composable
fun Modifier.lightCardShadow(shape: Shape): Modifier {
    if (LocalDshPalette.current.dark) return this
    // 与 [dshProminentShadow] / [dshSoftShadow] 同一口径：紧贴一层 + 大扩散一层，
    // 单层 10dp 在近白页面上几乎看不出卡片是浮起来的。
    return this
        .shadow(
            elevation = 3.dp,
            shape = shape,
            clip = false,
            ambientColor = Color(0x10000000),
            spotColor = Color(0x16000000),
        )
        .shadow(
            elevation = 12.dp,
            shape = shape,
            clip = false,
            ambientColor = Color(0x0D000000),
            spotColor = Color(0x10000000),
        )
}

/**
 * dsh 的 Button 变体（.primary / .outline / .ghost）
 */
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
            .then(if (kind == DshButtonKind.Outline) Modifier.border(DshSpacing.Hairline, palette.borderL3, shape) else Modifier)
            .dshClickable(enabled = enabled, interactionSource = interaction, onClick = onClick)
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
            .dshClickable(enabled = enabled, interactionSource = interaction, onClick = onClick),
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
            .border(DshSpacing.Hairline, if (checked) palette.labelPrimary else palette.borderL4, RoundedCornerShape(4.dp))
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
                .dshClickable(interactionSource = dshInteraction(), onClick = onDismiss)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier
                    .widthIn(max = width)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(palette.bgLayer2)
                    .dshClickable(interactionSource = dshInteraction(), onClick = {})
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(DshSpacing.Page),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = DshSpacing.Section, top = 22.dp, bottom = DshSpacing.Xxxl),
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
                    val scroll = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .padding(top = DshSpacing.Page, start = 24.dp, end = 24.dp)
                            .dshVerticalScrollbar(scroll, elevated = true)
                            .verticalScroll(scroll),
                    ) {
                        content()
                    }
                }
                if (footer != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl, Alignment.End),
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
 * 勾选框是 dsh 的契约：「我已了解风险，并愿意继续」是**复选框的文案**，右下角的确认按钮是
 * 「启用完全权限」，没勾选时是禁用的（.confirmAction{min-width:136px}）。
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
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxl),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = DshDockIcons.Warning,
                contentDescription = null,
                tint = palette.errorLabel,
                modifier = Modifier.padding(top = DshSpacing.Xs).size(18.dp),
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
                .padding(top = DshSpacing.Page)
                .dshClickable(interactionSource = dshInteraction()) { acknowledged = !acknowledged },
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxl),
            verticalAlignment = Alignment.Top,
        ) {
            // dsh 的 .acknowledgement input{margin:3px 0 0}
            Box(Modifier.padding(top = DshSpacing.Sm)) {
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
