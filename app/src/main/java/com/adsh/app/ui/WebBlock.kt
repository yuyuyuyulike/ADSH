package com.adsh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.tools.WebOutputText
import com.adsh.app.ui.theme.LocalDshPalette

/*
 * web_search / web_fetch 的展开体 —— 对齐 dsh 的 WebBlock
 * （ui-primitives/src/WebBlock.tsx：卡片自带 padding 12px 14px、底色 markdown-code-block）。
 *
 * dsh 的这张卡吃的是结果 **meta**（web_search：sources[{url,title,snippet,publishedAt}] +
 * answer + truncated；web_fetch：url + statusCode + truncated）；ADSH 的工具行只落库
 * 「人看的正文」（SubCall.result = outcome.text，见 WebTools 的 format / renderFetchOutput），
 * 所以这里把正文**解析回来** —— 两端的字面量共用 [WebOutputText]，不各写一遍。
 *
 * 网页搜索（dsh 的 WebSearchBlock）：
 *  - 有 answer 时先画一段 Markdown（dsh 的 MarkdownText）；
 *  - 来源清单是**有序列表**：序号 + 链接（有标题用标题、没有用主机名）+ 摘要 + 日期，
 *    超过 320px 就在卡片里滚动（dsh 的 .sources 高度上限）；
 *  - 一条来源都没有 → dsh 的「未找到结果」；来源被截断 → 「来源列表已截断」。
 *
 * 网页抓取（dsh 的 WebFetchBlock）：**只有 URL + HTTP 状态 + 截断提示** —— dsh 的正文只能
 * 从 inspect 面板看，ADSH 没有那个面板，所以卡片下面补一个默认收起的「正文」折叠（用户点名）。
 *
 * 手机侧偏离：来源链接前面那枚图标用 Material 的地球轮廓（dsh 是站点 favicon，取不到才画
 * 描边地球）——favicon 要联网抓还要缓存，这一轮不做；抓取正文按等宽**折行**显示。
 */

/** 一条来源（dsh 的 WebSource） */
internal data class WebSource(
    val url: String,
    val title: String? = null,
    val snippet: String? = null,
    val publishedAt: String? = null,
)

/** web_search 卡（dsh 的 WebSearchBlockProps） */
internal data class WebSearchCard(
    val answer: String?,
    val sources: List<WebSource>,
    val truncated: Boolean,
)

/** web_fetch 卡（dsh 的 WebFetchBlockProps + ADSH 的正文折叠） */
internal data class WebFetchCard(
    val url: String,
    val statusCode: Int?,
    val truncated: Boolean,
    /** 抓来的正文（卡片本身不画，挂在下面的「正文」折叠里） */
    val body: String,
)

private val FETCH_HEADER = Regex("^Fetched (\\S.*) \\(HTTP (\\d+)\\)$")

/**
 * web_search 正文 → 卡片。正文形状（WebTools 的 format）：外部内容声明 / 可选 answer /
 * `Sources:` + 一行一条 `- [标题](url) — 摘要 (日期)` / 可选 `(Showing the first N sources. …)` /
 * 引用要求。
 *
 * **按行解析，不能按空行切段**（第 90 轮真机实测的坑）：DeepSeek 后端把 `cited_text` 当摘要，
 * 那东西是**多行 markdown**、里面本来就有空行 —— 按 `\n\n` 切段的写法会把一条来源切成好几段，
 * 于是「只有第一条来源画出来」，其余全被当成段外的杂音丢掉（用户看到的就是「卡片只展示一条」）。
 * 一条来源 = 以 `- [标题](http…)` 开头的那一行，直到下一条来源 / 截断行 / 引用要求为止。
 *
 * 认不出形状（报错行、被截断的老数据）返回 null，调用方退回 ioCard。
 */
internal fun parseWebSearchCard(output: String?): WebSearchCard? {
    val text = output?.trim() ?: return null
    if (!text.startsWith(WebOutputText.NOTICE)) return null
    val lines = text.removePrefix(WebOutputText.NOTICE).trimStart('\n').lines()
    val sourcesAt = lines.indexOfFirst { it.trim() == WebOutputText.SOURCES_HEADER }
    var truncated = false
    var sawNoResults = false
    val sources = ArrayList<WebSource>()
    lines.forEach { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith(WebOutputText.SOURCES_TRUNCATED_PREFIX)) truncated = true
        if (trimmed == WebOutputText.NO_RESULTS) sawNoResults = true
    }
    if (sourcesAt >= 0) {
        val record = StringBuilder()
        fun flush() {
            if (record.isNotEmpty()) {
                parseSourceRecord(record.toString())?.let { sources += it }
                record.setLength(0)
            }
        }
        var index = sourcesAt + 1
        while (index < lines.size) {
            val raw = lines[index]
            val trimmed = raw.trim()
            if (trimmed == WebOutputText.CITE_HINT || trimmed.startsWith(WebOutputText.SOURCES_TRUNCATED_PREFIX)) break
            if (isSourceStart(raw)) {
                flush()
                record.append(raw)
            } else if (record.isNotEmpty()) {
                // 摘要的续行（多行 markdown、空行都算这一条来源的内容）
                record.append('\n').append(raw)
            }
            index++
        }
        flush()
    }
    // answer 在 Sources 之前（format 的顺序）；「无结果」「截断行」「引用要求」都不算 answer
    val answer = lines.take(if (sourcesAt >= 0) sourcesAt else lines.size)
        .filterNot {
            val trimmed = it.trim()
            trimmed == WebOutputText.NO_RESULTS ||
                trimmed.startsWith(WebOutputText.SOURCES_TRUNCATED_PREFIX) ||
                trimmed.startsWith(WebOutputText.CITE_HINT)
        }
        .joinToString("\n")
        .trim()
        .takeIf { it.isNotEmpty() }
    // 来源清单 / 无结果 / 截断行 / answer 一个都认不出来 = 这不是搜索正文
    if (sources.isEmpty() && sourcesAt < 0 && !sawNoResults && answer == null && !truncated) return null
    return WebSearchCard(answer = answer, sources = sources, truncated = truncated)
}

/** 一条来源是不是从这里开始：`- [标题](http…)`（摘要里的 markdown 列表项不满足这一条） */
private fun isSourceStart(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.startsWith("- [")) return false
    val close = trimmed.indexOf("](", 3)
    if (close < 0) return false
    val url = trimmed.substring(close + 2)
    return url.startsWith("http://") || url.startsWith("https://")
}

/**
 * 一条来源（可能跨多行）：`- [标题](url) — 摘要 (日期)`。
 * 摘要与日期都可选；日期是 dsh 追加在**整段摘要之后**的 `(日期)`，所以只看末尾那个括号。
 * 摘要里的换行按 dsh 的 HTML 口径折成空格（`word-break` 的 div 把 `\n` 当空格渲染）。
 */
private fun parseSourceRecord(record: String): WebSource? {
    val firstLine = record.substringBefore('\n').trim()
    if (!firstLine.startsWith("- [")) return null
    val close = firstLine.indexOf("](", 3)
    if (close < 0) return null
    val label = firstLine.substring(3, close)
    val separator = firstLine.indexOf(" — ", close + 2)
    val urlEnd = if (separator > 0) separator else firstLine.length
    val url = firstLine.substring(close + 2, urlEnd).trim().removeSuffix(")")
    if (!url.startsWith("http://") && !url.startsWith("https://")) return null
    val head = if (separator > 0) firstLine.substring(separator + 3) else ""
    val tail = if (record.contains('\n')) record.substringAfter('\n') else ""
    var meta = (head + "\n" + tail).replace(Regex("\\s+"), " ").trim()
    var publishedAt: String? = null
    if (meta.endsWith(")")) {
        val open = meta.lastIndexOf(" (")
        if (open > 0) {
            publishedAt = meta.substring(open + 2, meta.length - 1).trim().takeIf { it.isNotEmpty() }
            meta = meta.substring(0, open).trim()
        }
    }
    return WebSource(
        url = url,
        title = label.takeIf { it.isNotEmpty() },
        snippet = meta.takeIf { it.isNotEmpty() },
        publishedAt = publishedAt,
    )
}

/**
 * web_fetch 正文 → 卡片。正文形状（WebTools 的 renderFetchOutput）：
 * `Fetched <url> (HTTP <code>)` + 外部内容声明 + 正文（整体可能带截断页脚）。
 */
internal fun parseWebFetchCard(output: String?): WebFetchCard? {
    val text = output?.trim() ?: return null
    val firstBreak = text.indexOf('\n')
    if (firstBreak < 0) return null
    val header = FETCH_HEADER.matchEntire(text.substring(0, firstBreak)) ?: return null
    var body = text.substring(firstBreak + 1).trimStart('\n')
    body = body.removePrefix(WebOutputText.NOTICE).trimStart('\n')
    var truncated = false
    if (body.endsWith(WebOutputText.FETCH_TRUNCATION_FOOTER)) {
        truncated = true
        body = body.removeSuffix(WebOutputText.FETCH_TRUNCATION_FOOTER)
    }
    return WebFetchCard(
        url = header.groupValues[1],
        statusCode = header.groupValues[2].toIntOrNull(),
        truncated = truncated,
        body = body.trim(),
    )
}

/** web_search 卡（dsh 的 WebSearchBlock：answer + 有序来源清单） */
@Composable
internal fun RailWebSearchBlock(card: WebSearchCard, modifier: Modifier = Modifier) {
    val palette = LocalDshPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.codeBlock)
            .padding(horizontal = DshSpacing.Section, vertical = DshSpacing.Xxxl),
    ) {
        if (!card.answer.isNullOrEmpty()) {
            MarkdownBody(card.answer, Modifier.fillMaxWidth().padding(bottom = DshSpacing.Xl))
        }
        if (card.sources.isEmpty() && card.answer.isNullOrEmpty()) {
            Text(
                text = "未找到结果",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = palette.labelSecondary,
            )
        } else {
            // dsh 的 .sources：max-height 320px + overflow-y auto，条与条之间 10px
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DshSpacing.Xxl),
            ) {
                card.sources.forEachIndexed { index, source -> WebSourceRow(index + 1, source) }
            }
        }
        if (card.truncated) {
            Text(
                text = "来源列表已截断",
                modifier = Modifier.padding(top = DshSpacing.Xl),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = palette.labelTertiary,
            )
        }
    }
}

/** web_fetch 卡（dsh 的 .fetch：URL 一行 + HTTP 状态一行 + 截断提示） */
@Composable
internal fun RailWebFetchBlock(card: WebFetchCard, modifier: Modifier = Modifier) {
    val palette = LocalDshPalette.current
    var bodyOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.codeBlock)
                .padding(horizontal = DshSpacing.Section, vertical = DshSpacing.Xxxl),
            verticalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
        ) {
            Text(
                text = webLinkText(card.url, card.url),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxxl),
            ) {
                card.statusCode?.let {
                    Text(
                        text = "HTTP " + it,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = palette.labelSecondary,
                    )
                }
                if (card.truncated) {
                    Text(
                        text = "内容已截断",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = palette.labelTertiary,
                    )
                }
            }
        }
        // ADSH 补的那一层：dsh 的正文只在 inspect 面板里看得到，这里给一个默认收起的折叠
        if (card.body.isNotEmpty()) {
            Text(
                text = if (bodyOpen) "收起正文" else "查看正文",
                modifier = Modifier
                    .padding(start = DshSpacing.Md, top = DshSpacing.Md, bottom = DshSpacing.Md)
                    .clip(RoundedCornerShape(4.dp))
                    .dshClickable(interactionSource = dshInteraction()) { bodyOpen = !bodyOpen },
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = palette.labelTertiary,
            )
            if (bodyOpen) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.codeBlock)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = DshSpacing.Section, vertical = DshSpacing.Xxxl),
                ) {
                    Text(
                        text = card.body,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 19.sp,
                        color = palette.labelSecondary,
                    )
                }
            }
        }
    }
}

/**
 * 一条来源（dsh 的 SourceItem）：序号（右对齐的沟槽）+ 地球图标 + 链接 + 摘要 + 日期。
 * 序号是列表的 1 基序号，dsh 用 `li value` 显式钉住它。
 */
@Composable
private fun WebSourceRow(ordinal: Int, source: WebSource) {
    val palette = LocalDshPalette.current
    // dsh 的 .sources 是 padding-left: 2.5em 的 ol：序号右对齐在沟槽里。
    // 用 sp→dp 换算（内容字号跟着设置缩放，沟槽要跟着一起变宽）。
    val gutter = with(LocalDensity.current) { 32.sp.toDp() }
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = ordinal.toString() + ".",
            modifier = Modifier.width(gutter),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = palette.labelTertiary,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth()) {
                // dsh 的 .linkIcon：1.1em（14px 链接字号 ≈ 15px），与链接同色
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = null,
                    modifier = Modifier.padding(top = DshSpacing.Sm).size(15.dp),
                    tint = palette.link,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = webLinkText(source.url, sourceLabel(source)),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (!source.snippet.isNullOrEmpty()) {
                Text(
                    text = source.snippet,
                    modifier = Modifier.padding(top = DshSpacing.Xs),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = palette.labelSecondary,
                )
            }
            if (!source.publishedAt.isNullOrEmpty()) {
                Text(
                    text = source.publishedAt,
                    modifier = Modifier.padding(top = DshSpacing.Xs),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = palette.labelTertiary,
                )
            }
        }
    }
}

/**
 * 链接文字（dsh 的 SafeLink）：只有 http(s) 是可点的链接，其余按普通文字画
 * （dsh 的 safeHref 同样只放行这两种协议）；点击用系统浏览器打开（LinkAnnotation.Url）。
 */
@Composable
private fun webLinkText(url: String, label: String): AnnotatedString {
    val palette = LocalDshPalette.current
    val plain = SpanStyle(color = palette.labelPrimary)
    val styled = SpanStyle(color = palette.link)
    return buildAnnotatedString {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            withLink(LinkAnnotation.Url(url, TextLinkStyles(style = styled, pressedStyle = styled))) {
                withStyle(styled) { append(label) }
            }
        } else {
            withStyle(plain) { append(label) }
        }
    }
}

/** 链接上显示的名字（dsh 的 linkLabel）：标题 → 主机名 → 原 URL */
private fun sourceLabel(source: WebSource): String {
    if (!source.title.isNullOrEmpty()) return source.title
    return runCatching { java.net.URI(source.url).host }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
        ?: source.url
}
