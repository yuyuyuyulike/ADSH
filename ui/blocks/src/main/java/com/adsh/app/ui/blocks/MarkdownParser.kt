package com.adsh.app.ui.blocks

/**
 * Markdown 分块解析（够渲染对话与说明文档；不追求完整 CommonMark）。
 *
 * 覆盖的语法（对齐 dsh）：
 *  - 列表**支持嵌套**（按标记缩进分层）与 GFM 任务列表（`- [x]`）；
 *  - 围栏代码块带上语言信息串（```bash → 代码块横幅显示 bash）；
 *  - 引用块内容按块解析（引用里可以放代码块、列表）；
 *  - 单独成段的 `![alt](src)` 解析成图片块。
 *
 * 解析结果的块模型与渲染在 Markdown.kt。
 */
internal fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.split("\n")
    return parseBlocks(lines, 0, lines.size)
}

/** 顶层块 + 它在源文本里的起始行（增量解析冻结边界的口径，见 [MarkdownStreamParser]） */
internal data class ParsedMdBlock(val block: MdBlock, val startLine: Int)

/** 与 [parseMarkdown] 相同，但带回每个顶层块的起始行 */
internal fun parseMarkdownPositioned(lines: List<String>): List<ParsedMdBlock> {
    val starts = ArrayList<Int>()
    val blocks = parseBlocks(lines, 0, lines.size, starts)
    return blocks.mapIndexed { index, block -> ParsedMdBlock(block, starts[index]) }
}

/**
 * @param starts 非空时按**顶层块顺序**记录每个块的起始行；递归解析（引用 / 列表子块）传 null
 */
private fun parseBlocks(
    lines: List<String>,
    from: Int,
    to: Int,
    starts: MutableList<Int>? = null,
): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val paragraph = StringBuilder()
    var paragraphStart = -1
    var index = from

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.toString())
            starts?.add(paragraphStart)
            paragraph.setLength(0)
        }
    }

    while (index < to) {
        val line = lines[index]
        val trimmed = line.trim()
        when {
            // 块级公式：$$…$$（可跨行）或 \[…\]
            trimmed.startsWith("$$") || trimmed.startsWith("\\[") -> {
                flushParagraph()
                val open = if (trimmed.startsWith("$$")) "$$" else "\\["
                val close = if (open == "$$") "$$" else "\\]"
                val blockStart = index
                val body = StringBuilder(trimmed.removePrefix(open))
                if (trimmed.length >= open.length + close.length && trimmed.endsWith(close)) {
                    starts?.add(blockStart)
                    blocks += MdBlock.Math(trimmed.removePrefix(open).removeSuffix(close).trim())
                } else {
                    index++
                    var guard = 0
                    while (index < to && guard++ < 400) {
                        val current = lines[index]
                        if (current.trim().endsWith(close)) {
                            body.append('\n').append(current.trim().removeSuffix(close))
                            break
                        }
                        body.append('\n').append(current)
                        index++
                    }
                    starts?.add(blockStart)
                    blocks += MdBlock.Math(body.toString().trim())
                }
            }
            // 围栏代码块（``` 或 ~~~，可带语言信息串）
            fenceOf(trimmed) != null -> {
                flushParagraph()
                val blockStart = index
                val fence = fenceOf(trimmed)!!
                val lang = trimmed.removePrefix(fence).trim().substringBefore(' ').ifEmpty { null }
                val code = StringBuilder()
                index++
                while (index < to && !lines[index].trim().startsWith(fence)) {
                    code.appendLine(lines[index])
                    index++
                }
                starts?.add(blockStart)
                blocks += MdBlock.Code(code.toString().trimEnd(), lang)
            }
            trimmed.startsWith("#") -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
                starts?.add(index)
                blocks += MdBlock.Heading(level, trimmed.drop(level).trim())
            }
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                flushParagraph()
                starts?.add(index)
                blocks += MdBlock.Rule
            }
            // 表格：本行以 | 开头，且下一行是分隔行
            trimmed.startsWith("|") && index + 1 < to && isTableSeparator(lines[index + 1]) -> {
                flushParagraph()
                val blockStart = index
                val header = splitRow(trimmed)
                val rows = ArrayList<List<String>>()
                index += 2
                while (index < to && lines[index].trim().startsWith("|")) {
                    rows += splitRow(lines[index].trim())
                    index++
                }
                starts?.add(blockStart)
                blocks += MdBlock.Table(header, rows)
                index--
            }
            trimmed.startsWith(">") -> {
                flushParagraph()
                val blockStart = index
                val inner = ArrayList<String>()
                while (index < to) {
                    val current = lines[index]
                    val currentTrimmed = current.trim()
                    if (currentTrimmed.startsWith(">")) {
                        inner += currentTrimmed.removePrefix(">").removePrefix(" ")
                        index++
                        continue
                    }
                    // 引用里的空行：后面还接着引用就保留（用来分段），否则结束
                    if (currentTrimmed.isEmpty() &&
                        (lines.getOrNull(index + 1)?.trim()?.startsWith(">") == true)
                    ) {
                        inner += ""
                        index++
                        continue
                    }
                    break
                }
                starts?.add(blockStart)
                blocks += MdBlock.Quote(parseBlocks(inner, 0, inner.size))
                continue
            }
            isListItem(trimmed) -> {
                flushParagraph()
                val blockStart = index
                val parsed = parseListAt(lines, index, to)
                starts?.add(blockStart)
                blocks += parsed.first
                index = parsed.second
                continue
            }
            trimmed.isEmpty() -> flushParagraph()
            else -> {
                // 单独成段的图片 → 图片块
                val image = parseStandaloneImage(trimmed)
                if (image != null && paragraph.isEmpty()) {
                    starts?.add(index)
                    blocks += image
                } else {
                    if (paragraph.isEmpty()) paragraphStart = index
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(trimmed)
                }
            }
        }
        index++
    }
    flushParagraph()
    return blocks
}

/**
 * 一个列表块：从 [start] 起收集同级列表项，每项自己的缩进更深的内容递归成子块。
 *
 * @return 列表块与「列表之后的下标」
 */
private fun parseListAt(lines: List<String>, start: Int, to: Int): Pair<MdBlock.Bullets, Int> {
    val firstTrimmed = lines[start].trim()
    val startNumber = orderedStart(firstTrimmed)
    val ordered = startNumber != null
    val baseIndent = indentOf(lines[start])
    val items = ArrayList<MdBlock.Item>()
    var index = start

    while (index < to) {
        val line = lines[index]
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            // 空行：只有后面还是**同一层**的列表项时，这个列表才继续
            var probe = index + 1
            while (probe < to && lines[probe].isBlank()) probe++
            if (probe >= to) {
                index = probe
                break
            }
            val probeTrimmed = lines[probe].trim()
            if (indentOf(lines[probe]) == baseIndent && isListItem(probeTrimmed)) {
                index = probe
                continue
            }
            break
        }
        if (indentOf(line) != baseIndent || !isListItem(trimmed)) break

        // 这一项的正文行（含续行与子列表；子列表的缩进会原样留给递归解析）
        val body = ArrayList<String>()
        body += stripMarker(trimmed)
        index++
        while (index < to) {
            val follow = lines[index]
            val followTrimmed = follow.trim()
            if (followTrimmed.isEmpty()) {
                val next = lines.getOrNull(index + 1) ?: break
                if (next.isBlank()) break
                if (indentOf(next) > baseIndent) {
                    body += ""
                    index++
                    continue
                }
                break
            }
            val followIndent = indentOf(follow)
            if (followIndent > baseIndent) {
                // 去掉本层的缩进（标记宽 2），子块的相对缩进保持不变
                body += if (followIndent >= baseIndent + 2) follow.substring(baseIndent + 2) else followTrimmed
                index++
                continue
            }
            break
        }
        val children = parseBlocks(body, 0, body.size)
        val head = children.firstOrNull()
        val raw = if (head is MdBlock.Paragraph) head.text else ""
        val checked = taskState(raw)
        val text = if (checked == null) raw else raw.drop(4).trim()
        val rest = if (head is MdBlock.Paragraph) children.drop(1) else children
        items += MdBlock.Item(checked, text, rest)
    }
    return MdBlock.Bullets(ordered, startNumber ?: 1, items) to index
}

private fun fenceOf(trimmed: String): String? = when {
    trimmed.startsWith("```") -> "```"
    trimmed.startsWith("~~~") -> "~~~"
    else -> null
}

private fun indentOf(line: String): Int = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }

private fun isBullet(trimmed: String): Boolean =
    trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")

private val ORDERED = Regex("^(\\d{1,9})[.)]\\s")

private fun orderedStart(trimmed: String): Int? =
    ORDERED.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()

private fun isListItem(trimmed: String): Boolean = isBullet(trimmed) || orderedStart(trimmed) != null

/** 去掉行首标记（`- ` / `1. `） */
private fun stripMarker(trimmed: String): String =
    if (isBullet(trimmed)) trimmed.drop(2).trim() else ORDERED.replaceFirst(trimmed, "").trim()

/** GFM 任务列表：`[x]` / `[ ]`；普通项返回 null */
private fun taskState(text: String): Boolean? = when {
    text.startsWith("[x] ") || text.startsWith("[X] ") -> true
    text.startsWith("[ ] ") -> false
    else -> null
}

private fun isTableSeparator(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.startsWith("|")) return false
    val cells = splitRow(trimmed)
    return cells.isNotEmpty() && cells.all { cell -> cell.isNotEmpty() && cell.all { it == '-' || it == ':' } }
}

private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split("|").map { it.trim() }

private val STANDALONE_IMAGE = Regex("^!\\[([^]]*)]\\(([^)]+)\\)$")

private fun parseStandaloneImage(trimmed: String): MdBlock.Image? =
    STANDALONE_IMAGE.find(trimmed)?.let { MdBlock.Image(it.groupValues[1], it.groupValues[2]) }

/**
 * 流式 Markdown 的**增量块解析**（dsh 的 IncrementalMarkdownParser 语义）。
 *
 * 这一份解析器是行序产物：追加文本只可能改写**末尾**的顶层块（段落 / 列表 / 围栏都可能还在长），
 * 前面的块一旦被后面的块接上就再也不会变。于是每一拍只重解析尾部，把「倒数第
 * [UNSTABLE_TAIL_BLOCKS] 块之前」的块冻结下来直接复用 —— 每拍的成本由尾块长度决定，
 * 而不是整篇回答的长度（全量重解析在长回答上是 O(n²)）。
 *
 * 契约：
 *  - 只在 [update] 的新文本以旧文本为前缀时增量；否则（重连重置 / 换了一条消息）整体重来；
 *  - 返回的块与全量解析一致（解析器不支持跨块定义，冻结不会改变语义）；单测按「每个前缀 == 全量解析」钉住；
 *  - 解析器**有状态**：一条流式消息一个实例（见 MarkdownBody），不要跨消息复用。
 */
internal class MarkdownStreamParser {

    private var text = ""
    private var frozen: List<MdBlock> = emptyList()

    /** 冻结区之后的第一行在完整文本里的字符偏移；下一拍从它开始重解析 */
    private var frozenOffset = 0
    private var blocks: List<MdBlock> = emptyList()

    fun update(next: String): List<MdBlock> {
        if (next == text) return blocks
        if (!next.startsWith(text)) reset()
        text = next
        val tail = next.substring(frozenOffset)
        val lines = tail.split("\n")
        val parsed = parseMarkdownPositioned(lines)
        val freezeCount = (parsed.size - UNSTABLE_TAIL_BLOCKS).coerceAtLeast(0)
        if (freezeCount > 0) {
            frozen = frozen + parsed.subList(0, freezeCount).map { it.block }
            frozenOffset += offsetOfLine(lines, parsed[freezeCount].startLine)
        }
        blocks = frozen + parsed.drop(freezeCount).map { it.block }
        return blocks
    }

    private fun reset() {
        text = ""
        frozen = emptyList()
        frozenOffset = 0
        blocks = emptyList()
    }

    private fun offsetOfLine(lines: List<String>, line: Int): Int {
        var offset = 0
        for (index in 0 until line) offset += lines[index].length + 1
        return offset
    }

    private companion object {
        /** 末尾保留为不稳定的顶层块数（dsh 的 UNSTABLE_TAIL_BLOCKS = 2） */
        const val UNSTABLE_TAIL_BLOCKS = 2
    }
}
