package com.adsh.app.ui.blocks

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
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
import com.adsh.app.ui.kit.DshSpacing
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.kit.dshInteraction
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
 * | 行内代码 | 底色 markdown-inline-code、.5px border-l1、圆角 8（紧凑档 4）、左右 5px（紧凑档 3）、字号 .875em（紧凑档 1em） | .markdown :not(pre)>code 与 .compact 段 |
 * | 代码块 | 圆角 16（--dsl-code-block-border-radius = --dsw-radius-lg）、横幅 9px 14px 11/18、正文等宽 11/19 内边距 16px、pre-wrap + break-all | CodeBlock.module.css |
 * | 表格 | th/td padding 10px 16px；th 底 .5px border-l3、td 底 .5px border-l2；th 500 13/22、td 13/22；首列不左内边距、末列不右内边距；<4 列整表铺满列宽、单元格换行，≥4 列按内容宽横向滚动 | .tableScroll / .tableFill |
 * | 链接 | 颜色 --dsw-alias-link、字重 500、默认无下划线（悬停 dotted） | .markdown a |
 * | 图片 | 圆角 12（--dsw-radius-md）、最大宽 100% | ._image_ |
 * | 行内代码底框高度 | 普通档 19px（--dsw-font-markdown-code 的 12/19）、紧凑档继承 20px 行高 | .markdown :not(pre)>code |
 * | 代码块（对话里是 card 变体） | 圆角 16、工具栏 10/18/8/22 与 11/18；正文 11/19、内边距 6/22/20；**默认自动换行**，工具栏可切「不换行」 | CodeCard.module.css |
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
    /** 行内代码底框的圆角（dsh：普通档 --dsw-radius-sm = 8px、紧凑档 --dsw-radius-xs = 4px） */
    val inlineCodeRadius: Dp,
    /** 行内代码底框的左右内边距（dsh：普通档 5px、紧凑档 3px） */
    val inlineCodePadH: Dp,
    /**
     * 行内代码底框的高度（dsh 的 inline-flex 盒高 = 代码字体的行高）：普通档 19px、
     * 紧凑档继承正文行高 20px。底框在行内垂直居中；**不能**直接用整行行高（24px）——
     * 那会让底框比文字胖一圈。
     */
    val inlineCodeLineHeight: TextUnit,
    /** 表格单元格字号 / 行高（dsh：--dsw-font-markdown-table = 13/22，紧凑档 inherit = 13/20） */
    val tableFontSize: TextUnit,
    val tableLineHeight: TextUnit,
    /** 紧凑变体（思考正文 / 引用块）：只影响上表里那几条 compact 规则 */
    val compact: Boolean,
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
        inlineCodeRadius = 4.dp,
        inlineCodePadH = 3.dp,
        inlineCodeLineHeight = 20.sp,
        tableFontSize = 13.sp,
        tableLineHeight = 20.sp,
        compact = true,
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
        inlineCodeRadius = 8.dp,
        inlineCodePadH = 5.dp,
        inlineCodeLineHeight = 19.sp,
        tableFontSize = 13.sp,
        tableLineHeight = 22.sp,
        compact = false,
    )
}

/**
 * 消息里相对路径的解析根（会话工作区）。
 * 没有它就只认绝对路径 —— Markdown 里的图片 / 文件链接大多写成相对工作区的路径。
 */
val LocalMarkdownRoot = compositionLocalOf<String?> { null }

/**
 * 一行 Markdown 的行内解析结果。
 *
 * [text] 是最终要画的 AnnotatedString（公式位置是 1 个字符的占位符）；[latex] 是公式原文，
 * [codeRanges] 是行内代码在 [text] 里的区间（含占位符坐标），交给 [MdText] 画圆角底框。
 */
@Immutable
internal data class InlineParts(
    val text: AnnotatedString,
    val latex: List<String>,
    val codeRanges: List<IntRange>,
)

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
    /**
     * 这一段上面还有内容（长回答被切成多块渲染，见 [com.adsh.app.ui.ChatItem.TurnAnswer]）。
     *
     * 块间距（[blockGaps]）的首尾归零是为「单个 MarkdownBody 当整条消息」定的；切开后每一块都是
     * 独立的 MarkdownBody，照旧首尾归零会丢掉切点两侧的边距（段落之间的 32dp 变成 0）。
     * 所以切开的那几块把这两个标志传进来，边距与「整段一起渲染」逐像素一致。
     */
    hasPreceding: Boolean = false,
    /** 这一段下面还有内容（末块保留自己的下边距，见 [hasPreceding]） */
    hasFollowing: Boolean = false,
) {
    val palette = LocalDshPalette.current
    val style = remember(palette, compact) { markdownStyle(palette, compact) }
    val blocks = remember(text) { parseMarkdown(text) }
    // 根 Column **不** fillMaxWidth：调用方要给整幅宽度时自己传 Modifier.fillMaxWidth()。
    // 用户气泡就靠这一条「按内容收缩」—— 写死 fillMaxWidth 时两个字的消息也会被撑成整幅 82%。
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            // dsh 的 `>*:first-child{margin-top:0}` / `>*:last-child{margin-bottom:0}`
            val (top, bottom) = blockGaps(
                prev = blocks.getOrNull(index - 1),
                block = block,
                next = blocks.getOrNull(index + 1),
                style = style,
            )
            Box(
                Modifier.padding(
                    top = if (index == 0 && !hasPreceding) 0.dp else top,
                    bottom = if (index == blocks.lastIndex && !hasFollowing) 0.dp else bottom,
                ),
            ) {
                MdBlockView(block, style, compact)
            }
        }
    }
}

/**
 * 每个块的上下边距（dsh 的 CSS 数值；引用块下边距是 0）。
 *
 * 要看邻居的只有一条：`h4~h6 + 列表` 两侧都收成 8px（dsh 的 `.markdown :where(h4,h5,h6) + :where(ul,ol)`）。
 */
private fun blockGaps(
    prev: MdBlock?,
    block: MdBlock,
    next: MdBlock?,
    style: MarkdownStyle,
): Pair<Dp, Dp> {
    var top = when (block) {
        is MdBlock.Heading -> headingGaps(block.level, style).first
        else -> style.blockGap
    }
    var bottom = when (block) {
        is MdBlock.Heading -> headingGaps(block.level, style).second
        is MdBlock.Rule -> 32.dp
        is MdBlock.Quote -> 0.dp
        else -> style.blockGap
    }
    if (!style.compact) {
        if (prev is MdBlock.Heading && prev.level >= 4 && block is MdBlock.Bullets) top = HEADING_LIST_GAP
        if (block is MdBlock.Heading && block.level >= 4 && next is MdBlock.Bullets) bottom = HEADING_LIST_GAP
    }
    return top to bottom
}

/** 标题上下边距：h1~h3 是 32/16，h4~h6 是 16/16（紧凑档一律 8/4，见 dsh 的 .compact 段） */
private fun headingGaps(level: Int, style: MarkdownStyle): Pair<Dp, Dp> =
    if (style.compact || level <= 3) style.headingGapTop to style.headingGapBottom
    else style.headingGapBottom to style.headingGapBottom

/** dsh 的「h4~h6 + 列表」那条规则的两个 8px */
private val HEADING_LIST_GAP = 8.dp

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
                                // 标记色照 dsh：普通档 `.markdown li::marker{color:label-secondary}`，
                                // 紧凑档 `.compact li::marker{color:inherit}`（跟正文一个色）
                                Text(
                                    text = if (block.ordered) (block.start + index).toString() + "." else "•",
                                    fontSize = style.fontSize,
                                    lineHeight = style.lineHeight,
                                    color = if (compact) style.color else palette.labelSecondary,
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
        // dsh 的 blockquote：border-left 2px label-caption + padding-left 14px（紧凑档 8px），
        // 竖线跟着引用内容的实际高度走 —— 不能用固定高度的色条（多行引用会只画出第一行那么高）。
        is MdBlock.Quote -> Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        color = palette.labelCaption,
                        size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height),
                    )
                }
                .padding(start = if (compact) 10.dp else 16.dp),
        ) {
            MdBlocks(block.blocks, style, compact)
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
        MdBlock.Rule -> HorizontalDivider(thickness = DshSpacing.Hairline, color = palette.borderL2)
        is MdBlock.Image -> MdImage(block, style)
    }
}

/**
 * 一段块序列的渲染（引用块内部用）：块与块之间同样带 dsh 的上下边距，
 * 首块上、末块下归零（与 dsh 的 `>*:first-child` / `>*:last-child` 同理）。
 */
@Composable
private fun MdBlocks(blocks: List<MdBlock>, style: MarkdownStyle, compact: Boolean) {
    Column {
        blocks.forEachIndexed { index, inner ->
            val (top, bottom) = blockGaps(
                prev = blocks.getOrNull(index - 1),
                block = inner,
                next = blocks.getOrNull(index + 1),
                style = style,
            )
            Box(
                Modifier.padding(
                    top = if (index == 0) 0.dp else top,
                    bottom = if (index == blocks.lastIndex) 0.dp else bottom,
                ),
            ) {
                MdBlockView(inner, style, compact)
            }
        }
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
            .padding(top = DshSpacing.Md, end = DshSpacing.Xl)
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
 * 公式那一段的原文按出现顺序收在 [InlineParts.latex] 里：调用方（[MdText]）用它建 AnnotatedString
 * 的行内占位（id 由 [latexInlineId] 生成，两边必须一致）。行内代码的**区间**收在
 * [InlineParts.codeRanges] 里，由 [MdText] 在文字背后补画圆角底框（见那里的注释）。
 */
internal fun inlineMarkdown(
    text: String,
    palette: DshPalette,
    style: MarkdownStyle,
    codeFontSize: TextUnit,
): InlineParts {
    val maths = ArrayList<String>()
    val codes = ArrayList<IntRange>()
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
                        // dsh 的 .markdown :not(pre)>code：等宽 + .875em（紧凑档 1em）+ 底色
                        // markdown-inline-code + **圆角**（普通档 --dsw-radius-sm = 8、紧凑档 xs = 4）
                        // + 左右内边距（5 / 3）。Compose 的 SpanStyle 只有底色与字号，圆角与内边距
                        // 由 MdText 按这段区间在文字背后补画（不是纯底色方块）。
                        val start = length
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = codeFontSize * style.inlineCodeScale,
                            ),
                        ) {
                            append(rest.substring(1, end))
                        }
                        codes += start until length
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
    return InlineParts(builder, maths, codes)
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
    val maths = parsed.latex
    // 行内代码底框的像素尺寸（drawBehind 里用）
    val codeRadiusPx = with(density) { style.inlineCodeRadius.toPx() }
    val codePadPx = with(density) { style.inlineCodePadH.toPx() }
    // 底框高度 = 代码字体的行高（dsh 的 inline-flex 盒高）；在整行（24px）里垂直居中
    val codeChipHeightPx = with(density) { style.inlineCodeLineHeight.toPx() }
    val hairlinePx = with(density) { DshSpacing.Hairline.toPx() }
    // 布局结果的中转：布局阶段写、绘制阶段读。故意**不是** State —— 在布局里写 State 会多触发
    // 一次重组，而这里只要「这次布局的结果」在 drawBehind 时是新的就够了。
    val layoutHolder = remember { TextLayoutHolder() }
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
        text = parsed.text,
        inlineContent = inlineContent,
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = color,
        fontWeight = fontWeight,
        onTextLayout = { layoutHolder.value = it },
        modifier = modifier.drawBehind {
            val layout = layoutHolder.value ?: return@drawBehind
            if (parsed.codeRanges.isEmpty()) return@drawBehind
            // 几何按布局结果缓存（drawBehind 每次重录制都会跑，见 TextLayoutHolder）
            val rects = layoutHolder.rects(layout, parsed.codeRanges, codePadPx, codeChipHeightPx)
            val corner = androidx.compose.ui.geometry.CornerRadius(codeRadiusPx, codeRadiusPx)
            for (rect in rects) {
                drawRoundRect(color = palette.inlineCode, topLeft = rect.topLeft, size = rect.size, cornerRadius = corner)
                drawRoundRect(
                    color = palette.borderL1,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    cornerRadius = corner,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = hairlinePx),
                )
            }
        },
    )
}

/** 布局结果的中转（布局阶段写、绘制阶段读；故意不是 State —— 见 [MdText] 的注释） */
private class TextLayoutHolder {
    var value: androidx.compose.ui.text.TextLayoutResult? = null

    private var cachedFor: androidx.compose.ui.text.TextLayoutResult? = null
    private var cachedPadH = Float.NaN
    private var cachedChipHeight = Float.NaN
    private var cached: List<androidx.compose.ui.geometry.Rect> = emptyList()

    /**
     * 行内代码底框的矩形（**按布局结果缓存**）：`drawBehind` 在**每一次重录制**里都会跑，而底框几何
     * 只取决于布局结果与内边距，不缓存就等于每次重录都把 `getBoundingBox` 重新查一遍。一屏行内代码
     * 一次重录制会多花 ~100ms（快速滑动时「卡一下再瞬移」），缓存之后重录只剩几十个 `drawRoundRect`。
     */
    fun rects(
        layout: androidx.compose.ui.text.TextLayoutResult,
        ranges: List<IntRange>,
        padH: Float,
        chipHeight: Float,
    ): List<androidx.compose.ui.geometry.Rect> {
        if (cachedFor === layout && cachedPadH == padH && cachedChipHeight == chipHeight) return cached
        val computed = inlineCodeRects(layout, ranges, padH, chipHeight)
        cachedFor = layout
        cachedPadH = padH
        cachedChipHeight = chipHeight
        cached = computed
        return computed
    }
}

/**
 * 行内代码的圆角底框几何（dsh 的 `.markdown :not(pre)>code`：底色 + .5px 描边 + 圆角 + 左右内边距）。
 * Compose 的 [SpanStyle] 只能铺纯色底（直角、无内边距），所以这里按 [TextLayoutResult] 在文字**背后**
 * 补画：[ranges] 里每段逐行取包络、一行一个圆角矩形；一行只查两端字符框，逐字符查是白花钱。
 */
private fun inlineCodeRects(
    layout: androidx.compose.ui.text.TextLayoutResult,
    ranges: List<IntRange>,
    padH: Float,
    chipHeight: Float,
): List<androidx.compose.ui.geometry.Rect> {
    if (ranges.isEmpty()) return emptyList()
    val out = ArrayList<androidx.compose.ui.geometry.Rect>(ranges.size)
    for (range in ranges) {
        var offset = range.first
        while (offset <= range.last) {
            val line = layout.getLineForOffset(offset)
            val lineEnd = layout.getLineEnd(line, visibleEnd = true)
            val last = minOf(range.last, lineEnd - 1)
            if (last < offset) break
            // 横向取这一行里那段字符的包络；纵向用**行盒**居中、高度取代码字体行高 ——
            // getBoundingBox 的 top/bottom 是整行行高（24px），直接拿它画会把底框画胖一圈。
            val head = layout.getBoundingBox(offset)
            val tail = layout.getBoundingBox(last)
            val left = minOf(head.left, tail.left)
            val right = maxOf(head.right, tail.right)
            if (right > left) {
                val lineTop = layout.getLineTop(line)
                val lineBottom = layout.getLineBottom(line)
                val height = minOf(chipHeight, lineBottom - lineTop)
                out += androidx.compose.ui.geometry.Rect(
                    offset = androidx.compose.ui.geometry.Offset(
                        left - padH,
                        lineTop + (lineBottom - lineTop - height) / 2f,
                    ),
                    size = androidx.compose.ui.geometry.Size(right - left + padH * 2f, height),
                )
            }
            offset = last + 1
        }
    }
    return out
}

// ------------------------------------------------------------------ 表格

/** dsh 的 th/td：min-width:100px、max-width:min(30vw,320px)（手机上取绝对上限） */
private const val MD_TABLE_CELL_MIN_DP = 100f
private const val MD_TABLE_CELL_MAX_DP = 320f

/** dsh 的宽表判据：≥4 列按内容宽度横向滚动（.md-table-wide）；<4 列整表铺满列宽、单元格换行 */
private const val MD_TABLE_WIDE_COLUMNS = 4

@Composable
private fun MdTableView(block: MdBlock.Table, style: MarkdownStyle) {
    val palette = LocalDshPalette.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cellStyle = remember(style) { TextStyle(fontSize = style.tableFontSize, lineHeight = style.tableLineHeight) }
    val minCellPx = with(density) { MD_TABLE_CELL_MIN_DP.dp.roundToPx() }
    val maxCellPx = with(density) { MD_TABLE_CELL_MAX_DP.dp.roundToPx() }
    val columnCount = remember(block) {
        maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    }
    // 每列的自然宽度 = 该列所有单元格里最宽的那个（测的是不换行时的宽度，上限 320dp）
    val natural = remember(block, columnCount, minCellPx, maxCellPx, style, palette) {
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
                            text = inlineMarkdown(cell, palette, style, style.tableFontSize).text,
                            style = cellStyle,
                            maxLines = 1,
                        ).size.width
                    }
                    if (measured > widest) widest = measured
                }
                row++
            }
            widest.coerceAtMost(maxCellPx)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availablePx = with(density) { maxWidth.roundToPx() }
        // 窄表铺满列宽（dsh 的 .tableFill）；宽表保留自然宽度、整表横向滚动
        val filled = if (columnCount < MD_TABLE_WIDE_COLUMNS) {
            remember(natural, availablePx, columnCount) {
                fillTableWidthsPx(natural, availablePx, minCellPx)
            }
        } else {
            null
        }
        val widths = remember(natural, filled) { (filled ?: natural).map { with(density) { it.toDp() } } }
        val scroll = if (filled == null) Modifier.horizontalScroll(rememberScrollState()) else Modifier
        Column(scroll) {
            MdTableRow(block.header, header = true, widths = widths, style = style)
            block.rows.forEach { row -> MdTableRow(row, header = false, widths = widths, style = style) }
        }
    }
}

/**
 * 窄表的列宽（px）：整表铺满 [availablePx]（dsh 的 `.tableFill table { width: 100% }`）。
 *
 * 每列先拿 [minCellPx]（dsh 的 `min-width:100px`），剩余宽度按自然宽度比例分给各列；
 * 整数除法的零头依次补给前面的列，保证总和**恰好**等于可用宽度。
 *
 * @return null = 连最小宽度都放不下（这种「宽于可用宽度」的窄表与 dsh 一样退回横向滚动）
 */
internal fun fillTableWidthsPx(natural: List<Int>, availablePx: Int, minCellPx: Int): List<Int>? {
    if (natural.isEmpty() || availablePx <= 0) return null
    if (minCellPx * natural.size > availablePx) return null
    val widths = IntArray(natural.size) { minCellPx }
    val remaining = availablePx - minCellPx * natural.size
    if (remaining <= 0) return widths.toList()
    val weights = natural.map { it.coerceAtLeast(1) }
    val totalWeight = weights.sum()
    val extra = IntArray(weights.size) { (remaining.toLong() * weights[it] / totalWeight).toInt() }
    var assigned = extra.sum()
    var index = 0
    while (assigned < remaining) {
        extra[index % extra.size]++
        assigned++
        index++
    }
    for (i in widths.indices) widths[i] += extra[i]
    return widths.toList()
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
                fontSize = style.tableFontSize,
                lineHeight = style.tableLineHeight,
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
        thickness = DshSpacing.Hairline,
        color = if (header) palette.borderL3 else palette.borderL2,
    )
}

// ------------------------------------------------------------------ 图片

/**
 * Markdown 图片（dsh 的 `._image_`：圆角 12（--dsw-radius-md）、最大宽 100%）。
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
    val bitmap by rememberImage(path, 1280)
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
            .clip(RoundedCornerShape(12.dp))
            .dshClickable(interactionSource = dshInteraction()) { ImagePreviewState.open(path) },
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
