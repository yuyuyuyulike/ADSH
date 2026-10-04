package com.adsh.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette

/**
 * 设置页**共用的小件**（尺寸与配色都来自 dsh 的 settings CSS；从 [SettingsScreen] 切出来的
 * 第三簇，R55）。
 *
 * 卡片与分隔线（SettingsCard / DshHairline / DisclosureCard / CardFrame / PluginCard）、
 * 药丸与标签（SelectorPill / DshTag / TagTone / CredentialDot）、输入框（DshInput / ValueField /
 * SecretField）、按钮（PrimaryButton / SecondaryButton / LinkButton / IconActionButton /
 * CircleIconAction）与勾选框（CheckBoxMark）。
 *
 * 这一簇的可见性是 `internal`：三块分节（通用设置 / [SettingsModelsSection] /
 * [SettingsFeaturesSection]）都从别的文件用它们。切缝口径见 [SettingsModelsSection] 的说明。
 */

// ------------------------------------------------------------------ 设置页共用的小件（尺寸都来自 dsh 的 CSS）

/** dsh 的卡片边框：.5px border-l4、圆角 16（rowCard / PluginCard 都是这一套） */
@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    background: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalDshPalette.current
    Surface(
        color = background ?: Color.Transparent,
        contentColor = palette.labelPrimary,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(DshSpacing.Hairline, palette.borderL4),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/** 一条 .5px 的分隔线（dsh 的 border-l2） */
@Composable
internal fun DshHairline(color: Color? = null) {
    val palette = LocalDshPalette.current
    Box(Modifier.fillMaxWidth().height(DshSpacing.Hairline).background(color ?: palette.borderL2))
}

/** 可展开的卡片：只有头部 + 主体，没有保存脚（工作区 / 系统提示词 / 关于用它） */
@Composable
internal fun DisclosureCard(
    icon: ImageVector,
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // 底色与同一页的设置行一致（透明）：深色下 bg-layer-3 会比其它卡片亮一档，
    // 最下面这三张卡之前就是因此看着「气泡颜色不一样」
    SettingsCard {
        CardFrame(icon = icon, title = title, description = description, open = open, dirty = false) { open = !open }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.Card)) {
                DshHairline()
                Spacer(Modifier.height(12.dp))
                content()
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/**
 * 卡片的头部（dsh 的 .header）：图标 + 名称 + 说明 + 未保存标签 + 倒角。
 *
 * [description] 可以留空（「权限」卡按用户口径**不带小字介绍**）—— 留空时那一行整块不画，
 * 否则会留下一条 20sp 的空行，看着像卡片被压扁了。
 */
@Composable
internal fun CardFrame(
    icon: ImageVector,
    title: String,
    description: String = "",
    open: Boolean,
    dirty: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (open) palette.bgLayer2 else palette.bgLayer3)
            .dshClickable(interactionSource = interaction, onClick = onClick)
            .padding(horizontal = DshSpacing.Card, vertical = DshSpacing.Section),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxxl),
    ) {
        Icon(icon, contentDescription = null, tint = palette.labelSecondary, modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DshSpacing.Md)) {
            Text(title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = palette.labelPrimary)
            if (description.isNotEmpty()) {
                Text(description, fontSize = 13.sp, lineHeight = 20.sp, color = palette.labelTertiary)
            }
        }
        if (dirty) DshTag("未保存")
        Icon(
            imageVector = DshSettingIcons.ChevronDown,
            contentDescription = if (open) "收起设置" else "展开设置",
            tint = palette.labelTertiary,
            modifier = Modifier.size(14.dp).rotate(if (open) 180f else 0f),
        )
    }
}

/** dsh 的 PluginCard：头部（名称 + 说明 + 未保存标签 + 倒角）+ 主体 + 保存脚 */
@Composable
internal fun PluginCard(
    icon: ImageVector,
    title: String,
    description: String,
    dirty: Boolean,
    saving: Boolean,
    failed: String?,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    /**
     * 脚部「放弃修改」左边多出来的动作（dsh 的 PluginCard 里没有这一格；
     * 网页搜索的「更换」用它，样式与「放弃修改」同一枚 SecondaryButton）。
     */
    extraAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalDshPalette.current
    var open by remember { mutableStateOf(false) }
    // dsh：保存成功后自动收起。本地保存是同步的（saving 在同一帧里 true→false），
    // 所以盯的是「脏 → 不脏」这一跳，而不是 saving（dsh 那边 saving 要等一次远端往返）。
    var wasDirty by remember { mutableStateOf(dirty) }
    LaunchedEffect(dirty, failed) {
        when {
            dirty -> wasDirty = true
            wasDirty && failed == null -> {
                wasDirty = false
                open = false
            }
        }
    }
    SettingsCard(background = if (open) palette.bgLayer2 else palette.bgLayer3) {
        CardFrame(icon = icon, title = title, description = description, open = open, dirty = dirty) { open = !open }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.Card)) {
                DshHairline()
                content()
                DshHairline()
                Row(
                    Modifier.fillMaxWidth().padding(top = DshSpacing.Xxxl, bottom = DshSpacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
                ) {
                    if (failed != null) {
                        Text(
                            text = failed,
                            modifier = Modifier.weight(1f),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = palette.errorLabel,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    extraAction?.invoke()
                    SecondaryButton("放弃修改", enabled = dirty && !saving) { onDiscard() }
                    PrimaryButton(if (saving) "保存中…" else "保存", enabled = dirty && !saving) { onSave() }
                }
            }
        }
    }
}

internal enum class TagTone { Neutral, Quiet, Outline }

/** dsh 的 Tag：圆角 999、padding 1 8、11/17 500；tone 取 neutral / quiet / outline */
@Composable
internal fun DshTag(text: String, tone: TagTone = TagTone.Neutral) {
    val palette = LocalDshPalette.current
    val background = when (tone) {
        TagTone.Neutral -> palette.bgModulePlatform
        TagTone.Quiet -> Color.Transparent
        TagTone.Outline -> Color.Transparent
    }
    val border = if (tone == TagTone.Outline) BorderStroke(DshSpacing.Hairline, palette.borderL4) else null
    val color = when (tone) {
        TagTone.Neutral -> palette.labelSecondary
        TagTone.Quiet -> palette.labelTertiary
        TagTone.Outline -> palette.labelTertiary
    }
    Surface(color = background, contentColor = color, shape = RoundedCornerShape(999.dp), border = border) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Xxs),
            fontSize = 11.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
        )
    }
}

/** dsh 的 .selector：bg-module-platform、高 36、圆角 18、padding 0 14、gap 12 + 倒角 */
@Composable
internal fun SelectorPill(label: String, expanded: Boolean, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (pressed) palette.bgLayer3 else palette.bgModulePlatform)
            .dshClickable(interactionSource = interaction, onClick = onClick)
            .padding(horizontal = DshSpacing.Section),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxxl),
    ) {
        Text(label, fontSize = 14.sp, lineHeight = 22.sp, color = palette.labelPrimary, maxLines = 1)
        Icon(
            imageVector = DshSettingIcons.ChevronDown,
            contentDescription = if (expanded) "收起选项" else "展开选项",
            tint = palette.labelPrimary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** dsh 的 .credentialDot：8x8 圆点，已配置绿、缺失红 */
@Composable
internal fun CredentialDot(configured: Boolean) {
    val palette = LocalDshPalette.current
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (configured) palette.success else palette.errorLabel),
    )
}

/** dsh 的 .input：border .5px border-l4、bg-layer-1、高 32~34、圆角 8、padding 0 12 */
@Composable
internal fun DshInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    numeric: Boolean = false,
    secret: Boolean = false,
    invalid: Boolean = false,
    singleLine: Boolean = true,
    minHeight: Dp = 34.dp,
) {
    val palette = LocalDshPalette.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(fontSize = 13.sp, lineHeight = 21.sp, color = palette.labelPrimary),
        cursorBrush = SolidColor(palette.accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.bgLayer1)
            .border(DshSpacing.Hairline, if (invalid) palette.errorLabel else palette.borderL4, RoundedCornerShape(8.dp))
            .padding(horizontal = DshSpacing.Xxxl, vertical = DshSpacing.Lg),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        color = palette.labelDimmed,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
}

/** dsh 的 ValueField：标签 13/1.5 500 + 输入框 + 一行 hint，字段之间一条 .5px 分隔线 */
@Composable
internal fun ValueField(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean = false,
    placeholder: String = "",
    invalid: Boolean = false,
    first: Boolean = false,
) {
    val palette = LocalDshPalette.current
    Column(Modifier.fillMaxWidth()) {
        if (!first) DshHairline()
        Column(Modifier.fillMaxWidth().padding(vertical = DshSpacing.Xxxl), verticalArrangement = Arrangement.spacedBy(DshSpacing.Lg)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.labelPrimary,
                )
            }
            DshInput(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                numeric = numeric,
                invalid = invalid,
            )
            Text(
                text = if (invalid) "请填数字；留空表示使用默认值。" else hint,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = if (invalid) palette.errorLabel else palette.labelTertiary,
            )
        }
    }
}

/** dsh 的 SecretField：标签 + 状态标签 + 密码框 + hint（值不回显） */
@Composable
internal fun SecretField(
    label: String,
    stateLabel: String,
    configured: Boolean,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    first: Boolean = false,
) {
    val palette = LocalDshPalette.current
    Column(Modifier.fillMaxWidth()) {
        if (!first) DshHairline()
        Column(Modifier.fillMaxWidth().padding(vertical = DshSpacing.Xxxl), verticalArrangement = Arrangement.spacedBy(DshSpacing.Lg)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.labelPrimary,
                )
                DshTag(stateLabel, if (configured) TagTone.Neutral else TagTone.Quiet)
            }
            DshInput(value = value, onValueChange = onValueChange, secret = true)
            Text(hint, fontSize = 12.sp, lineHeight = 18.sp, color = palette.labelTertiary)
        }
    }
}

/** dsh 的 .save：底色 label-primary、文字 bg-layer-3；禁用降到 40% */
@Composable
internal fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) palette.labelPrimary else palette.labelPrimary.copy(alpha = 0.4f))
            .dshClickable(enabled = enabled, interactionSource = dshInteraction(), onClick = onClick)
            .padding(horizontal = DshSpacing.Section),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, lineHeight = 22.sp, color = palette.bgLayer3, maxLines = 1)
    }
}

/** dsh 的 .secondaryButton / .discard：border .5px border-l3、文字 label-primary；small = 28 高、圆角 14、12/18 */
@Composable
internal fun SecondaryButton(text: String, enabled: Boolean = true, small: Boolean = false, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(if (small) 14.dp else 18.dp)
    Box(
        Modifier
            .height(if (small) 28.dp else 36.dp)
            .clip(shape)
            .background(if (pressed && enabled) palette.hover else Color.Transparent)
            .border(DshSpacing.Hairline, palette.borderL3, shape)
            .dshClickable(enabled = enabled, interactionSource = interaction, onClick = onClick)
            .padding(horizontal = if (small) 10.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = if (small) 12.sp else 14.sp,
            lineHeight = if (small) 18.sp else 22.sp,
            color = if (enabled) palette.labelPrimary else palette.labelCaption,
            maxLines = 1,
        )
    }
}

/** dsh 的 .linkButton：高 28、圆角 14、12/18、三级色 */
@Composable
internal fun LinkButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (pressed && enabled) palette.hover else Color.Transparent)
            .dshClickable(enabled = enabled, interactionSource = interaction, onClick = onClick)
            .padding(horizontal = DshSpacing.Xxl),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = if (enabled) palette.labelTertiary else palette.labelCaption,
            maxLines = 1,
        )
    }
}

/** dsh 的 .iconButton：28x28、圆角 6、三级色 */
@Composable
internal fun IconActionButton(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (pressed) palette.hover else Color.Transparent)
            .dshClickable(interactionSource = interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/** dsh 的 .close：28 圆形按钮 */
@Composable
internal fun CircleIconAction(icon: ImageVector, description: String, iconSize: Dp, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (pressed) palette.hover else Color.Transparent)
            .dshClickable(interactionSource = interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = palette.labelPrimary, modifier = Modifier.size(iconSize))
    }
}

/** 候选清单前的勾选框 */
@Composable
internal fun CheckBoxMark(checked: Boolean) {
    val palette = LocalDshPalette.current
    Box(
        Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) palette.accent else Color.Transparent)
            .border(1.5.dp, if (checked) palette.accent else palette.labelCaption, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = DshSettingIcons.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}
