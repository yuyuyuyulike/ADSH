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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
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
private val CLAIM_TOKENS = listOf("/plan")

/** 取出草稿开头的 claim token（dsh 的 input.claim.token） */
fun claimTokenOf(draft: String): String? {
    val text = draft.trimStart()
    return CLAIM_TOKENS.firstOrNull { token ->
        text == token || (text.startsWith(token) && text.length > token.length && text[token.length].isWhitespace())
    }
}

/** dsh 的 hint.plan（token 之后还没输入内容时显示） */
private fun claimHintOf(token: String?): String? = when (token) {
    "/plan" -> COMPOSER_HINT_PLAN
    else -> null
}


/** 触发菜单窗口的高度上限（dsh 的 .menu{max-height:400px}，见 MenuView.module.css:12） */
private val PALETTE_MAX_HEIGHT = 394.dp
/** 触发菜单窗口的最小高度：与模型子页同一个下限，再挤也不把菜单压成一条 */
private val PALETTE_MIN_HEIGHT = 160.dp
/** 触发菜单与输入卡片上沿的间距（dsh 的 .menu{bottom: calc(100% + 4px)}） */
private val PALETTE_GAP = 4.dp
/** 触发菜单卡片顶边至少要避开的距离（状态栏高度拿不到时用它） */
private val PALETTE_TOP_MARGIN = 8.dp


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
internal fun popupPanelMaxHeight(
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
    /**
     * 与**窗口边**之间至少留出的距离（dsh 的 useAnchoredPosition margin，默认等于 [gap]）。
     *
     * dsh 的浮层定位是「先按锚点摆，再夹进视口」：左边界 = 锚点左边（[alignStart]）或右边界减浮层
     * 宽度，然后夹进 [margin, 窗口宽 - 浮层宽 - margin]；上下同理，上方浮层 = 锚点上边减间距减高度。
     * 统计 / 用量 / 上下文那几个面板取 12dp —— 它们贴着屏幕底边弹，夹边距决定气泡不顶到屏幕边缘。
     */
    margin: androidx.compose.ui.unit.Dp = gap,
    content: @Composable () -> Unit,
) {
    RegisterOverlayDismiss(onDismiss)
    val density = LocalDensity.current
    val lift = with(density) { liftBottom.roundToPx() }
    val provider = remember(density, alignStart, below, lift, gap, margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val gapPx = with(density) { gap.roundToPx() }
                val marginPx = with(density) { margin.roundToPx() }
                val width = popupContentSize.width
                val height = popupContentSize.height
                val x = if (alignStart) anchorBounds.left else anchorBounds.right - width
                val y = if (below) {
                    anchorBounds.bottom + gapPx
                } else {
                    anchorBounds.top - height - gapPx - lift
                }
                // 宽 / 高还没测出来（=0）时不夹 —— 与 dsh 的 if (width > 0) 同一条
                val clampedX = if (width > 0) minOf(maxOf(x, marginPx), windowSize.width - width - marginPx) else x
                val clampedY = if (height > 0) minOf(maxOf(y, marginPx), windowSize.height - height - marginPx) else y
                return IntOffset(clampedX, clampedY)
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
            // 不夹在父窗口里：默认的弹窗窗口**不含导航栏那一条**（真机 1280x2568，屏是 1280x2772），
            // 而输入框下方那一排胶囊正好落在被切掉的那一段里 —— 定位算法按 dsh 的
            // useAnchoredPosition 把浮层「夹进视口」时，用的就是这个小窗口，于是气泡被整整顶上去
            // 33dp（实测 anchor.top 2636、窗口高 2568 → 只能摆到 2009）。关掉裁剪之后窗口就是整屏，
            // 夹取才等于 dsh 里的 window.innerWidth/innerHeight。
            clippingEnabled = false,
        ),
    ) { content() }
}

/**
 * dsh 的菜单卡片：--dsw-specific-menu（纯白）+ 圆角 20 + 4px 内边距 + elevation-prominent
 *
 * **要滚动就在 content 里套一层 Column**，别把 `verticalScroll` 传给 `modifier`
 * （第 99 轮真机反馈：「展开小卡片选择提供方并滑动时，小卡片上方消失了一点、圆角也没有了」）：
 * Surface 把 shape/background 接在 `modifier` **之后**，滚动节点一旦在外层，背景与圆角就成了
 * 滚动内容的一部分，一滑就跟着走；正确的形状见 Composer 的模型菜单与设置页的提供方下拉。
 */
@Composable
fun DshMenuCard(
    modifier: Modifier = Modifier,
    /** 统一 12dp：与上下文占用窗口（dsh 的 ._2WTFBq_panel）/ 统计卡（.Xt1eiG_panel）一致 */
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
            .dshClickable(interactionSource = interaction, onClick = onClick)
            .padding(horizontal = DshSpacing.Xxxl, vertical = DshSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
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
 * 上下文占用（dsh 的 ContextMeter，._2WTFBq_trigger）：
 * **圆环 + 百分比**组成的胶囊（14px 圆环 / 2px 线宽、8px 圆角底、内边距 1px 8px、gap 6px、
 * 13/20 三级字色），点开是 264dp 宽的气泡 —— 「上下文已用 X% … ~已用 / 窗口」+ 4px 分段条 +
 * 系统提示词 / 工具定义 / 对话消息。
 *
 * 它在输入框下方那一排里（见 [ChatStatsDock]），所以气泡一律往**上**弹
 * （dsh 的 side: "top"、gap 8、margin 12 —— 贴着屏幕底边弹也不会被切掉）。
 */
@Composable
fun ContextMeter(
    usage: ContextUsage,
    open: Boolean = false,
    onToggle: () -> Unit = {},
) {
    val palette = LocalDshPalette.current
    // dsh 的 ._2WTFBq_fill / trigger 都是 label-tertiary；展开时换二级色 + hover 底
    val ink = if (open) palette.labelSecondary else palette.labelTertiary
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (open) palette.hover else Color.Transparent)
                .dshClickable(interactionSource = dshInteraction(), onClick = onToggle)
                .semantics { contentDescription = "上下文已用 " + usage.percent + "%" }
                .padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
        ) {
            // dsh 的圆环：viewBox 14、r=5.5、线宽 2、从 -90° 起画
            Canvas(Modifier.size(14.dp)) {
                val stroke = 2.dp.toPx()
                val radius = 5.5f / 14f * size.minDimension
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(palette.borderL3, radius = radius, center = center, style = Stroke(stroke))
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
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = ink,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (open) {
            DshPopup(onDismiss = onToggle, margin = DshSpacing.Xxxl) { ContextPanel(usage) }
        }
    }
}

/** dsh 的 ._2WTFBq_panel：264px 宽、16px 圆角（--dsw-radius-lg）、12px 内边距、12/20 字号 */
@Composable
private fun ContextPanel(usage: ContextUsage) {
    val palette = LocalDshPalette.current
    val (systemColor, toolsColor, messagesColor) = contextColors()
    val total = (usage.system + usage.tools + usage.messages).coerceAtLeast(1L)
    DshMenuCard(modifier = Modifier.width(264.dp), radius = 16.dp, padding = 12.dp) {
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
                .padding(top = DshSpacing.Xxl, bottom = DshSpacing.Xxxl)
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
            .padding(vertical = DshSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxxl),
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
 * 右：模型（**模型名** + 倒角，内含 模型 / 推理等级 两个子菜单）、发送/停止（34dp 圆形）。
 * 上下文占用不在这一行（在卡片**下方**那一排，见 [dock]），所以模型按钮的位置不随会话状态变化。
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
    busyEnter: String,
    /** 模型菜单的数据：按提供方分组（dsh 的 ModelSelect groups） */
    modelGroups: List<ChatViewModel.ModelGroup>,
    currentModel: String,
    /** 当前提供方 id：选中态要「提供方 + 模型」都对上 */
    currentProviderId: String,
    /** 每个提供方的余额（DeepSeek 专有接口；显示在分组标题的最右边） */
    balances: Map<String, ChatViewModel.BalanceState>,
    /** 打开模型菜单时刷新余额（每次点开自动刷新一次） */
    onRefreshBalance: () -> Unit,
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
    onPaletteDismiss: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    planMode: Boolean,
    onTogglePlan: () -> Unit,
    /** 待发附件（会话私有目录里的绝对路径）：显示在输入框内部、文字输入区上方 */
    attachments: List<String>,
    onRemoveAttachment: (String) -> Unit,
    /** 命令面板里的 /permission 直接展开权限菜单 */
    requestPermission: Boolean,
    onRequestHandled: () -> Unit,
    /** 是否显示输入框外的工作区 chip（dsh 只在 hero 阶段显示） */
    showWorkspace: Boolean,
    /** 已绑定的工作区（dsh 侧栏的树）与当前会话所属工作区 */
    workspaces: List<com.adsh.app.core.data.WorkspaceEntity>,
    workspaceId: Long?,
    onPickWorkspace: (Long) -> Unit,
    onAddWorkspace: () -> Unit,
    /**
     * 父层主动改写草稿的次数（发送后清空、指令面板写入 token）。
     * 只有这个计数变化时输入框才会被外部回灌，见 fieldValue 的注释。
     */
    draftRevision: Int,
    /** 当前打开的那一个浮层（[ChatOverlays.open]；本组件只认 [OPEN_PERMISSION] / [OPEN_MODEL] / [OPEN_WORKSPACE]） */
    menu: String?,
    onMenuChange: (String?) -> Unit,
    /**
     * 输入框**下方**那一排（dsh 的 InputBar dock：会话统计 / Token 用量 / 上下文占用）。
     *
     * 由调用方给内容：这一排要读会话统计与上下文占用，而它们不属于输入框的状态。
     * 位置在这里（而不是调用点）是因为间距属于输入框那一摞：dsh 的 dock 就是卡片后面的
     * 下一个兄弟节点，间距 4px 由它自己带（.RlGAzG_dock 的 padding-top）。
     */
    dock: @Composable () -> Unit = {},
) {
    val palette = LocalDshPalette.current
    // 三个弹层互斥；状态托管给 ChatScreen（弹层自己登记进全局登记处，不用再铺拦截层）
    // 弹层贴着「整个输入框的上沿」而不是按钮上沿：先记下输入框卡片与触发按钮的顶边
    var composerCardTop by remember { mutableFloatStateOf(0f) }
    /** 输入卡片的宽度（px）：触发菜单与卡片同宽（dsh 的 .menu 是 left:0;right:0） */
    var composerCardWidth by remember { mutableIntStateOf(0) }
    val permissionOpen = menu == "permission"
    val modelOpen = menu == "model"
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

    // 下边距 4dp：dsh 的 .RlGAzG_root 就是 padding-bottom:4px（输入框那一摞的下沿）
    Column(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.Xxl).padding(bottom = DshSpacing.Md)) {
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
            Column(Modifier.padding(start = DshSpacing.Xl, end = DshSpacing.Xl, top = DshSpacing.Xxl, bottom = DshSpacing.Lg)) {
                // 附件在输入框内部：卡片自动变高，文字输入区留在下方
                if (attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = DshSpacing.Lg),
                        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
                    ) {
                        attachments.forEach { path -> AttachmentCard(path = path, onRemove = onRemoveAttachment) }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = DshSpacing.Lg)
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
                    horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
                ) {
                    // ＋：触发菜单（与键入 "/" 同一个菜单，含「文件」那一行）。
                    // dsh 的 .uV2eYG_tools：28px 圆形、--dsw-specific-selector 底、gap 8px（窄容器）。
                    CircleIconButton(DshIcons.Plus, "添加文件或调用指令", active = paletteVisible) {
                        onMenuChange(null)
                        onPaletteVisibleChange(!paletteVisible)
                    }

                    // 权限预设（弹层在 ComposerMenus.kt，R15 搬出主函数）
                    PermissionMenuTrigger(
                        permission = permission,
                        open = permissionOpen,
                        cardWidthPx = composerCardWidth,
                        onOpenChange = { want -> onMenuChange(if (want) "permission" else null) },
                        onSelect = onSelectPermission,
                        onSelectNeedsConfirm = { confirmFullAccess = true },
                    )

                    if (planMode) PlanChip(onExit = onTogglePlan)

                    Spacer(Modifier.weight(1f))

                    // 模型 / 推理等级两级菜单（弹层在 ComposerMenus.kt，R15 搬出主函数）
                    ModelMenuTrigger(
                        open = modelOpen,
                        modelGroups = modelGroups,
                        balances = balances,
                        currentModel = currentModel,
                        currentProviderId = currentProviderId,
                        efforts = efforts,
                        currentEffort = currentEffort,
                        onOpenChange = { want -> onMenuChange(if (want) "model" else null) },
                        onSelectModel = onSelectModel,
                        onSelectEffort = onSelectEffort,
                    )

                    // 上下文占用不在这一行里：dsh 把它放在输入卡片**下方**的 dock 里
                    // （见 dock 参数与 ChatStatsDock）。它以前在这一行的最右边，出现在会话
                    // 开始之后 —— 一出现就把左边的模型按钮顶走一格。
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

        // 输入框下方那一排（会话统计 / Token 用量 / 上下文占用）：dsh 的 .RlGAzG_dock，
        // 在卡片**后面**、间距 4px；没有会话时调用方传空的 lambda，这一排整个不存在。
        dock()

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
    // 倒角跟着展开态翻转（与权限 / 模型那两个 TriggerPill 同一套：120ms 转 180°）——
    // 展开后朝上。以前它是写死的 ChevronDown，展开时纹丝不动（用户第 103 轮点名）。
    val chevron by animateFloatAsState(if (open) 180f else 0f, tween(120), label = "workspaceChevron")
    Box(Modifier.fillMaxWidth().padding(start = DshSpacing.Xxl, end = DshSpacing.Card)) {
        Row(
            modifier = Modifier
                .heightIn(min = 28.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (pressed || open) palette.hover else Color.Transparent)
                .dshClickable(interactionSource = interaction) {
                    if (workspaces.isEmpty()) onAdd() else onOpenChange(!open)
                }
                .padding(horizontal = DshSpacing.Xl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Md),
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
                modifier = Modifier.size(12.dp).rotate(chevron),
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
// isImagePath 已搬到 ui/ImageDecode.kt（与缩略图解码同一个家：图片这条路上的共用件只有一处）

/**
 * 待发附件：**图片**与**文件**是两种形状（各自向参考实现看齐）。
 *
 *  - 图片：只显示缩略图，**不带文件名**，移除的叉压在图的右上角（dsh 的图片附件）；
 *  - 文件：类型图标（与文件浏览里同一个 [FileTypeIcon]）+ 文件名 + 大小，
 *    右上角一枚深色圆形叉徽标 —— 形状照 DeepSeek app 的附件卡片。
 *
 * 是不是图片用**魔数**判定（与发出去之后消息里那一行同一套），不看扩展名：
 * 否则会出现「输入框里是图片缩略图、发出去变成文件卡片」这种前后不一致。
 */
@Composable
private fun AttachmentCard(path: String, onRemove: (String) -> Unit) {
    if (remember(path) { isImagePath(path) }) {
        ImageAttachment(path, onRemove)
    } else {
        FileAttachment(path, onRemove)
    }
}

/** 图片附件：缩略图 + 压在右上角的叉（没有文件名 —— dsh 那边也没有） */
@Composable
private fun ImageAttachment(path: String, onRemove: (String) -> Unit) {
    val palette = LocalDshPalette.current
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { cachedImage(path, THUMB_TARGET_PX) }
    }
    Box(Modifier.size(72.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.selector),
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
            }
        }
        RemoveBadge(
            onRemove = { onRemove(path) },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/** 文件附件：类型图标 + 文件名 + 大小（右上角同样是那枚叉徽标） */
@Composable
private fun FileAttachment(path: String, onRemove: (String) -> Unit) {
    val palette = LocalDshPalette.current
    val file = remember(path) { File(path) }
    val size = remember(path) { runCatching { file.length() }.getOrDefault(0L) }
    Box(Modifier.widthIn(max = 240.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(palette.selector)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 与工作区文件浏览同一个图标（按类型给角标：PDF / MD / PY …）
            FileTypeIcon(name = file.name, size = 40.dp)
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = file.name,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = fileSizeText(size),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = palette.labelTertiary,
                    maxLines = 1,
                )
            }
        }
        RemoveBadge(
            onRemove = { onRemove(path) },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/**
 * 移除徽标：一枚深色圆底 + 白色叉，压在卡片的右上角上（DeepSeek app 的附件卡片就这么画的）。
 *
 * 深色用「黑 55%」而不是某个主题令牌：它在浅色卡片和深色卡片上都得是**深底白叉**
 * （用 labelPrimary 这类跟着主题反转的令牌，深色主题下会变成白圆底）。
 */
@Composable
private fun RemoveBadge(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .offset(x = 6.dp, y = (-6).dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(REMOVE_BADGE_BACKGROUND)
            .dshClickable(interactionSource = dshInteraction()) { onRemove() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            DshIcons.CloseFill,
            contentDescription = "移除附件",
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** 移除徽标的底色：黑 55%（深浅两套主题下都是「深底白叉」） */
private val REMOVE_BADGE_BACKGROUND = Color(0x8C000000)

/** 待发附件缩略图的目标长边（解码与缓存在 ui/ImageDecode.kt，第 182 轮起只有那一份实现） */
private const val THUMB_TARGET_PX = 144

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
            .dshClickable(interactionSource = interaction, onClick = onClick),
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

/** dsh 的 PermissionSelect trigger：28dp 高、圆角 24、图标 + 可转动倒角 */
@Composable
internal fun TriggerPill(
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
            .dshClickable(interactionSource = interaction, onClick = onClick)
            .padding(start = DshSpacing.Xl, end = DshSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Md),
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

/**
 * dsh 的 ModelSelect 触发器（.wq12jW_trigger）：**模型名 + 倒角**，高 28、圆角 8（--dsw-radius-sm）、
 * 内边距 0 4 0 8、gap 4、13/20 字、二级色。
 *
 * 两处刻意与 dsh 保持一致：
 *  - 图标（IconDataOutline16）在 dsh 里是**窄容器专用的备用形态** ——
 *    --dsh-composer-model-icon-display 默认 none，只有一行放不下时才换出来，所以这里不画；
 *  - 推理等级是触发器里的另一个 span（dsh 的 triggerEffort），用户口径是**不显示**，
 *    它仍然留在菜单的「推理等级」那一页里。
 */
@Composable
internal fun ModelTriggerPill(label: String, open: Boolean, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(120), label = "chevron")
    // dsh 的 max-width 是 min(360px, 45cqw)，cqw 是**输入框那一行**的宽度（手机上是窗口宽减两侧留白）
    val maxWidth = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.width * 0.45f).toDp()
    }
    Row(
        modifier = Modifier
            .height(28.dp)
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(8.dp))
            .background(if (open) palette.hover else Color.Transparent)
            .dshClickable(interactionSource = dshInteraction(), onClick = onClick)
            .padding(start = DshSpacing.Xl, end = DshSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Md),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f, fill = false),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = palette.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
            .dshClickable(interactionSource = interaction, onClick = onExit)
            .padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Md),
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
            .dshClickable(enabled = active, interactionSource = dshInteraction()) { if (stops) onCancel() else onSend() },
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
                                        start = DshSpacing.Xl,
                                        end = DshSpacing.Xl,
                                        top = if (lastSection == null) 5.dp else 8.dp,
                                        bottom = DshSpacing.Xs,
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
            .dshClickable(interactionSource = interaction, onClick = onPick)
            .padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
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

/**
 * 附件选择器：系统文件选择器（多选）→ [onImport] 把选中的文件复制进「工作区/.adsh/attachments/<会话>/」。
 *
 * R20 从 `ChatScreen` 搬出来（原来那 11 行就挂在主函数里）。导入成功是「看得见的结果」（附件卡片会
 * 出现在输入框里），所以**不弹提示**；只有失败才提示，因为那是用户看不出来的。
 *
 * @return 一个「打开选择器」的动作，接给触发菜单的「文件」那一行。
 */
@Composable
internal fun rememberAttachmentPicker(onImport: (List<android.net.Uri>) -> ImportResult): () -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val result = onImport(uris)
        if (result.failures.isNotEmpty()) {
            android.widget.Toast.makeText(context, "导入失败：" + result.failures.first(), android.widget.Toast.LENGTH_LONG).show()
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

