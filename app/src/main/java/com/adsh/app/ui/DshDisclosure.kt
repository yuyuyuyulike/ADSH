package com.adsh.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.delay

/*
 * 会话里「一行」用到的全部基元：行骨架、图标位、倒角、分隔点、运行扫光、文字微光。
 *
 * 这些东西以前在 TurnRail（工具行 / 思考行）与 DshDisclosure（系统提示行 / 上下文行）
 * 里各写了一份 —— 两套 leading、两套倒角、两套扫光。现在只有这一份，行几何也只在这里
 * 定义一次（dsh 的 primitives/DisclosureRow.module.css 就是这样一个共享构件）。
 */

/*
 * 运行态的「流光」只有一处：下面 [RunningSweep] 的**延迟扫光**（跑够 2 秒才从行首扫到行尾）。
 *
 * 这里原先还有第二处 —— dsh 的 TextShimmer（把 250% 宽的渐变当文字 brush，让标题 / 摘要自己
 * 也流动）。两者叠在一起时，一行里同时有「文字在流」和「一道光扫过」，用户实测的观感是
 * 「调用工具时流光太多了」（第 77 轮）。dsh 网页端其实也只有底下那一道扫光，
 * 文字微光是同一行上的另一种实现，这里按用户要求只留扫光。
 */

// ------------------------------------------------------------------ 行骨架

/**
 * 会话行里的**一段文字**（标题 / 摘要 / 后缀）：单行、超出省略。
 *
 * 会话里所有这类文字都是这个形态，所以只留一处定义 —— 以前「运行态文字微光」还在时，
 * 调用方得在「微光版」和「普通版」之间二选一，两边的字号 / 行高 / 省略行为各写一份，
 * 很容易漂移（第 77 轮把文字微光整条删掉之后就只剩这一种了）。
 */
@Composable
fun DshRowTitle(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * dsh 的 DisclosureRow（primitives/lib/DisclosureRow.module.css）——**会话里所有可展开行的唯一骨架**：
 * 思考行、工具行、系统提示行、上下文注入行都用它，行几何只在这里定义一次。
 *
 * ```
 * .row      height calc(24px + D)、position:relative、overflow:hidden、flex 居中
 * .leading  16×16 的图标格（margin-right 6px、色 label-tertiary），里面 svg 14×14
 * .title    13px（secondary 档）/ calc(24px + D)、label-secondary、字重 400
 * .iconIdle / .chevronHover  opacity 100ms ease 交叉淡出
 * ```
 *
 * dsh 是「悬停时图标换成倒角」；触屏没有悬停，这里用「**展开时**换成向上倒角、收起时是本体图标」
 * 表达同一件事（收起态也确实需要倒角来提示可展开）。
 *
 * @param leading 覆盖左侧图标格（工具行的 error / stopped 状态点用它）
 * @param running 运行态：行上一道 2.6s ease-out 的扫光（延迟 2 秒才出现，见 [RunningSweep]）
 * @param trailing 标题之后的内容（分隔点 + 摘要 + 后缀），通常自带 weight(1f)
 * @param expandable 不可展开的行没有点击手势（dsh 的 preparing 阶段）
 */
@Composable
fun DshDisclosureRow(
    icon: ImageVector?,
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    expandable: Boolean = true,
    running: Boolean = false,
    rowHeight: Dp = 24.dp,
    titleSize: TextUnit = 13.sp,
    titleColor: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    body: (@Composable () -> Unit)? = null,
) {
    val palette = LocalDshPalette.current
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = expandable,
                    ) { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    leading()
                } else if (icon != null) {
                    RailLeading(icon = icon, open = open && expandable)
                }
                DshRowTitle(
                    text = title,
                    fontSize = titleSize,
                    lineHeight = titleSize * 1.85f,
                    color = titleColor ?: palette.labelSecondary,
                )
                if (trailing != null) trailing()
            }
            if (running) RunningSweep(Modifier.matchParentSize())
        }
        if (open && body != null) {
            Box(Modifier.fillMaxWidth()) { body() }
        }
    }
}

/**
 * dsh 的 .leading（16×16 的图标格）：收起时是本体图标（14px、label-tertiary），
 * 展开时换成正上/正下的倒角（label-secondary），两者 100ms 交叉淡出。
 */
@Composable
fun RailLeading(icon: ImageVector, open: Boolean) {
    val palette = LocalDshPalette.current
    val idleAlpha by animateFloatAsState(if (open) 0f else 1f, tween(100), label = "leadingIdle")
    val chevronAlpha by animateFloatAsState(if (open) 1f else 0f, tween(100), label = "leadingChevron")
    Box(Modifier.size(16.dp).padding(end = 6.dp), contentAlignment = Alignment.Center) {
        if (idleAlpha > 0f) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.labelTertiary,
                modifier = Modifier.size(14.dp).alpha(idleAlpha),
            )
        }
        if (chevronAlpha > 0f) {
            Icon(
                // 展开时朝下（0°）—— 与折叠行的倒角同一套角度：收起朝右、展开朝下（第 76 轮用户口径）
                imageVector = DshIcons.ChevronDown,
                contentDescription = null,
                tint = palette.labelSecondary,
                modifier = Modifier.size(14.dp).alpha(chevronAlpha),
            )
        }
    }
}

/** 行内来源标签（dsh 的 .XrJvXW_source）：13/24 三级色，前置 2×2px 圆点 */
@Composable
fun RailSourceLabel(text: String, summary: String? = null) {
    val palette = LocalDshPalette.current
    RailDot()
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
        RailDot()
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

/** 2×2px 圆点，左右各 8px（dsh 的 .lcKema_separator / .o3BgMG_sep） */
@Composable
fun RailDot() {
    val palette = LocalDshPalette.current
    Box(
        Modifier
            .padding(horizontal = 8.dp)
            .size(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(palette.labelCaption),
    )
}

/**
 * 轮次控制行的倒角（`.l_V-RG_chevron`：14×14、label-caption、margin-left 4px、
 * `transition: transform .1s`）。
 *
 * 角度按用户要求（第 76 轮）：**收起时朝右（-90°）、展开时朝下（0°）** ——
 * 收起 = 下面还有内容可以展开，展开 = 内容已经铺在下面。
 * （dsh 是「收起朝下、展开朝上」，这里按用户口径改。）
 */
@Composable
fun RailChevron(open: Boolean, leadingGap: Dp = 4.dp) {
    val palette = LocalDshPalette.current
    val rotation by animateFloatAsState(if (open) 0f else -90f, tween(100), label = "railChevron")
    Spacer(Modifier.width(leadingGap))
    Icon(
        DshIcons.ChevronDown,
        contentDescription = null,
        tint = palette.labelCaption,
        modifier = Modifier.size(14.dp).rotate(rotation),
    )
}

// ------------------------------------------------------------------ 运行指示

/**
 * dsh 的运行扫光（ReasoningRow / ToolRow 的 `[data-state=running]` 伪元素）：
 * `@keyframes{0%{left:-300px} 90%,to{left:100%}}` + `2.6s ease-out infinite` ——
 * 前 90%（2.34s）用 ease-out 从行首外扫到行尾外，最后 10%（260ms）停住不动。
 *
 * **比 dsh 多一个 2 秒的入场延迟**（用户要求）：执行很快的工具（读个文件、跑条命令）在扫光
 * 刚扫出来的那一瞬间就结束了，扫光被撤掉 —— 看起来像「闪了一下」。等这一行真的跑了 2 秒
 * 才开始扫，快的工具从出现到结束全程没有扫光，只有真正在等的行才在流动。
 * 延迟状态用 rememberSaveable：行被滑出屏幕再滑回来（LazyColumn 回收）不会重新计时。
 */
@Composable
fun RunningSweep(modifier: Modifier = Modifier) {
    var flowing by rememberSaveable { mutableStateOf(RUNNING_SWEEP_DELAY_MS <= 0L) }
    LaunchedEffect(Unit) {
        if (!flowing) {
            delay(RUNNING_SWEEP_DELAY_MS)
            flowing = true
        }
    }
    if (!flowing) return
    val bg = MaterialTheme.colorScheme.background
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sweepPx = with(density) { 300.dp.toPx() }
    val transition = rememberInfiniteTransition(label = "sweep")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweepProgress",
    )
    Canvas(modifier.clipToBounds()) {
        // CSS ease-out = cubic-bezier(0, 0, 0.58, 1)
        val t = (progress / 0.9f).coerceAtMost(1f)
        val u = 1f - t
        val eased = 1f - u * u * u
        val x = -sweepPx + eased * (size.width + sweepPx)
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    // dsh 的峰值是底色的 60%；这里按需求加强到 90%，扫光才看得出来
                    0.55f to bg.copy(alpha = 0.9f),
                    1f to Color.Transparent,
                ),
                startX = x,
                endX = x + sweepPx,
            ),
            topLeft = Offset(x, 0f),
            size = Size(sweepPx, size.height),
        )
    }
}

/**
 * 运行扫光的入场延迟（ms）：跑够这么久才开始扫。
 * 快工具（< 2s）全程没有扫光，也就不会出现「刚出现就停」的闪烁感。
 */
private const val RUNNING_SWEEP_DELAY_MS = 2000L

// ------------------------------------------------------------------ 具体行

/** dsh 的 context 行正文框：等宽 11/16、圆角 8、code-block 底、最多 141px */
@Composable
fun DshCodeBody(text: String) {
    val palette = LocalDshPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, top = 4.dp, bottom = 4.dp)
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
