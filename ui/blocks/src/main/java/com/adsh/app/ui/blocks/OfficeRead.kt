package com.adsh.app.ui.blocks

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/*
 * Office / 表格的**就地解析**：.docx / .xlsx / .pptx 都是 ZIP + XML，用系统自带的 [ZipFile] + DOM 直接读
 * （dsh 由 Host 的 LibreOffice 转 PDF、浏览器里用 FortuneSheet，Android 上两样都没有）。这一层只吐数据、
 * 不碰 Compose；上限都是**预览**口径：单个部件 6MB、表格 500 行 × 60 列、段落 / 幻灯片 400 块，超了截断。
 *
 * Word 那一条线（段落格式 / 表格）在 WordRead.kt —— 这一份只留表格 / 幻灯片与共用的小件。
 */

/** 单个 OOXML 部件最多读多少字节（超了给一句说明） */
internal const val MAX_PART_BYTES = 6 * 1024 * 1024

/** 表格预览的行 / 列上限 */
private const val MAX_SHEET_ROWS = 500
private const val MAX_SHEET_COLS = 60

/** 一张工作表（csv / tsv 只有一张，名字取文件名） */
internal data class SheetTable(val name: String, val rows: List<List<String>>)

/**
 * 按**内容**认 OOXML 三件套：认的是 ZIP 里那几个固定部件名，与后缀无关 —— 工具有把 .docx 存成 .doc
 * 的习惯，光看后缀会走错渲染器。不是 ZIP（老式二进制 .doc/.xls/.ppt 是 OLE 复合文档）就返回 null，
 * 由调用方给说明。一次预览只读一次；读不动就当认不出来。
 */
internal fun ooxmlKindOf(file: File): String? = runCatching {
    ZipFile(file).use { zip ->
        when {
            zip.getEntry("word/document.xml") != null -> "word"
            zip.getEntry("xl/workbook.xml") != null -> "excel"
            zip.getEntry("ppt/presentation.xml") != null -> "slides"
            else -> null
        }
    }
}.getOrNull()

/** .xlsx：工作簿里的每张工作表（顺序按 xl/workbook.xml，部件路径走关系表） */
internal fun readWorkbook(file: File): List<SheetTable> = ZipFile(file).use { zip ->
    val workbook = parseXml(
        zip.partText("xl/workbook.xml") ?: throw IllegalStateException("不是 Excel 工作簿：缺 xl/workbook.xml"),
    )
    val rels = zip.partText("xl/_rels/workbook.xml.rels")
        ?.let { parseXml(it).documentElement.descendants("Relationship") }
        ?.associate { it.getAttribute("Id") to it.getAttribute("Target") }
        .orEmpty()
    val shared = zip.partText("xl/sharedStrings.xml")
        ?.let { parseXml(it).documentElement.descendants("si").map { si -> textRuns(si, "t") } }
        .orEmpty()
    workbook.documentElement.descendants("sheet").mapNotNull { sheet ->
        val part = rels[sheet.getAttribute("r:id")]?.let { partPath("xl", it) } ?: return@mapNotNull null
        val xml = zip.partText(part) ?: return@mapNotNull null
        SheetTable(sheet.getAttribute("name").ifEmpty { part }, parseSheet(xml, shared))
    }
}

/** .pptx：一张幻灯片一段文本（页序按 ppt/presentation.xml 的 sldIdLst）。文字与页序交给旧的幻灯片预览。 */
internal fun readSlideDeck(file: File): List<List<String>> = ZipFile(file).use { zip ->
    val presentation = zip.partText("ppt/presentation.xml")?.let { parseXml(it) }
    val rels = zip.partText("ppt/_rels/presentation.xml.rels")
        ?.let { parseXml(it).documentElement.descendants("Relationship") }
        ?.associate { it.getAttribute("Id") to it.getAttribute("Target") }
        .orEmpty()
    val ordered = presentation?.documentElement?.descendants("sldId")
        ?.mapNotNull { rels[it.getAttribute("r:id")]?.let { target -> partPath("ppt", target) } }
        .orEmpty()
    // 顺序取不到时按文件名排（slide2 与 slide10 必须按数字比，不能按字符串比）
    val parts = (if (ordered.isNotEmpty()) ordered else zip.slideParts()).take(MAX_BLOCKS)
    parts.map { part -> zip.partText(part)?.let { slideParagraphs(it) }.orEmpty() }
}

/** 段落 / 幻灯片 / 表格的上限（预览口径） */
internal const val MAX_BLOCKS = 400

private fun ZipFile.slideParts(): List<String> = entries()
    .asSequence()
    .map { it.name }
    .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
    .sortedBy { it.substringAfterLast("slide").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
    .toList()

/** ZIP 里的一个部件读成文本（不存在返回 null；超上限直接抛，由调用方给说明） */
internal fun ZipFile.partText(name: String): String? {
    val entry = getEntry(name) ?: return null
    if (entry.size > MAX_PART_BYTES) throw IllegalStateException("文档太大，无法预览")
    return getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
}

/** XML 文本 → DOM。**不开命名空间**：OOXML 的前缀是固定写法，按 `w:p` 这种全名取最省事。 */
internal fun parseXml(text: String): Document =
    DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        .newDocumentBuilder()
        .parse(InputSource(StringReader(text)))

/** 关系表里的 Target → ZIP 内的部件路径（相对 / 绝对两种写法都要认） */
internal fun partPath(base: String, target: String): String {
    val clean = target.removePrefix("/")
    return if (clean.startsWith(base + "/")) clean else base + "/" + clean
}

/** 某个元素下的直接子元素 */
internal fun Element.children(name: String): List<Element> {
    val out = ArrayList<Element>()
    for (index in 0 until childNodes.length) {
        val node = childNodes.item(index)
        if (node is Element && node.tagName == name) out += node
    }
    return out
}

/** 某个元素下的**全部后代**（文档顺序） */
internal fun Element.descendants(name: String): List<Element> {
    val nodes = getElementsByTagName(name)
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}

/**
 * 一段元素里的文字（w:t 按顺序拼起来，tab / br 各按字面处理）：xlsx 的共享字符串与 pptx 的段落
 * 复用这一份；Word 段落走 WordRead.kt 里的 run 收集（那边要留格式）。
 */
internal fun textRuns(element: Element, tag: String): String {
    val out = StringBuilder()
    fun walk(node: Node) {
        for (index in 0 until node.childNodes.length) {
            val child = node.childNodes.item(index)
            if (child !is Element) continue
            when (child.tagName) {
                tag -> out.append(child.textContent)
                "w:tab", "a:tab" -> out.append('\t')
                "w:br", "w:cr", "a:br" -> out.append('\n')
                else -> walk(child)
            }
        }
    }
    walk(element)
    return out.toString()
}

/** 一张工作表：行 → 单元格（按 c 的 r 属性补空格子，值取已保存的结果） */
private fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
    val rows = ArrayList<List<String>>()
    for (row in parseXml(xml).documentElement.descendants("row")) {
        if (rows.size >= MAX_SHEET_ROWS) break
        val cells = ArrayList<String>()
        for (cell in row.children("c")) {
            val column = columnOf(cell.getAttribute("r"))
            if (column != null) {
                while (cells.size < column && cells.size < MAX_SHEET_COLS) cells += ""
            }
            if (cells.size >= MAX_SHEET_COLS) break
            cells += cellText(cell, shared)
        }
        if (cells.isNotEmpty()) rows += cells
    }
    return rows
}

/** "B12" → 1（列下标，从 0 起）；没有 r 属性时返回 null（调用方按顺序放） */
private fun columnOf(reference: String): Int? {
    var column = 0
    var seen = false
    for (ch in reference) {
        if (ch !in 'A'..'Z') break
        column = column * 26 + (ch - 'A' + 1)
        seen = true
    }
    return if (seen) column - 1 else null
}

/** 单元格的值：共享字符串下标 / 内联字符串 / 其余取 v 的字面量（公式结果就是它存下来的那个） */
private fun cellText(cell: Element, shared: List<String>): String = when (cell.getAttribute("t")) {
    "s" -> cell.children("v").firstOrNull()?.textContent?.trim()?.toIntOrNull()
        ?.let { shared.getOrNull(it) }.orEmpty()
    "inlineStr" -> cell.descendants("t").joinToString("") { it.textContent }
    else -> cell.children("v").firstOrNull()?.textContent.orEmpty()
}.trim()

/** 一张幻灯片里的段落文字（页内换行按 a:p 分，空段丢掉） */
private fun slideParagraphs(xml: String): List<String> =
    parseXml(xml).documentElement.descendants("a:p")
        .map { paragraph -> paragraph.descendants("a:t").joinToString("") { it.textContent }.trim() }
        .filter { it.isNotEmpty() }

/**
 * csv / tsv：dsh 的表格解析口径 —— 引号包裹的字段里可以有分隔符与换行、两个引号是一个引号，
 * **首行仍是数据**，值保持字面量（前导零、日期、像公式的文本都不动）。
 */
internal fun parseDelimited(text: String, delimiter: Char): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val field = StringBuilder()
    var quoted = false
    var index = 0
    while (index < text.length) {
        val ch = text[index]
        when {
            ch == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                field.append('"')
                index++
            }
            ch == '"' -> quoted = !quoted
            !quoted && ch == delimiter -> {
                row += field.toString()
                field.setLength(0)
            }
            !quoted && (ch == '\n' || ch == '\r') -> {
                if (ch == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                row += field.toString()
                field.setLength(0)
                rows += row
                row = ArrayList()
                if (rows.size >= MAX_SHEET_ROWS) return rows
            }
            else -> field.append(ch)
        }
        index++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) {
        row += field.toString()
        rows += row
    }
    return rows
}
