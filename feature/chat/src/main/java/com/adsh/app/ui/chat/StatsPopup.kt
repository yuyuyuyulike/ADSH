package com.adsh.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.llm.SessionStats
import com.adsh.app.ui.blocks.DshIcons
import com.adsh.app.ui.blocks.dshProminentShadow
import com.adsh.app.ui.blocks.lightCardShadow
import com.adsh.app.ui.theme.LocalDshPalette
import com.adsh.app.ui.kit.DshSpacing
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.kit.dshInteraction
import kotlin.math.roundToLong

/**
 * 会话统计 / Token 用量：两处显示、两张卡片（逐项对齐 dsh 的 StatsPills + stat-dialog）。
 *
 *  - 触发面是输入框下方那两个胶囊（[ChatStatsDock]），点开各自弹一张卡片；
 *  - 卡片是 [StatPanel]（dsh 的 .Xt1eiG_panel）：16px 圆角 / 16px 内边距 / 12·18 字 /
 *    标题行（图标 + 标题 … 右上角总计）+ .5px 分隔线 + 两列 dl（标签 76px 起 / 数值右对齐）；
 *  - 轮尾那两个「用量 / 用时」按钮复用同一张卡片（见 TurnRail.kt）。
 */

/** dsh 的 formatDuration：<60s 用「秒」（<10 保留一位小数），否则「N分M秒」 */
fun formatCompactDuration(millis: Long): String {
    val seconds = millis / 1000.0
    if (seconds < 60) {
        val rounded = (seconds * 10).roundToLong() / 10.0
        return (if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()) + "秒"
    }
    val whole = seconds.roundToLong()
    return (whole / 60).toString() + "分" + (whole % 60) + "秒"
}

/** dsh 的 formatExactTokens：三位一组的千分位 */
fun formatExactTokens(value: Long): String {
    val digits = value.toString()
    val sb = StringBuilder()
    var end = digits.length
    while (end > 0) {
        val start = (end - 3).coerceAtLeast(0)
        if (sb.isNotEmpty()) sb.insert(0, ",")
        sb.insert(0, digits.substring(start, end))
        end = start
    }
    return sb.toString()
}

/** dsh 的 formatTokensPerSecond：≥10 取整，<10 保留一位小数 */
fun formatTps(tps: Double): String {
    val clamped = tps.coerceAtLeast(0.0)
    return if (clamped >= 10) clamped.roundToLong().toString() else (clamped * 10).roundToLong().let { it / 10.0 }.toString()
}

/** dsh 的缓存命中率：保留一位小数，四舍五入；正好是整数时不带小数（100 而不是 100.0） */
fun formatCacheHitPercent(cacheRead: Long, input: Long): String {
    if (input <= 0) return "0"
    val value = cacheRead.toDouble() * 100.0 / input.toDouble()
    val rounded = (value * 10).roundToLong() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}

/**
 * dsh 的 billedInputTokens：三个互不重叠的输入计价桶之和。
 *
 * ADSH 的 promptTokens 已经是「未命中缓存」的口径（LlmClient 按 dsh 的 mapUsage 扣过缓存命中），
 * 没有缓存写入那一档 —— 所以计费输入 = 未缓存输入 + 缓存读取。
 */
private val SessionStats.billedInput: Long get() = promptTokens.coerceAtLeast(0) + cacheHitTokens

/** dsh 的「{count} tok」总数口径：计费输入 + 输出 */
private val SessionStats.billedTotal: Long get() = billedInput + completionTokens

/** 一张统计卡片（dsh 的 .Xt1eiG_panel）；轮尾的「用量 / 用时」弹层也复用它 */
@Composable
internal fun StatPanel(
    title: String,
    icon: ImageVector,
    titleValue: String? = null,
    rows: List<Pair<String, String>>,
) {
    val palette = LocalDshPalette.current
    // dsh 的 --dsw-radius-lg 是 16px（--dsw-radius-sm/md 才是 8/12）
    val shape = RoundedCornerShape(16.dp)
    Surface(
        // .Xt1eiG_panel 的底也是 --dsw-specific-menu（半透明菜单面 + backdrop blur），
        // 安卓的弹层窗口取不到身后内容 → 用合成色（见 DshPalette.menuSurface 的说明）
        color = palette.menuSurface,
        shape = shape,
        // .Xt1eiG_panel 的 box-shadow = --dsw-elevation-prominent：.5px 描边 + 两层 4%/5% 光晕
        border = androidx.compose.foundation.BorderStroke(DshSpacing.Hairline, palette.borderL1),
        // dsh 的 .Xt1eiG_panel 是 `width:max-content` 夹在 300 / 440 之间 —— 交给布局算：卡片按内容
        // 撑开（一行 = 标签 + 16 + 数值；标题行同理），只夹上下限。**别再手写 TextMeasurer 量宽度**：
        // 那种算法量得比实际渲染窄，「3,465 tok（其中推理 2,952 tok）」明明放得下也折行。
        modifier = Modifier
            .widthIn(min = 300.dp, max = 440.dp)
            .width(IntrinsicSize.Max)
            .dshProminentShadow(shape),
    ) {
        Column(Modifier.padding(DshSpacing.Card)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = DshSpacing.Xl),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.Card),
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
                ) {
                    Icon(icon, contentDescription = null, tint = palette.labelPrimary, modifier = Modifier.size(14.dp))
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.labelPrimary,
                    )
                }
                if (titleValue != null) {
                    Text(
                        text = titleValue,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.labelPrimary,
                    )
                }
            }
            // dsh 的 .Xt1eiG_titleRule：0.5px 分隔线，下留 10px
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(DshSpacing.Hairline)
                    .background(palette.borderL2),
            )
            Column(Modifier.padding(top = DshSpacing.Xxl), verticalArrangement = Arrangement.spacedBy(DshSpacing.Lg)) {
                rows.forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Card),
                    ) {
                        Text(
                            text = label,
                            // dsh 是 grid 的 minmax(76px,auto)：标签按内容定宽、永不换行
                            modifier = Modifier.widthIn(min = 76.dp),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = palette.labelTertiary,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Text(
                            text = value,
                            modifier = Modifier.weight(1f),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = palette.labelSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}

/** dsh 的 TimePill 那张卡：会话统计（模型用时 / 工具用时 / TTFT / TPS） */
@Composable
internal fun SessionStatsPanel(stats: SessionStats) {
    StatPanel(
        title = "会话统计",
        icon = DshIcons.Gauge,
        // 「N 轮 M 步」放在这里而不是胶囊里：胶囊只留输出速度，轮 / 步与 dsh 的 stats.counts
        // 一样放进卡片的标题行。
        titleValue = stats.turns.toString() + " 轮 " + stats.steps + " 步",
        rows = listOf(
            "模型用时" to formatCompactDuration(stats.llmMillis),
            "工具调用用时" to formatCompactDuration(stats.toolMillis),
            "首 token 平均（TTFT）" to formatCompactDuration(stats.ttftAverage),
            "输出速度（TPS）" to (formatTps(stats.tps) + " tok/s"),
        ),
    )
}

/** dsh 的 UsagePill 那张卡：Token 用量（缓存命中 / 未缓存输入 / 缓存读取 / 输出） */
@Composable
internal fun SessionUsagePanel(stats: SessionStats) {
    val cacheHit = formatCacheHitPercent(stats.cacheHitTokens, stats.billedInput)
    StatPanel(
        title = "Token 用量",
        icon = DshIcons.Database,
        // dsh 的 stats.counts 只出现在标题右边（轮 / 步）；这里仍是总计
        titleValue = formatExactTokens(stats.billedTotal) + " tok",
        rows = listOf(
            "缓存命中" to (cacheHit + "%"),
            "未缓存输入" to (formatExactTokens(stats.promptTokens.coerceAtLeast(0)) + " tok"),
            "缓存读取" to (formatExactTokens(stats.cacheHitTokens) + " tok"),
            "输出" to (formatExactTokens(stats.completionTokens) + " tok"),
        ),
    )
}

/**
 * 这一排此刻有没有内容：有输出速度或 token 才算跑过一轮。
 *
 * 可见性判据只此一处 —— [ChatViewModel] 用它决定会话载入时是否武装入场动画
 * （[ChatUiState.dockEntranceArmed]），UI 用它做 [ChatStatsDock] 的可见性，两边不许各写一份。
 */
internal fun statsDockVisible(stats: SessionStats): Boolean =
    stats.tps > 0 || stats.billedInput > 0 || stats.completionTokens > 0

/**
 * 输入框下方那一排（dsh 的 composer dock + ContextMeter）：
 * 「N 轮 M 步 · TPS」、「{total} tok」、「上下文圆环 + NN%」三个胶囊，居中、间距 12。
 *
 * 与 dsh 同一条可见性规则：一步都没跑、也没有 token 时**不画统计与用量**（dsh 的
 * stats.steps === 0 && !hasTokens 直接 return null）；上下文占用要拿到窗口大小才画。
 * 三个都不画时整排不存在 —— 不为没用到的能力常驻一行。
 *
 * **footer 的高度就是滚动容器的布局输入**（底部内边距 = 实测 footer 高度 + 间距），所以这一排
 * 一旦出现，任何高度变化都会经贴底滚动把会话流顶一下。由此两条硬规矩：
 *
 *  - 入场只在 [animateEntrance] 为 true 时播 150ms 的淡入 + 高度展开，且**只有新会话第一轮
 *    跑出统计的那一次**该播；启动恢复 / 切到已有统计的旧会话时组态层已经给出 false ——
 *    那一帧胶囊本来就该在，播动画等于给 footer 做一次高度动画。
 *  - 出现之后的数字 / 胶囊增删**只做宽度变化，不做高度动画**（没有 animateContentSize，
 *    也没有 expand/shrink）：它们只影响自己这一行，footer 高度不变，会话流不重贴底。
 *    退出永远瞬间（[ExitTransition.None]）：那一帧不是被抽屉盖着，就是会话已经清空。
 */
@Composable
internal fun ChatStatsDock(
    stats: SessionStats,
    context: ContextUsage,
    /** 当前打开的那一个浮层（[ChatOverlays.open]）；本排的取值是 OPEN_STATS / OPEN_USAGE / OPEN_CONTEXT */
    open: String?,
    onToggle: (String) -> Unit,
    /**
     * 这一次出现要不要播入场动画。由 [ChatUiState.dockEntranceArmed] 决定：
     * 会话载入时统计为空才武装（= 新会话第一轮），其余时候必须瞬间出现。
     */
    animateEntrance: Boolean = true,
) {
    // 胶囊标签只有**输出速度**（几轮几步在详情卡里），没有速度就没得显示 —— 与 dsh 的 compact
    // 形态同一条（TimePill 的 speed === null 就不画）。
    val showStats = stats.tps > 0
    val showTokens = stats.billedInput > 0 || stats.completionTokens > 0
    // 上下文占用跟它们**同步出现**（用户口径：不要提前出来，一轮对话完成后再出现）：
    // 「有 token」或「有速度」都意味着这一轮真的跑起来了
    val showMeter = context.window > 0 && (showStats || showTokens)
    val visible = statsDockVisible(stats)
    // 初始状态 = 首次组合时的可见性：启动恢复 / 切会话的第一帧里胶囊本来就该在，
    // 不能从 false 开始播一遍展开（那是旧实现「加载会话时页面上滚一下」的来源）。
    val visibleState = remember { MutableTransitionState(visible) }
    visibleState.targetState = visible
    AnimatedVisibility(
        visibleState = visibleState,
        enter = if (animateEntrance) {
            fadeIn(tween(DOCK_FADE_MS)) +
                expandVertically(tween(DOCK_FADE_MS), expandFrom = Alignment.Top)
        } else {
            EnterTransition.None
        },
        exit = ExitTransition.None,
    ) {
        Row(
            // 行高由胶囊的固定高度决定；数字 / 宽度变化不参与任何布局动画。
            Modifier
                .fillMaxWidth()
                .padding(top = DshSpacing.Md),
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxxl, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        if (showStats) {
            DockPill(
                icon = DshIcons.Gauge,
                // 只有输出速度；「N 轮 M 步」在详情卡的标题行里（见 SessionStatsPanel）
                label = formatTps(stats.tps) + " tok/s",
                expanded = open == OPEN_STATS,
                description = "会话统计",
                onClick = { onToggle(OPEN_STATS) },
            ) {
                SessionStatsPanel(stats)
            }
        }
        if (showTokens) {
            // 只去掉「缓存命中」四个字，**百分数留着** —— 于是这一粒读作「8.7K tok · 0%」
            // （dsh 的 .iq1doa_sep 就是夹在中间那个「·」）。
            val hit = if (stats.billedInput > 0) {
                formatCacheHitPercent(stats.cacheHitTokens, stats.billedInput) + "%"
            } else {
                null
            }
            DockPill(
                icon = DshIcons.Database,
                label = formatTokensCompact(stats.billedTotal) + " tok" + (hit?.let { " · " + it } ?: ""),
                expanded = open == OPEN_USAGE,
                description = "Token 用量",
                onClick = { onToggle(OPEN_USAGE) },
                alignStart = false,
            ) {
                SessionUsagePanel(stats)
            }
        }
        if (showMeter) {
            ContextMeter(
                usage = context,
                open = open == OPEN_CONTEXT,
                onToggle = { onToggle(OPEN_CONTEXT) },
            )
        }
        }
    }
}

/** dock 出现 / 消失的过渡时长（与重连条同一档：150ms） */
private const val DOCK_FADE_MS = 150

/** dsh StatsPills.module.css 的 trigger 圆角：--dsw-radius-sm = 8 */
internal val StatsDockPillRadius = 8.dp

/**
 * 一个胶囊 = 触发器 + 它自己的气泡（[panel] 只在展开时组合）。dsh 的 .iq1doa_pill：圆角 8、
 * 内边距 1px 8px、gap 6px、图标 14px、12/20 字；**触摸区就是这个胶囊本身**。气泡上留 8px、离屏幕边 12px；
 * 横向挂法：[alignStart] = 左边缘贴触发器左边缘，false = 右边缘贴右边缘（面板宽，于是整块往左铺）。
 */
@Composable
private fun DockPill(
    icon: ImageVector,
    label: String,
    expanded: Boolean,
    description: String,
    onClick: () -> Unit,
    alignStart: Boolean = true,
    panel: @Composable () -> Unit,
) {
    val palette = LocalDshPalette.current
    val anchor = remember { OverlayAnchorState() }
    Box(Modifier.overlayAnchor(anchor)) {
        val ink = if (expanded) palette.labelSecondary else palette.labelTertiary
        Row(
            modifier = Modifier
                .lightCardShadow(RoundedCornerShape(StatsDockPillRadius))
                .clip(RoundedCornerShape(StatsDockPillRadius))
                .background(if (expanded) palette.hover else Color.Transparent)
                .dshClickable(interactionSource = dshInteraction(), onClick = onClick)
                .semantics { contentDescription = description }
                .padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
        ) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
            Text(
                text = label,
                // dsh 的 .pill 是 font-variant-numeric: tabular-nums —— 数字等宽，数字变化时胶囊宽度不抖
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                fontSize = 12.sp,
                lineHeight = 20.sp,
                color = ink,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (expanded) {
            DshPopup(onDismiss = onClick, alignStart = alignStart, margin = DshSpacing.Xxxl, anchor = anchor, content = panel)
        }
    }
}
