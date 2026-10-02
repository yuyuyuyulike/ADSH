package com.adsh.app.ui

/**
 * Markdown 分块解析（够渲染对话与说明文档；不追求完整 CommonMark）。
 *
 * 与旧实现的差别（也正是「看齐 dsh」缺的部分）：
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

private fun parseBlocks(lines: List<String>, from: Int, to: Int): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val paragraph = StringBuilder()
    var index = from

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.toString())
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
                val body = StringBuilder(trimmed.removePrefix(open))
                if (trimmed.length >= open.length + close.length && trimmed.endsWith(close)) {
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
                    blocks += MdBlock.Math(body.toString().trim())
                }
            }
            // 围栏代码块（``` 或 ~~~，可带语言信息串）
            fenceOf(trimmed) != null -> {
                flushParagraph()
                val fence = fenceOf(trimmed)!!
                val lang = trimmed.removePrefix(fence).trim().substringBefore(' ').ifEmpty { null }
                val code = StringBuilder()
                index++
                while (index < to && !lines[index].trim().startsWith(fence)) {
                    code.appendLine(lines[index])
                    index++
                }
                blocks += MdBlock.Code(code.toString().trimEnd(), lang)
            }
            trimmed.startsWith("#") -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
                blocks += MdBlock.Heading(level, trimmed.drop(level).trim())
            }
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                flushParagraph()
                blocks += MdBlock.Rule
            }
            // 表格：本行以 | 开头，且下一行是分隔行
            trimmed.startsWith("|") && index + 1 < to && isTableSeparator(lines[index + 1]) -> {
                flushParagraph()
                val header = splitRow(trimmed)
                val rows = ArrayList<List<String>>()
                index += 2
                while (index < to && lines[index].trim().startsWith("|")) {
                    rows += splitRow(lines[index].trim())
                    index++
                }
                blocks += MdBlock.Table(header, rows)
                index--
            }
            trimmed.startsWith(">") -> {
                flushParagraph()
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
                blocks += MdBlock.Quote(parseBlocks(inner, 0, inner.size))
                continue
            }
            isListItem(trimmed) -> {
                flushParagraph()
                val parsed = parseListAt(lines, index, to)
                blocks += parsed.first
                index = parsed.second
                continue
            }
            trimmed.isEmpty() -> flushParagraph()
            else -> {
                // 单独成段的图片 → 图片块
                val image = parseStandaloneImage(trimmed)
                if (image != null && paragraph.isEmpty()) {
                    blocks += image
                } else {
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
