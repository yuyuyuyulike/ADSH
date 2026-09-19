package com.adsh.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.delay

/**
 * dsh 的 DisclosureRow 系列行（ContextInjectionRow.module.css 与 ReasoningRow / ToolRow
 * 共用同一套行框架）：
 *
 * - 行高 24px；图标 14px + 6px 间距 + 标题（14/24，二级色，字重 400）；
 * - 可选的 2×2px 圆点分隔（label-caption，左右各 8px）；
 * - 可选的一段 13/24 三级色来源标签，再可选一段 13/24 三级色摘要（省略号）；
 * - 行尾倒角 16px；
 * - 展开后正文：margin 4px 0 0 22px、圆角 8、code-block 底、11/16 等宽字、
 *   max-height 141px、内边距 10px 16px 12px 12px。
 */
@Composable
fun DshDisclosureRow(
    icon: ImageVector?,
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    expandable: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    body: (@Composable () -> Unit)? = null,
) {
    val palette = LocalDshPalette.current
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = expandable,
                ) { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // dsh 的 DisclosureRow：倒角在 **leading** 位，不在行尾。
            //  - 收起：显示本来的图标（图标位 16×16，图标 14px，右侧 6px 间距）；
            //  - 展开：图标位换成向下的倒角（CSS 里悬停也是同一个位置换图标，触屏没有悬停，
            //    所以用「展开时变成倒角」表达；见 ._leading_luwio_29 / ._iconIdle_luwio_57）。
            if (icon != null) {
                RailLeadingInternal(icon = icon, open = open && expandable)
                Spacer(Modifier.width(6.dp))
            }
            Text(title, fontSize = 14.sp, lineHeight = 24.sp, color = palette.labelSecondary)
            trailing?.invoke()
            Spacer(Modifier.weight(1f))
        }
        if (open && body != null) {
            Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) { body() }
        }
    }
}

/** 行内来源标签（dsh 的 .XrJvXW_source）：13/24 三级色，前置 2×2px 圆点 */
@Composable
fun RailSourceLabel(text: String, summary: String? = null) {
    val palette = LocalDshPalette.current
    RailDotInternal()
    Text(
        text = text,
        fontSize = 13.sp,
        lineHeight = 24.sp,
        color = palette.labelTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 220.dp),
    )
    if (!summary.isNullOrBlank()) {
        RailDotInternal()
        Text(
            text = summary,
            fontSize = 13.sp,
            lineHeight = 24.sp,
            color = palette.labelTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** dsh 的 context 行正文框：等宽 11/16、圆角 8、code-block 底、最多 141px */
@Composable
fun DshCodeBody(text: String) {
    val palette = LocalDshPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp)
            .heightIn(max = 141.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.codeBlock)
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, top = 10.dp, end = 16.dp, bottom = 12.dp),
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = palette.labelTertiary,
        )
    }
}

/**
 * dsh 的 SystemPromptRow（message.systemPrompt / message.systemPromptUpdate）：
 * 浏览图标 14 + 标题 + 倒角；展开是系统提示词全文。
 */
@Composable
fun SystemPromptRow(text: String, update: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    DshDisclosureRow(
        // dsh 的 SystemPromptRow 用的是 IconBrowseOutline16（不是文件图标）
        icon = DshToolIcons.Browse,
        title = if (update) "系统提示词更新" else "系统提示词",
        open = open,
        onToggle = { open = !open },
        body = { DshCodeBody(text) },
    )
}

/**
 * dsh 的 ContextInjectionRow（message.contextInjection / message.contextRecall）：
 * 上下文注入图标 14 + 标题 + 圆点 + 来源标签（+ 摘要）+ 倒角；展开是注入原文。
 */
@Composable
fun ContextInjectionRow(label: String, form: String, text: String, recall: Boolean = false) {
    var open by rememberSaveable { mutableStateOf(false) }
    DshDisclosureRow(
        // dsh 的 ContextInjectionRow 用的是 IconContextInjectionOutline16
        icon = DshToolIcons.ContextInjection,
        title = if (recall) "跨会话召回" else "上下文注入",
        open = open,
        onToggle = { open = !open },
        trailing = if (label.isBlank()) null else {
            { RailSourceLabel(label, summary = formSummary(form)) }
        },
        body = { DshCodeBody(text) },
    )
}

private fun formSummary(form: String): String? = when (form) {
    "instructions" -> "已载入"
    else -> null
}

/** dsh 的 --dsw-static-deepseek-500（品牌蓝）：TurnStatus 的文字底色 */
val DeepseekBlue500 = Color(0xFF4176E6)

/** dsh 的 --dsw-static-deepseek-200：流光高光 */
val DeepseekBlue200 = Color(0xFFD3E2FF)

/**
 * dsh 的 TurnStatus（chat.deepDiving）—— 常驻在输入框左上角的「本轮模型活动」标识。
 *
 * 逐项对齐 .EvIC1a_turnStatus / .EvIC1a_turnStatusClock：
 *  - 行高 26px，14px 字重 600（这里按手机再放大到 15sp），文字用品牌蓝；
 *  - 流光 = 250% 宽的渐变贴图（deepseek-500 0~40% / deepseek-200 50% / deepseek-500 60~100%），
 *    background-position 100% → 0，1.8s 线性无限循环；
 *  - 左侧锚定（align-self:flex-start），右侧 8px 是 13px 的用时（label-caption、tabular-nums）。
 *
 * 与 dsh 的差别只有一处：dsh 是 elapsed >= 15s 才显示时钟，这里从 0 秒起就显示
 * （需求：右侧要能一直看到计时；本轮结束或被打断后整行随 sending 一起消失，时钟自然停）。
 */
@Composable
fun TurnStatusRow(startedAt: Long, modifier: Modifier = Modifier, label: String = "吃白饭中...") {
    val palette = LocalDshPalette.current
    val anchor = if (startedAt > 0) startedAt else remember { System.currentTimeMillis() }
    var elapsed by remember { mutableLongStateOf((System.currentTimeMillis() - anchor).coerceAtLeast(0)) }
    LaunchedEffect(anchor) {
        while (true) {
            elapsed = (System.currentTimeMillis() - anchor).coerceAtLeast(0)
            delay(1000)
        }
    }
    Row(
        modifier = modifier.fillMaxWidth().height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        ShimmerText(text = label)
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatRunDuration(elapsed),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = palette.labelCaption,
        )
    }
}

/**
 * 文字流光：dsh 的 .EvIC1a_turnStatus —— 250% 宽的渐变贴图从右往左扫，1.8s 线性无限循环。
 * Compose 没有 background-clip:text，这里把同一段渐变直接作为文字的 brush，并按帧平移
 * （贴图宽度按文字实测宽度取 2.5 倍，对应 background-size:250%）。
 */
@Composable
private fun ShimmerText(text: String) {
    val style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    val width = remember(measurer, text) {
        measurer.measure(AnnotatedString(text), style).size.width.toFloat().coerceAtLeast(1f)
    }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1800, easing = LinearEasing)),
        label = "shimmerPhase",
    )
    // background-position: 100% → 0  ⇒  startX: -1.5W → 0
    val startX = -1.5f * width * (1f - phase)
    Text(
        text = text,
        style = style.copy(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to DeepseekBlue500,
                    0.4f to DeepseekBlue500,
                    0.5f to DeepseekBlue200,
                    0.6f to DeepseekBlue500,
                    1f to DeepseekBlue500,
                ),
                startX = startX,
                endX = startX + 2.5f * width,
                tileMode = TileMode.Clamp,
            ),
        ),
    )
}

/**
 * 倒角：16px，收起时 -90°（指向右），展开时 0°（指向下），transition .1s。
 * 单独一份是为了给「倒角在行尾」的构件（轮折叠行）用。
 */
@Composable
internal fun RailChevronInternal(open: Boolean, tint: Color, leadingGap: Dp) {
    val rotation by animateFloatAsState(if (open) 0f else -90f, tween(100), label = "railChevron")
    Spacer(Modifier.width(leadingGap))
    Icon(
        DshIcons.ChevronDown,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(16.dp).rotate(rotation),
    )
}

/**
 * dsh 的 ._leading_luwio_29（DisclosureRow 的图标位）：
 * 收起时是图标本身（14px，三级色），展开时整格换成向下的倒角（二级色）。
 * 与 TurnRail 里工具行 / 思考行的 RailLeading 行为一致。
 */
@Composable
internal fun RailLeadingInternal(icon: ImageVector, open: Boolean) {
    val palette = LocalDshPalette.current
    Icon(
        imageVector = if (open) DshIcons.ChevronDown else icon,
        contentDescription = null,
        tint = if (open) palette.labelSecondary else palette.labelTertiary,
        modifier = Modifier.size(14.dp),
    )
}

/** 2×2px 圆点，左右各 8px（dsh 的 .lcKema_separator / .XrJvXW_sep） */
@Composable
internal fun RailDotInternal() {
    val palette = LocalDshPalette.current
    Box(
        Modifier
            .padding(horizontal = 8.dp)
            .size(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(palette.labelCaption),
    )
}
