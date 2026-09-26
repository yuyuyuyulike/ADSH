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
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * 点开一张图时的原图预览（dsh-client-ui-attachment 的 lightbox："Document-level original-image
 * preview opened by clicking a thumbnail"）：整屏黑底 + 双指缩放/拖动，右上角关闭。
 *
 * 对话里**任何**一张缩略图（用户附件、read_image 的结果）都打开这一个对话框，
 * 状态放在 [ImagePreviewState] 里 —— 图片散在消息/工具行的深处，逐层传 lambda
 * 只会把每一层都污染一遍。
 */
object ImagePreviewState {
    private val _path = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val path: kotlinx.coroutines.flow.StateFlow<String?> = _path.asStateFlow()

    fun open(path: String) {
        if (path.isNotBlank()) _path.value = path
    }

    fun close() {
        _path.value = null
    }
}

@Composable
fun ImagePreviewDialog(path: String, onDismiss: () -> Unit) {
    val palette = LocalDshPalette.current
    val view = LocalView.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Compose 的 Dialog 自带一层系统 dim，这里自己画背景，所以把它压掉（与 DshModal 同一做法）
        androidx.compose.runtime.SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setDimAmount(0f)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(palette.bgLayer1)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Box(Modifier.fillMaxSize().padding(top = 48.dp, bottom = 40.dp)) {
                ImagePreview(path)
            }
            Text(
                text = File(path).name,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 14.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.menu.copy(alpha = 0.85f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                fontSize = 12.sp,
                color = palette.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            androidx.compose.material3.IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 8.dp),
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Outlined.Close,
                    contentDescription = "关闭",
                    tint = palette.labelSecondary,
                )
            }
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
    SelectionContainer {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            item { MarkdownBody(body, modifier = Modifier.fillMaxWidth()) }
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

/**
 * 认识的着色语言：只有这几门走下面那套（C 系关键字 + 注释 + 字符串 + 数字）规则。
 * dsh 用 Shiki 按语言着色；只有一套规则时，硬套到 `diff` / `text` / `yaml` 上会把内容标成
 * 乱七八糟的颜色，所以不认识的语言一律按纯文本渲染（与 Shiki 的行为一致：不着色）。
 */
private val HIGHLIGHTED_LANGUAGES = setOf(
    "js", "javascript", "jsx", "mjs", "cjs",
    "ts", "typescript", "tsx",
    "json", "jsonc",
    "java", "kt", "kotlin", "cs", "csharp", "cpp", "c", "h", "go", "rs", "rust",
    "swift", "php", "scala", "dart",
)

/** 逐行高亮：字符数保持不变，便于按行惰性渲染 */
internal fun highlightCode(
    line: String,
    language: String?,
    palette: com.adsh.app.ui.theme.DshPalette,
): AnnotatedString {
    if (language == null || line.isEmpty() || language.lowercase() !in HIGHLIGHTED_LANGUAGES) {
        return AnnotatedString(line, SpanStyle(color = palette.labelPrimary))
    }
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
