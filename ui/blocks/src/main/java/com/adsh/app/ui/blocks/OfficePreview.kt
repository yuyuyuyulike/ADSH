package com.adsh.app.ui.blocks

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette
import com.adsh.app.ui.kit.DshSpacing
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.kit.dshInteraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * 文档预览的画：.docx / .xlsx / .pptx 与 .csv / .tsv（dsh 的 sidebar-documentpreview 里
 * 「Office」与「表格」那两条线的等价物）。
 *
 * 读在 [OfficeRead.kt] / [WordRead.kt]（这一份只管把 WordDocument / SheetTable 画出来）。
 * 老式二进制 Office（.doc/.xls/.ppt）与加密文档读不了 —— 落到「不支持」那一屏（见 DocumentPreview.kt
 * 的 unsupportedNoteOf），并给「用其他应用打开」这条安卓标准出路。
 */

/** 单元格宽度（网格的横向滚动就是靠它撑出来的，与 dsh 的列宽近似无关） */
private val SHEET_CELL_WIDTH = 132.dp

/** 内嵌图解码的目标像素（最长边）：版面里图片按页宽缩，最大也就几百 dp，1280 留了余量 */
private const val INLINE_IMAGE_PX = 1280

/** 表格最小行高（空行也要有版面的方正感；按页面缩放系数再乘） */
private val WORD_MIN_ROW_HEIGHT = 16.dp

/** PDF 预览的外边距（文档类预览共用：页面按屏幕宽减掉这两边再缩放） */
private val DOCUMENT_INSET = 12.dp

// ------------------------------------------------------------------ 渲染

/**
 * 读一次预览并记住结果（文档类预览共用这一份）：返回（内容, 失败说明）。
 * 两个都是 null = 还在读。
 */
@Composable
private fun <T> previewOf(path: String, read: () -> T): Pair<T?, String?> {
    var value by remember(path) { mutableStateOf<T?>(null) }
    var failure by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        value = null
        failure = null
        withContext(Dispatchers.IO) { runCatching { read() } }.fold(
            onSuccess = { value = it },
            onFailure = { failure = it.message ?: it::class.java.simpleName },
        )
    }
    return value to failure
}

/**
 * Office 文件的**唯一入口**：先按内容认格式（见 [ooxmlKindOf]），认不出来再认老式 .doc
 * （CFB 容器里有 WordDocument 流），都不认才落到「不支持」那一屏 —— 说明文案解释为什么。
 */
@Composable
internal fun OfficePreview(path: String) {
    val ext = File(path).name.substringAfterLast('.', "").lowercase()
    val ooxml = remember(path) { runCatching { ooxmlKindOf(File(path)) }.getOrNull() }
    when (ooxml) {
        "word" -> WordPreview(path)
        "excel" -> WorkbookPreview(path)
        "slides" -> SlidesPreview(path)
        else -> UnsupportedPreview(path, note = unsupportedNoteOf(ext))
    }
}

/** .docx 预览：页面按 A4 版心缩到屏幕宽（与 PDF 预览同比例），段落 / 表格 / 公式 / 图片都在版心里 */
@Composable
internal fun WordPreview(path: String) {
    val (doc, failure) = previewOf(path) { readWordDoc(File(path)) }
    when {
        failure != null -> CenteredNote("cannot read this document: " + failure)
        doc == null -> CenteredNote("正在读取…")
        else -> WordBody(doc)
    }
}

/**
 * Word 正文（.docx）：白色纸面 + 页边距，按**页宽**缩放到可用宽度。
 *
 * 缩放系数 = 可用宽度 / 页宽（dp），字号、缩进、表格列宽、图片尺寸都乘它 —— 与 PDF 预览里
 * 「A4 页填满屏幕宽」是同一个比例，所以横向不会出现滚动条。
 */
@Composable
private fun WordBody(doc: WordDocument) {
    val palette = LocalDshPalette.current
    Column(Modifier.fillMaxSize().background(palette.bgDocumentPreview)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(DOCUMENT_INSET)) {
            val scale = (maxWidth.value / (doc.page.widthTw * DP_PER_TWIP)).coerceIn(0.05f, 4f)
            val left = (doc.page.marginLeftTw * DP_PER_TWIP * scale).dp
            val right = (doc.page.marginRightTw * DP_PER_TWIP * scale).dp
            val top = (doc.page.marginTopTw * DP_PER_TWIP * scale).dp
            val bottom = (doc.page.marginBottomTw * DP_PER_TWIP * scale).dp
            SelectionContainer {
                val listState = rememberLazyListState()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White)
                        .dshVerticalScrollbar(listState),
                    state = listState,
                    contentPadding = PaddingValues(start = left, end = right, top = top, bottom = bottom),
                ) {
                    items(doc.blocks.size) { index ->
                        when (val block = doc.blocks[index]) {
                            is WordBlock.Para -> WordParaRow(block.para, scale)
                            is WordBlock.Table -> WordTableBlock(block, scale)
                            is WordBlock.Image -> WordImage(block, scale)
                        }
                    }
                }
            }
        }
    }
}

/** 一段落的画：段落格式走 Text 的 style，run 之间的差异（粗 / 斜 / 下划线 / 字号 / 颜色 / 上下标）走 span */
@Composable
private fun WordParaRow(para: WordPara, scale: Float) {
    val fontSizeDp = para.sizePt * DP_PER_PT * scale
    val lineHeightDp = para.lineHeightDp?.times(scale) ?: (fontSizeDp * WORD_LINE_FACTOR)
    val top = (para.spaceBeforeDp * scale).dp
    val bottom = (para.spaceAfterDp * scale).dp
    // 独立成行的公式（m:oMathPara）：居中一张图，不参与正文排版
    val displayMath = para.inlines.singleOrNull() as? WordInline.Math
    if (displayMath != null && displayMath.display) {
        WordMathBlock(displayMath.latex, fontSizeDp, Modifier.padding(top = top, bottom = bottom))
        return
    }
    if (para.inlines.isEmpty()) {
        // 空段落 = Word 里的一个空行：只占行高，不画字
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = top, bottom = bottom)
                .height(lineHeightDp.dp),
        )
        return
    }
    val density = LocalDensity.current
    val mathSpans = remember(para, fontSizeDp, scale) {
        wordMathSpans(para, fontSizeDp, scale, density.density, density.fontScale)
    }
    Text(
        text = mathSpans.text,
        inlineContent = mathSpans.content,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = top, bottom = bottom, start = (para.indentLeftDp * scale).dp),
        fontSize = fontSizeDp.sp,
        lineHeight = lineHeightDp.sp,
        fontWeight = if (para.bold || para.heading > 0) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (para.italic) FontStyle.Italic else FontStyle.Normal,
        color = Color.Black,
        textAlign = para.align.toTextAlign(),
        style = TextStyle(
            textIndent = TextIndent(
                firstLine = (para.indentFirstDp * scale).sp,
                restLine = (para.indentRestDp * scale).sp,
            ),
        ),
    )
}

/** 行内公式的渲染结果：AnnotatedString（公式位置是占位符）+ 占位符 → 位图 */
private class WordMathSpans(val text: AnnotatedString, val content: Map<String, InlineTextContent>)

/**
 * 把段落里的公式渲染成位图（与聊天公式同一套 LaTeX 排版 / 缓存），文字 run 走 span。
 * 位图尺寸是 dp，占位符用 sp 表达（除一次 fontScale，保证与文字同一缩放）。
 */
private fun wordMathSpans(
    para: WordPara,
    fontSizeDp: Float,
    scale: Float,
    density: Float,
    fontScale: Float,
): WordMathSpans {
    val sizePx = fontSizeDp * density
    val safeScale = if (fontScale > 0f) fontScale else 1f
    val contents = LinkedHashMap<String, InlineTextContent>()
    val text = buildAnnotatedString {
        para.bullet?.let { bullet ->
            append(bullet)
            append(" ")
        }
        para.inlines.forEachIndexed { index, inline ->
            when (inline) {
                is WordInline.Text -> withStyle(spanStyleFor(inline.run, para, fontSizeDp, scale)) { append(inline.run.text) }
                is WordInline.Math -> {
                    val id = "wordmath" + index
                    val render = runCatching {
                        latexRenderOf(inline.latex, sizePx, density, Color.Black.toArgb(), display = false)
                    }.getOrNull()
                    if (render == null) {
                        append(inline.latex)
                    } else {
                        appendInlineContent(id, inline.latex)
                        contents[id] = InlineTextContent(
                            Placeholder(
                                width = (render.widthSp / safeScale).sp,
                                height = (render.heightSp / safeScale).sp,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                            ),
                        ) {
                            Image(bitmap = render.bitmap, contentDescription = "公式", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
    return WordMathSpans(text, contents)
}

/** 独立成行的公式：居中画一张位图；渲染不出来时退回等宽原文（不丢信息） */
@Composable
private fun WordMathBlock(latex: String, fontSizeDp: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val render = remember(latex, fontSizeDp) {
        runCatching {
            latexRenderOf(latex, fontSizeDp * density.density, density.density, Color.Black.toArgb(), display = true)
        }.getOrNull()
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (render == null) {
            Text(latex, fontSize = fontSizeDp.sp, color = Color.Black)
        } else {
            Image(
                bitmap = render.bitmap,
                contentDescription = "公式",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(render.widthSp.dp, render.heightSp.dp),
            )
        }
    }
}

/** run → span：与段落默认值相同的部分不重复写；上下标抬降基线并缩字号（Word 的 65%） */
private fun spanStyleFor(run: WordRun, para: WordPara, baseSizeDp: Float, scale: Float): SpanStyle {
    val baseBold = para.bold || para.heading > 0
    val decorations = ArrayList<TextDecoration>(2)
    if (run.underline) decorations += TextDecoration.Underline
    if (run.strike) decorations += TextDecoration.LineThrough
    val runSizeDp = if (run.sizePt > 0f) run.sizePt * DP_PER_PT * scale else baseSizeDp
    val sizeDp = if (run.vertAlign == WordVertAlign.NONE) runSizeDp else runSizeDp * SCRIPT_SCALE
    val color = run.color?.let { Color(0xFF000000.toInt() or it) } ?: Color.Unspecified
    val shift = when (run.vertAlign) {
        WordVertAlign.SUPER -> BaselineShift.Superscript
        WordVertAlign.SUB -> BaselineShift.Subscript
        WordVertAlign.NONE -> null
    }
    return SpanStyle(
        fontWeight = when {
            run.bold == baseBold -> null
            run.bold -> FontWeight.Bold
            else -> FontWeight.Normal
        },
        fontStyle = when {
            run.italic == para.italic -> null
            run.italic -> FontStyle.Italic
            else -> FontStyle.Normal
        },
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        fontSize = if (abs(sizeDp - baseSizeDp) > 0.05f) sizeDp.sp else TextUnit.Unspecified,
        color = color,
        baselineShift = shift,
    )
}

/** Word 上下标的字号比例（Word 默认 65%） */
private const val SCRIPT_SCALE = 0.65f

private fun WordAlign.toTextAlign(): TextAlign = when (this) {
    WordAlign.LEFT -> TextAlign.Start
    WordAlign.CENTER -> TextAlign.Center
    WordAlign.RIGHT -> TextAlign.End
    WordAlign.JUSTIFY -> TextAlign.Justify
}

// ------------------------------------------------------------------ Word 表格

/** 版面上的一格：起点（第几列 / 行）、占几列 / 几行 */
private class WordTableSlot(
    val cell: WordCell,
    val row: Int,
    val col: Int,
    val span: Int,
    var rowSpan: Int = 1,
)

/**
 * 把行 / 格摊成版面：横向 w:gridSpan 直接占列，纵向 w:vMerge 的续格把**起始格**的行数撑大
 * （续格自己不占一个可画的格子）。列数对不上时按实际格子数兜底。
 */
private fun wordTableSlots(table: WordBlock.Table, columns: Int): List<WordTableSlot> {
    val slots = ArrayList<WordTableSlot>()
    table.rows.forEachIndexed { rowIndex, row ->
        var column = 0
        row.cells.forEach { cell ->
            // 一行的格子比列数还多（老式 .doc 的合并标志没解出来 / 生成器写坏）：多出来的直接略过，
            // 不让列下标越过 columns（越界会在 measure 里直接崩）
            if (column >= columns) return@forEach
            val span = cell.span.coerceIn(1, (columns - column).coerceAtLeast(1))
            if (cell.vContinue) {
                val owner = slots.lastOrNull {
                    it.col <= column && column < it.col + it.span && it.row + it.rowSpan == rowIndex
                }
                if (owner != null) owner.rowSpan += 1
            } else {
                slots += WordTableSlot(cell, rowIndex, column, span)
            }
            column += span
        }
    }
    return slots
}

/**
 * 表格几何：measure 阶段写、draw 阶段读（纯数据，不进快照；与 ChatTranscript 的 rowTops 同一套做法）。
 *
 * 格子**只量一次内容**（Compose 的 Measurable 一次测量只能量一次）：行高、纵向对齐位置、底纹与
 * 边框都在这里算好，父层在 draw 阶段按几何画 —— 不能再「先量内容、再按行高重量一次」。
 */
private class WordTableGeometry {
    var columnWidths: IntArray = IntArray(0)
    var rowTops: FloatArray = FloatArray(0)
    var rowCount: Int = 0
    var slots: List<WordTableSlot> = emptyList()
    var borders: List<Map<WordBorderEdge, WordBorderSpec?>> = emptyList()
    var scale: Float = 1f

    fun draw(scope: DrawScope, palette: com.adsh.app.ui.theme.DshPalette) {
        if (slots.isEmpty() || columnWidths.isEmpty() || rowCount == 0) return
        val colLefts = FloatArray(columnWidths.size + 1)
        for (col in columnWidths.indices) colLefts[col + 1] = colLefts[col] + columnWidths[col]
        fun left(slot: WordTableSlot): Float = colLefts[slot.col]
        fun right(slot: WordTableSlot): Float = colLefts[minOf(slot.col + slot.span, columnWidths.size)]
        fun top(slot: WordTableSlot): Float = rowTops[slot.row]
        fun bottom(slot: WordTableSlot): Float = rowTops[minOf(slot.row + slot.rowSpan, rowCount)]
        // 底纹先铺，边框后画（线压在底上）
        slots.forEach { slot ->
            val fill = slot.cell.shading ?: return@forEach
            scope.drawRect(
                color = Color(0xFF000000.toInt() or fill),
                topLeft = Offset(left(slot), top(slot)),
                size = Size(right(slot) - left(slot), bottom(slot) - top(slot)),
            )
        }
        slots.forEachIndexed { index, slot ->
            borders.getOrElse(index) { emptyMap() }.forEach { (edge, spec) ->
                if (spec == null) return@forEach
                val width = with(scope) { (spec.widthEighthPt / 8f * DP_PER_PT * scale).dp.toPx() }.coerceAtLeast(1f)
                // wordColor 存的是 0xRRGGBB：补上不透明的 alpha（Color(0) 是全透明 —— 黑线会消失）
                val color = spec.color?.let { Color(0xFF000000.toInt() or it) } ?: palette.borderL2
                val half = width / 2f
                when (edge) {
                    WordBorderEdge.TOP -> scope.drawLine(
                        color,
                        Offset(left(slot), top(slot) + half),
                        Offset(right(slot), top(slot) + half),
                        width,
                    )
                    WordBorderEdge.BOTTOM -> scope.drawLine(
                        color,
                        Offset(left(slot), bottom(slot) - half),
                        Offset(right(slot), bottom(slot) - half),
                        width,
                    )
                    WordBorderEdge.LEFT -> scope.drawLine(
                        color,
                        Offset(left(slot) + half, top(slot)),
                        Offset(left(slot) + half, bottom(slot)),
                        width,
                    )
                    WordBorderEdge.RIGHT -> scope.drawLine(
                        color,
                        Offset(right(slot) - half, top(slot)),
                        Offset(right(slot) - half, bottom(slot)),
                        width,
                    )
                    WordBorderEdge.INSIDE_H, WordBorderEdge.INSIDE_V -> Unit
                }
            }
        }
    }
}

/**
 * Word 文档里的表格：**按版面的列宽比例**铺（w:tblGrid 的 twip），合并单元格占对应份数。
 *
 * 缩放与正文同一个系数（页面宽度）；宽度比版心还宽的表按比例压到版心内（不再横向滚动），
 * 比版心窄的表保持原宽并按 w:jc 左 / 中 / 右对齐。
 * 行高按内容量：纵向合并的起始格不够高时把差额摊给被它盖住的那几行 —— 合并格必须是**一整个**
 * 高格子，不能是「一格 + 下面几条空的」。
 */
@Composable
internal fun WordTableBlock(table: WordBlock.Table, scale: Float) {
    val palette = LocalDshPalette.current
    val columns = maxOf(table.grid.size, table.rows.maxOfOrNull { row -> row.cells.sumOf { it.span } } ?: 0)
        .coerceAtMost(MAX_TABLE_COLUMNS)
    if (columns == 0 || table.rows.isEmpty()) return
    val slots = remember(table, columns) { wordTableSlots(table, columns) }
    val weights = remember(table.grid, columns) {
        if (table.grid.size == columns) table.grid.map { it.toFloat().coerceIn(1f, 40000f) } else List(columns) { 1f }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = (DshSpacing.Xl.value * scale).dp)) {
        val density = LocalDensity.current
        // 表宽优先用 w:tblW 的真值，没有它才按网格列宽求和
        val naturalTw = (table.widthTw?.toFloat() ?: weights.sum()).coerceIn(1f, 40000f)
        val naturalDp = naturalTw * DP_PER_TWIP * scale
        val availableDp = maxWidth.value
        val targetDp = minOf(naturalDp, availableDp)
        val fit = if (naturalDp > 0f) (targetDp / naturalDp).coerceIn(0.05f, 1f) else 1f
        val columnWidths = weights.map {
            with(density) { (it * DP_PER_TWIP * scale * fit).dp.roundToPx() }.coerceIn(1, MAX_TABLE_COLUMN_PX)
        }
        val totalWidthPx = columnWidths.sum()
        if (totalWidthPx <= 0) return@BoxWithConstraints
        val alignOffsetDp = when (table.align) {
            WordAlign.CENTER -> ((availableDp - targetDp) / 2f).coerceAtLeast(0f)
            WordAlign.RIGHT -> (availableDp - targetDp).coerceAtLeast(0f)
            else -> 0f
        }
        val indentDp = (table.indentTw * DP_PER_TWIP * scale).coerceIn(0f, 200f)
        val marginPx = with(density) { (table.cellMarginTw * DP_PER_TWIP * scale).dp.toPx() }.coerceIn(0f, 40f)
        val verticalMarginPx = with(density) { (2f * scale).dp.toPx() }
        val minRowHeight = with(density) { (WORD_MIN_ROW_HEIGHT.value * scale).dp.toPx() }
        val rowCount = table.rows.size
        val rowMinHeights = FloatArray(rowCount) { index ->
            table.rows[index].heightDp?.let { with(density) { (it * scale).dp.toPx() } }
                ?.coerceIn(0f, MAX_TABLE_ROW_HEIGHT_PX) ?: 0f
        }
        val geometry = remember(table) { WordTableGeometry() }
        Box(Modifier.fillMaxWidth().padding(start = (alignOffsetDp + indentDp).dp)) {
            Layout(
                content = { slots.forEach { slot -> WordCellContent(slot.cell, scale) } },
                modifier = Modifier
                    .width(with(density) { totalWidthPx.toDp() })
                    .drawBehind { geometry.draw(this, palette) },
            ) { measurables, constraints ->
                val tableWidth = constraints.maxWidth
                val widths = columnWidths.toIntArray()
                if (widths.isNotEmpty()) {
                    widths[widths.lastIndex] = (widths.last() + tableWidth - widths.sum()).coerceAtLeast(1)
                }
                val colLefts = FloatArray(widths.size + 1)
                for (col in widths.indices) colLefts[col + 1] = colLefts[col] + widths[col]
                fun slotWidth(slot: WordTableSlot): Int =
                    (colLefts[minOf(slot.col + slot.span, widths.size)] - colLefts[slot.col])
                        .roundToInt().coerceAtLeast(1)
                fun innerWidth(slot: WordTableSlot): Int =
                    (slotWidth(slot) - 2 * marginPx).roundToInt().coerceAtLeast(1)
                val placeables = measurables.mapIndexed { index, measurable ->
                    val width = innerWidth(slots[index])
                    measurable.measure(Constraints(minWidth = width, maxWidth = width))
                }
                val rowHeights = FloatArray(rowCount) { rowMinHeights[it] }
                placeables.forEachIndexed { index, placeable ->
                    val slot = slots[index]
                    if (slot.rowSpan == 1) {
                        rowHeights[slot.row] = maxOf(rowHeights[slot.row], placeable.height + 2 * verticalMarginPx)
                    }
                }
                placeables.forEachIndexed { index, placeable ->
                    val slot = slots[index]
                    if (slot.rowSpan > 1) {
                        val endRow = minOf(slot.row + slot.rowSpan, rowCount)
                        var current = 0f
                        for (row in slot.row until endRow) current += rowHeights[row]
                        val needed = placeable.height + 2 * verticalMarginPx - current
                        if (needed > 0f) {
                            val share = needed / (endRow - slot.row)
                            for (row in slot.row until endRow) rowHeights[row] += share
                        }
                    }
                }
                for (row in rowHeights.indices) rowHeights[row] = maxOf(rowHeights[row], minRowHeight)
                val rowTops = FloatArray(rowCount + 1)
                for (row in 0 until rowCount) rowTops[row + 1] = rowTops[row] + rowHeights[row]
                val height = rowTops[rowCount].roundToInt()
                geometry.columnWidths = widths
                geometry.rowTops = rowTops
                geometry.rowCount = rowCount
                geometry.slots = slots
                geometry.borders = slots.map { wordCellBorders(table, it, columns, rowCount) }
                geometry.scale = scale
                layout(tableWidth, height) {
                    placeables.forEachIndexed { index, placeable ->
                        val slot = slots[index]
                        val endRow = minOf(slot.row + slot.rowSpan, rowCount)
                        val cellHeight = rowTops[endRow] - rowTops[slot.row]
                        val vertical = when (slot.cell.vAlign) {
                            WordVAlign.TOP -> verticalMarginPx
                            WordVAlign.CENTER -> (cellHeight - placeable.height) / 2f
                            WordVAlign.BOTTOM -> cellHeight - placeable.height - verticalMarginPx
                        }
                        placeable.place(
                            x = (colLefts[slot.col] + marginPx).roundToInt(),
                            y = (rowTops[slot.row] + vertical).roundToInt(),
                        )
                    }
                }
            }
        }
    }
}

/** 一格的内容：多段文字 + 可能一张图（底纹 / 边框由父层按几何画，见 [WordTableGeometry]） */
@Composable
private fun WordCellContent(cell: WordCell, scale: Float) {
    Column(Modifier.fillMaxWidth()) {
        cell.paragraphs.forEach { WordParaRow(it, scale) }
        cell.image?.let { WordImage(it, scale) }
    }
}

/**
 * 一格要画的边：自己的 w:tcBorders 优先，否则用表格的边（外圈用外框线、内圈用内侧线）。
 * 只画左 / 上 + 最后一列的右 + 最后一行的下：相邻两格共用的那条线只由其中一格画，避免线宽叠成两倍。
 */
private fun wordCellBorders(
    table: WordBlock.Table,
    slot: WordTableSlot,
    columns: Int,
    rowCount: Int,
): Map<WordBorderEdge, WordBorderSpec?> {
    val cell = slot.cell
    val lastCol = slot.col + slot.span - 1
    val lastRow = slot.row + slot.rowSpan - 1
    fun pick(edge: WordBorderEdge, outer: WordBorderEdge, inner: WordBorderEdge, isOuter: Boolean): WordBorderSpec? {
        val own = cell.borders
        if (own != null && own.containsKey(edge)) return own[edge]
        return table.borders[if (isOuter) outer else inner]
    }
    val out = LinkedHashMap<WordBorderEdge, WordBorderSpec?>()
    out[WordBorderEdge.LEFT] = pick(WordBorderEdge.LEFT, WordBorderEdge.LEFT, WordBorderEdge.INSIDE_V, slot.col == 0)
    out[WordBorderEdge.TOP] = pick(WordBorderEdge.TOP, WordBorderEdge.TOP, WordBorderEdge.INSIDE_H, slot.row == 0)
    if (lastCol >= columns - 1) out[WordBorderEdge.RIGHT] = table.borders[WordBorderEdge.RIGHT]
    if (lastRow >= rowCount - 1) out[WordBorderEdge.BOTTOM] = table.borders[WordBorderEdge.BOTTOM]
    return out
}

/** 表格列数 / 列宽 / 行高的上限（预览口径：防生成器写坏的巨大值把 Compose 的约束撑爆） */
private const val MAX_TABLE_COLUMNS = 60
private const val MAX_TABLE_COLUMN_PX = 4096
private const val MAX_TABLE_ROW_HEIGHT_PX = 4096f

/**
 * 段落 / 格子里的图：版心给了显示尺寸就按它（已按页面缩放），没给就按可用宽度撑满；
 * 解码走 [rememberImageBytes]（IO 线程 + 统一采样），解不出来就不画。
 */
@Composable
private fun WordImage(image: WordBlock.Image, scale: Float) {
    val decoded by rememberImageBytes(image.data, INLINE_IMAGE_PX)
    val picture = decoded ?: return
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(vertical = (DshSpacing.Xs.value * scale).dp),
        contentAlignment = Alignment.Center,
    ) {
        val naturalWidth = (if (image.width > 0f) (image.width * scale).dp else maxWidth).coerceAtLeast(1.dp)
        val naturalHeight = if (image.height > 0f) (image.height * scale).dp
        else naturalWidth * picture.height / picture.width
        val adjusted = minOf(1f, maxWidth / naturalWidth)
        Image(
            bitmap = picture,
            contentDescription = null,
            modifier = Modifier.size(naturalWidth * adjusted, naturalHeight * adjusted),
            contentScale = ContentScale.Fit,
        )
    }
}

// ------------------------------------------------------------------ Excel / 幻灯片（与 Word 无关，保持原样）

/** .xlsx 预览（网格 + 工作表标签） */
@Composable
internal fun WorkbookPreview(path: String) {
    val (book, failure) = previewOf(path) { readWorkbook(File(path)) }
    when {
        failure != null -> CenteredNote("cannot read this workbook: " + failure)
        book == null -> CenteredNote("正在读取…")
        book.isEmpty() -> CenteredNote("This workbook has no sheets to preview.")
        else -> SheetGrid(book)
    }
}

/** .csv / .tsv 预览（dsh 把这两者也交给表格视图：首行是数据、值保持字面量） */
@Composable
internal fun DelimitedPreview(path: String, delimiter: Char) {
    val name = File(path).name
    val (tables, failure) = previewOf(path) {
        listOf(SheetTable(name, parseDelimited(readTextPreview(path).first, delimiter)))
    }
    when {
        failure != null -> CenteredNote("cannot read this table: " + failure)
        tables == null -> CenteredNote("正在读取…")
        else -> SheetGrid(tables)
    }
}

/** 网格：一行一个 LazyColumn 项、整表共用一个横向滚动；多张工作表时上面挂一排标签 */
@Composable
private fun SheetGrid(tables: List<SheetTable>) {
    val palette = LocalDshPalette.current
    var index by remember(tables) { mutableStateOf(0) }
    val table = tables.getOrNull(index) ?: return
    val horizontal = rememberScrollState()
    val sheetTabs = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        if (tables.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .dshHorizontalScrollbar(sheetTabs)
                    .horizontalScroll(sheetTabs)
                    .padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Lg),
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
            ) {
                tables.forEachIndexed { at, sheet ->
                    Text(
                        text = sheet.name,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (at == index) palette.selector else Color.Transparent)
                            .dshClickable(interactionSource = dshInteraction()) { index = at }
                            .padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Md),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = if (at == index) palette.labelPrimary else palette.labelTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        SelectionContainer {
            val listState = rememberLazyListState()
            // 各行共用一个横向滚动状态：横条只画在外层可视区，挂在每一行上会画出 N 条
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .dshVerticalScrollbar(listState)
                    .dshHorizontalScrollbar(horizontal),
                state = listState,
            ) {
                items(table.rows.size) { rowIndex ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(horizontal),
                    ) {
                        val row = table.rows[rowIndex]
                        for (column in 0 until maxOf(row.size, 1)) {
                            SheetCell(row.getOrNull(column).orEmpty())
                        }
                    }
                }
            }
        }
    }
}

/** 一个单元格：定宽 + 细边（宽度就是横向滚动的跨度）、最多三行 */
@Composable
private fun SheetCell(text: String) {
    val palette = LocalDshPalette.current
    Text(
        text = text,
        modifier = Modifier
            .width(SHEET_CELL_WIDTH)
            .heightIn(min = 34.dp)
            .border(DshSpacing.Hairline, palette.borderL2)
            .padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Lg),
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = palette.labelPrimary,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/** .pptx 预览：一页一张卡（页序见 [readSlideDeck]） */
@Composable
internal fun SlidesPreview(path: String) {
    val palette = LocalDshPalette.current
    val (deck, failure) = previewOf(path) { readSlideDeck(File(path)) }
    when {
        failure != null -> CenteredNote("cannot read this presentation: " + failure)
        deck == null -> CenteredNote("正在读取…")
        else -> SelectionContainer {
            val listState = rememberLazyListState()
            LazyColumn(
                modifier = Modifier.fillMaxSize().dshVerticalScrollbar(listState),
                state = listState,
                contentPadding = PaddingValues(DshSpacing.Xxxl),
                verticalArrangement = Arrangement.spacedBy(DshSpacing.Xxxl),
            ) {
                items(deck.size) { index ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.bgLayer1)
                            .border(DshSpacing.Hairline, palette.borderL2, RoundedCornerShape(16.dp))
                            .padding(DshSpacing.Card),
                    ) {
                        Text(
                            text = "第 " + (index + 1) + " 页",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = palette.labelTertiary,
                        )
                        val lines = deck[index]
                        if (lines.isEmpty()) {
                            Text(
                                text = "（这一页没有文字）",
                                modifier = Modifier.padding(top = DshSpacing.Xl),
                                fontSize = 14.sp,
                                lineHeight = 24.sp,
                                color = palette.labelTertiary,
                            )
                        } else {
                            lines.forEach { line ->
                                Text(
                                    text = line,
                                    modifier = Modifier.padding(top = DshSpacing.Xl),
                                    fontSize = 14.sp,
                                    lineHeight = 24.sp,
                                    color = palette.labelPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
