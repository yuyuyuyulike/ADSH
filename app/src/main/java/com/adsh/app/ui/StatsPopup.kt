package com.adsh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.llm.SessionStats
import com.adsh.app.ui.theme.LocalDshPalette
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
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    /**
     * 宽度按 dsh 的 .Xt1eiG_panel 算：width:max-content 夹在 min(300px,100vw-24px) 与
     * min(440px,100vw-24px) 之间 —— **内容窄的时候就是 300，而不是一路顶到 440**（否则
     * 「缓存命中 ……… 0%」中间会空出一大片）。
     *
     * max-content 在这个 dl 上等于：标签列（grid 的 minmax(76px,auto)）+ 16px 列距 + 数值列；
     * 标题行同理（图标 14 + 6 + 标题 + 16 + 右上角总计）。两处取大者，再加 32px 内边距。
     */
    val width = remember(title, titleValue, rows, density) {
        val body = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
        val head = body.copy(fontWeight = FontWeight.Medium)
        fun w(text: String, style: TextStyle): Dp = with(density) { measurer.measure(text, style).size.width.toDp() }
        val labelCol = rows.maxOfOrNull { w(it.first, body) }?.coerceAtLeast(76.dp) ?: 76.dp
        val valueCol = rows.maxOfOrNull { w(it.second, body) } ?: 0.dp
        val headRow = 14.dp + DshSpacing.Lg + w(title, head) +
            (titleValue?.let { DshSpacing.Card + w(it, head) } ?: 0.dp)
        maxOf(labelCol + DshSpacing.Card + valueCol, headRow)
            .plus(DshSpacing.Card * 2)
            .coerceIn(300.dp, 440.dp)
    }
    Surface(
        color = palette.menu,
        // dsh 的 --dsw-radius-lg 是 16px（--dsw-radius-sm/md 才是 8/12）
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.borderL1),
        shadowElevation = 12.dp,
        modifier = Modifier.width(width),
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
 * 输入框下方那一排（dsh 的 composer dock + ContextMeter）：
 * 「N 轮 M 步 · TPS」、「{total} tok」、「上下文圆环 + NN%」三个胶囊，居中、间距 12。
 *
 * 与 dsh 同一条可见性规则：一步都没跑、也没有 token 时**不画统计与用量**（dsh 的
 * stats.steps === 0 && !hasTokens 直接 return null）；上下文占用要拿到窗口大小才画。
 * 三个都不画时整排不存在 —— 不为没用到的能力常驻一行。
 */
@Composable
internal fun ChatStatsDock(
    stats: SessionStats,
    context: ContextUsage,
    /** 当前打开的那一个浮层（[ChatOverlays.open]）；本排的取值是 OPEN_STATS / OPEN_USAGE / OPEN_CONTEXT */
    open: String?,
    onToggle: (String) -> Unit,
) {
    // dsh 的判据是「assistant 步数为 0 且没有 token 就整条不画」；ADSH 的 steps 是**工具调用**数，
    // 所以再加一条「模型用时」—— 一次纯聊天的回复不会让 steps 涨，但它确实有统计可看
    val showStats = stats.steps > 0 || stats.llmMillis > 0
    val showTokens = stats.billedInput > 0 || stats.completionTokens > 0
    val showMeter = context.window > 0
    if (!showStats && !showTokens && !showMeter) return
    Row(
        Modifier.fillMaxWidth().padding(top = DshSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxxl, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showStats) {
            val tps = if (stats.tps > 0) formatTps(stats.tps) + " tok/s" else null
            val label = stats.turns.toString() + " 轮 " + stats.steps + " 步" + (tps?.let { " · " + it } ?: "")
            DockPill(
                icon = DshIcons.Gauge,
                label = label,
                expanded = open == OPEN_STATS,
                description = "会话统计",
                onClick = { onToggle(OPEN_STATS) },
            ) {
                SessionStatsPanel(stats)
            }
        }
        if (showTokens) {
            DockPill(
                icon = DshIcons.Database,
                label = formatTokensCompact(stats.billedTotal) + " tok",
                expanded = open == OPEN_USAGE,
                description = "Token 用量",
                onClick = { onToggle(OPEN_USAGE) },
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

/**
 * 一个胶囊 = 触发器 + 它自己的气泡（[panel] 只在展开时组合）。
 *
 * dsh 的 .iq1doa_pill：圆角 999、内边距 1px 8px、gap 6px、图标 14px、12/20 字、tabular-nums；
 * **触摸区就是这个胶囊本身**（dsh 没有再加一圈透明扩大区）。气泡与它左对齐、上留 8px、
 * 离屏幕边 12px（dsh 的 useAnchoredPosition margin）—— 三个参数一起决定气泡的形状与位置。
 */
@Composable
private fun DockPill(
    icon: ImageVector,
    label: String,
    expanded: Boolean,
    description: String,
    onClick: () -> Unit,
    panel: @Composable () -> Unit,
) {
    val palette = LocalDshPalette.current
    Box {
        val ink = if (expanded) palette.labelSecondary else palette.labelTertiary
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (expanded) palette.hover else Color.Transparent)
                .dshClickable(interactionSource = dshInteraction(), onClick = onClick)
                .semantics { contentDescription = description }
                .padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
        ) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
            Text(label, fontSize = 12.sp, lineHeight = 20.sp, color = ink, maxLines = 1, softWrap = false)
        }
        if (expanded) {
            DshPopup(onDismiss = onClick, alignStart = true, margin = DshSpacing.Xxxl, content = panel)
        }
    }
}
