package com.adsh.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.blocks.AnchorRect
import com.adsh.app.ui.blocks.DshIcons
import com.adsh.app.ui.blocks.FileTypeIcon
import com.adsh.app.ui.blocks.dshVerticalScrollbar
import com.adsh.app.ui.blocks.lightCardShadow
import com.adsh.app.ui.blocks.toAnchorRect
import com.adsh.app.ui.kit.DshSpacing
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.theme.LocalDshPalette

/**
 * 输入框的 `@` 文件候选菜单（dsh 的 input-trigger MenuView 里的 `@reference` 组）。
 *
 * 几何与 slash 触发菜单共用（[Composer.kt] 的 CommandPalette）：贴输入卡片上沿 [gap]、
 * 与卡片同宽、向上长、不占布局；高度上限由父层按卡片上方空间夹好传进来。
 * 行照 dsh：14dp 文件 / 文件夹图标、文件名（目录带尾 `/`）、右对齐父目录、目录行尾部进入箭头。
 *
 * 触屏补充（dsh 没有移动端）：整行点击 = 引用该条目；目录行的尾箭头 = drill（把 token 换成
 * `@dir/` 并保持菜单打开），键盘 Tab 等价于 drill、Enter 等价于 settle。
 */
@Composable
internal fun MentionPanel(
    visible: Boolean,
    items: List<MentionEntry>,
    /** 当前高亮行的下标（键盘 / 手指）；超出范围时夹到有效区间 */
    highlighted: Int,
    widthPx: Int,
    /** 卡片内容高度上限（父层已按输入框上方空间夹过） */
    height: Dp,
    /** 面板自己的窗口矩形：父层把它与输入框那一摞拼成「按下别处即关闭」的锚点 */
    bounds: AnchorRect,
    gap: Dp,
    onHighlight: (Int) -> Unit,
    onPick: (MentionEntry) -> Unit,
    onDrill: (MentionEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible || items.isEmpty()) return
    val palette = LocalDshPalette.current
    val width = with(LocalDensity.current) { widthPx.toDp() }
    val active = highlighted.coerceIn(0, items.size - 1)
    Box(
        // 与 CommandPalette 同一条「不占布局」路径：面板自己报 0 高、整块画到锚点上方。
        modifier = Modifier
            .outOfFlowAbove()
            .then(if (widthPx > 0) Modifier.width(width) else Modifier)
            .height(height + gap)
            .padding(bottom = gap)
            .onGloballyPositioned { bounds.value = it.boundsInWindow().toAnchorRect() },
        contentAlignment = Alignment.BottomStart,
    ) {
        // 卡片上方那一片空白落在消息区之上：按下要自己接住，否则会漏给会话流（关菜单 + 顺带滚动）。
        Box(
            Modifier.matchParentSize().pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
        )
        DshMenuCard(
            modifier = Modifier.fillMaxWidth().lightCardShadow(RoundedCornerShape(16.dp)),
            padding = 3.dp,
        ) {
            BoxWithConstraints {
                val maxNameWidth = maxWidth * 0.4f
                val maxParentWidth = maxWidth * 0.45f
                val scroll = rememberScrollState()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = height - 6.dp)
                        .dshVerticalScrollbar(scroll, elevated = true, topInset = 3.dp, bottomInset = 3.dp)
                        .verticalScroll(scroll),
                ) {
                    items.forEachIndexed { index, entry ->
                        MentionRow(
                            entry = entry,
                            active = index == active,
                            maxNameWidth = maxNameWidth,
                            maxParentWidth = maxParentWidth,
                            onHighlight = { onHighlight(index) },
                            onPick = { onPick(entry) },
                            onDrill = { onDrill(entry) },
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

/** dsh 的候选行：图标 / 文件名（目录尾 `/`）/ 右对齐父目录 / 目录行的进入箭头。 */
@Composable
private fun MentionRow(
    entry: MentionEntry,
    active: Boolean,
    maxNameWidth: Dp,
    maxParentWidth: Dp,
    onHighlight: () -> Unit,
    onPick: () -> Unit,
    onDrill: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val drillInteraction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active || pressed) palette.hover else Color.Transparent)
            .dshClickable(
                interactionSource = interaction,
                onClick = {
                    onHighlight()
                    onPick()
                },
            )
            .padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
    ) {
        if (entry.directory) {
            Icon(
                DshIcons.FolderClose,
                contentDescription = null,
                tint = palette.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        } else {
            FileTypeIcon(entry.name, size = 14.dp)
        }
        Text(
            text = if (entry.directory) entry.name + "/" else entry.name,
            modifier = Modifier.widthIn(max = maxNameWidth),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = palette.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        if (entry.parent.isNotEmpty()) {
            Text(
                text = entry.parent,
                modifier = Modifier.widthIn(max = maxParentWidth),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.directory) {
            Box(
                Modifier
                    .size(20.dp)
                    .dshClickable(
                        interactionSource = drillInteraction,
                        onClickLabel = "进入目录",
                        onClick = onDrill,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    DshIcons.ChevronRight,
                    contentDescription = "进入目录",
                    tint = palette.labelTertiary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
