package com.adsh.app.ui.blocks

import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile

/*
 * Word（.docx）的就地解析：段落 / run 的格式、表格结构、编号与样式继承都在这一层摊平，渲染只管画。
 *
 * 口径照 OOXML（ECMA-376）：
 *  - **继承链**：docDefaults → 段落样式（含 basedOn 链）→ 段落直接格式 → run 直接格式；
 *  - **单位**：字号 w:sz 是半磅（磅 × 4/3 = dp）；缩进 / 间距 / 列宽是 twip（1/20 磅，1 twip = 1/15 dp）；
 *  - **合并单元格**：w:gridSpan 横向、w:vMerge 纵向（续格只有起始格那一行画）；
 *  - **表格边框**：w:tblBorders / w:tcBorders 显式给；样式名带 Grid / 网格 的表按网格线兜底。
 *
 * 上限是**预览**口径（[MAX_BLOCKS] / [MAX_TABLE_ROWS] / [MAX_MEDIA_BYTES]），超了截断而不是崩。
 */

/** 1 磅折成 dp（96dpi：1 磅 = 4/3 dp） */
internal const val DP_PER_PT = 4f / 3f

/** 1 twip（1/20 磅）折成 dp：与 PDF 页面同比例（96dp = 1 英寸） */
internal const val DP_PER_TWIP = 1f / 15f

/** 单倍行距相对字号的倍数（Word 的 ascent + descent + lineGap 近似值） */
internal const val WORD_LINE_FACTOR = 1.22f

/** Word 没写字号时的默认值：中文文档的五号 = 10.5 磅 */
internal const val DEFAULT_WORD_SIZE_PT = 10.5f

/** 正文块的上限（与 OfficeRead 的 MAX_BLOCKS 同名不同语义：这里是 Word 段 / 表 / 图） */
private const val MAX_TABLE_ROWS = 100
private const val MAX_CELLS_PER_ROW = 60

/** 每张图最多读多少字节（预览口径：超了就不画这一张） */
private const val MAX_MEDIA_BYTES = 4 * 1024 * 1024

/** EMU（OOXML 的图片尺寸单位）折成 dp：914400 EMU = 1 英寸 = 96dp */
private const val EMU_PER_DP = 9525L

/** Word 里的水平对齐 */
internal enum class WordAlign { LEFT, CENTER, RIGHT, JUSTIFY }

/** 表格单元格的纵向对齐 */
internal enum class WordVAlign { TOP, CENTER, BOTTOM }

/** 边框的边（表格级与单元格级共用） */
internal enum class WordBorderEdge { TOP, LEFT, BOTTOM, RIGHT, INSIDE_H, INSIDE_V }

/** 一条边框：宽（八分之一磅）+ 颜色（0xRRGGBB；null = 跟随主题） */
internal data class WordBorderSpec(val widthEighthPt: Int, val color: Int?)

/** run 的上下标（w:vertAlign）：手打的 x² / H₂O 靠它抬降基线 */
internal enum class WordVertAlign { NONE, SUPER, SUB }

/** 段落里的一段内容：文字 run，或 OMML 公式（转成 LaTeX 交给 [LatexCore] 排版） */
internal sealed interface WordInline {
    data class Text(val run: WordRun) : WordInline

    /** [display] = m:oMathPara（独立成行的公式）；否则是行内公式 */
    data class Math(val latex: String, val display: Boolean) : WordInline
}

/** 一段连续文字的格式：继承链已经摊平，这里只有**生效值**（sizePt = 0 表示跟随段落） */
internal data class WordRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sizePt: Float = 0f,
    val color: Int? = null,
    val vertAlign: WordVertAlign = WordVertAlign.NONE,
)

/** 段落（含单元格里的一段）的生效格式：样式 + 直接格式都已摊平 */
internal data class WordPara(
    val inlines: List<WordInline>,
    val sizePt: Float,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: WordAlign = WordAlign.LEFT,
    val indentLeftDp: Float = 0f,
    /** 首行缩进（正数；Word 的 firstLine） */
    val indentFirstDp: Float = 0f,
    /** 首行之后的缩进（正数；Word 的悬挂缩进 hanging 折到这里） */
    val indentRestDp: Float = 0f,
    val spaceBeforeDp: Float = 0f,
    val spaceAfterDp: Float = 0f,
    /** 行高（dp）；null = 按字号和 [WORD_LINE_FACTOR] 算 */
    val lineHeightDp: Float? = null,
    /** 1..6 = 标题档（0 = 正文） */
    val heading: Int = 0,
    /** 列表前缀（项目符号 / 编号；null = 不是列表段） */
    val bullet: String? = null,
)

/** 一个单元格（合并信息由 vContinue + span 表达） */
internal data class WordCell(
    val paragraphs: List<WordPara>,
    /** 横向合并占几列（w:gridSpan） */
    val span: Int = 1,
    /** 纵向合并的续格（w:vMerge 没有 val="restart"）：版面上被上面那一格盖住，不单独画 */
    val vContinue: Boolean = false,
    val vAlign: WordVAlign = WordVAlign.TOP,
    /** 底纹填充色（w:shd 的 w:fill；null = 无） */
    val shading: Int? = null,
    /** 格自己的边框（null = 全部继承表格） */
    val borders: Map<WordBorderEdge, WordBorderSpec?>? = null,
    /** 格子里的图（一张格子里只画第一张） */
    val image: WordBlock.Image? = null,
)

internal data class WordRow(
    val cells: List<WordCell>,
    /** w:trHeight 的最小行高（dp）；null = 按内容 */
    val heightDp: Float? = null,
)

/** Word 正文的一个块 */
internal sealed interface WordBlock {
    data class Para(val para: WordPara) : WordBlock

    /**
     * 段落里的图（w:drawing）：[data] 是图片字节，[width] / [height] 是版心写的显示尺寸（dp）。
     * 尺寸拿不到时是 0，由画的地方按可用宽度撑满。
     */
    data class Image(val data: ByteArray, val width: Float, val height: Float) : WordBlock

    /**
     * 表格：列宽比例 / 版面宽度来自 w:tblGrid 与 w:tblW；边框、底纹、纵向对齐、合并都在行 / 格里。
     */
    data class Table(
        /** w:tblGrid 的列宽（twip） */
        val grid: List<Int>,
        /** w:tblW 的 dxa（null = 自动） */
        val widthTw: Int?,
        val align: WordAlign,
        /** 表格整体左缩进（w:tblInd，twip） */
        val indentTw: Int,
        /** 表格级边框（边 → 线；显式 nil 用 null 值表示「明确没有」） */
        val borders: Map<WordBorderEdge, WordBorderSpec?>,
        /** 单元格左右内边距（w:tblCellMar，twip） */
        val cellMarginTw: Int,
        val rows: List<WordRow>,
    ) : WordBlock
}

/**
 * 页宽与页边距（twip）。预览按「宽度」缩放到屏幕宽（与 PDF 预览同一口径）：
 * 版心宽度 = 页宽 − 左右边距，表格 / 图片都按同一个比例缩，横向不再滚动。
 */
internal data class WordPage(
    val widthTw: Int = 11906,
    val marginLeftTw: Int = 1418,
    val marginRightTw: Int = 1418,
    val marginTopTw: Int = 1440,
    val marginBottomTw: Int = 1440,
)

/** 一篇 Word 文档：正文块 + 页面设置 */
internal data class WordDocument(val blocks: List<WordBlock>, val page: WordPage)

// ------------------------------------------------------------------------------------------------
// 属性（继承链上的中间形态：字段可空 = 「这一层没写」）
// ------------------------------------------------------------------------------------------------

private class WordRunProps(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val sizePt: Float? = null,
    val color: Int? = null,
    val vertAlign: WordVertAlign? = null,
)

private class WordParaProps(
    val align: WordAlign? = null,
    val indentLeftTw: Int? = null,
    val firstLineTw: Int? = null,
    val hangingTw: Int? = null,
    val beforeTw: Int? = null,
    val afterTw: Int? = null,
    val lineTw: Int? = null,
    val lineRule: String? = null,
    val numId: String? = null,
    val ilvl: Int? = null,
    val outlineLvl: Int? = null,
)

private class WordStyleDef(
    name: String,
    val basedOn: String?,
    val run: WordRunProps?,
    val para: WordParaProps?,
) {
    val name: String = name
}

private class WordStyles(
    val defaultRun: WordRunProps?,
    /** w:docDefaults/w:pPrDefault：Word 的默认段间距 / 行距挂在这里 */
    val defaultPara: WordParaProps?,
    val byId: Map<String, WordStyleDef>,
    val defaultParagraph: String?,
) {
    companion object {
        val EMPTY = WordStyles(null, null, emptyMap(), null)
    }
}

private fun mergeRun(base: WordRunProps?, next: WordRunProps?): WordRunProps? {
    if (base == null) return next
    if (next == null) return base
    return WordRunProps(
        bold = next.bold ?: base.bold,
        italic = next.italic ?: base.italic,
        underline = next.underline ?: base.underline,
        strike = next.strike ?: base.strike,
        sizePt = next.sizePt ?: base.sizePt,
        color = next.color ?: base.color,
        vertAlign = next.vertAlign ?: base.vertAlign,
    )
}

private fun mergePara(base: WordParaProps?, next: WordParaProps?): WordParaProps? {
    if (base == null) return next
    if (next == null) return base
    return WordParaProps(
        align = next.align ?: base.align,
        indentLeftTw = next.indentLeftTw ?: base.indentLeftTw,
        firstLineTw = next.firstLineTw ?: base.firstLineTw,
        hangingTw = next.hangingTw ?: base.hangingTw,
        beforeTw = next.beforeTw ?: base.beforeTw,
        afterTw = next.afterTw ?: base.afterTw,
        lineTw = next.lineTw ?: base.lineTw,
        lineRule = next.lineRule ?: base.lineRule,
        numId = next.numId ?: base.numId,
        ilvl = next.ilvl ?: base.ilvl,
        outlineLvl = next.outlineLvl ?: base.outlineLvl,
    )
}

// ------------------------------------------------------------------------------------------------
// 入口
// ------------------------------------------------------------------------------------------------

internal fun readWordDoc(file: File): WordDocument = ZipFile(file).use { zip ->
    val xml = zip.partText("word/document.xml")
        ?: throw IllegalStateException("不是 Word 文档：缺 word/document.xml")
    val styles = zip.partText("word/styles.xml")?.let(::parseWordStyles) ?: WordStyles.EMPTY
    val numbering = zip.partText("word/numbering.xml")?.let(::parseWordNumbering)
    val media = readWordMedia(zip)
    val body = parseXml(xml).documentElement.descendants("w:body").firstOrNull()
        ?: throw IllegalStateException("文档结构不完整：缺 w:body")
    val out = ArrayList<WordBlock>()
    collectWordBlocks(body, styles, WordNumberingState(numbering), media, out)
    WordDocument(out, wordPage(body))
}

/** 页面设置取正文最后一个 w:sectPr；缺字段按 A4 + Word 默认页边距 */
private fun wordPage(body: Element): WordPage {
    val sect = body.descendants("w:sectPr").lastOrNull() ?: return WordPage()
    val size = sect.children("w:pgSz").firstOrNull()
    val margins = sect.children("w:pgMar").firstOrNull()
    fun attr(element: Element?, name: String, fallback: Int): Int =
        element?.getAttribute(name)?.toIntOrNull()?.takeIf { it > 0 } ?: fallback
    return WordPage(
        widthTw = attr(size, "w:w", 11906).coerceIn(2000, 60000),
        marginLeftTw = attr(margins, "w:left", 1418).coerceIn(0, 14400),
        marginRightTw = attr(margins, "w:right", 1418).coerceIn(0, 14400),
        marginTopTw = attr(margins, "w:top", 1440).coerceIn(0, 14400),
        marginBottomTw = attr(margins, "w:bottom", 1440).coerceIn(0, 14400),
    )
}

/** 正文块收集：直接子节点是段落 / 表格，内容控件（w:sdt）会把这一层包起来 */
private fun collectWordBlocks(
    parent: Element,
    styles: WordStyles,
    numbering: WordNumberingState,
    media: Map<String, ByteArray>,
    out: MutableList<WordBlock>,
) {
    for (index in 0 until parent.childNodes.length) {
        if (out.size >= MAX_BLOCKS) return
        val node = parent.childNodes.item(index) as? Element ?: continue
        when (node.tagName) {
            "w:p" -> {
                out += WordBlock.Para(wordPara(node, styles, numbering))
                // 段落里插的图（报告、说明书里很常见）：文字在上一块，图紧跟其后
                wordImage(node, media)?.let { out += it }
            }
            "w:tbl" -> out += wordTable(node, styles, numbering, media)
            "w:sdt", "w:sdtContent", "w:customXml" -> collectWordBlocks(node, styles, numbering, media, out)
        }
    }
}

// ------------------------------------------------------------------------------------------------
// 段落
// ------------------------------------------------------------------------------------------------

private fun wordPara(p: Element, styles: WordStyles, numbering: WordNumberingState): WordPara {
    val pPr = p.children("w:pPr").firstOrNull()
    val direct = pPr?.let(::parseParaProps)
    val styleId = pPr?.children("w:pStyle")?.firstOrNull()?.getAttribute("w:val")?.ifEmpty { null }
    val chain = styleChain(styles, styleId ?: styles.defaultParagraph)

    var paraProps: WordParaProps? = styles.defaultPara
    chain.forEach { paraProps = mergePara(paraProps, it.para) }
    paraProps = mergePara(paraProps, direct)

    var runProps: WordRunProps? = styles.defaultRun
    chain.forEach { runProps = mergeRun(runProps, it.run) }
    runProps = mergeRun(runProps, pPr?.children("w:rPr")?.firstOrNull()?.let(::parseRunProps))

    val baseSize = runProps?.sizePt ?: DEFAULT_WORD_SIZE_PT
    val numId = paraProps?.numId?.takeIf { it.isNotEmpty() && it != "0" }
    val ilvl = paraProps?.ilvl ?: 0
    val level = numId?.let { numbering.level(it, ilvl) }
    val bullet = numId?.let { numbering.label(it, ilvl) }

    // 缩进：段落 / 样式没写时取编号级别里的（Word 的列表缩进挂在 numbering 上）
    val indentLeftTw = paraProps?.indentLeftTw ?: level?.indentLeftTw
    val firstLineTw = paraProps?.firstLineTw ?: level?.firstLineTw
    val hangingTw = paraProps?.hangingTw ?: level?.hangingTw

    val inlines = ArrayList<WordInline>()
    collectWordInlines(p, runProps, baseSize, inlines)

    return WordPara(
        inlines = inlines,
        sizePt = baseSize,
        bold = runProps?.bold == true,
        italic = runProps?.italic == true,
        align = paraProps?.align ?: WordAlign.LEFT,
        // 版面量一律夹进可用范围：生成器写坏的巨大 twip 会把 Compose 的约束撑爆
        indentLeftDp = ((indentLeftTw ?: 0) * DP_PER_TWIP).coerceIn(-2000f, 2000f),
        indentFirstDp = ((firstLineTw ?: 0).coerceAtLeast(0) * DP_PER_TWIP).coerceIn(0f, 2000f),
        indentRestDp = ((hangingTw ?: 0).coerceAtLeast(0) * DP_PER_TWIP).coerceIn(0f, 2000f),
        spaceBeforeDp = ((paraProps?.beforeTw ?: 0) * DP_PER_TWIP).coerceIn(0f, 400f),
        spaceAfterDp = ((paraProps?.afterTw ?: 0) * DP_PER_TWIP).coerceIn(0f, 400f),
        lineHeightDp = lineHeightDp(paraProps?.lineTw, paraProps?.lineRule, baseSize)?.coerceIn(4f, 400f),
        heading = headingLevel(chain, paraProps),
        bullet = bullet,
    )
}

/**
 * 段落里的内容：顺序遍历 w:r 与 OMML（m:oMath / m:oMathPara），超链接、内容控件、修订插入继续往里走。
 * 相邻且格式完全相同的文字 run 合并（AnnotatedString 的 span 数量别爆）；公式按位置插进同一串。
 */
private fun collectWordInlines(
    parent: Element,
    base: WordRunProps?,
    baseSize: Float,
    out: MutableList<WordInline>,
) {
    for (index in 0 until parent.childNodes.length) {
        val node = parent.childNodes.item(index) as? Element ?: continue
        when (node.tagName) {
            "w:r" -> {
                val props = mergeRun(base, node.children("w:rPr").firstOrNull()?.let(::parseRunProps))
                val text = runText(node)
                if (text.isEmpty()) {
                    // 有的生成器把行内公式塞在 run 里：文字没有，公式照收
                    collectWordInlines(node, props, baseSize, out)
                    continue
                }
                val run = WordRun(
                    text = text,
                    bold = props?.bold == true,
                    italic = props?.italic == true,
                    underline = props?.underline == true,
                    strike = props?.strike == true,
                    sizePt = props?.sizePt ?: baseSize,
                    color = props?.color,
                    vertAlign = props?.vertAlign ?: WordVertAlign.NONE,
                )
                val previous = out.lastOrNull() as? WordInline.Text
                if (previous != null && previous.run.copy(text = "") == run.copy(text = "")) {
                    out[out.lastIndex] = WordInline.Text(previous.run.copy(text = previous.run.text + text))
                } else {
                    out += WordInline.Text(run)
                }
            }
            // 公式：m:oMath 行内、m:oMathPara 独立成行（Word 的「显示」公式）
            "m:oMath" -> out += WordInline.Math(ommlToLatex(node), display = false)
            "m:oMathPara" -> out += WordInline.Math(ommlToLatex(node), display = true)
            // 修订里删掉的内容不显示；其余容器继续往里走
            "w:del", "w:moveFrom" -> Unit
            "w:hyperlink", "w:ins", "w:smartTag", "w:fldSimple", "w:sdt", "w:sdtContent", "w:customXml" ->
                collectWordInlines(node, base, baseSize, out)
        }
    }
}

/** 一个 run 里的文字：w:t / w:tab / w:br；域代码（w:instrText）与图片不在这里出字 */
private fun runText(run: Element): String {
    val out = StringBuilder()
    for (index in 0 until run.childNodes.length) {
        val child = run.childNodes.item(index) as? Element ?: continue
        when (child.tagName) {
            "w:t" -> out.append(child.textContent)
            "w:tab" -> out.append('\t')
            "w:br", "w:cr" -> out.append('\n')
            "w:noBreakHyphen" -> out.append('\u2011')
        }
    }
    return out.toString()
}

/** 行高（dp）：auto 按 240 为一倍行距折算；exact / atLeast 是 twip */
private fun lineHeightDp(lineTw: Int?, lineRule: String?, sizePt: Float): Float? {
    val auto = sizePt * WORD_LINE_FACTOR * DP_PER_PT
    if (lineTw == null || lineTw <= 0) return auto
    return when (lineRule) {
        "exact" -> lineTw * DP_PER_TWIP
        "atLeast" -> maxOf(auto, lineTw * DP_PER_TWIP)
        else -> sizePt * WORD_LINE_FACTOR * (lineTw / 240f) * DP_PER_PT
    }
}

/** 标题档：样式名 / id 先认，再看 w:outlineLvl */
private fun headingLevel(chain: List<WordStyleDef>, props: WordParaProps?): Int {
    val outline = props?.outlineLvl
    if (outline != null && outline in 0..5) return outline + 1
    chain.asReversed().forEach { def ->
        for (candidate in listOf(def.name, def.name.replace(" ", ""))) {
            val match = WORD_HEADING.matchEntire(candidate)
            if (match != null) return match.groupValues[1].toIntOrNull() ?: 0
        }
    }
    return 0
}

private val WORD_HEADING = Regex("^(?:Heading|heading|标题)\\s*([1-9])$")

// ------------------------------------------------------------------------------------------------
// 样式 / 编号
// ------------------------------------------------------------------------------------------------

private fun parseWordStyles(xml: String): WordStyles {
    val root = parseXml(xml).documentElement
    val defaults = root.descendants("w:docDefaults").firstOrNull()
    val defaultRun = defaults?.descendants("w:rPrDefault")?.firstOrNull()
        ?.children("w:rPr")?.firstOrNull()?.let(::parseRunProps)
    val defaultPara = defaults?.descendants("w:pPrDefault")?.firstOrNull()
        ?.children("w:pPr")?.firstOrNull()?.let(::parseParaProps)
    var defaultParagraph: String? = null
    val byId = HashMap<String, WordStyleDef>()
    root.children("w:style").forEach { style ->
        val id = style.getAttribute("w:styleId")
        if (id.isEmpty()) return@forEach
        val type = style.getAttribute("w:type")
        if (type != "paragraph" && type != "character") return@forEach
        val name = style.children("w:name").firstOrNull()?.getAttribute("w:val").orEmpty()
        val basedOn = style.children("w:basedOn").firstOrNull()?.getAttribute("w:val")?.ifEmpty { null }
        val run = style.children("w:rPr").firstOrNull()?.let(::parseRunProps)
        val para = if (type == "paragraph") style.children("w:pPr").firstOrNull()?.let(::parseParaProps) else null
        byId[id] = WordStyleDef(name, basedOn, run, para)
        if (type == "paragraph" && style.getAttribute("w:default") == "1") defaultParagraph = id
    }
    return WordStyles(defaultRun, defaultPara, byId, defaultParagraph)
}

/** 样式链（base → 派生，最后是它自己）；basedOn 成环时截断 */
private fun styleChain(styles: WordStyles, id: String?): List<WordStyleDef> {
    if (id == null) return emptyList()
    val out = ArrayList<WordStyleDef>()
    val seen = HashSet<String>()
    var current: String? = id
    while (current != null && seen.add(current)) {
        val def = styles.byId[current] ?: break
        out.add(0, def)
        current = def.basedOn
    }
    return out
}

private fun parseRunProps(rPr: Element): WordRunProps = WordRunProps(
    bold = rPr.children("w:b").firstOrNull()?.let(::wordToggle),
    italic = rPr.children("w:i").firstOrNull()?.let(::wordToggle),
    underline = rPr.children("w:u").firstOrNull()?.let { element ->
        val value = element.getAttribute("w:val")
        value != "none" && value != "0" && value != "false"
    },
    strike = rPr.children("w:strike").firstOrNull()?.let(::wordToggle),
    // 半磅 → 磅；离谱的值（生成器写坏）夹进 4..96 磅：Compose 的文本布局不接受无限大的字号
    sizePt = rPr.children("w:sz").firstOrNull()?.getAttribute("w:val")?.toFloatOrNull()?.div(2f)
        ?.coerceIn(4f, 96f),
    color = rPr.children("w:color").firstOrNull()?.getAttribute("w:val")?.let(::wordColor),
    vertAlign = when (rPr.children("w:vertAlign").firstOrNull()?.getAttribute("w:val")) {
        "superscript" -> WordVertAlign.SUPER
        "subscript" -> WordVertAlign.SUB
        else -> null
    },
)

private fun parseParaProps(pPr: Element): WordParaProps {
    val ind = pPr.children("w:ind").firstOrNull()
    val spacing = pPr.children("w:spacing").firstOrNull()
    val numPr = pPr.children("w:numPr").firstOrNull()
    return WordParaProps(
        align = pPr.children("w:jc").firstOrNull()?.getAttribute("w:val")?.let(::wordAlign),
        indentLeftTw = ind?.attrTw("w:left") ?: ind?.attrTw("w:start"),
        firstLineTw = ind?.attrTw("w:firstLine"),
        hangingTw = ind?.attrTw("w:hanging"),
        beforeTw = spacing?.attrTw("w:before") ?: spacing?.attrTw("w:beforeLines")?.let { it * 12 / 5 },
        afterTw = spacing?.attrTw("w:after") ?: spacing?.attrTw("w:afterLines")?.let { it * 12 / 5 },
        lineTw = spacing?.attrTw("w:line"),
        lineRule = spacing?.getAttribute("w:lineRule")?.ifEmpty { "auto" },
        numId = numPr?.children("w:numId")?.firstOrNull()?.getAttribute("w:val"),
        ilvl = numPr?.children("w:ilvl")?.firstOrNull()?.getAttribute("w:val")?.toIntOrNull(),
        outlineLvl = pPr.children("w:outlineLvl").firstOrNull()?.getAttribute("w:val")?.toIntOrNull(),
    )
}

/** w:b / w:i 这类开关：没有 val 就是开，val 为 0/false 是关 */
private fun wordToggle(element: Element): Boolean {
    val value = element.getAttribute("w:val")
    return value.isEmpty() || (value != "0" && value != "false" && value != "off")
}

private fun Element.attrTw(name: String): Int? = getAttribute(name).toIntOrNull()

private fun wordAlign(value: String): WordAlign = when (value) {
    "center" -> WordAlign.CENTER
    "right", "end" -> WordAlign.RIGHT
    "both", "distribute", "justify" -> WordAlign.JUSTIFY
    else -> WordAlign.LEFT
}

/** OOXML 的颜色：6 位十六进制；auto 或认不出 = 跟随主题 */
private fun wordColor(value: String): Int? {
    if (value.isEmpty() || value == "auto") return null
    return value.removePrefix("#").toIntOrNull(16)
}

// ------------------------------------------------------------------------------------------------
// 编号
// ------------------------------------------------------------------------------------------------

private class WordNumLevel(
    val format: String,
    val text: String,
    val start: Int,
    val indentLeftTw: Int?,
    val firstLineTw: Int?,
    val hangingTw: Int?,
)

private class WordNumbering(
    /** numId → abstractNumId */
    val nums: Map<String, String>,
    /** abstractNumId → 各级定义（下标 = ilvl） */
    val levels: Map<String, List<WordNumLevel>>,
)

/** 编号计数器：一级 +1、更深级清零；lvlText 里的 %1 按级替换 */
private class WordNumberingState(private val numbering: WordNumbering?) {
    private val counters = HashMap<Pair<String, Int>, Int>()

    fun level(numId: String, ilvl: Int): WordNumLevel? {
        val numbering = numbering ?: return null
        val abstractId = numbering.nums[numId] ?: return null
        return numbering.levels[abstractId]?.getOrNull(ilvl)
    }

    fun label(numId: String, ilvl: Int): String? {
        val level = level(numId, ilvl) ?: return null
        if (level.format == "none") return null
        if (level.format == "bullet") return level.text.ifEmpty { "•" }
        val key = numId to ilvl
        val value = (counters[key] ?: (level.start - 1)) + 1
        counters[key] = value
        counters.keys.filter { it.first == numId && it.second > ilvl }.forEach { counters[it] = 0 }
        var label = level.text.ifEmpty { "%" + (ilvl + 1) + "." }
        for (slot in 0..8) {
            val slotValue = if (slot == ilvl) value else (counters[numId to slot] ?: 0)
            label = label.replace("%" + (slot + 1), slotValue.toString())
        }
        return label
    }
}

private fun parseWordNumbering(xml: String): WordNumbering {
    val root = parseXml(xml).documentElement
    val abstract = HashMap<String, List<WordNumLevel>>()
    root.children("w:abstractNum").forEach { element ->
        val id = element.getAttribute("w:abstractNumId")
        val levels = ArrayList<WordNumLevel>()
        element.children("w:lvl").forEach { level ->
            val ilvl = level.getAttribute("w:ilvl").toIntOrNull() ?: levels.size
            val ind = level.children("w:pPr").firstOrNull()?.children("w:ind")?.firstOrNull()
            val parsed = WordNumLevel(
                format = level.children("w:numFmt").firstOrNull()?.getAttribute("w:val").orEmpty(),
                text = level.children("w:lvlText").firstOrNull()?.getAttribute("w:val").orEmpty(),
                start = level.children("w:start").firstOrNull()?.getAttribute("w:val")?.toIntOrNull() ?: 1,
                indentLeftTw = ind?.attrTw("w:left") ?: ind?.attrTw("w:start"),
                firstLineTw = ind?.attrTw("w:firstLine"),
                hangingTw = ind?.attrTw("w:hanging"),
            )
            while (levels.size <= ilvl) {
                levels += WordNumLevel("decimal", "", 1, null, null, null)
            }
            levels[ilvl] = parsed
        }
        abstract[id] = levels
    }
    val nums = HashMap<String, String>()
    root.children("w:num").forEach { element ->
        val id = element.getAttribute("w:numId")
        val abstractId = element.children("w:abstractNumId").firstOrNull()?.getAttribute("w:val")
        if (id.isNotEmpty() && !abstractId.isNullOrEmpty()) nums[id] = abstractId
    }
    return WordNumbering(nums, abstract)
}

// ------------------------------------------------------------------------------------------------
// 表格
// ------------------------------------------------------------------------------------------------

private fun wordTable(
    table: Element,
    styles: WordStyles,
    numbering: WordNumberingState,
    media: Map<String, ByteArray>,
): WordBlock.Table {
    val tblPr = table.children("w:tblPr").firstOrNull()
    val grid = table.children("w:tblGrid").firstOrNull()
        ?.children("w:gridCol")
        ?.mapNotNull { it.getAttribute("w:w").toIntOrNull()?.coerceAtLeast(1) }
        .orEmpty()
    val borders = parseBorders(tblPr?.children("w:tblBorders")?.firstOrNull()).toMutableMap()
    val styleName = tblPr?.children("w:tblStyle")?.firstOrNull()?.getAttribute("w:val").orEmpty()
    if (borders.isEmpty() && GRID_STYLE.containsMatchIn(styleName)) {
        WordBorderEdge.values().forEach { borders[it] = WordBorderSpec(4, null) }
    }
    val cellMar = tblPr?.children("w:tblCellMar")?.firstOrNull()
    val marginTw = cellMar?.children("w:left")?.firstOrNull()?.attrTw("w:w")
        ?: cellMar?.children("w:right")?.firstOrNull()?.attrTw("w:w")
        ?: DEFAULT_CELL_MARGIN_TW
    val tblW = tblPr?.children("w:tblW")?.firstOrNull()
    val rows = table.children("w:tr").take(MAX_TABLE_ROWS).map { row ->
        val trPr = row.children("w:trPr").firstOrNull()
        val cells = ArrayList<WordCell>()
        row.children("w:tc").take(MAX_CELLS_PER_ROW).forEach { cell ->
            val tcPr = cell.children("w:tcPr").firstOrNull()
            val vMerge = tcPr?.children("w:vMerge")?.firstOrNull()
            val paragraphs = cell.children("w:p").map { wordPara(it, styles, numbering) }
            cells += WordCell(
                paragraphs = paragraphs,
                span = tcPr?.children("w:gridSpan")?.firstOrNull()
                    ?.getAttribute("w:val")?.toIntOrNull()?.coerceIn(1, MAX_CELLS_PER_ROW) ?: 1,
                vContinue = vMerge != null && vMerge.getAttribute("w:val") != "restart",
                vAlign = when (tcPr?.children("w:vAlign")?.firstOrNull()?.getAttribute("w:val")) {
                    "center" -> WordVAlign.CENTER
                    "bottom" -> WordVAlign.BOTTOM
                    else -> WordVAlign.TOP
                },
                shading = tcPr?.children("w:shd")?.firstOrNull()?.getAttribute("w:fill")?.let(::wordColor),
                borders = parseBorders(tcPr?.children("w:tcBorders")?.firstOrNull()).takeIf { it.isNotEmpty() },
                image = wordImage(cell, media),
            )
        }
        WordRow(
            cells = cells,
            heightDp = trPr?.children("w:trHeight")?.firstOrNull()?.getAttribute("w:val")
                ?.toIntOrNull()?.times(DP_PER_TWIP),
        )
    }
    return WordBlock.Table(
        grid = grid,
        widthTw = tblW?.takeIf { it.getAttribute("w:type") == "dxa" }?.attrTw("w:w"),
        align = tblPr?.children("w:jc")?.firstOrNull()?.getAttribute("w:val")?.let(::wordAlign) ?: WordAlign.LEFT,
        indentTw = tblPr?.children("w:tblInd")?.firstOrNull()?.attrTw("w:w") ?: 0,
        borders = borders,
        cellMarginTw = marginTw,
        rows = rows,
    )
}

/** 默认单元格左右内边距：Word 的 108 twip（0.19cm） */
private const val DEFAULT_CELL_MARGIN_TW = 108

/** 表格样式名里带 Grid / 网格：样式自带网格线（w:tblStyle 是「Table Grid」这类内置样式） */
private val GRID_STYLE = Regex("(?i)grid|网格")

/** 一组边框（w:tblBorders / w:tcBorders）：只收显式写出来的边；nil / none 记成 null（明确没有线） */
private fun parseBorders(element: Element?): Map<WordBorderEdge, WordBorderSpec?> {
    val borders = LinkedHashMap<WordBorderEdge, WordBorderSpec?>()
    if (element == null) return borders
    element.children("w:top").firstOrNull()?.let { borders[WordBorderEdge.TOP] = parseBorder(it) }
    element.children("w:left").firstOrNull()?.let { borders[WordBorderEdge.LEFT] = parseBorder(it) }
    element.children("w:start").firstOrNull()?.let { borders[WordBorderEdge.LEFT] = parseBorder(it) }
    element.children("w:bottom").firstOrNull()?.let { borders[WordBorderEdge.BOTTOM] = parseBorder(it) }
    element.children("w:right").firstOrNull()?.let { borders[WordBorderEdge.RIGHT] = parseBorder(it) }
    element.children("w:end").firstOrNull()?.let { borders[WordBorderEdge.RIGHT] = parseBorder(it) }
    element.children("w:insideH").firstOrNull()?.let { borders[WordBorderEdge.INSIDE_H] = parseBorder(it) }
    element.children("w:insideV").firstOrNull()?.let { borders[WordBorderEdge.INSIDE_V] = parseBorder(it) }
    return borders
}

private fun parseBorder(element: Element): WordBorderSpec? {
    val style = element.getAttribute("w:val")
    if (style == "nil" || style == "none") return null
    return WordBorderSpec(
        widthEighthPt = element.getAttribute("w:sz").toIntOrNull() ?: 4,
        color = element.getAttribute("w:color")?.let(::wordColor),
    )
}

// ------------------------------------------------------------------------------------------------
// 图片
// ------------------------------------------------------------------------------------------------

/**
 * 正文里引用的图片：关系表（word/_rels/document.xml.rels）的 Id → 图片字节。
 * 只认 Target 指向 media/ 的关系；读不动的（太大 / 坏了）跳过，画的时候自然就没有这一张。
 */
private fun readWordMedia(zip: ZipFile): Map<String, ByteArray> {
    val rels = zip.partText("word/_rels/document.xml.rels") ?: return emptyMap()
    return parseXml(rels).documentElement.descendants("Relationship").mapNotNull { rel ->
        val target = rel.getAttribute("Target")
        if (!target.contains("media/")) return@mapNotNull null
        val entry = zip.getEntry(partPath("word", target)) ?: return@mapNotNull null
        if (entry.size <= 0 || entry.size > MAX_MEDIA_BYTES) return@mapNotNull null
        val bytes = runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull()
            ?: return@mapNotNull null
        rel.getAttribute("Id") to bytes
    }.toMap()
}

/**
 * 元素（段落或单元格）里的第一张图：DrawingML 写 a:blip 的 r:embed，老式 VML 写 v:imagedata 的
 * r:id，两种都认；显示尺寸取 wp:extent（EMU）。关系表里没有这张图就返回 null。
 */
private fun wordImage(element: Element, media: Map<String, ByteArray>): WordBlock.Image? {
    val id = element.descendants("a:blip").firstOrNull()?.getAttribute("r:embed").orEmpty()
        .ifEmpty { element.descendants("v:imagedata").firstOrNull()?.getAttribute("r:id").orEmpty() }
    val data = media[id] ?: return null
    val extent = element.descendants("wp:extent").firstOrNull()
    val width = extent?.getAttribute("cx")?.toLongOrNull()?.div(EMU_PER_DP) ?: 0L
    val height = extent?.getAttribute("cy")?.toLongOrNull()?.div(EMU_PER_DP) ?: 0L
    return WordBlock.Image(data, width.toFloat(), height.toFloat())
}
