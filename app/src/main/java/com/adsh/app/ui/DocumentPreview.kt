package com.adsh.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件预览（对齐 dsh 的 sidebar-documentpreview）：顶部是像浏览器一样的「文件窗口」标签条，
 * 打开一个文件加一个标签、点 ✕ 关闭；正文按类型渲染 —— 图片解码后可直接看（支持双指缩放/拖动），
 * 文本按行分块惰性渲染（不会把几百 KB 塞进一个 Text），二进制类型明确告知不可预览（不再吐乱码）。
 */

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "ico", "avif")

private val TEXT_EXT = setOf(
    "txt", "text", "log", "md", "markdown", "mdx", "json", "json5", "jsonc", "yaml", "yml", "toml", "ini", "cfg",
    "conf", "properties", "env", "xml", "html", "htm", "css", "scss", "sass", "less", "js", "mjs", "cjs", "jsx",
    "ts", "tsx", "mts", "cts", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cc", "cpp", "hpp", "cs",
    "sh", "bash", "zsh", "fish", "gradle", "pro", "sql", "csv", "tsv", "vue", "svelte", "php", "lua", "swift",
    "dart", "pl", "pm", "r", "scala", "clj", "ex", "exs", "erl", "hs", "diff", "patch", "mk", "makefile",
    "dockerfile", "gitignore", "editorconfig", "svg",
)

/** 预览时最多读多少字节（超出的部分提示截断） */
private const val MAX_TEXT_BYTES = 512 * 1024

/** 文本按多少行一块渲染（惰性列表，避免一次布局几十万字符） */
private const val LINES_PER_CHUNK = 120

/** 预览正文（标签条由 FileWorkspacePanel 负责，这里只管渲染当前文件） */
@Composable
fun FilePreviewBody(path: String) {
    when (kindOf(path)) {
        PreviewKind.IMAGE -> ImagePreview(path)
        PreviewKind.MARKDOWN -> MarkdownPreview(path)
        PreviewKind.CODE -> TextPreview(path)
        PreviewKind.PDF -> PdfPreview(path)
        PreviewKind.OTHER -> UnsupportedPreview(path)
    }
}

private enum class PreviewKind { IMAGE, MARKDOWN, CODE, PDF, OTHER }

private fun kindOf(path: String): PreviewKind {
    val ext = File(path).name.substringAfterLast('.', "").lowercase()
    return when {
        ext in IMAGE_EXT -> PreviewKind.IMAGE
        ext == "pdf" -> PreviewKind.PDF
        ext in setOf("md", "markdown", "mdx") -> PreviewKind.MARKDOWN
        ext in TEXT_EXT -> PreviewKind.CODE
        else -> PreviewKind.OTHER
    }
}

/** 扩展名 → 高亮语言标识 */
private fun languageOf(path: String): String? = when (File(path).name.substringAfterLast('.', "").lowercase()) {
    "js", "mjs", "cjs", "jsx", "node" -> "js"
    "ts", "tsx", "mts", "cts" -> "ts"
    "kt", "kts" -> "kt"
    "java" -> "java"
    "py" -> "python"
    "go" -> "go"
    "rs" -> "rust"
    "c", "h", "cc", "cpp", "hpp", "cs" -> "c"
    "sh", "bash", "zsh", "fish" -> "sh"
    "json", "json5", "jsonc" -> "json"
    "yaml", "yml" -> "yaml"
    "xml", "html", "htm", "svg" -> "xml"
    "css", "scss", "less" -> "css"
    "sql" -> "sql"
    "gradle", "properties" -> "sh"
    else -> null
}

/** 图片预览：后台下采样解码（不会 OOM），双指缩放 + 拖动 */
@Composable
private fun ImagePreview(path: String) {
    val palette = LocalDshPalette.current
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var failure by remember(path) { mutableStateOf<String?>(null) }
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(path) {
        bitmap = null
        failure = null
        scale = 1f
        offset = Offset.Zero
        val result = withContext(Dispatchers.IO) { runCatching { decodeDownsampled(path) } }
        bitmap = result.getOrNull()
        failure = result.exceptionOrNull()?.message
    }

    val image = bitmap
    when {
        failure != null -> CenteredNote("无法解码这张图片：" + failure)
        image == null -> CenteredNote("正在解码…")
        else -> Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(path) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale <= 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            Text(
                text = image.width.toString() + " × " + image.height + "  ·  " + formatSize(File(path).length()),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.menu.copy(alpha = 0.85f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                fontSize = 11.sp,
                color = palette.labelTertiary,
            )
        }
    }
}

/** 文本预览：按行分块惰性渲染，二进制内容直接判为不可预览 */
@Composable
private fun TextPreview(path: String) {
    val palette = LocalDshPalette.current
    var chunks by remember(path) { mutableStateOf<List<String>?>(null) }
    var note by remember(path) { mutableStateOf<String?>(null) }
    var failure by remember(path) { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        chunks = null
        note = null
        failure = null
        val result = withContext(Dispatchers.IO) { runCatching { readTextPreview(path) } }
        result.fold(
            onSuccess = { (text, extra) ->
                note = extra
                chunks = text.split("\n").chunked(LINES_PER_CHUNK).map { it.joinToString("\n") }
            },
            onFailure = { failure = it.message ?: it::class.java.simpleName },
        )
    }

    val failureText = failure
    if (failureText != null) {
        CenteredNote("读取失败：" + failureText)
        return
    }
    val loaded = chunks
    val language = languageOf(path)
    if (loaded == null) {
        CenteredNote("正在读取…")
        return
    }
    Column(Modifier.fillMaxSize()) {
        note?.let { line ->
            Text(
                text = line,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = 11.sp,
                color = palette.labelTertiary,
            )
            HorizontalDivider(thickness = 0.5.dp, color = palette.borderL3)
        }
        // dsh 的 .dhJKeW_body：等宽、white-space: pre、横向滚动（不折行），这样代码对齐才正常
        SelectionContainer {
            Column(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    items(loaded.size) { index ->
                        Text(
                            text = highlightCode(loaded[index], language, palette),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 21.sp,
                            softWrap = false,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

/**
 * Markdown 预览：按块渲染（对齐 dsh 用 MarkdownText 渲染 .md 的做法）——
 * 标题 / 无序有序列表 / 引用 / 代码围栏 / 分隔线 + 行内 粗体、斜体、行内代码、链接。
 */
@Composable
private fun MarkdownPreview(path: String) {
    val palette = LocalDshPalette.current
    var text by remember(path) { mutableStateOf<String?>(null) }
    var failure by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        text = null
        failure = null
        val result = withContext(Dispatchers.IO) { runCatching { readTextPreview(path).first } }
        text = result.getOrNull()
        failure = result.exceptionOrNull()?.message
    }
    val failureText = failure
    if (failureText != null) {
        UnsupportedPreview(path, note = failureText)
        return
    }
    val body = text
    if (body == null) {
        CenteredNote("正在读取…")
        return
    }
    val blocks = remember(body) { parseMarkdown(body) }
    SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            items(blocks.size) { index ->
                when (val block = blocks[index]) {
                    is MdBlock.Heading -> Text(
                        text = inline(block.text, palette),
                        fontSize = when (block.level) {
                            1 -> 21.sp
                            2 -> 18.sp
                            else -> 15.sp
                        },
                        lineHeight = when (block.level) {
                            1 -> 28.sp
                            2 -> 25.sp
                            else -> 22.sp
                        },
                        fontWeight = FontWeight.SemiBold,
                        color = palette.labelPrimary,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 10.dp, bottom = 4.dp),
                    )
                    is MdBlock.Paragraph -> Text(
                        text = inline(block.text, palette),
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = palette.labelSecondary,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                    is MdBlock.Bullet -> Row(Modifier.padding(vertical = 2.dp)) {
                        Text(block.marker, fontSize = 14.sp, lineHeight = 22.sp, color = palette.labelTertiary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = inline(block.text, palette),
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = palette.labelSecondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    is MdBlock.Quote -> Row(Modifier.padding(vertical = 3.dp)) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(20.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(palette.borderL4),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = inline(block.text, palette),
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = palette.labelTertiary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    is MdBlock.Code -> Text(
                        text = block.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = palette.labelPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.selector)
                            .padding(10.dp),
                    )
                    is MdBlock.Table -> MdTableView(block, palette)
                    MdBlock.Rule -> HorizontalDivider(
                        thickness = 0.5.dp,
                        color = palette.borderL3,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * 表格（对齐 dsh 的 MarkdownText 表格）：
 *
 *  - 整张表左右可滚（dsh 的 ._tableScroll_: max-width:100%; overflow-x:auto），
 *    表格自身宽度是 max-content（格子按内容宽自适应，短内容就窄）；
 *  - 单元格宽度 = 该列内容「不换行时的宽度」，夹在 min-width:100px 与
 *    max-width:min(30vw,320px) 之间 —— 手机上 30vw 只有 ~118dp，比原来的定宽 140dp 还窄，
 *    用户明确要求「一行里能有更多文字」，所以这里取 dsh 的绝对上限 320dp；
 *  - **不截断**：dsh 的 th/td 没有任何行数上限，之前 maxLines=4 + Ellipsis 会把长单元格
 *    的后半截直接吃掉（用户看到的就是「内容多的直接就省略掉了」）；
 *  - 单元格 padding 10/16，首列不留左内边距、末列不留右内边距（dsh 的 :first-child/:last-child），
 *    行下一条 .5px 的 border-l2，表头一条 border-l3。
 */
@Composable
internal fun MdTableView(block: MdBlock.Table, palette: com.adsh.app.ui.theme.DshPalette) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val cellStyle = remember { androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 20.sp) }
    val minCellPx = with(density) { MD_TABLE_CELL_MIN_DP.dp.roundToPx() }
    val maxCellPx = with(density) { MD_TABLE_CELL_MAX_DP.dp.roundToPx() }
    val columnCount = remember(block) {
        maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    }
    // 每列宽度 = 该列所有单元格里最宽的那个（测的是不换行时的自然宽度）
    val widths = remember(block, columnCount, minCellPx, maxCellPx, palette) {
        List(columnCount) { column ->
            var widest = minCellPx
            var row = 0
            while (row <= block.rows.size && widest < maxCellPx) {
                val cell = if (row == 0) block.header.getOrNull(column) else block.rows[row - 1].getOrNull(column)
                if (!cell.isNullOrEmpty()) {
                    // 长文本必然撑满上限（13sp 下 160 个字符最窄也有 ~480dp），直接取上限，
                    // 免得流式期间每个 token 都把上万个字符重新量一遍
                    val measured = if (cell.length > 160) {
                        maxCellPx
                    } else {
                        measurer.measure(
                            text = inline(cell, palette, codeFontSize = 14.sp),
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
            .padding(vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        MdTableRow(block.header, header = true, widths = widths, palette = palette)
        block.rows.forEach { row -> MdTableRow(row, header = false, widths = widths, palette = palette) }
    }
}
/** 供聊天消息复用的 Markdown 渲染（dsh 的 MarkdownText 语义：对话正文也是 Markdown） */
@Composable
fun MarkdownBody(text: String, modifier: Modifier = Modifier) {
    val palette = LocalDshPalette.current
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        blocks.forEachIndexed { index, block -> MdBlockView(block, palette, index == 0) }
    }
}

@Composable
internal fun MdBlockView(block: MdBlock, palette: com.adsh.app.ui.theme.DshPalette, first: Boolean) {
    when (block) {
        is MdBlock.Heading -> Text(
            text = inline(block.text, palette),
            fontSize = if (block.level == 1) 20.sp else if (block.level == 2) 17.sp else 15.sp,
            lineHeight = if (block.level == 1) 27.sp else 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.labelPrimary,
            modifier = Modifier.padding(top = if (first) 0.dp else 10.dp, bottom = 4.dp),
        )
        is MdBlock.Paragraph -> Text(
            text = inline(block.text, palette),
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = palette.labelSecondary,
            modifier = Modifier.padding(vertical = 3.dp),
        )
        is MdBlock.Bullet -> Row(Modifier.padding(vertical = 2.dp)) {
            Text(block.marker, fontSize = 14.sp, lineHeight = 22.sp, color = palette.labelTertiary)
            Spacer(Modifier.width(8.dp))
            Text(
                text = inline(block.text, palette),
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = palette.labelSecondary,
                modifier = Modifier.weight(1f),
            )
        }
        is MdBlock.Quote -> Row(Modifier.padding(vertical = 3.dp)) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.borderL4),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = inline(block.text, palette),
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = palette.labelTertiary,
                modifier = Modifier.weight(1f),
            )
        }
        is MdBlock.Code -> Text(
            text = block.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = palette.labelPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.selector)
                .padding(10.dp),
        )
        is MdBlock.Table -> MdTableView(block, palette)
        MdBlock.Rule -> HorizontalDivider(
            thickness = 0.5.dp,
            color = palette.borderL3,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

/** dsh 的 th/td：min-width:100px、max-width:min(30vw,320px)（这里取绝对上限，见 MdTableView） */
private const val MD_TABLE_CELL_MIN_DP = 100f
private const val MD_TABLE_CELL_MAX_DP = 320f

/**
 * 表格一行：按算好的列宽铺单元格，单元格顶部对齐、行高由最高的那个决定，
 * 行下面一条 .5px 的分隔线（dsh 的 td{border-bottom}）。
 */
@Composable
private fun MdTableRow(
    cells: List<String>,
    header: Boolean,
    widths: List<androidx.compose.ui.unit.Dp>,
    palette: com.adsh.app.ui.theme.DshPalette,
) {
    Row {
        widths.forEachIndexed { index, width ->
            val cell = cells.getOrNull(index)
            Text(
                text = inline(cell.orEmpty(), palette, codeFontSize = 14.sp),
                modifier = Modifier
                    .width(width)
                    .padding(
                        start = if (index == 0) 0.dp else 16.dp,
                        end = if (index == widths.lastIndex) 0.dp else 16.dp,
                        top = 10.dp,
                        bottom = 10.dp,
                    ),
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                color = if (header) palette.labelPrimary else palette.labelSecondary,
            )
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = if (header) palette.borderL3 else palette.borderL2)
}

/**
 * Markdown 块。
 *
 * @Immutable 是流式渲染的关键：块在解析时是新建对象，只有 content 相等的块被判为「没变」，
 * Compose 才会跳过它（不再重建 AnnotatedString、不再重新排版整段文字）。
 * 未标注时 List 字段会让整个块被判为 unstable，于是每个 token 都会把整篇 Markdown 重排一遍。
 */
@androidx.compose.runtime.Immutable
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/** 极简 Markdown 分块：够渲染 README/说明文档，不追求完整规范 */
internal fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val lines = source.split("\n")
    var index = 0
    val paragraph = StringBuilder()
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.toString())
            paragraph.setLength(0)
        }
    }
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                flushParagraph()
                val code = StringBuilder()
                index++
                val fence = if (trimmed.startsWith("~~~")) "~~~" else "```"
                while (index < lines.size && !lines[index].trim().startsWith(fence)) {
                    code.appendLine(lines[index])
                    index++
                }
                blocks += MdBlock.Code(code.toString().trimEnd())
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
            trimmed.startsWith("|") && trimmed.endsWith("|") -> {
                flushParagraph()
                val collected = ArrayList<List<String>>()
                while (index < lines.size && lines[index].trim().startsWith("|")) {
                    val cells = lines[index].trim().trim('|').split("|").map { it.trim() }
                    val separator = cells.all { cell -> cell.isNotEmpty() && cell.all { it == '-' || it == ':' } }
                    if (!separator) collected += cells
                    index++
                }
                if (collected.isNotEmpty()) {
                    blocks += MdBlock.Table(collected.first(), collected.drop(1))
                }
                index--
            }
            trimmed.startsWith(">") -> {
                flushParagraph()
                val body = trimmed.removePrefix(">").trim()
                if (body.startsWith("```") || body.startsWith("~~~")) {
                    val fence = if (body.startsWith("~~~")) "~~~" else "```"
                    val code = StringBuilder()
                    index++
                    while (index < lines.size && !lines[index].trim().removePrefix(">").trim().startsWith(fence)) {
                        code.appendLine(lines[index].trim().removePrefix(">").trimStart())
                        index++
                    }
                    blocks += MdBlock.Code(code.toString().trimEnd())
                } else {
                    blocks += MdBlock.Quote(body)
                }
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                flushParagraph()
                blocks += MdBlock.Bullet("•", trimmed.drop(2).trim())
            }
            Regex("^\\d+\\.\\s").containsMatchIn(trimmed) -> {
                flushParagraph()
                val marker = trimmed.substringBefore(' ').ifEmpty { "1." }
                blocks += MdBlock.Bullet(marker, trimmed.substringAfter(' ').trim())
            }
            trimmed.isEmpty() -> flushParagraph()
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }
        index++
    }
    flushParagraph()
    return blocks
}

/**
 * 行内样式：**粗体**、*斜体*、`代码`、[文字](链接)。
 * codeFontSize = 行内代码的字号（比正文大一号，表格里用小一号，保持「比周围大」的观感）。
 */
private fun inline(
    text: String,
    palette: com.adsh.app.ui.theme.DshPalette,
    codeFontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val rest = text.substring(index)
        when {
            rest.startsWith("**") -> {
                val end = rest.indexOf("**", 2)
                if (end > 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = palette.labelPrimary)) {
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
                    // 行内代码不再铺灰底：改成等宽 + 加粗 + 加大（需求）
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = codeFontSize,
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
                val end = rest.indexOf(')')
                if (close > 0 && end > close) {
                    withStyle(SpanStyle(color = palette.messages)) { append(rest.substring(1, close)) }
                    index += end + 1
                } else {
                    append(rest.first()); index++
                }
            }
            rest.startsWith("*") -> {
                val end = rest.indexOf('*', 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                        append(rest.substring(1, end))
                    }
                    index += end + 1
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


/** PDF：用系统自带的 android.graphics.pdf.PdfRenderer 逐页渲染成位图，底部翻页 */
@Composable
private fun PdfPreview(path: String) {
    val palette = LocalDshPalette.current
    var total by remember(path) { mutableStateOf(0) }
    var index by remember(path) { mutableStateOf(0) }
    var page by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var failure by remember(path) { mutableStateOf<String?>(null) }

    LaunchedEffect(path, index) {
        page = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val descriptor = android.os.ParcelFileDescriptor.open(
                    File(path),
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                )
                descriptor.use { fd ->
                    android.graphics.pdf.PdfRenderer(fd).use { renderer ->
                        val count = renderer.pageCount
                        val target = index.coerceIn(0, (count - 1).coerceAtLeast(0))
                        val bitmap = renderer.openPage(target).use { pdfPage ->
                            val width = (pdfPage.width * 2).coerceAtLeast(1)
                            val height = (pdfPage.height * 2).coerceAtLeast(1)
                            val created = android.graphics.Bitmap.createBitmap(
                                width,
                                height,
                                android.graphics.Bitmap.Config.ARGB_8888,
                            )
                            created.eraseColor(android.graphics.Color.WHITE)
                            pdfPage.render(
                                created,
                                null,
                                null,
                                android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                            created.asImageBitmap()
                        }
                        Pair(count, bitmap)
                    }
                }
            }
        }
        result.fold(
            onSuccess = { pair ->
                total = pair.first
                page = pair.second
                failure = null
            },
            onFailure = { failure = it.message ?: it::class.java.simpleName },
        )
    }

    val failureText = failure
    if (failureText != null) {
        UnsupportedPreview(path, note = "PDF 打开失败：" + failureText)
        return
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val image = page
            if (image == null) {
                Text("正在渲染…", fontSize = 13.sp, color = palette.labelSecondary)
            } else {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = palette.borderL3)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PdfNavButton("上一页", enabled = index > 0) { index-- }
            Text(
                text = (index + 1).toString() + " / " + total.coerceAtLeast(1),
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = palette.labelSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            PdfNavButton("下一页", enabled = index < total - 1) { index++ }
        }
    }
}

@Composable
private fun PdfNavButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) palette.selector else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 13.sp, color = if (enabled) palette.labelPrimary else palette.labelCaption)
    }
}

/** 语法高亮：单遍正则分词（注释 / 字符串 / 数字 / 关键字 / 类型名 / 其余） */
private val CODE_KEYWORDS = setOf(
    "const", "let", "var", "function", "fun", "return", "if", "else", "for", "while", "do", "switch", "case",
    "break", "continue", "class", "interface", "object", "struct", "enum", "new", "import", "export", "from",
    "package", "async", "await", "try", "catch", "finally", "throw", "throws", "typeof", "instanceof", "null",
    "undefined", "None", "true", "false", "this", "self", "super", "extends", "implements", "of", "in", "is",
    "val", "suspend", "override", "private", "public", "protected", "internal", "static", "final", "void", "int",
    "long", "float", "double", "boolean", "char", "def", "elif", "lambda", "yield", "with", "as", "pass",
    "raise", "impl", "trait", "pub", "use", "mod", "fn", "mut", "where", "select", "insert", "update", "delete",
    "create", "table", "and", "or", "not",
)

private val CODE_TOKEN = Regex(
    "//[^\\n]*|#[^\\n]*|/\\*[\\s\\S]*?\\*/|'(?:\\\\.|[^'\\\\])*'|\"(?:\\\\.|[^\"\\\\])*\"" +
        "|\\b\\d+(?:\\.\\d+)?\\b|\\b[A-Za-z_][A-Za-z0-9_]*\\b",
)

/** 逐行高亮：字符数保持不变，便于按行惰性渲染 */
internal fun highlightCode(
    line: String,
    language: String?,
    palette: com.adsh.app.ui.theme.DshPalette,
): AnnotatedString {
    if (language == null || line.isEmpty()) return AnnotatedString(line, SpanStyle(color = palette.labelPrimary))
    val builder = StringBuilder()
    val spans = ArrayList<Triple<Int, Int, Color>>()
    var cursor = 0
    CODE_TOKEN.findAll(line).forEach { match ->
        val text = match.value
        val color = when {
            text.startsWith("//") || text.startsWith("#") || text.startsWith("/*") -> palette.labelCaption
            text.startsWith("'") || text.startsWith("\"") -> palette.codeString
            text[0].isDigit() -> palette.codeNumber
            text in CODE_KEYWORDS -> palette.codeKeyword
            text.first().isUpperCase() -> palette.codeType
            else -> palette.labelPrimary
        }
        builder.append(line, cursor, match.range.first)
        val start = builder.length
        builder.append(text)
        spans += Triple(start, builder.length, color)
        cursor = match.range.last + 1
    }
    builder.append(line, cursor, line.length)
    return buildAnnotatedString {
        append(builder.toString())
        spans.forEach { (spanStart, spanEnd, color) -> addStyle(SpanStyle(color = color), spanStart, spanEnd) }
    }
}

@Composable
private fun UnsupportedPreview(path: String, note: String? = null) {
    val palette = LocalDshPalette.current
    val file = File(path)
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = null,
            tint = palette.labelTertiary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(file.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.labelPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            text = note ?: ("这种类型不支持预览 · " + formatSize(file.length())),
            fontSize = 12.sp,
            color = palette.labelTertiary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = path,
            fontSize = 11.sp,
            color = palette.labelCaption,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CenteredNote(text: String) {
    val palette = LocalDshPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = palette.labelSecondary)
    }
}

/** 下采样解码：长边不超过 2048，避免大图 OOM */
private fun decodeDownsampled(path: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IllegalStateException("不是可识别的图片格式")
    }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 2048 || bounds.outHeight / (sample * 2) >= 2048) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    return try {
        BitmapFactory.decodeFile(path, options)?.asImageBitmap()
    } catch (oom: OutOfMemoryError) {
        throw IllegalStateException("图片太大，内存不足")
    }
}

/** 读文本：限长 + 二进制探测（含 NUL 就当作二进制，不再吐乱码） */
private fun readTextPreview(path: String): Pair<String, String?> {
    val file = File(path)
    if (!file.isFile) throw IllegalStateException("文件不存在")
    val length = file.length()
    val limit = minOf(length, MAX_TEXT_BYTES.toLong()).toInt()
    val bytes = ByteArray(limit)
    var read = 0
    file.inputStream().use { input ->
        while (read < limit) {
            val step = input.read(bytes, read, limit - read)
            if (step <= 0) break
            read += step
        }
    }
    val probe = minOf(read, 8192)
    for (index in 0 until probe) {
        if (bytes[index].toInt() == 0) throw IllegalStateException("二进制文件，无法按文本预览")
    }
    val text = String(bytes, 0, read, Charsets.UTF_8)
    val note = if (length > read) {
        "仅显示前 " + formatSize(read.toLong()) + "，文件共 " + formatSize(length)
    } else {
        formatSize(length) + "  ·  " + path
    }
    return text to note
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
}
