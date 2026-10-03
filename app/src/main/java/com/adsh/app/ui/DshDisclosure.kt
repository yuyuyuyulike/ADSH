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
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
 * 行标题字号（dsh 的 `.title` 是 13px，secondary 档）。
 * 调用方不能改它 —— 以前那两个「标题字号 / 标题颜色」参数在四处调用点一次都没传过，第 96 轮删掉。
 */
private val TITLE_SIZE = 13.sp

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
 * @param running 运行态：行上一道 2.6s ease-out 的扫光（延迟 1 秒才出现，见 [RunningSweep]）
 * @param failed 这一行失败了：图标格交叉淡出成一个状态点（dsh 的 `leadingFor(error/stopped)`）。
 *   100ms 的交叉淡出是**照 dsh 的 CSS 抄的**（ToolRow 的 `.title/.summary{transition:color .1s}`
 *   与 DisclosureRow 的 `.iconIdle/.chevronHover`）：失败那一帧「红点 + 变色 + 换摘要」三处
 *   同时变，硬切看着就是闪一下。
 * @param sweepEpoch 扫光时钟起点；0 = 自己取（见 [rememberSweepEpoch]）。工具行会把自己的
 *   传下来，让被组合的子行与它同相
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
    failed: Boolean = false,
    sweepEpoch: Long = 0L,
    rowHeight: Dp = 24.dp,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    body: (@Composable () -> Unit)? = null,
) {
    val palette = LocalDshPalette.current
    val ownSweepEpoch = rememberSweepEpoch()
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .dshClickable(interactionSource = dshInteraction(), enabled = expandable) { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    RailLeading(icon = icon, open = open && expandable, failed = failed)
                }
                DshRowTitle(
                    text = title,
                    // dsh 的 .title 是 13px（secondary 档），行高按内容字号的 1.85 倍
                    fontSize = TITLE_SIZE,
                    lineHeight = TITLE_SIZE * 1.85f,
                    color = palette.labelSecondary,
                )
                if (trailing != null) trailing()
            }
            if (running) {
                RunningSweep(
                    epoch = if (sweepEpoch > 0L) sweepEpoch else ownSweepEpoch,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        if (open && body != null) {
            Box(Modifier.fillMaxWidth()) { body() }
        }
    }
}

/**
 * 图标格里画什么。**纯函数**（单测直接打 `RailLeadingTest`）：它的唯一规矩是
 * **失败优先** —— 一行失败了就画状态点，不管它是不是展开着；否则展开画倒角，收起画本体图标。
 *
 * 抽出来的理由就是第 101 轮那个 bug：以前这个「画什么」是由三个动画的中间值隐式决定的
 * （`iconAlpha > 0f` / `chevron > 0f` / `failedAlpha > 0f`），动画值一旦没推进，三样都不画 ——
 * 行首成了空格子。判定与动画分开之后，这条规矩可以被单测钉住。
 */
internal enum class RailLeadingState { DOT, CHEVRON, ICON }

internal fun railLeadingState(open: Boolean, failed: Boolean): RailLeadingState = when {
    failed -> RailLeadingState.DOT
    open -> RailLeadingState.CHEVRON
    else -> RailLeadingState.ICON
}

/**
 * dsh 的 .leading（16×16 的图标格）：收起时是本体图标（14px、label-tertiary），
 * 展开时换成正上/正下的倒角（label-secondary），失败时是状态点（6px、error 色）。
 *
 * **第 101 轮重写**（用户实测：run_code 及其子调用在**流式期间**失败时，这一格整个是空的 ——
 * 摘要已经是红的（`tool.error = true`），左侧却既没有图标也没有红点，等这一轮结束、界面改从库里
 * 渲染那一步才出现）。旧实现把三样东西都挂在 `animateFloatAsState` 的交叉淡出上，而且**用动画值
 * 决定画不画**（`if (iconAlpha > 0f)` / `if (chevron > 0f)` / `if (failedAlpha > 0f)`）——
 * 只要那三个动画值没推进（或落在 0、或是非有限值），三样就一起不画，行首只剩一个空格子；
 * 而「画什么」本来是**状态**，不该由动画的中间值决定（见 [railLeadingState]）。
 *
 * 现在：判定走纯函数，而且**红点不挂任何动画**（`failed` 就满不透明地画出来）——
 * 「红点该不该出现」与「动画跑到哪一帧」彻底解耦：只要 `failed` 为真，红点一定在。
 * 代价是失败那一帧的红点没有 100ms 淡入（图标仍然瞬间让位）；行摘要的**颜色**过渡照旧保留
 * （`summaryColor` 的 `transition: color .1s`），失败那一下看着仍是一次渐变而不是硬切。
 */
@Composable
private fun RailLeading(icon: ImageVector, open: Boolean, failed: Boolean = false) {
    val palette = LocalDshPalette.current
    Box(Modifier.size(16.dp).padding(end = DshSpacing.Lg), contentAlignment = Alignment.Center) {
        when (railLeadingState(open = open, failed = failed)) {
            // 失败：状态点（6px、error 色）—— **不透明的实心点，不参与任何淡入淡出**，见上面的注释
            RailLeadingState.DOT -> Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(palette.errorLabel),
            )
            // 展开时朝下（0°）—— 与折叠行的倒角同一套角度：收起朝右、展开朝下（第 76 轮用户口径）
            RailLeadingState.CHEVRON -> Icon(
                imageVector = DshIcons.ChevronDown,
                contentDescription = null,
                tint = palette.labelSecondary,
                modifier = Modifier.size(14.dp),
            )
            RailLeadingState.ICON -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 图标格交叉淡出的时长（dsh 的 `transition: … .1s`） */
internal const val RAIL_LEADING_FADE_MS = 100

/** 行内来源标签（dsh 的 .XrJvXW_source）：13/24 三级色，前置 2×2px 圆点 */
@Composable
private fun RailSourceLabel(text: String, summary: String? = null) {
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
            .padding(horizontal = DshSpacing.Xl)
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
fun RailChevron(open: Boolean) {
    val palette = LocalDshPalette.current
    val rotation by animateFloatAsState(if (open) 0f else -90f, tween(100), label = "railChevron")
    Spacer(Modifier.width(4.dp))
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
 * **比 dsh 多一个 1 秒的入场延迟**（用户要求，第 90 轮从 2 秒改成 1 秒）：执行很快的工具
 * （读个文件、跑条命令）在扫光刚扫出来的那一瞬间就结束了，扫光被撤掉 —— 看起来像「闪了一下」。
 * 等这一行真的跑了 1 秒才开始扫，快的工具从出现到结束全程没有扫光，只有真正在等的行才在流动。
 *
 * **相位与延迟都从 [epoch] 算**（第 90 轮用户实测：被组合的工具慢一步出现时，它的流光和
 * run_code 那一行总是错开半拍）。以前每一行各自 remember 一个计时器、各自从 0 开始无限动画，
 * 于是「父行已经扫了 2 秒、子行才出现」这种最常见的形状必然不同相。现在：
 *  - 延迟 = epoch + 1s 到点就开始（父行跑了 1 秒之后才出现的子行**不再重新等**）；
 *  - 相位 = 从同一个 epoch 推出的偏移 + 各自的线性斜坡 —— 同源的每一行逐帧同相。
 * epoch 由 [LocalSweepEpoch] 从父行传下来（见 [rememberSweepEpoch]）。
 *
 * @param epoch 这一行（或它在跑的父行）开始运行的时刻，毫秒（见 [rememberSweepEpoch]）
 */
@Composable
private fun RunningSweep(epoch: Long, modifier: Modifier = Modifier) {
    var flowing by rememberSaveable(epoch) { mutableStateOf(false) }
    LaunchedEffect(epoch) {
        if (!flowing) {
            val remaining = RUNNING_SWEEP_DELAY_MS - (System.currentTimeMillis() - epoch)
            if (remaining > 0) delay(remaining)
            flowing = true
        }
    }
    if (!flowing) return
    val bg = MaterialTheme.colorScheme.background
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sweepPx = with(density) { 300.dp.toPx() }
    /**
     * 相位 =「这一行进入组合那一刻，共用时钟走到哪儿了」+ 动画进度。
     *
     * **偏移只取一次**（remember(epoch)），这是第 95 轮修的坑：以前这行写着
     * `val offset = ((System.currentTimeMillis() - epoch - DELAY) % PERIOD) / PERIOD`，
     * 而这个 composable 因为下面读了动画值、每一帧都会重组 —— 于是墙钟偏移与动画进度
     * **两个时钟被加在了一起**，扫光实际按两倍速跑，还会在回绕处跳一下。用户看到的就是
     * 「流光看起来比较卡顿」。取一次之后：偏移是常量（同源的每一行仍然同相），
     * 前进完全由动画时钟负责，周期就是 [SWEEP_PERIOD_MS]。
     */
    val offset = remember(epoch) { sweepPhaseAt(epoch, System.currentTimeMillis()) }
    val transition = rememberInfiniteTransition(label = "sweep")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SWEEP_PERIOD_MS.toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweepProgress",
    )
    val phase = (offset + progress) % 1f
    Canvas(modifier.clipToBounds()) {
        // CSS ease-out = cubic-bezier(0, 0, 0.58, 1)
        val t = (phase / 0.9f).coerceAtMost(1f)
        val u = 1f - t
        val eased = 1f - u * u * u
        val x = -sweepPx + eased * (size.width + sweepPx)
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    // 峰值 = [SWEEP_PEAK_ALPHA]
                    0.55f to bg.copy(alpha = SWEEP_PEAK_ALPHA),
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
 * 共用扫光时钟在 [nowMillis] 这一刻的相位（0..1）。纯函数，单测直接打。
 *
 * **相位只由 epoch 决定**（见 [RunningSweep]）：任何一行、任何时刻取到的相位都只跟
 * 「它挂在的那次运行是什么时候开始的」有关，与它自己什么时候进入组合无关 ——
 * 这是「父行跑了 1 秒之后才出现的子行不掉拍」的实现。
 */
internal fun sweepPhaseAt(epoch: Long, nowMillis: Long): Float {
    val elapsed = nowMillis - epoch - RUNNING_SWEEP_DELAY_MS
    if (elapsed <= 0L) return 0f
    return ((elapsed % SWEEP_PERIOD_MS).toFloat() / SWEEP_PERIOD_MS).coerceIn(0f, 1f)
}

/**
 * 扫光的峰值不透明度：**用户点名 80%**（第 96 轮）。
 *
 * 取值经过一轮来回：dsh 的 CSS 是 `color-mix(bg-base 60%, transparent)`，早期在这里被加强到
 * 90% 才看得出来；90% 的问题是这道光扫过时等于把底下的字**擦掉**（第 95 轮用户报的
 * 「字的右侧被一块白雾遮住」），于是回到 60%；60% 又被用户点名「太淡」，最终定在 80% ——
 * 看得见在流，但字始终是读得出来的那一点。
 */
private const val SWEEP_PEAK_ALPHA = 0.8f

/**
 * 运行扫光的入场延迟（ms）：跑够这么久才开始扫。
 * 快工具（< 1s）全程没有扫光，也就不会出现「刚出现就停」的闪烁感。
 */
private const val RUNNING_SWEEP_DELAY_MS = 1000L

/**
 * dsh 的扫光周期是 `2.6s ease-out infinite`；**第 92 轮放慢到 4.2s**（用户点名：
 * 「速度降低点，频率也跟着速度一起降低」），**第 101 轮又提回 3.6s**（用户点名：
 * 「稍微提高工具执行与思考时其上扫过的流光的频率」），**第 105 轮定在 3.0s**（用户点名：
 * 「增加点思考与工具执行时的扫光频率，速度不变」，随后确认「周期改为 3 秒，速度可以提升」）。
 *
 * 一个周期同时管两件事：①扫光从左到右走完的时间（前 90%，即 2.7s）—— 走得快慢；
 * ②每秒扫过去的次数（1/3.0s ≈ 0.33 次/秒；dsh 是 0.38，第 101 轮那版是 0.28）—— 频率。
 * 第 105 轮要的是「更频繁」，所以周期从 3.6s 砍到 3.0s：频率 +20%，同时横穿时间从 3.24s
 * 缩到 2.7s（用户明确允许速度一起提升）。相位偏移那条公式也读同一个常量，同源的行不会因此错拍。
 */
internal const val SWEEP_PERIOD_MS = 3000L

/**
 * 「运行中」的行共用的扫光时钟起点。
 *
 * 子行（被组合的工具）必须和它挂在的那一行**同相**：父行（run_code）把自己的开始时刻用这个
 * CompositionLocal 传下去，子行的延迟与相位都从它算 —— 父行跑了 1 秒之后才出现的子行不再
 * 重新等 1 秒，扫光相位与父行逐帧一致（第 90 轮用户实测要求）。
 * 0 = 上面没有在跑的行，各行用自己的出现时刻。
 */
internal val LocalSweepEpoch = compositionLocalOf { 0L }

/** 本行的扫光时钟起点：有在跑的父行时用父行的（同步），否则用自己的出现时刻 */
@Composable
internal fun rememberSweepEpoch(): Long {
    val inherited = LocalSweepEpoch.current
    val own = rememberSaveable { System.currentTimeMillis() }
    return if (inherited > 0L) minOf(inherited, own) else own
}

// ------------------------------------------------------------------ 具体行

/** dsh 的 context 行正文框：等宽 11/16、圆角 8、code-block 底、最多 141px */
@Composable
private fun DshCodeBody(text: String) {
    val palette = LocalDshPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, top = DshSpacing.Md, bottom = DshSpacing.Md)
            .heightIn(max = 141.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.codeBlock)
            // 「滚到头就把剩下的滚动吃掉」，见 [ScrollEdgeEater]
            .nestedScroll(ScrollEdgeEater)
            .verticalScroll(rememberScrollState())
            .padding(start = DshSpacing.Xxxl, top = DshSpacing.Xxl, end = DshSpacing.Card, bottom = DshSpacing.Xxxl),
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
 * 卡片正文滚到头之后，**剩下的滚动量留在这里**（用户第 103 轮点名）。
 *
 * 问题：正文框是 `verticalScroll`，它外面是会话的 LazyColumn —— Compose 的嵌套滚动会把
 * 「正文框吃不下」的那一段继续交给 LazyColumn。于是正文滑到底之后再往上滑，整条会话跟着一起滚，
 * 手指还停在卡片里、页面却动了。
 *
 * 做法：在正文框和 LazyColumn 之间插一个连接，把 post 阶段剩下的位移 / 惯性全部认领掉。
 * 它只吃**剩下的**（正文框自己还能滚时，量在更内层就被吃光了，这里拿到 0），
 * 所以卡片内部的滚动不受影响；卡片外的会话照旧由 LazyColumn 滚。
 *
 * **挂哪儿**（第 115 轮）：必须挂在「内层滚动容器」和会话 LazyColumn **之间**的某个祖先上 ——
 * 挂在 LazyColumn 之外没用（那时 LazyColumn 已经先吃过了）。所以两个使用点各挂一层：
 * [DshCodeBody] 挂在正文框自己身上，工具详情卡片挂在展开体的那个 Column 上（一处覆盖卡片里
 * 所有滚动容器：ioCard、代码块、终端、diff、read/search、todo、ask…）。
 */
internal object ScrollEdgeEater : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    override fun onPostScroll(
        consumed: androidx.compose.ui.geometry.Offset,
        available: androidx.compose.ui.geometry.Offset,
        source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
    ): androidx.compose.ui.geometry.Offset = available

    override suspend fun onPostFling(
        consumed: androidx.compose.ui.unit.Velocity,
        available: androidx.compose.ui.unit.Velocity,
    ): androidx.compose.ui.unit.Velocity = available
}


/**
 * dsh 的 SystemPromptRow（message.systemPrompt / message.systemPromptUpdate）：
 * 浏览图标 14 + 标题 + 倒角；展开是系统提示词全文。
 *
 * [onReaderAction]：会话里**任何一行**的展开/收起都要停自动跟随（HANDOFF.md 的『不要破的硬规矩』）。
 */
@Composable
fun SystemPromptRow(text: String, update: Boolean, onReaderAction: () -> Unit = {}) {
    var open by rememberSaveable { mutableStateOf(false) }
    DshDisclosureRow(
        // dsh 的 SystemPromptRow 用的是 IconBrowseOutline16（不是文件图标）
        icon = DshToolIcons.Browse,
        title = if (update) "系统提示词更新" else "系统提示词",
        open = open,
        onToggle = {
            onReaderAction()
            open = !open
        },
        body = { DshCodeBody(text) },
    )
}

/**
 * dsh 的 ContextInjectionRow（message.contextInjection / message.contextRecall）：
 * 上下文注入图标 14 + 标题 + 圆点 + 来源标签（+ 摘要）+ 倒角；展开是注入原文。
 */
@Composable
fun ContextInjectionRow(
    label: String,
    form: String,
    text: String,
    onReaderAction: () -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf(false) }
    DshDisclosureRow(
        // dsh 的 ContextInjectionRow 用的是 IconContextInjectionOutline16
        icon = DshToolIcons.ContextInjection,
        title = "上下文注入",
        open = open,
        onToggle = {
            onReaderAction()
            open = !open
        },
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

/** dsh 的 --dsw-static-deepseek-500（品牌蓝）：TurnStatus 的文字底色、DeepSeek 鲸鱼 mark 的 tint */
internal val DeepseekBlue500 = Color(0xFF4176E6)

/** dsh 的 --dsw-static-deepseek-200：流光高光 */
private val DeepseekBlue200 = Color(0xFFD3E2FF)

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
/**
 * 本轮模型活动那一行（dsh 的 TurnStatus）：左边鲸鱼尾 + 中间流光文字 + 右边用时。
 *
 * 鲸鱼尾是第 99 轮照 dsh **0.2.0-rc.1** 新加的（那边「深度求索中…」左侧的同一条尾巴，
 * 见 [RunningWhaleTail]）：与文字间隔 6dp（dsh 的 `.runningContent{gap:6px}`），
 * 颜色与流光文字同一支品牌蓝；尺寸第 105 轮按用户要求从 14dp 提到 16dp，字重回落到常规字重。
 *
 * **第 101 轮两处**（用户点名）：
 *  - 「吃白饭中」这一块整体右移**一个汉字的距离**（= 尾巴 + 间隔，见 [TURN_STATUS_INDENT_DP]），
 *    于是尾巴图标正好落在「吃」字原来的位置上、用时那一段跟着一起右移；
 *  - 流光**也要扫过鲸鱼尾**：以前尾巴是单色描边、只有文字在流，现在两者共用同一道渐变
 *    （见 [TurnStatusShimmer]），光先扫过尾巴再扫过文字。
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
        Spacer(Modifier.width(TURN_STATUS_INDENT_DP.dp))
        TurnStatusShimmer(label = label)
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
 * 「吃白饭中」这一块整体右移的距离：**一个汉字的距离**（用户第 101 轮的口径 ——
 * 「把尾巴图标移到「吃」字的位置，其余同样右移」）。就是这个宽度：尾巴 + 它到文字的间隔
 * （[WHALE_TAIL_SIZE_DP] 16dp + [WHALE_TAIL_GAP_DP] 6dp = 22dp，第 105 轮尾巴变大后自动跟着走）。
 */
internal const val TURN_STATUS_INDENT_DP = WHALE_TAIL_SIZE_DP + WHALE_TAIL_GAP_DP

/**
 * 「吃白饭中」那一块：鲸鱼尾 + 间隔 + 流光文字。**两者共用同一道流光**（第 101 轮用户点名
 * 「让流光也扫过它」）。
 *
 * 实现要点：渐变是**按整块宽度**算的（尾巴 + 间隔 + 文字 = dsh 的 `background-size:250%`
 * 里的那个「元素」），然后各自平移到自己的坐标系 —— 尾巴画布的 0 点在整块的最左边，
 * 文字的 0 点在尾巴 + 间隔之后。这样同一个 `phase` 下，高光先扫过尾巴、再扫过文字，
 * 看起来就是一道光扫过整块，而不是各自在自己那一格里循环。
 *
 * 这个 composable 因为读动画值**每帧重组**：所以尾巴、间隔、文字都放在这里，
 * 外面的计时那一行（每秒才变）不受影响。
 */
@Composable
private fun TurnStatusShimmer(label: String) {
    // 字重：dsh 的 `.running` 只给 font-size / line-height，**没有 font-weight**（= 400）；
    // 这里以前是 Bold，用户第 105 轮点名「这四个字太粗了」，于是回到 dsh 的常规字重。
    val style = remember { TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal) }
    val measurer = rememberTextMeasurer()
    val textWidth = remember(measurer, label) {
        measurer.measure(AnnotatedString(label), style).size.width.toFloat().coerceAtLeast(1f)
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val tailPx = with(density) { WHALE_TAIL_SIZE_DP.dp.toPx() }
    val gapPx = with(density) { WHALE_TAIL_GAP_DP.dp.toPx() }
    val contentPx = tailPx + gapPx + textWidth
    val transition = rememberInfiniteTransition(label = "turnStatusShimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(TURN_STATUS_SHIMMER_MS, easing = LinearEasing)),
        label = "turnStatusPhase",
    )
    // dsh 的 .EvIC1a_turnStatus：background-position 100% → 0 ⇒ startX: -1.5W → 0
    val startX = -1.5f * contentPx * (1f - phase)
    /**
     * 整块渐变平移到 [offsetPx] 之后的画笔（尾巴传 0、文字传尾巴 + 间隔）。
     * 渐变锚点：deepseek-500 0~40% / deepseek-200 50% / deepseek-500 60~100%（逐字照 dsh 抄）。
     */
    fun brushAt(offsetPx: Float) = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to DeepseekBlue500,
            0.4f to DeepseekBlue500,
            0.5f to DeepseekBlue200,
            0.6f to DeepseekBlue500,
            1f to DeepseekBlue500,
        ),
        startX = startX - offsetPx,
        endX = startX + 2.5f * contentPx - offsetPx,
        tileMode = TileMode.Clamp,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        RunningWhaleTail(
            modifier = Modifier.size(WHALE_TAIL_SIZE_DP.dp),
            tint = DeepseekBlue500,
            brush = brushAt(0f),
        )
        Spacer(Modifier.width(WHALE_TAIL_GAP_DP.dp))
        Text(text = label, style = style.copy(brush = brushAt(tailPx + gapPx)))
    }
}

/**
 * 「吃白饭中」这一行流光的周期：**保持 dsh 的 1.8s**（第 93 轮用户点名：「吃白饭中」这一行的
 * 速度原本就刚好）。第 92 轮放慢过的那处是**运行行的扫光**（[SWEEP_PERIOD_MS]），与这里无关。
 */
private const val TURN_STATUS_SHIMMER_MS = 1800
