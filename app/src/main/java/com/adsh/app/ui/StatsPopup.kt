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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.llm.SessionStats
import com.adsh.app.ui.theme.LocalDshPalette
import kotlin.math.roundToLong

/**
 * 会话统计弹层（逐项对齐 dsh 的 stat-dialog）：
 * 两张卡片 —— 「会话统计」（IconGaugeOutline16）与「Token 用量」（IconDatabaseOutline16），
 * 排版为 title（图标+标题 … 右上角总计）+ .5px 分隔线 + 两列 dl（标签 76dp 起 / 数值右对齐）。
 *
 * 尺寸与配色取自 dsh 的 stat-dialog.module.css：
 *   panel  = 12px 圆角 / 16px 内边距 / 12px 字号 / 18px 行高 / 白底（--dsw-specific-menu）
 *   title  = primary / 500 / 下 8px；titleRule = 0.5px border-l2 / 下 10px
 *   details= 两列 gap 6×16；dt tertiary；dd secondary + tabular + 右对齐
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

/** 一张统计卡片（dsh 的 .bRhRbq_panel）；轮尾的「用量 / 用时」弹层也复用它 */
@Composable
internal fun StatPanel(
    title: String,
    icon: ImageVector,
    titleValue: String? = null,
    rows: List<Pair<String, String>>,
) {
    val palette = LocalDshPalette.current
    Surface(
        color = palette.menu,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.borderL1),
        shadowElevation = 12.dp,
        modifier = Modifier.widthIn(min = 300.dp, max = 380.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
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
            // dsh 的 .bRhRbq_titleRule：0.5px 分隔线，下留 10px
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.borderL2),
            )
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
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

/** 会话统计窗口：会话统计 + Token 用量 两张卡 */
@Composable
fun SessionStatsPanels(stats: SessionStats) {
    // promptTokens 已经是「未命中缓存」的口径（LlmClient 按 dsh 的 mapUsage 扣过缓存命中），
    // 这里不要再减一次 cacheHitTokens，否则缓存多的时候会算成 0
    val uncachedInput = stats.promptTokens.coerceAtLeast(0)
    val billedInput = uncachedInput + stats.cacheHitTokens
    val total = billedInput + stats.completionTokens
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatPanel(
            title = "会话统计",
            icon = DshIcons.Gauge,
            // dsh 的 stats.counts =「{turns} 轮 {steps} 步」：和左下角统计胶囊同一份文案
            titleValue = stats.turns.toString() + " 轮 " + stats.steps + " 步",
            rows = listOf(
                "模型用时" to formatCompactDuration(stats.llmMillis),
                "工具调用用时" to formatCompactDuration(stats.toolMillis),
                "首 token 平均（TTFT）" to formatCompactDuration(stats.ttftAverage),
                "输出速度（TPS）" to (formatTps(stats.tps) + " tok/s"),
            ),
        )
        StatPanel(
            title = "Token 用量",
            icon = DshIcons.Database,
            titleValue = formatExactTokens(total) + " tok",
            rows = listOf(
                "缓存命中" to (formatCacheHitPercent(stats.cacheHitTokens, billedInput) + "%"),
                "未缓存输入" to (formatExactTokens(uncachedInput) + " tok"),
                "缓存读取" to (formatExactTokens(stats.cacheHitTokens) + " tok"),
                "输出" to (formatExactTokens(stats.completionTokens) + " tok"),
            ),
        )
    }
}
