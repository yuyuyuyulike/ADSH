package com.adsh.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.DshPalette
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/*
 * 对话与文档预览共用的 Markdown 渲染。
 *
 * 规格逐条取自 dsh 的 MarkdownText（dsh-client-ui-primitives/lib/markdown/
 * MarkdownText.module.css + CodeBlock.module.css），数值都是从那两个文件里抄的：
 *
 * | 元素 | dsh | 出处 |
 * |---|---|---|
 * | 正文 | 14/24、label-primary、容器内边距外无额外行距 | .markdown{font:--dsw-font-markdown-base} |
 * | 段 | margin 16px 0（首块上、末块下为 0） | .markdown p / >*:first-child |
 * | h1/h2/h3 | 700 21/30、19/28、18/26，margin 32px 0 16px | --dsw-font-markdown-h1..h3 |
 * | h4 | 600 14/24，margin 16px 0 | --dsw-font-markdown-h4 |
 * | h5/h6 | 600 14/24（base-strong），margin 16px 0 | .markdown :where(h5,h6) |
 * | 粗体 | font-weight 600 | .markdown strong |
 * | 列表 | margin 16px 0、padding-left 18px；li+li 6px；嵌套 4px；marker 行高 24、label-secondary | .markdown :where(ul,ol) 等 |
 * | 引用 | border-left 2px label-caption；margin 16px 0 0；padding-left 14px | .markdown blockquote |
 * | 分隔线 | 高 .5px、margin 32px 0、border-l2 | .markdown hr |
 * | 行内代码 | 底色 markdown-inline-code、.5px border-l1、圆角 6、左右 5px、字号 .875em | .markdown :not(pre)>code |
 * | 代码块 | 圆角 12、横幅 9px 14px 11/18、正文等宽 11/19 内边距 16px、pre-wrap + break-all | CodeBlock.module.css |
 * | 表格 | th/td padding 10px 16px；th 底 .5px border-l3、td 底 .5px border-l2；th 500 13/22、td 13/22；首列不左内边距、末列不右内边距 | .tableScroll th/td |
 * | 链接 | 颜色 --dsw-alias-link、字重 500、默认无下划线（悬停 dotted） | .markdown a |
 * | 图片 | 圆角 8、最大宽 100% | ._image_ |
 * | 紧凑变体（思考正文） | 13/20、label-tertiary；段落 4px、标题 8px/4px、li 2px、表格 4/8、代码块 4px | MarkdownText.module.css 的 .compact 段 |
 */

/** dsh 的 MarkdownText 两档排版：正文（默认）与紧凑（思考正文 / 引用块内） */
@Immutable
internal data class MarkdownStyle(
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
    val color: Color,
    /** 段 / 列表 / 代码块的上下边距 */
    val blockGap: Dp,
    /** 标题的上 / 下边距 */
    val headingGapTop: Dp,
    val headingGapBottom: Dp,
    /** 列表项之间的间距 */
    val itemGap: Dp,
    val nestedGap: Dp,
    /** 表格单元格的内边距 */
    val cellPaddingV: Dp,
    val cellPaddingH: Dp,
    /** 行内代码字号（正文是 .875em；紧凑变体是 1em） */
    val inlineCodeScale: Float,
)

private fun markdownStyle(palette: DshPalette, compact: Boolean): MarkdownStyle = if (compact) {
    MarkdownStyle(
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = palette.labelTertiary,
        blockGap = 4.dp,
        headingGapTop = 8.dp,
        headingGapBottom = 4.dp,
        itemGap = 2.dp,
        nestedGap = 2.dp,
        cellPaddingV = 4.dp,
        cellPaddingH = 8.dp,
        inlineCodeScale = 1f,
    )
} else {
    MarkdownStyle(
        fontSize = 14.sp,
        lineHeight = 24.sp,
        color = palette.labelPrimary,
        blockGap = 16.dp,
        headingGapTop = 32.dp,
        headingGapBottom = 16.dp,
        itemGap = 6.dp,
        nestedGap = 4.dp,
        cellPaddingV = 10.dp,
        cellPaddingH = 16.dp,
        inlineCodeScale = 0.875f,
    )
}

/**
 * 消息里相对路径的解析根（会话工作区）。
 * 没有它就只认绝对路径 —— Markdown 里的图片 / 文件链接大多写成相对工作区的路径。
 */
val LocalMarkdownRoot = compositionLocalOf<String?> { null }

/** Markdown 块。@Immutable 是流式渲染的关键：内容没变的块会被 Compose 跳过（见 MdText 的注释）。 */
@Immutable
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock

    /** 列表项：可带复选框（GFM 任务列表）与子块（嵌套列表 / 代码块） */
    @Immutable
    data class Item(val checked: Boolean?, val text: String, val children: List<MdBlock>)

    data class Bullets(val ordered: Boolean, val start: Int, val items: List<Item>) : MdBlock

    /** 引用：内容按块解析（dsh 的 blockquote 里也能放代码块 / 列表） */
    data class Quote(val blocks: List<MdBlock>) : MdBlock

    data class Code(val text: String, val lang: String?) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class Math(val latex: String) : MdBlock
    data class Image(val alt: String, val src: String) : MdBlock
    data object Rule : MdBlock
}

// ------------------------------------------------------------------ 渲染入口

/**
 * 渲染一段 Markdown（dsh 的 MarkdownText）。
 *
 * @param compact 紧凑变体（13/20 三级色、更紧的块间距）—— 思考正文与引用块用它
 */
@Composable
fun MarkdownBody(
    text: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val palette = LocalDshPalette.current
    val style = remember(palette, compact) { markdownStyle(palette, compact) }
    val blocks = remember(text) { parseMarkdown(text) }
    // 根 Column **不** fillMaxWidth：调用方要给整幅宽度时自己传 Modifier.fillMaxWidth()。
    // 用户气泡就靠这一条「按内容收缩」—— 以前这里写死了 fillMaxWidth，
    // 短到两个字的消息也会被撑成整幅 82%（第 75 轮用户实测：气泡长得离谱）。
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            // dsh 的 `>*:first-child{margin-top:0}` / `>*:last-child{margin-bottom:0}`
            val (top, bottom) = blockGaps(block, style)
            Box(
                Modifier.padding(
                    top = if (index == 0) 0.dp else top,
                    bottom = if (index == blocks.lastIndex) 0.dp else bottom,
                ),
            ) {
                MdBlockView(block, style, compact)
            }
        }
    }
}

/** 每个块的上下边距（dsh 的 CSS 数值；引用块下边距是 0） */
private fun blockGaps(block: MdBlock, style: MarkdownStyle): Pair<Dp, Dp> = when (block) {
    is MdBlock.Heading -> style.headingGapTop to style.headingGapBottom
    is MdBlock.Rule -> 32.dp to 32.dp
    is MdBlock.Quote -> style.blockGap to 0.dp
    is MdBlock.Bullets -> style.blockGap to style.blockGap
    is MdBlock.Paragraph, is MdBlock.Code, is MdBlock.Table, is MdBlock.Math, is MdBlock.Image ->
        style.blockGap to style.blockGap
}

@Composable
private fun MdBlockView(block: MdBlock, style: MarkdownStyle, compact: Boolean) {
    val palette = LocalDshPalette.current
    when (block) {
        is MdBlock.Heading -> MdText(
            markdown = block.text,
            style = style,
            fontSize = headingSize(block.level, style.fontSize),
            lineHeight = headingLineHeight(block.level, style.lineHeight),
            fontWeight = if (block.level <= 3) FontWeight.Bold else FontWeight.SemiBold,
        )
        is MdBlock.Paragraph -> MdText(block.text, style)
        is MdBlock.Bullets -> Column(Modifier.fillMaxWidth()) {
            block.items.forEachIndexed { index, item ->
                Column(Modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else style.itemGap)) {
                    Row(Modifier.fillMaxWidth()) {
                        // dsh 的 padding-left:18px 由「标记列 + 内容列」实现：
                        // 标记列宽 18dp（无序项是 •，有序项是「1.」），marker 色 label-secondary
                        Box(Modifier.width(18.dp)) {
                            if (item.checked != null) {
                                TaskCheckbox(item.checked)
                            } else {
                                Text(
                                    text = if (block.ordered) (block.start + index).toString() + "." else "•",
                                    fontSize = style.fontSize,
                                    lineHeight = style.lineHeight,
                                    color = palette.labelSecondary,
                                )
                            }
                        }
                        MdText(
                            markdown = item.text,
                            style = style,
                            fontSize = style.fontSize,
                            lineHeight = style.lineHeight,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (item.children.isNotEmpty()) {
                        Column(Modifier.padding(start = 18.dp, top = style.nestedGap)) {
                            item.children.forEach { child -> MdBlockView(child, style, compact) }
                        }
                    }
                }
            }
        }
        is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(if (compact) 18.dp else 22.dp)
                    .background(palette.labelCaption),
            )
            Column(Modifier.weight(1f).padding(start = if (compact) 8.dp else 14.dp)) {
                block.blocks.forEach { inner ->
                    // 引用里按紧凑排版（dsh 的引用块没有独立字号，这里沿用当前档位，
                    // 只把标题降级成正文级，避免引用里冒出 21px 的大标题）
                    when (inner) {
                        is MdBlock.Heading -> MdText(
                            markdown = inner.text,
                            style = style,
                            fontSize = style.fontSize,
                            lineHeight = style.lineHeight,
                            fontWeight = FontWeight.SemiBold,
                        )
                        else -> MdBlockView(inner, style, compact)
                    }
                }
            }
        }
        is MdBlock.Code -> DshCodeBlock(
            code = block.text,
            lang = block.lang,
            compact = compact,
            modifier = Modifier.fillMaxWidth(),
        )
        is MdBlock.Table -> MdTableView(block, style)
        is MdBlock.Math -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            LatexBlockImage(
                latex = block.latex,
                fontSize = if (compact) 13.sp else 16.sp,
                color = style.color,
            )
        }
        MdBlock.Rule -> HorizontalDivider(thickness = 0.5.dp, color = palette.borderL2)
        is MdBlock.Image -> MdImage(block, style)
    }
}

/** dsh 的 h1/h2/h3 = 21/19/18px（行高 30/28/26）；h4~h6 与正文同字号只加粗 */
private fun headingSize(level: Int, base: TextUnit): TextUnit = when (level) {
    1 -> 21.sp
    2 -> 19.sp
    3 -> 18.sp
    else -> base
}

private fun headingLineHeight(level: Int, base: TextUnit): TextUnit = when (level) {
    1 -> 30.sp
    2 -> 28.sp
    3 -> 26.sp
    else -> base
}

/** dsh 的 GFM 任务列表复选框：`input[type=checkbox]{margin:0 8px 0 0; accent-color:label-secondary}` */
@Composable
private fun TaskCheckbox(checked: Boolean) {
    val palette = LocalDshPalette.current
    Box(
        Modifier
            .padding(top = 4.dp, end = 8.dp)
            .size(14.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) palette.business else Color.Transparent)
            .border(1.dp, if (checked) palette.business else palette.borderL4, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            androidx.compose.material3.Icon(
                DshIcons.Check,
                contentDescription = "已完成",
                tint = palette.onPrimary,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ 行内

/**
 * 行内样式：**粗体**、*斜体*、~~删除线~~、`代码`、[文字](链接)、行内公式（$…$ / \(…\)）。
 *
 * 返回值第二项是公式原文的列表（按出现顺序）：调用方（[MdText]）用它建
 * AnnotatedString 的行内占位（id 由 [latexInlineId] 生成，两边必须一致）。
 */
internal fun inlineMarkdown(
    text: String,
    palette: DshPalette,
    style: MarkdownStyle,
    codeFontSize: TextUnit,
): Pair<AnnotatedString, List<String>> {
    val maths = ArrayList<String>()
    val builder = buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            val rest = text.substring(index)
            when {
                rest.startsWith("**") -> {
                    val end = rest.indexOf("**", 2)
                    if (end > 2) {
                        // dsh 的 strong：font-weight 600（颜色继承正文）
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(rest.substring(2, end))
                        }
                        index += end + 2
                    } else {
                        append(rest.first()); index++
                    }
                }
                rest.startsWith("~~") -> {
                    val end = rest.indexOf("~~", 2)
                    if (end > 2) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(rest.substring(2, end))
                        }
                        index += end + 2
                    } else {
                        append(rest.first()); index++
                    }
                }
                rest.startsWith("`") -> {
                    val end = rest.indexOf('`', 1)
                    if (end > 0) {
                        // dsh 的 .markdown :not(pre)>code：底色 + 圆角 + 左右 5px 内边距 + .875em。
                        // Compose 的 SpanStyle 画不了圆角与内边距，这里取「底色 + 等宽 + .875em」，
                        // 观感与 dsh 一致（见报告的已知差异清单）。
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = codeFontSize * style.inlineCodeScale,
                                background = palette.inlineCode,
                            ),
                        ) {
                            append(" " + rest.substring(1, end) + " ")
                        }
                        index += end + 1
                    } else {
                        append(rest.first()); index++
                    }
                }
                rest.startsWith("[") -> {
                    val close = rest.indexOf("](")
                    val end = rest.indexOf(')', close + 2)
                    if (close > 0 && end > close) {
                        val label = rest.substring(1, close)
                        val url = rest.substring(close + 2, end)
                        // dsh 的链接：--dsw-alias-link + 字重 500，默认无下划线
                        withLink(LinkAnnotation.Url(url)) {
                            withStyle(SpanStyle(color = palette.link, fontWeight = FontWeight.Medium)) {
                                append(label)
                            }
                        }
                        index += end + 1
                    } else {
                        append(rest.first()); index++
                    }
                }
                rest.startsWith("*") || rest.startsWith("_") -> {
                    val marker = rest.first()
                    val end = rest.indexOf(marker, 1)
                    if (end > 0) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(rest.substring(1, end))
                        }
                        index += end + 1
                    } else {
                        append(rest.first()); index++
                    }
                }
                // 行内公式：$…$（或 $$…$$ / \(…\) / \[…\]）。识不出来就按字面 $ 输出 ——
                // 价格这类「$5 到 $10」不会被误判成公式（见 looksLikeMath）。
                rest.startsWith("$") || rest.startsWith("\\(") || rest.startsWith("\\[") -> {
                    val close = when {
                        rest.startsWith("$$") -> "$$"
                        rest.startsWith("$") -> "$"
                        rest.startsWith("\\(") -> "\\)"
                        else -> "\\]"
                    }
                    val openLength = if (close == "$$") 2 else if (close == "$") 1 else 2
                    val end = rest.indexOf(close, openLength)
                    val inner = if (end > 0) rest.substring(openLength, end) else null
                    if (inner != null && (close == "$$" || looksLikeMath(inner))) {
                        appendInlineContent(latexInlineId(maths.size), inner)
                        maths += inner
                        index += end + close.length
                    } else {
                        append(rest.first()); index++
                    }
                }
                else -> {
                    append(rest.first()); index++
                }
            }
        }
    }
    return builder to maths
}

/**
 * 一段 $…$ 里的内容到底是不是公式。
 *
 * 只看「像不像」：含 LaTeX 标记（反斜杠 / 上下标 / 花括号 / 关系符 / 大运算符）就是公式；
 * 没有任何标记时，只有**不含空白**的才当公式（$x$、$n$ 这种单符号）——
 * 否则「价格 $5 到 $10」这类中文里的美元会被误当成公式。
 */
internal fun looksLikeMath(inner: String): Boolean {
    if (inner.isEmpty() || inner.length > 500) return false
    if (inner.first().isWhitespace() || inner.last().isWhitespace()) return false
    if (inner.contains('\n')) return false
    val markers = "\\^_{}=+<>/∑∫√"
    if (markers.any { it in inner }) return true
    return !inner.any { it.isWhitespace() }
}

/**
 * Markdown 行内文本（含行内公式）。
 *
 * 与直接用 `Text(inline(...))` 的差别：这里把行内公式换成 [LatexRender] 的位图占位。
 * 公式的解析/排版/位图都在 [LatexRender] 的三层缓存里，所以流式期间每帧重建
 * AnnotatedString 也不会真的重排公式（用户要求：滑动不能卡）。
 */
@Composable
internal fun MdText(
    markdown: String,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = style.fontSize,
    lineHeight: TextUnit = style.lineHeight,
    color: Color = style.color,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    val palette = LocalDshPalette.current
    val density = LocalDensity.current
    val sizePx = with(density) { fontSize.toPx() }
    val pxPerSp = with(density) { 1.sp.toPx() }
    val argb = color.toArgb()
    // 行内代码字号：dsh 是 .875em（跟着正文缩放）；紧凑变体是 1em
    val codeFontSize = fontSize * style.inlineCodeScale
    val parsed = remember(markdown, palette, style, codeFontSize) {
        inlineMarkdown(markdown, palette, style, codeFontSize)
    }
    val maths = parsed.second
    val inlineContent: Map<String, InlineTextContent> = if (maths.isEmpty()) {
        emptyMap()
    } else {
        remember(markdown, sizePx, pxPerSp, argb) {
            maths.mapIndexedNotNull { index, latex ->
                val render = latexRenderOf(latex, sizePx, pxPerSp, argb, display = false)
                    ?: return@mapIndexedNotNull null
                latexInlineId(index) to InlineTextContent(
                    // AboveBaseline：占位底边贴在文字基线上（公式自身下沉多少就抬高多少，
                    // 见 Latex.kt 的说明；这是 Compose 行内占位唯一能做到的基线对齐）
                    Placeholder(
                        width = render.widthSp.sp,
                        height = render.heightSp.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
                    ),
                ) {
                    Image(
                        bitmap = render.bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }.toMap()
        }
    }
    Text(
        text = parsed.first,
        inlineContent = inlineContent,
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = color,
        fontWeight = fontWeight,
        modifier = modifier,
    )
}

// ------------------------------------------------------------------ 表格

/** dsh 的 th/td：min-width:100px、max-width:min(30vw,320px)（手机上取绝对上限） */
private const val MD_TABLE_CELL_MIN_DP = 100f
private const val MD_TABLE_CELL_MAX_DP = 320f

@Composable
private fun MdTableView(block: MdBlock.Table, style: MarkdownStyle) {
    val palette = LocalDshPalette.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cellStyle = remember(style) { TextStyle(fontSize = style.fontSize, lineHeight = style.lineHeight) }
    val minCellPx = with(density) { MD_TABLE_CELL_MIN_DP.dp.roundToPx() }
    val maxCellPx = with(density) { MD_TABLE_CELL_MAX_DP.dp.roundToPx() }
    val columnCount = remember(block) {
        maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    }
    // 每列宽度 = 该列所有单元格里最宽的那个（测的是不换行时的自然宽度）
    val widths = remember(block, columnCount, minCellPx, maxCellPx, style, palette) {
        List(columnCount) { column ->
            var widest = minCellPx
            var row = 0
            while (row <= block.rows.size && widest < maxCellPx) {
                val cell = if (row == 0) block.header.getOrNull(column) else block.rows[row - 1].getOrNull(column)
                if (!cell.isNullOrEmpty()) {
                    // 长文本必然撑满上限：直接取上限，免得流式期间每个 token 都把上万个字符重新量一遍
                    val measured = if (cell.length > 160) {
                        maxCellPx
                    } else {
                        measurer.measure(
                            text = inlineMarkdown(cell, palette, style, style.fontSize).first,
                            style = cellStyle,
                            maxLines = 1,
                        ).size.width
                    }
                    if (measured > widest) widest = measured
                }
                row++
            }
            with(density) { widest.coerceAtMost(maxCellPx).toDp() }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        MdTableRow(block.header, header = true, widths = widths, style = style)
        block.rows.forEach { row -> MdTableRow(row, header = false, widths = widths, style = style) }
    }
}

/** 表格一行：单元格顶部对齐、行高由最高的那个决定，行下一条 .5px 分隔线（dsh 的 td{border-bottom}） */
@Composable
private fun MdTableRow(
    cells: List<String>,
    header: Boolean,
    widths: List<Dp>,
    style: MarkdownStyle,
) {
    val palette = LocalDshPalette.current
    Row(verticalAlignment = Alignment.Top) {
        widths.forEachIndexed { index, width ->
            val cell = cells.getOrNull(index)
            MdText(
                markdown = cell.orEmpty(),
                style = style,
                fontSize = style.fontSize,
                lineHeight = style.lineHeight,
                fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
                color = if (header) palette.labelPrimary else style.color,
                modifier = Modifier
                    .width(width)
                    .padding(
                        // dsh 的 th:first-child / td:last-child 不留外侧内边距
                        start = if (index == 0) 0.dp else style.cellPaddingH,
                        end = if (index == widths.lastIndex) 0.dp else style.cellPaddingH,
                        top = style.cellPaddingV,
                        bottom = style.cellPaddingV,
                    ),
            )
        }
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = if (header) palette.borderL3 else palette.borderL2,
    )
}

// ------------------------------------------------------------------ 图片

/**
 * Markdown 图片（dsh 的 `._image_`：圆角 8、最大宽 100%）。
 *
 * 只解析**本地文件**（绝对路径 / file:// / 相对工作区）—— 手机上不去抓远端图片；
 * http(s) 的图片按链接文字渲染（点击用系统浏览器打开），避免出现一个永远转圈的空框。
 */
@Composable
private fun MdImage(block: MdBlock.Image, style: MarkdownStyle) {
    val palette = LocalDshPalette.current
    val root = LocalMarkdownRoot.current
    val path = remember(block.src, root) { resolveMarkdownImage(block.src, root) }
    if (path == null) {
        MdText(
            markdown = if (block.alt.isBlank()) block.src else "[" + block.alt + "](" + block.src + ")",
            style = style,
            fontSize = style.fontSize,
            lineHeight = style.lineHeight,
        )
        return
    }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { cachedAttachmentBitmap(path, 1280) }
    }
    val image = bitmap
    if (image == null) {
        Text(
            text = "图片加载失败：" + File(path).name,
            fontSize = style.fontSize,
            lineHeight = style.lineHeight,
            color = palette.labelTertiary,
        )
        return
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { ImagePreviewState.open(path) },
    ) {
        Image(
            bitmap = image,
            contentDescription = block.alt.ifBlank { null },
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 图片地址 → 本地绝对路径（相对路径按 [root] 解析）；不是本地文件时返回 null */
private fun resolveMarkdownImage(src: String, root: String?): String? {
    if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("data:")) return null
    val raw = if (src.startsWith("file://")) src.removePrefix("file://") else src
    val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    val file = File(decoded)
    if (file.isAbsolute) return file.takeIf { it.isFile }?.absolutePath
    if (root == null) return null
    return File(root, decoded).takeIf { it.isFile }?.absolutePath
}
