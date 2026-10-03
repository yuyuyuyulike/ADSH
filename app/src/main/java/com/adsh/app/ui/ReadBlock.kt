package com.adsh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette
import kotlin.math.ceil

/*
 * read 的展开体 —— 对齐 dsh 的 ReadBlock（ui-primitives/src/ReadBlock.tsx）：
 * CodeCard 壳（横幅 = 语言标签 + 路径标题 + 「显示 N / M 行」窗口注记 + 复制）+
 * 行号沟槽（右对齐、三级色、至少 3 位）+ 高亮正文，聊天行按 CHAT_READ_MAX_LINES(=8)
 * 折中显示（头 4 + 「… 其余 N 行」 + 尾 4）。
 *
 * dsh 的 ReadBlock 吃的是结果 meta（结构化 lines/totalLines）；ADSH 的工具行只有输出正文，
 * 所以这里从 read 的 formatReadOutput 信封（<path>/<type>/<content> + 「N: 内容」+ 页脚）
 * 把它**解析回来** —— 信封格式是逐字对齐 dsh 的稳定格式（Tools.kt 的 readEnvelope）。
 * 解析不出来（报错行、web_fetch、被 2MB 上限截断的老数据）就退回 ioCard 显示原文。
 *
 * 手机侧偏离与 DiffBlock 同一条：正文折行、不做横向滚动；展开后最多渲染 [MAX_READ_ROWS] 行。
 */

/** dsh 的 CHAT_READ_MAX_LINES：聊天行里的 read 卡只显示 8 行再折中 */
private const val CHAT_READ_MAX_LINES = 8

/** 展开后最多渲染多少行（同 DiffBlock 的安全阀） */
private const val MAX_READ_ROWS = 400

/** 解析出来的 read 卡模型（dsh 的 ReadCardModel：label/lines/totalLines/lang） */
internal data class ReadCardModel(
    val path: String,
    val lang: String?,
    val lines: List<Pair<Int, String>>,
    /** 文件总行数（页脚里带的）；null = 页脚缺失/被截断 */
    val totalLines: Int?,
)

private val READ_PATH = Regex("(?s)<path>(.*?)</path>")
private val READ_NUMBERED_LINE = Regex("^(\\d+): (.*)$")
private val READ_FOOTER_WINDOW = Regex("of (\\d+)\\.")
private val READ_FOOTER_TOTAL = Regex("total (\\d+) lines")

/**
 * 从 read 的输出信封还原卡片模型；不是这个信封（报错、web_fetch、老数据）返回 null。
 * 对截断宽容：没有 `</content>` 就取到末尾，页脚残缺就不报总行数，能解析几行算几行。
 */
internal fun parseReadEnvelope(output: String?): ReadCardModel? {
    if (output == null) return null
    val path = READ_PATH.find(output)?.groupValues?.get(1) ?: return null
    val openTag = output.indexOf("<content>")
    if (openTag < 0) return null
    var content = output.substring(openTag + "<content>".length).removePrefix("\n")
    val closeTag = content.indexOf("\n</content>")
    if (closeTag >= 0) content = content.substring(0, closeTag)
    // 页脚与正文之间隔一个空行（readEnvelope 的 "\n\n" + footer）
    var totalLines: Int? = null
    var bodyText = content
    val footerAt = content.lastIndexOf("\n\n(")
    if (footerAt >= 0 && content.endsWith(")")) {
        val footer = content.substring(footerAt + 2)
        bodyText = content.substring(0, footerAt)
        totalLines = READ_FOOTER_WINDOW.find(footer)?.groupValues?.get(1)?.toIntOrNull()
            ?: READ_FOOTER_TOTAL.find(footer)?.groupValues?.get(1)?.toIntOrNull()
    }
    val lines = ArrayList<Pair<Int, String>>()
    bodyText.lineSequence().forEach { raw ->
        val match = READ_NUMBERED_LINE.matchEntire(raw) ?: return@forEach
        lines += match.groupValues[1].toInt() to match.groupValues[2]
    }
    // 一行都解析不出来 = 这不是一个正常的 read 信封（截断得太狠），交回 ioCard 显示原文
    if (lines.isEmpty() && bodyText.isNotBlank()) return null
    if (lines.size > MAX_READ_ROWS) {
        return ReadCardModel(path, languageOf(path), lines.subList(0, MAX_READ_ROWS), totalLines)
    }
    return ReadCardModel(path, languageOf(path), lines, totalLines)
}

/** read 卡（dsh 的 ReadBlock，聊天行变体） */
@Composable
internal fun RailReadBlock(model: ReadCardModel, modifier: Modifier = Modifier) {
    val palette = LocalDshPalette.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val lines = model.lines
    val hidden = lines.size - CHAT_READ_MAX_LINES
    val capped = hidden > 0 && !expanded
    val headLines = ceil(CHAT_READ_MAX_LINES / 2.0).toInt()
    val head = if (capped) lines.take(headLines) else lines
    val tail = if (capped) lines.takeLast(CHAT_READ_MAX_LINES - headLines) else emptyList()
    // dsh 的 windowed：返回的行数少于文件总行数 → 横幅右侧挂「显示 N / M 行」
    val total = model.totalLines
    val windowed = total != null && lines.size < total
    // 沟槽宽度按最大行号的位数（dsh 的 gutterDigits，最少 3 位）；等宽字体下 1 位 ≈ 7 个字号单位。
    // 用 sp→dp 换算（而不是写死 dp）：会话内容字号跟着设置缩放，沟槽必须跟着数字一起变宽。
    val gutterDigits = lines.maxOfOrNull { it.first.toString().length }?.coerceAtLeast(3) ?: 3
    val gutterWidth = with(androidx.compose.ui.platform.LocalDensity.current) { (gutterDigits * 7).sp.toDp() }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.codeBlock),
    ) {
        // 横幅（dsh 的 CodeToolbar：语言标签 + 路径标题 + 窗口注记 + 复制）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, top = DshSpacing.Xxl, end = 18.dp, bottom = DshSpacing.Xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // dsh 的 CodeToolbar：语言认不出来时显示 '代码块'（zh 字典的 codeBlock.title）
            Text(
                text = model.lang ?: CODE_BLOCK_LABEL,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = model.path,
                modifier = Modifier.weight(1f),
                fontSize = 11.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // windowed 本身就含着 total != null（K2 的数据流也这么认为，别再写一遍）
            if (windowed) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "显示 " + lines.size + " / " + total + " 行",
                    fontSize = 11.sp,
                    lineHeight = 18.sp,
                    color = palette.labelTertiary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (lines.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                // dsh 的 CodeToolbar 动作：24×24 图标按钮（复制 → 成功后换勾）
                RailCopyAction(payload = { lines.joinToString("\n") { it.second } })
            }
        }
        // 正文：行号沟槽 + 高亮内容（与 DshCodeBlock 同一套内边距）
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, top = DshSpacing.Lg, end = 22.dp, bottom = DshSpacing.Page)) {
            head.forEach { (number, text) ->
                ReadLine(number, text, model.lang, gutterWidth)
            }
            if (hidden > 0) {
                Text(
                    text = if (expanded) "收起" else "… 其余 " + hidden + " 行",
                    modifier = Modifier
                        .padding(start = gutterWidth + 12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .dshClickable(interactionSource = dshInteraction()) { expanded = !expanded },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 19.sp,
                    color = palette.labelTertiary,
                )
            }
            tail.forEach { (number, text) ->
                ReadLine(number, text, model.lang, gutterWidth)
            }
            if (lines.size >= MAX_READ_ROWS && model.totalLines == null) {
                // 解析时已经截过（超大文件）：给一条明确的交代，别看着像文件到此为止
                Text(
                    text = "⋯（内容过长，已截断）",
                    modifier = Modifier.padding(start = gutterWidth + 12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 19.sp,
                    color = palette.labelTertiary,
                )
            }
        }
    }
}

/** 一行 read 内容：右对齐行号（三级色）+ 12dp 沟 + 高亮正文（折行） */
@Composable
private fun ReadLine(number: Int, text: String, lang: String?, gutterWidth: androidx.compose.ui.unit.Dp) {
    val palette = LocalDshPalette.current
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = number.toString(),
            modifier = Modifier.width(gutterWidth),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 19.sp,
            color = palette.labelTertiary,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = highlightCode(text, lang, palette),
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 19.sp,
        )
    }
}
