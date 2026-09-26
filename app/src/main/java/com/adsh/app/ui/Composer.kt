package com.adsh.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.adsh.app.core.agent.imageMediaTypeOf
import com.adsh.app.core.data.SettingsStore
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * dsh 的 leadingInput 命令（只有这条带 hint）：token 直接在输入框里着色，
 * 用户接着输入内容，回车即把内容作为参数执行该指令。
 */
val CLAIM_TOKENS = listOf("/plan")

/** 取出草稿开头的 claim token（dsh 的 input.claim.token） */
fun claimTokenOf(draft: String): String? {
    val text = draft.trimStart()
    return CLAIM_TOKENS.firstOrNull { token ->
        text == token || (text.startsWith(token) && text.length > token.length && text[token.length].isWhitespace())
    }
}

/** dsh 的 hint.plan（token 之后还没输入内容时显示） */
fun claimHintOf(token: String?): String? = when (token) {
    "/plan" -> COMPOSER_HINT_PLAN
    else -> null
}

/** 模型窗口根页的固定内容高度：两行按 weight 均分（54dp/行），切换子页时窗口不会改变尺寸 */
private val MODEL_MENU_HEIGHT = 108.dp

/** 子页（模型清单 / 推理等级）的高度上限（dsh 的菜单是 min(360px, 100vh-96px)） */
private val MODEL_MENU_MAX_HEIGHT = 360.dp
/** 模型窗口宽度：与上下文占用窗口一致（dsh 的 .JObwrW_panel 也是 264px） */
private val MODEL_MENU_WIDTH = 264.dp
/** 卡片底边与触发按钮之间的间距（dsh 的 `bottom: calc(100% + 8px)`）：底边恒定锚在这里 */
private val MODEL_MENU_GAP = 8.dp
/** 卡片顶边至少要避开的距离（状态栏高度拿不到时用它）：再挤也不让卡片顶到屏幕最上沿 */
private val MODEL_MENU_TOP_MARGIN = 8.dp
/** 子页的最小高度：横屏 / 输入框长得很高时锚点上方空间有限，也不能把菜单压成一条 */
private val MODEL_MENU_MIN_HEIGHT = 160.dp

/** 触发菜单窗口的高度上限（dsh 的 .menu{max-height:400px}，见 MenuView.module.css:12） */
private val PALETTE_MAX_HEIGHT = 394.dp
/** 触发菜单窗口的最小高度：与模型子页同一个下限，再挤也不把菜单压成一条 */
private val PALETTE_MIN_HEIGHT = 160.dp
/** 触发菜单与输入卡片上沿的间距（dsh 的 .menu{bottom: calc(100% + 4px)}） */
private val PALETTE_GAP = 4.dp
/** 触发菜单卡片顶边至少要避开的距离（状态栏高度拿不到时用它） */
private val PALETTE_TOP_MARGIN = 8.dp

/**
 * 权限预设弹层的宽度下限（内容兜底）：三行里最长的是「工作区内修改」，
 * 图标 14 + 间距 + 6 个汉字 + 勾选 16 + 左右内边距，约 150dp。
 */
private val PERMISSION_MENU_MIN_WIDTH = 160.dp

/**
 * 弹层**窗口**的高度上限：由「锚点上方实际可用的空间」决定（模型菜单与触发菜单共用）。
 *
 * 为什么必须按上方空间夹：DshPopup 的定位规则是「内容底边 = 锚点顶边 - gap」
 * （对应 dsh 的 `bottom: calc(100% + 8px)`，见 DshPopup.calculatePosition）。
 * 只要内容不高于锚点上方剩下的空间，底边就恒定不动，多出来的内容只能往上长；
 * 一旦内容比上方空间还高，定位里的 `coerceAtLeast(gap)` 就会把整张卡片往下推 ——
 * 底边跟着往下跑，最后盖住输入框，这正是要修的病。
 * 所以这里把高度夹进「锚点上方空间」，让那个 coerce 永远不生效；超出的部分交给卡片自己的
 * verticalScroll 在内部消化：底边不动、卡片不变形，因此也不需要任何动画/动效。
 * （dsh 的 DOM 版是同一件事：把 max-height 夹到输入框上方的空间，见 MenuView.tsx:59-62。）
 *
 * @param anchorTopPx 锚点顶边在 root 里的 y（px，来自 onGloballyPositioned）；0 = 还没测量到
 * @param safeTopPx   卡片顶边要避开的高度（状态栏高度，px）
 * @param screenHeight 屏幕高度（锚点还没测量时的回退用）
 * @param maxHeight   设计稿的高度上限（dsh 的 max-height）
 * @param minHeight   再挤也不低于这个高度
 * @param gap         卡片底边与锚点顶边之间的间距
 * @param topMargin   卡片顶边至少要避开的距离
 */
private fun popupPanelMaxHeight(
    density: Density,
    anchorTopPx: Float,
    safeTopPx: Int,
    screenHeight: Dp,
    maxHeight: Dp,
    minHeight: Dp,
    gap: Dp,
    topMargin: Dp,
): Dp {
    // 还没测量到触发按钮（菜单在首帧就被展开）：退回原来的「屏幕高度 - 96dp」公式
    if (anchorTopPx <= 0f) return minOf(maxHeight, screenHeight - 96.dp)
    return with(density) {
        val gapPx = gap.roundToPx()
        val safe = maxOf(safeTopPx, topMargin.roundToPx())
        // 再让出 1px：dp -> px 来回取整可能把高度放大不到 1px，留出这点余量后，
        // 定位里的 coerceAtLeast(gap) 才是真的永远不插手（它一插手，底边就是在动）
        (floor(anchorTopPx - gapPx - safe).toInt() - 1)
            .coerceAtLeast(minHeight.roundToPx())
            .coerceAtMost(maxHeight.roundToPx())
            .toDp()
    }
}

/** dsh 的输入框占位文案（placeholder.default） */
const val COMPOSER_HINT = "发消息或创建任务, / 调用指令, @ 文件或对话"

/** dsh 的 placeholder.plan（plan mode 生效时的占位文案） */
const val COMPOSER_HINT_PLAN = "描述你的任务以生成计划"

// 触发菜单的行与规则（PALETTE_SECTION_* / PaletteCommand / slashQueryOf …）在 Palette.kt：
// 那一层只认字符串，能在 JVM 单测里逐条钉死；这里只管把它画出来。

/** 权限预设（dsh 的 permission preset 三档，图标为 dsh 的三枚 glyph） */
data class PermissionPreset(val id: String, val label: String, val icon: ImageVector, val hint: String)

val PERMISSION_PRESETS = listOf(
    PermissionPreset(
        SettingsStore.PERMISSION_READ_ONLY, "仅可查看", DshIcons.ShieldCheck,
        "只读：禁止写文件与执行命令",
    ),
    PermissionPreset(
        SettingsStore.PERMISSION_WORKSPACE_WRITE, "工作区内修改", DshIcons.ShieldEdit,
        "写操作限定在工作区内，允许执行命令",
    ),
    PermissionPreset(
        SettingsStore.PERMISSION_FULL_ACCESS, "完全权限", DshIcons.ShieldAlert,
        "不做额外限制，可直接执行外部命令",
    ),
)

fun permissionPreset(id: String): PermissionPreset =
    PERMISSION_PRESETS.firstOrNull { it.id == id } ?: PERMISSION_PRESETS.first()

/** 三段色与 dsh 的 ContextMeter 一致：colorSystem / colorTools(#a78bfa) / colorMessages */
@Composable
private fun contextColors(): Triple<Color, Color, Color> {
    val p = LocalDshPalette.current
    return Triple(p.system, p.tools, p.messages)
}

/** dsh 的 formatTokens：<1000 原样，<1e6 用 K（>=100 取整），否则用 M */
fun formatTokensCompact(value: Long): String {
    fun scaled(v: Double): String =
        if (v >= 100) v.roundToLong().toString() else (Math.round(v * 10) / 10.0).toString()
    return when {
        value < 1000 -> value.toString()
        value < 1_000_000 -> scaled(value / 1000.0) + "K"
        else -> scaled(value / 1_000_000.0) + "M"
    }
}

// --------------------------------------------------------------------- 弹层基元

/**
 * dsh 的浮层定位：贴在锚点上方 [gap]（默认 8px，对应 dsh 的 `bottom: calc(100% + 8px)`），
 * 右（或左）边缘对齐，并夹在窗口内。
 *
 * focusable 默认 false：可获焦的 Popup 会成为一个新的焦点窗口，系统会因此把输入法收起来
 * （光标消失、输入框掉回底部）。菜单本身不需要键盘输入，所以一律不抢焦点；
 * 「点空白处关闭」由根布局的 [dismissOverlaysOnPress] 统一负责 —— 这个弹层一组合就
 * 登记进 [LocalOverlayDismiss]，所以每个调用处都不必再自己铺拦截层。
 */
@Composable
fun DshPopup(
    onDismiss: () -> Unit,
    alignStart: Boolean = false,
    /** true = 弹在锚点下方（顶部按钮用），false = 弹在上方（输入框按钮用） */
    below: Boolean = false,
    focusable: Boolean = false,
    /**
     * 再往上抬多少（输入框里的按钮用）：菜单要贴在**整个输入框上沿**之上，
     * 而不是贴在按钮上方 —— 否则会盖住输入框本身。
     */
    liftBottom: androidx.compose.ui.unit.Dp = 0.dp,
    /** 与锚点之间的间距（dsh 的触发菜单是 4px，其余浮层是 8px） */
    gap: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    RegisterOverlayDismiss(onDismiss)
    val density = LocalDensity.current
    val lift = with(density) { liftBottom.roundToPx() }
    val provider = remember(density, alignStart, below, lift, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val gapPx = with(density) { gap.roundToPx() }
                val x = if (alignStart) anchorBounds.left else anchorBounds.right - popupContentSize.width
                val maxX = (windowSize.width - popupContentSize.width - gapPx).coerceAtLeast(gapPx)
                val maxY = (windowSize.height - popupContentSize.height - gapPx).coerceAtLeast(gapPx)
                val y = if (below) {
                    (anchorBounds.bottom + gapPx).coerceAtMost(maxY)
                } else {
                    (anchorBounds.top - popupContentSize.height - gapPx - lift).coerceAtLeast(gapPx)
                }
                return IntOffset(x.coerceIn(gapPx, maxX), y)
            }
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        // 不抢焦点（否则输入法会掉）之后，Popup 自己也会监听「外部触摸」来关闭：
        // 打开菜单的那一下点击正好在弹窗外面，会被判定成外部触摸，菜单一闪就没。
        // 所以两种自动关闭都关掉，「点空白关闭」由根布局统一负责（见 OverlayDismiss.kt）。
        properties = PopupProperties(
            focusable = focusable,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) { content() }
}

/** dsh 的菜单卡片：--dsw-specific-menu（纯白）+ 圆角 20 + 4px 内边距 + elevation-prominent */
@Composable
fun DshMenuCard(
    modifier: Modifier = Modifier,
    /** 统一 12dp：与上下文占用窗口（dsh 的 .JObwrW_panel / .bRhRbq_panel）一致 */
    radius: Dp = 12.dp,
    padding: Dp = 4.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalDshPalette.current
    Surface(
        color = palette.menu,
        // Surface 只在底色命中配色方案时才推导内容色；这里是 dsh 的自定义白/深灰，必须显式给
        contentColor = palette.labelPrimary,
        shape = RoundedCornerShape(radius),
        border = BorderStroke(1.dp, palette.borderL1),
        shadowElevation = 12.dp,
        modifier = modifier,
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** dsh 的菜单行：高 40、圆角 10、左右 10、gap 8；hover -> --dsw-alias-interactive-bg-hover */
@Composable
fun DshMenuRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconSize: Dp = 14.dp,
    value: String? = null,
    chevronRight: Boolean = false,
    selected: Boolean = false,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) palette.hover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = palette.labelPrimary, modifier = Modifier.size(iconSize))
        }
        Text(
            text = label,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = palette.labelPrimary,
            maxLines = 1,
        )
        if (value != null) {
            Text(
                text = value,
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = palette.labelTertiary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (chevronRight) {
            Icon(
                DshIcons.ChevronRight,
                contentDescription = null,
                tint = palette.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
        if (selected) {
            Icon(
                DshIcons.Check,
                contentDescription = null,
                tint = palette.labelPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ 上下文占用

/**
 * 上下文占用（dsh 的 ContextMeter，.JObwrW_trigger）：
 * **圆环 + 百分比**组成的胶囊（13/20 三级色、圆角 24、内边距 1px 8px、gap 6px），
 * 点开是 264dp 宽的白底面板 —— 「上下文已用 X% … ~已用 / 窗口」+ 4px 分段条 +
 * 系统提示词/工具定义/对话消息。
 *
 * 位置在顶栏（会话统计图标右侧），所以 [below] = true：面板往**下**弹，不会盖住状态栏。
 */
@Composable
fun ContextMeter(
    usage: ContextUsage,
    open: Boolean = false,
    below: Boolean = false,
    onOpenChange: (Boolean) -> Unit = {},
) {
    val palette = LocalDshPalette.current
    // 用户要求（第 75 轮）：圆环与顶栏其他图标（20dp）差不多大，线条与百分比再深一档。
    // dsh 的 .JObwrW_fill 用 label-tertiary；这里按需求加深到 label-secondary（展开时到 primary）。
    val ink = if (open) palette.labelPrimary else palette.labelSecondary
    Box {
        Row(
            modifier = Modifier
                .height(26.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(if (open) palette.hover else Color.Transparent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onOpenChange(!open) }
                .semantics { contentDescription = "上下文已用 " + usage.percent + "%" }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Canvas(Modifier.size(20.dp)) {
                val stroke = 2.dp.toPx()
                val radius = 8f / 20f * size.minDimension
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(palette.borderL4, radius = radius, center = center, style = Stroke(stroke))
                if (usage.percent > 0) {
                    drawArc(
                        color = ink,
                        startAngle = -90f,
                        sweepAngle = 360f * usage.percent.coerceIn(0, 100) / 100f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Text(
                text = usage.percent.toString() + "%",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = ink,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (open) {
            DshPopup(onDismiss = { onOpenChange(false) }, below = below) { ContextPanel(usage) }
        }
    }
}

/** dsh 的 .JObwrW_panel：264px 宽、12px 圆角、12px 内边距、12/20 字号 */
@Composable
private fun ContextPanel(usage: ContextUsage) {
    val palette = LocalDshPalette.current
    val (systemColor, toolsColor, messagesColor) = contextColors()
    val total = (usage.system + usage.tools + usage.messages).coerceAtLeast(1L)
    DshMenuCard(modifier = Modifier.width(264.dp), radius = 12.dp, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("上下文已用", fontSize = 12.sp, lineHeight = 20.sp, color = palette.labelTertiary)
            Spacer(Modifier.width(6.dp))
            Text(
                usage.percent.toString() + "%",
                fontSize = 12.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = palette.labelPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "~" + formatTokensCompact(usage.used) + " / " + formatTokensCompact(usage.window),
                fontSize = 12.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = palette.labelPrimary,
            )
        }
        // 三段条按「已用百分比 × 各段占比」绝对定宽；用 weight 会被拉伸成整条（dsh 用的是 width:%）
        Canvas(
            Modifier
                .padding(top = 10.dp, bottom = 12.dp)
                .fillMaxWidth()
                .height(4.dp),
        ) {
            drawRoundRect(
                color = palette.hover,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
            )
            val gap = 1.dp.toPx()
            val minWidth = 2.dp.toPx()
            var x = 0f
            listOf(
                usage.system to systemColor,
                usage.tools to toolsColor,
                usage.messages to messagesColor,
            ).forEach { (tokens, color) ->
                val span = size.width * (usage.percent / 100f) * (tokens.toFloat() / total)
                if (span > 0f) {
                    val width = span.coerceAtLeast(minWidth).coerceAtMost(size.width - x)
                    if (width > 0f) {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x, 0f),
                            size = Size(width, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
                        )
                        x += width + gap
                    }
                }
            }
        }
        ContextRow(systemColor, "系统提示词", usage.system)
        ContextRow(toolsColor, "工具定义", usage.tools)
        ContextRow(messagesColor, "对话消息", usage.messages)
    }
}

@Composable
private fun ContextRow(dot: Color, label: String, tokens: Long) {
    val palette = LocalDshPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, lineHeight = 20.sp, color = palette.labelSecondary)
        }
        Text(
            "~" + formatTokensCompact(tokens),
            fontSize = 12.sp,
            lineHeight = 20.sp,
            color = palette.labelPrimary,
        )
    }
}

// ---------------------------------------------------------------------- 输入框

/**
 * 输入框（逐项对齐 dsh 的 InputBar）：
 * 左：＋（=触发菜单，与输入 `/` 同一个菜单，28dp 圆形 selector 底；「文件」是菜单里的一行）、
 * 权限预设（只留图标 + 可转动倒角）、Plan chip；
 * 右：模型（数据库图标 + 倒角，内含 模型 / 推理等级 两个子菜单）、发送/停止（34dp 圆形）。
 * 上下文占用不在这一行（在顶栏会话统计右侧），所以模型按钮的位置不随会话状态变化。
 */
@Composable
fun DshComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    /**
     * 繁忙时的发送行为（dsh 的 ui-conversation.busyEnter）：
     * queue = 排队发送（默认），steer = 插话发送。运行中且草稿非空时，
     * 主按钮发的是这条消息（而不是停止），文案也跟着换 —— dsh 的 primaryStops
     * 就是「运行中且草稿为空」才把主按钮变成停止。
     */
    busyEnter: String = com.adsh.app.core.data.SettingsStore.BUSY_QUEUE,
    /** 排队里还有几条（排队发送模式下给个可见的交代） */
    queuedCount: Int = 0,
    /** 模型菜单的数据：按提供方分组（dsh 的 ModelSelect groups） */
    modelGroups: List<ChatViewModel.ModelGroup>,
    currentModel: String,
    /** 当前提供方 id：选中态要「提供方 + 模型」都对上 */
    currentProviderId: String = "",
    /** 模型菜单的分组标题（dsh 的 provider displayName，例如 DeepSeek） */
    modelGroupTitle: String = "DeepSeek",
    /** 每个提供方的余额（DeepSeek 专有接口；显示在分组标题的最右边） */
    balances: Map<String, ChatViewModel.BalanceState> = emptyMap(),
    /** 打开模型菜单时刷新余额（每次点开自动刷新一次） */
    onRefreshBalance: () -> Unit = {},
    /** 推理等级菜单的行：Default + 当前**模型**支持的等级（dsh 的 ModelSelect 子页；空表 = 该模型没有等级） */
    efforts: List<Pair<String, String>>,
    currentEffort: String,
    onSelectModel: (String, String) -> Unit,
    onSelectEffort: (String) -> Unit,
    permission: String,
    onSelectPermission: (String) -> Unit,
    /** 触发菜单里**要显示的**行（父层已按 dsh 的规则过滤：可用性 / 查询命中，见 Palette.kt） */
    paletteCommands: List<PaletteCommand>,
    paletteVisible: Boolean,
    onPaletteVisibleChange: (Boolean) -> Unit,
    /** 菜单被「点别处 / 返回键」关掉时的收尾（把当前草稿标记为已忽略，别立刻又弹出来） */
    onPaletteDismiss: () -> Unit = {},
    onSend: () -> Unit,
    onCancel: () -> Unit,
    planMode: Boolean = false,
    onTogglePlan: () -> Unit = {},
    /** 待发附件（会话私有目录里的绝对路径）：显示在输入框内部、文字输入区上方 */
    attachments: List<String> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    /** 命令面板里的 /permission 直接展开权限菜单 */
    requestPermission: Boolean = false,
    onRequestHandled: () -> Unit = {},
    /** 是否显示输入框外的工作区 chip（dsh 只在 hero 阶段显示） */
    showWorkspace: Boolean = false,
    /** 已绑定的工作区（dsh 侧栏的树）与当前会话所属工作区 */
    workspaces: List<com.adsh.app.core.data.WorkspaceEntity> = emptyList(),
    workspaceId: Long? = null,
    onPickWorkspace: (Long) -> Unit = {},
    onAddWorkspace: () -> Unit = {},
    /**
     * 父层主动改写草稿的次数（发送后清空、指令面板写入 token）。
     * 只有这个计数变化时输入框才会被外部回灌，见 fieldValue 的注释。
     */
    draftRevision: Int = 0,
    /** 当前打开的输入框弹层：null / "permission" / "model" / "context" / "workspace"（由 ChatScreen 托管） */
    menu: String? = null,
    onMenuChange: (String?) -> Unit = {},
    enabled: Boolean = true,
) {
    val palette = LocalDshPalette.current
    // 三个弹层互斥；状态托管给 ChatScreen（弹层自己登记进全局登记处，不用再铺拦截层）
    // 弹层贴着「整个输入框的上沿」而不是按钮上沿：先记下输入框卡片与触发按钮的顶边
    var composerCardTop by remember { mutableFloatStateOf(0f) }
    /** 输入卡片的宽度（px）：触发菜单与卡片同宽（dsh 的 .menu 是 left:0;right:0） */
    var composerCardWidth by remember { mutableIntStateOf(0) }
    var modelTriggerTop by remember { mutableFloatStateOf(0f) }
    val permissionOpen = menu == "permission"
    val modelOpen = menu == "model"
    var modelPane by remember { mutableStateOf("root") }
    var confirmFullAccess by remember { mutableStateOf(false) }

    LaunchedEffect(requestPermission) {
        if (requestPermission) {
            onMenuChange("permission")
            onRequestHandled()
        }
    }

    // 「每次点开时自动刷新」余额：菜单一打开就拉一次（拉取在 VM 的 IO 线程上）
    LaunchedEffect(modelOpen) {
        if (modelOpen) onRefreshBalance()
    }

    // 输入 "/" 与点 ＋ 打开的是同一个菜单（dsh 的 input.commands）。
    // 这里只负责「消费掉触发词」：行有哪些、菜单显不显示，全由父层按 dsh 的三条规则算好
    // （可用性过滤 / 精确命中即完整 / 一条候选都不剩就关，见 Palette.kt）。
    val slashQuery = remember(draft) { slashQueryOf(draft) }

    // dsh 的 leadingInput 命令：/goal、/plan 的 token 在输入框里着色，用户直接在后面输入内容
    val claim = remember(draft) { claimTokenOf(draft) }
    val claimRest = remember(draft, claim) {
        if (claim == null) "" else draft.trimStart().removePrefix(claim).trim()
    }
    val claimHint = claimHintOf(claim)
    val inputStyle = TextStyle(fontSize = 14.sp, lineHeight = 24.sp, color = palette.labelPrimary)
    val measurer = rememberTextMeasurer()
    val hintOffset = remember(claim, inputStyle) {
        claim?.let { measurer.measure(AnnotatedString(it + " "), inputStyle).size.width } ?: 0
    }
    // 选完指令后把焦点交回输入框：/goal、/plan 之后可以直接打字
    val inputFocus = remember { FocusRequester() }
    // 用 TextFieldValue 托管选区：面板写入 "/goal " 这类文本时光标自动落到末尾（否则会停在开头）
    var fieldValue by remember { mutableStateOf(TextFieldValue(draft)) }
    /**
     * 输入框自己就是文本的唯一真相：父层**只在主动改输入框时**（指令面板写入 "/goal "、
     * 发送后清空）把 [draftRevision] 加一，通知这里回灌一次。
     *
     * 之前是「比较 draft 与上一次自己发出的文本」：父层的 draft 只要有一帧落后
     * （状态流合并、流式输出期间的高频重组都会造成这种落后），effect 就会把 fieldValue
     * 重置成旧文本 —— 那一刻输入法的组合区（拼音）被丢掉，回车时输入法只好把
     * 还没上屏的拼音当英文字母提交，也就是「抢键盘 / 拼音变字母」。
     * 有了 revision，按键产生的回显永远不会再碰 fieldValue。
     */
    var appliedRevision by remember { mutableStateOf(draftRevision) }
    LaunchedEffect(draftRevision) {
        if (draftRevision != appliedRevision) {
            appliedRevision = draftRevision
            fieldValue = TextFieldValue(draft, selection = TextRange(draft.length))
        }
    }
    val claimColor = palette.warnLabel
    val claimTransform = remember(claim, claimColor) {
        VisualTransformation { text ->
            val value = text.text
            if (claim == null || !value.startsWith(claim)) {
                TransformedText(text, OffsetMapping.Identity)
            } else {
                TransformedText(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = claimColor)) { append(value.substring(0, claim.length)) }
                        append(value.substring(claim.length))
                    },
                    OffsetMapping.Identity,
                )
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 8.dp)) {
        // dsh 的 heroWorkspaceRow：输入框「外」左上角的工作区入口（文件夹图标 + 名称 + 倒角）
        // 只在「新对话且还没有内容」时出现（dsh 的 hero 阶段），开始对话后自动隐藏
        if (showWorkspace) WorkspaceChipRow(
            workspaces = workspaces,
            workspaceId = workspaceId,
            open = menu == "workspace",
            onOpenChange = { open -> onMenuChange(if (open) "workspace" else null) },
            onPick = { id ->
                onMenuChange(null)
                onPickWorkspace(id)
            },
            onAdd = {
                onMenuChange(null)
                onAddWorkspace()
            },
        )

        // 这一层 Box 有两个身份：它是触发菜单的**锚点**（dsh 的菜单是
        // `position:absolute; bottom:calc(100% + 4px); left:0; right:0`，即宽度等于整张
        // 输入卡、底边贴在卡片上沿 4px），也是卡片尺寸的来源（onGloballyPositioned）。
        Box(
            Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                composerCardTop = coordinates.positionInRoot().y
                composerCardWidth = coordinates.size.width
            },
        ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = palette.inputMajor,
            contentColor = palette.labelPrimary,
            border = BorderStroke(1.dp, palette.borderL2),
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
                // 附件在输入框内部：卡片自动变高，文字输入区留在下方
                if (attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        attachments.forEach { path -> AttachmentCard(path = path, onRemove = onRemoveAttachment) }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .heightIn(min = 44.dp),
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            text = if (planMode) COMPOSER_HINT_PLAN else COMPOSER_HINT,
                            fontSize = 14.sp,
                            lineHeight = 24.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = palette.labelCaption,
                        )
                    }
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { updated ->
                            fieldValue = updated
                            onDraftChange(updated.text)
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
                        enabled = enabled,
                        textStyle = inputStyle,
                        cursorBrush = SolidColor(palette.accent),
                        visualTransformation = claimTransform,
                        maxLines = 6,
                    )
                    // claim 生效且还没输入内容时，在 token 后面显示 dsh 的 hint（如「输入目标，智能体将持续执行」）
                    if (claim != null && claimHint != null && claimRest.isEmpty()) {
                        Text(
                            text = claimHint,
                            modifier = Modifier.padding(
                                start = with(LocalDensity.current) { hintOffset.toDp() },
                            ),
                            fontSize = 14.sp,
                            lineHeight = 24.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = palette.labelCaption,
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // ＋：触发菜单（与键入 "/" 同一个菜单，含「文件」那一行）。
                    // dsh 的 .uV2eYG_tools：28px 圆形、--dsw-specific-selector 底、gap 8px（窄容器）。
                    CircleIconButton(DshIcons.Plus, "添加文件或调用指令", active = paletteVisible) {
                        onMenuChange(null)
                        onPaletteVisibleChange(!paletteVisible)
                    }

                    // 权限预设：只显示当前档位的图标 + 可转动倒角（dsh 在窄容器下就是隐藏文字）
                    val preset = permissionPreset(permission)
                    Box {
                        TriggerPill(icon = preset.icon, contentDescription = "权限预设：" + preset.label, open = permissionOpen) {
                            onMenuChange(if (permissionOpen) null else "permission")
                        }
                        if (permissionOpen) {
                            DshPopup(onDismiss = { onMenuChange(null) }, alignStart = true) {
                                // 宽度：**输入卡片的一半**（用户第 80 轮的真机口径，首版照搬 dsh 的
                                // `max-width: 100%` 直接顶满，被截图打回）。
                                // dsh 的同类弹层是内容定宽、`min-width: min(220px, 100%)`、
                                // `max-width: 100%`（ui-commands/src/client/PopupSelectView.module.css:13-16），
                                // 那是桌面宽度下的取值；手机上没有那个宽度，按用户口径折半，
                                // 再给内容兜一个下限（行是 fillMaxWidth 的，宽度必须自己定死，
                                // 否则它会一路涨到父约束的上限）。
                                val permissionMenuWidth = with(LocalDensity.current) {
                                    (composerCardWidth / 2).toDp()
                                }.coerceAtLeast(PERMISSION_MENU_MIN_WIDTH)
                                DshMenuCard(Modifier.width(permissionMenuWidth)) {
                                    PERMISSION_PRESETS.forEach { item ->
                                        DshMenuRow(
                                            label = item.label,
                                            icon = item.icon,
                                            selected = item.id == permission,
                                            onClick = {
                                                onMenuChange(null)
                                                if (item.id == SettingsStore.PERMISSION_FULL_ACCESS && item.id != permission) {
                                                    confirmFullAccess = true
                                                } else {
                                                    onSelectPermission(item.id)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (planMode) PlanChip(onExit = onTogglePlan)

                    Spacer(Modifier.weight(1f))

                    // 模型：与设置页「模型」分节同一枚图标（IconDataOutline16）+ 可转动倒角
                    // -> 「模型 / 推理等级」两级菜单
                    Box(Modifier.onGloballyPositioned { modelTriggerTop = it.positionInRoot().y }) {
                        TriggerPill(icon = DshSettingIcons.Data, contentDescription = "模型与推理等级", open = modelOpen) {
                            modelPane = "root"
                            onMenuChange(if (modelOpen) null else "model")
                        }
                        if (modelOpen) {
                            // 位置与最初版本一致：菜单底边贴在触发按钮上方 8dp（不再往上抬到输入框上沿）。
                            // 定位要用的两个量在 Popup **外面**取：Popup 是独立窗口，它自己的 root 坐标与
                            // insets 不能代表主窗口；放在这里读，输入框长高（触发按钮上移）时也会跟着重算。
                            val density = LocalDensity.current
                            val statusBarTopPx = WindowInsets.statusBars.getTop(density)
                            val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
                            // 窗口高度在**整个菜单生命周期里恒定**，这是「不要动效」的关键：
                            // Popup 是独立窗口，只要窗口几何在打开期间发生变化（换子页时窗口变高变矮），
                            // 系统就会先把旧尺寸那一帧画出来、再跳到新位置 —— 用户看到的就是
                            // 「点推理等级时上滑一下」。窗口尺寸定死之后，切子页只是卡片自己在窗口里
                            // 长高/缩矮（纯组合层布局，即时生效、没有动画），底边始终钉在触发按钮上方 8dp。
                            val panelHeight = popupPanelMaxHeight(
                                density = density,
                                anchorTopPx = modelTriggerTop,
                                safeTopPx = statusBarTopPx,
                                screenHeight = screenHeight,
                                maxHeight = MODEL_MENU_MAX_HEIGHT,
                                minHeight = MODEL_MENU_MIN_HEIGHT,
                                gap = MODEL_MENU_GAP,
                                topMargin = MODEL_MENU_TOP_MARGIN,
                            )
                            DshPopup(onDismiss = { onMenuChange(null) }) {
                                Box(
                                    Modifier.width(MODEL_MENU_WIDTH).height(panelHeight),
                                    contentAlignment = Alignment.BottomCenter,
                                ) {
                                    // 卡片上方那一片空白也在弹窗窗口里（不抢焦点的 Popup 照样收得到触摸），
                                    // 落在窗口内、卡片外的点击必须自己接住：否则会被窗口吃掉 ——
                                    // 既不关菜单、也点不到下面的消息。行为与「点空白关闭」一致。
                                    Box(
                                        Modifier.matchParentSize().pointerInput(Unit) {
                                            detectTapGestures { onMenuChange(null) }
                                        },
                                    )
                                    // 卡片贴底：内容多高就往上长多高（dsh 的 bottom: calc(100% + 8px)）。
                                    // 根页两行用 weight 均分固定高度；子页高度上限就是这个窗口高度，
                                    // 超过则在卡片内部滚动。
                                    DshMenuCard(Modifier.fillMaxWidth()) {
                                        Column(
                                            if (modelPane == "root") {
                                                Modifier.height(MODEL_MENU_HEIGHT)
                                            } else {
                                                Modifier.heightIn(max = panelHeight).verticalScroll(rememberScrollState())
                                            },
                                        ) {
                                                when (modelPane) {
                                                    // 根页是固定高度（MODEL_MENU_HEIGHT），两行按 weight 均分；
                                                    // 注意 weight 只能在固定高度的 Column 里用 ——
                                                    // 子页那层是 heightIn + verticalScroll（滚动 → 高度无界），
                                                    // 里面再放 weight 的行会被量成 0 高（菜单看着像没打开）。
                                                    "root" -> {
                                                        DshMenuRow(
                                                            label = "模型",
                                                            value = currentModel,
                                                            chevronRight = true,
                                                            modifier = Modifier.weight(1f),
                                                            onClick = { modelPane = "model" },
                                                        )
                                                        DshMenuRow(
                                                            label = "推理等级",
                                                            // 该模型一个等级都没有时不显示值（dsh 的 effortLabel === undefined）
                                                            value = efforts.firstOrNull { it.first == currentEffort }?.second,
                                                            chevronRight = true,
                                                            modifier = Modifier.weight(1f),
                                                            onClick = { modelPane = "effort" },
                                                        )
                                                    }
                                                    "model" -> {
                                                        // dsh 的 ModelSelect：按提供方分组，组标题 12/18 三级色，
                                                        // 列表超过菜单高度时自己滚（dsh 的 .groups{overflow-y:auto}）。
                                                        // 组标题这一行最右边是余额（DeepSeek 的 /user/balance，
                                                        // 每次打开菜单刷新一次）—— 拿不到就不占位。
                                                        Column(Modifier.fillMaxWidth()) {
                                                            modelGroups.forEach { group ->
                                                                val balance = balances[group.providerId]
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth()
                                                                        .padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 3.dp),
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                ) {
                                                                    Text(
                                                                        text = group.providerName,
                                                                        fontSize = 12.sp,
                                                                        lineHeight = 18.sp,
                                                                        fontWeight = FontWeight.Medium,
                                                                        color = palette.labelTertiary,
                                                                    )
                                                                    val balanceText = balance?.text.orEmpty()
                                                                    if (balanceText.isNotEmpty()) {
                                                                        Spacer(Modifier.weight(1f))
                                                                        Text(
                                                                            // 刷新中在数字后面跟一个省略号，位置不动
                                                                            text = if (balance?.loading == true) balanceText + " …" else balanceText,
                                                                            fontSize = 12.sp,
                                                                            lineHeight = 18.sp,
                                                                            color = palette.labelTertiary,
                                                                            maxLines = 1,
                                                                        )
                                                                    }
                                                                }
                                                                group.models.forEach { model ->
                                                                    DshMenuRow(
                                                                        label = model,
                                                                        selected = model == currentModel &&
                                                                            group.providerId == currentProviderId,
                                                                        onClick = {
                                                                            onMenuChange(null)
                                                                            onSelectModel(model, group.providerId)
                                                                        },
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                    else -> Column(Modifier.fillMaxWidth()) {
                                                        if (efforts.isEmpty()) {
                                                            // dsh 的 empty.efforts：「当前模型未提供推理等级。」
                                                            Text(
                                                                text = "当前模型未提供推理等级。",
                                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                                fontSize = 13.sp,
                                                                lineHeight = 20.sp,
                                                                color = palette.labelTertiary,
                                                            )
                                                        }
                                                        efforts.forEach { (id, label) ->
                                                            DshMenuRow(
                                                                label = label,
                                                                selected = id == currentEffort,
                                                                onClick = {
                                                                    onMenuChange(null)
                                                                    onSelectEffort(id)
                                                                },
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                    }
                                }
                            }
                        }
                    }

                    // 上下文占用不在这一行里：dsh 把它放在输入卡片**下方**的会话统计旁边，
                    // 这里是顶栏「会话统计」图标的右侧（见 ChatScreen 的顶栏）。它以前在这一行
                    // 的最右边，出现在会话开始之后 —— 一出现就把左边的模型按钮顶走一格。
                    SendButton(
                        sending = sending,
                        // 只有附件没有文字也能发（dsh 的 canSubmit = 文本非空或附件非空）：
                        // 旧实现发送键看的是正文，只发一张图是点不动的
                        enabled = draft.isNotBlank() || attachments.isNotEmpty(),
                        busyEnter = busyEnter,
                        onSend = onSend,
                        onCancel = onCancel,
                    )
                }
            }
        }
        }

        // 触发菜单：锚点就是上面那张卡片（宽度与卡片一致、底边贴卡片上沿 4px，dsh 的 .menu）
        CommandPalette(
            visible = paletteVisible,
            commands = paletteCommands,
            widthPx = composerCardWidth,
            anchorTopPx = composerCardTop,
            onDismiss = onPaletteDismiss,
            onPick = { command ->
                onPaletteVisibleChange(false)
                // dsh 的 pick 会先按 span 消费掉触发词那一段（ui-commands/src/client/service.ts:270-273
                // `consumeVia(span)`），再执行命令：键入的 `/comp` 不会留在输入框里。
                // 「+」打开的菜单没有触发词，草稿原样不动 —— 用户打的字不该被菜单吃掉。
                val slash = slashQuery
                if (slash != null) {
                    val rest = draft.removeRange(0, 1 + slash.length)
                    if (rest != draft) onDraftChange(rest)
                }
                command.run()
                // 选完指令把焦点交回输入框：/plan 这类后面可以直接打字
                runCatching { inputFocus.requestFocus() }
            },
        )
    }

    if (confirmFullAccess) {
        // dsh 的 RiskConfirmation：正文 + 勾选框 + 「取消 / 启用完全权限」两个按钮。
        // 之前这里把「我已了解风险，并愿意继续」当成了确认按钮，等于少了一道勾选。
        RiskConfirmationDialog(
            title = "确认启用完全权限？",
            description = "启用完全权限后，智能体将减少确认步骤，并且可以直接执行更多操作，" +
                "包括敏感操作、文件修改或外部命令。仅建议在你信任当前任务时使用。",
            onCancel = { confirmFullAccess = false },
            onConfirm = {
                confirmFullAccess = false
                onSelectPermission(SettingsStore.PERMISSION_FULL_ACCESS)
            },
        )
    }
}

/**
 * dsh 的工作区 chip（HeroShell.module.css 的 .workspace/.workspaceRow）：
 * min-height 28px、圆角 16px、gap 4px、左右 8px 内边距、13/20 Medium；
 * 文件夹图标 16px（未绑定用闭合文件夹），名称超长省略，右侧 12px 倒角；
 * hover / 展开时底色 --dsw-alias-interactive-bg-hover。
 *
 * 点开是 dsh 的工作区菜单：已有工作区列表（当前项打勾）+「添加工作区…」；
 * 一个工作区都没有时直接进目录选择（对应 dsh 的 addIsTheOnlyEntry）。
 */
@Composable
private fun WorkspaceChipRow(
    workspaces: List<com.adsh.app.core.data.WorkspaceEntity>,
    workspaceId: Long?,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onPick: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val current = workspaces.firstOrNull { it.id == workspaceId }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(Modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp)) {
        Row(
            modifier = Modifier
                .heightIn(min = 28.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (pressed || open) palette.hover else Color.Transparent)
                .clickable(interactionSource = interaction, indication = null) {
                    if (workspaces.isEmpty()) onAdd() else onOpenChange(!open)
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (current == null) DshIcons.FolderClose else DshIcons.FolderOpen,
                contentDescription = null,
                tint = palette.labelPrimary,
                modifier = Modifier.size(19.dp),
            )
            Text(
                text = current?.name ?: "选择工作区",
                modifier = Modifier.widthIn(max = 220.dp),
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = palette.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = DshIcons.ChevronDown,
                contentDescription = "选择工作区",
                tint = palette.labelCaption,
                modifier = Modifier.size(12.dp),
            )
        }
        if (open) {
            DshPopup(onDismiss = { onOpenChange(false) }, alignStart = true) {
                DshMenuCard(Modifier.width(200.dp)) {
                    workspaces.forEach { workspace ->
                        DshMenuRow(
                            label = workspace.name,
                            icon = DshIcons.FolderClose,
                            selected = workspace.id == workspaceId,
                            onClick = { onPick(workspace.id) },
                        )
                    }
                    DshMenuRow(
                        label = "添加工作区…",
                        icon = DshIcons.Plus,
                        onClick = onAdd,
                    )
                }
            }
        }
    }
}

/**
 * 这条路径是不是图片：按**魔数**判断，与发送时（describeAttachments）和消息里用的是同一个判定。
 * 以前这里只看扩展名，于是「输入框显示图片缩略图、发出去变成文件卡片」——
 * 拇指图能解出来就显示成图，而发出去的 mediaType 是按字节嗅探的。
 */
private fun isImagePath(path: String): Boolean =
    runCatching { imageMediaTypeOf(File(path)) != null }.getOrDefault(false)

/** 附件卡片：封面（图片缩略图 / 文件图标）+ 文件名 + 移除（dsh 的 attachment chip） */
@Composable
private fun AttachmentCard(path: String, onRemove: (String) -> Unit) {
    val palette = LocalDshPalette.current
    val name = remember(path) { File(path).name }
    // 是不是图片用**魔数**判定（与发出去之后消息里那一行同一套），不看扩展名：
    // 否则会出现「输入框里是图片缩略图、发出去变成文件卡片」这种前后不一致
    val isImage = remember(path) { isImagePath(path) }
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, path, isImage) {
        value = if (isImage) withContext(Dispatchers.IO) { decodeThumbnail(path) } else null
    }
    Row(
        modifier = Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.selector)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(palette.menu),
            contentAlignment = Alignment.Center,
        ) {
            val image = thumbnail
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = palette.labelTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = name,
            modifier = Modifier.widthIn(max = 130.dp),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = palette.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onRemove(path) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                DshIcons.CloseFill,
                contentDescription = "移除附件",
                tint = palette.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 生成附件封面：图片按 2 的幂下采样解码；非图片返回 null（用文件图标兜底） */
private const val THUMB_TARGET_PX = 144

private fun decodeThumbnail(path: String): ImageBitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= THUMB_TARGET_PX && bounds.outHeight / (sample * 2) >= THUMB_TARGET_PX) {
        sample *= 2
    }
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    android.graphics.BitmapFactory.decodeFile(path, options)?.asImageBitmap()
}.getOrNull()

/** dsh 的 .uV2eYG_add：28dp 圆形、--dsw-specific-selector 底、图标 14px */
@Composable
private fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (pressed || active) palette.hoverSolid else palette.selector)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = palette.labelPrimary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** dsh 的 PermissionSelect / ModelSelect trigger：28dp 高、圆角 24、图标 + 可转动倒角 */
@Composable
private fun TriggerPill(
    icon: ImageVector,
    contentDescription: String,
    open: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(120), label = "chevron")
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (pressed || open) palette.hover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = palette.labelSecondary, modifier = Modifier.size(14.dp))
        Icon(
            DshIcons.ChevronDown,
            contentDescription = null,
            tint = palette.labelCaption,
            modifier = Modifier.size(14.dp).rotate(rotation),
        )
    }
}

/** dsh 的 PlanModeControl chip：琥珀底、圆角 999、`Plan` + 关闭图标；点击 = /plan off */
@Composable
private fun PlanChip(onExit: () -> Unit) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (pressed) palette.warnLabel.copy(alpha = 0.18f) else palette.warnBg)
            .clickable(interactionSource = interaction, indication = null, onClick = onExit)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Plan", fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = palette.warnLabel)
        Icon(DshIcons.CloseFill, contentDescription = "退出计划模式", tint = palette.warnLabel, modifier = Modifier.size(12.dp))
    }
}

/**
 * 发送/停止：dsh 的 .uV2eYG_primary（34dp 圆形、deepseek-500 底、白色箭头，无涟漪）。
 *
 * dsh 的 primaryStops = running && (empty || blocked)：运行中只要草稿还是空的，主按钮才是停止；
 * 草稿一有内容，主按钮就变成「排队发送 / 插话发送」（走 busyEnter 那一档）。
 */
@Composable
private fun SendButton(
    sending: Boolean,
    enabled: Boolean,
    busyEnter: String,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val stops = sending && !enabled
    val active = sending || enabled
    val background by animateColorAsState(
        targetValue = if (active) palette.accent else palette.accent.copy(alpha = 0.4f),
        animationSpec = tween(180),
        label = "sendBg",
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(
                enabled = active,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (stops) onCancel() else onSend() },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = stops,
            transitionSpec = {
                (fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.7f)) togetherWith
                    (fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.7f))
            },
            label = "sendIcon",
        ) { isSending ->
            Icon(
                imageVector = if (isSending) DshIcons.Stop else DshIcons.ArrowUp,
                contentDescription = when {
                    isSending -> "停止生成"
                    sending -> if (busyEnter == com.adsh.app.core.data.SettingsStore.BUSY_STEER) "插话发送" else "排队发送"
                    else -> "发送消息"
                },
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ 触发菜单（＋ / ）

/**
 * dsh 的触发菜单（dsh-client-ui-input-trigger 的 MenuView）：`+` 与键入 `/` 打开的是
 * **同一个菜单**，逐项对齐 dsh 的 .menu / .viewport / .sectionTitle / .item：
 *
 * ```
 * .menu        max-height 400px、圆角 16px、内边距 3px、底边贴锚点上沿 4px、左右与锚点同宽
 * .sectionTitle 11/16 Medium 三级色、padding 5px 8px 2px、min-height 23px（非首节再 margin-top 3px）
 * .item        min-height 34px、圆角 8、padding 6px 8px、gap 6、13/20 主色；悬停 interactive-bg-hover
 * .itemIcon    14px 三级色
 * .itemName    标题，最多占 40% 宽（超出省略）
 * .itemAlias   命令名（本地化标题与命令名不同才显示），12/18 三级色，最多 20%
 * .itemDescription 说明，12/18 三级色、**右对齐**、占满剩余
 * ```
 *
 * 空查询时按 dsh 的 SECTION_ROWS 分「添加 / 指令」两节；一旦键入了查询，dsh 不显示小节标题。
 *
 * **窗口几何在菜单的整个生命周期里恒定**（高度 = [popupPanelMaxHeight] 夹出来的上限，卡片贴窗口底边）：
 * 列表随查询收窄/变长时，动的只是卡片自己的高度（纯组合层布局、即时生效），窗口本身不重摆。
 * 这是「删字时菜单从下往上滑一下」的根治办法 —— Popup 是独立窗口，窗口一变尺寸，
 * 系统会先画旧尺寸那一帧再跳到新位置（第 79 轮模型菜单踩的是同一个坑）。
 * dsh 的 DOM 版没有这个问题（.menu 只有 max-height，没有任何 transition，见 MenuView.module.css）。
 *
 * @param widthPx 锚点（输入卡片）的宽度，px：菜单与卡片同宽
 * @param anchorTopPx 锚点（输入卡片）顶边在 root 里的 y（px）：窗口高度按「它上方的空间」夹
 */
@Composable
fun CommandPalette(
    visible: Boolean,
    commands: List<PaletteCommand>,
    widthPx: Int,
    anchorTopPx: Float,
    onDismiss: () -> Unit,
    onPick: (PaletteCommand) -> Unit,
) {
    val palette = LocalDshPalette.current
    if (!visible) return
    val density = LocalDensity.current
    val width = with(density) { widthPx.toDp() }
    val windowHeight = popupPanelMaxHeight(
        density = density,
        anchorTopPx = anchorTopPx,
        safeTopPx = WindowInsets.statusBars.getTop(density),
        screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp,
        maxHeight = PALETTE_MAX_HEIGHT,
        minHeight = PALETTE_MIN_HEIGHT,
        gap = PALETTE_GAP,
        topMargin = PALETTE_TOP_MARGIN,
    )
    // 空查询才分节（dsh：查询非空时列表按匹配度排，不显示小节标题）
    val sectioned = commands.any { it.section.isNotEmpty() } &&
        commands.map { it.section }.distinct().size > 1
    DshPopup(onDismiss = onDismiss, alignStart = true, gap = PALETTE_GAP, liftBottom = 0.dp) {
        Box(
            modifier = if (widthPx > 0) Modifier.width(width).height(windowHeight) else Modifier,
            contentAlignment = Alignment.BottomStart,
        ) {
            // 卡片上方那一片空白也在弹窗窗口里（不抢焦点的 Popup 照样收得到触摸），
            // 落在窗口内、卡片外的点击必须自己接住：否则会被窗口吃掉 ——
            // 既不关菜单、也点不到下面的消息。行为与「点空白关闭」一致。
            Box(
                Modifier.matchParentSize().pointerInput(Unit) {
                    detectTapGestures { onDismiss() }
                },
            )
            DshMenuCard(
                modifier = if (widthPx > 0) Modifier.width(width) else Modifier,
                radius = 16.dp,
                padding = 3.dp,
            ) {
            BoxWithConstraints {
                // dsh 的两条宽度上限（.itemName 40% / .itemAlias 20%）：在同一个约束里算一次，
                // 免得每一行各做一次子组合
                val nameMax = maxWidth * 0.4f
                val aliasMax = maxWidth * 0.2f
                Column(
                    Modifier
                        .fillMaxWidth()
                        // 卡片自己的高度上限是「窗口高度减去卡片内边距」：超出就在卡片内部滚，
                        // 绝不让卡片长过窗口（长过去就会被窗口裁掉，也会把底边顶离锚点）
                        .heightIn(max = windowHeight - 6.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    var lastSection: String? = null
                    commands.forEach { command ->
                        if (sectioned && command.section != lastSection) {
                            // dsh 的 .sectionTitle：padding 5px 8px 2px（非首节再 margin-top 3px）、
                            // 11/16 Medium 三级色
                            Text(
                                text = command.section,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = 8.dp,
                                        end = 8.dp,
                                        top = if (lastSection == null) 5.dp else 8.dp,
                                        bottom = 2.dp,
                                    ),
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.labelTertiary,
                            )
                            lastSection = command.section
                        }
                        PaletteRow(
                            command = command,
                            nameMax = nameMax,
                            aliasMax = aliasMax,
                            onPick = { onPick(command) },
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
            }
        }
    }
}

/** 触发菜单的一行（dsh 的 .item，含图标 / 标题 / 别名 / 右对齐说明） */
@Composable
private fun PaletteRow(
    command: PaletteCommand,
    nameMax: Dp,
    aliasMax: Dp,
    onPick: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) palette.hover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onPick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = command.icon,
            contentDescription = null,
            tint = palette.labelTertiary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = command.label,
            modifier = Modifier.widthIn(max = nameMax),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = palette.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 别名：本地化标题与命令名不同时显示（dsh 的 item.label !== item.name）
        if (!command.label.equals(command.name, ignoreCase = true)) {
            Text(
                text = command.name,
                modifier = Modifier.widthIn(max = aliasMax),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (command.description.isNotEmpty()) {
            Text(
                text = command.description,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}
