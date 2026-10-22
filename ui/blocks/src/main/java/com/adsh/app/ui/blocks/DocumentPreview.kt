package com.adsh.app.ui.blocks

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.kit.dshInteraction
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件预览正文（对齐 dsh 的 sidebar-documentpreview）：标签条在 [FilePreviewBody] 外面
 * （FileWorkspacePanel 里是 chip 条 + 文档头），这里只负责当前文件的渲染。
 *
 * 视觉口径出自 dsh 的 TextPreview.module.css / code/CodeBody.module.css /
 * image/ImageBody.module.css / pdf/PdfBody.module.css / zoom/ZoomControls.module.css：
 *  - 加载态：居中 28dp 环形 spinner + 14sp「文档渲染中...」；
 *  - 空态：36dp 文件类型图标（60% 透明）+ 13sp 说明 + 32dp「重试」胶囊；
 *  - 代码 / 纯文本：11sp / 19sp 等宽 + 行号 + 复制（横幅 10/18/8/22）；
 *  - 图片：12dp 内边距 + 12dp 圆角 + 底部常显缩放胶囊（手机无 hover，dsh 的 @media (hover:none) 也是常显）；
 *  - PDF：连续页 + 12dp 页间隙 + 文档底色 + 未渲染页 3:4 占位块。
 */

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "ico", "avif")

private val TEXT_EXT = setOf(
    "txt", "text", "log", "md", "markdown", "mdx", "json", "json5", "jsonc", "yaml", "yml", "toml", "ini", "cfg",
    "conf", "properties", "env", "xml", "html", "htm", "css", "scss", "sass", "less", "js", "mjs", "cjs", "jsx",
    "ts", "tsx", "mts", "cts", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cc", "cpp", "hpp", "cs",
    "sh", "bash", "zsh", "fish", "gradle", "pro", "sql", "vue", "svelte", "php", "lua", "swift",
    "dart", "pl", "pm", "r", "scala", "clj", "ex", "exs", "erl", "hs", "diff", "patch", "mk", "makefile",
    "dockerfile", "gitignore", "editorconfig", "svg",
)

// 文档类格式（dsh 的 Office / 表格两条线，解析全在 OfficeRead.kt）：段落流 / 网格 / 幻灯片三种渲染，
// 分隔符表格复用网格那一条。渲染器由**内容**决定（见 OfficeRead.ooxmlKindOf）：这些后缀只负责进那个
// 入口，后缀不对的 OOXML（工具另存成 .doc 那种）照样能看对。
private val OFFICE_EXT = setOf("docx", "xlsx", "pptx", "doc", "xls", "ppt")
private val DELIMITED_EXT = setOf("csv", "tsv")

/** 老式二进制 Office 格式：dsh 靠 Host 的 LibreOffice 转 PDF，Android 上没有那个引擎 */
private val LEGACY_OFFICE_EXT = setOf("doc", "xls", "ppt")

/** 预览时最多读多少字节（超出的部分提示截断） */
private const val MAX_TEXT_BYTES = 512 * 1024


/** dsh 的 documentpreview.loading：整页加载态那一行 */
internal const val PREVIEW_LOADING_TEXT = "文档渲染中..."

/** dsh 的 documentpreview.unviewable：已知二进制容器 / 读不出正文时那一句 */
internal const val PREVIEW_UNVIEWABLE_TEXT = "This file type cannot be previewed"

/** 预览正文（tags 条与文档头由 FileWorkspacePanel 负责，这里只管渲染当前文件） */
@Composable
fun FilePreviewBody(path: String, wrap: Boolean = true, reloadKey: Int = 0) {
    val ext = File(path).name.substringAfterLast('.', "").lowercase()
    when (kindOf(ext)) {
        PreviewKind.IMAGE -> ImagePreview(path, reloadKey)
        PreviewKind.MARKDOWN -> MarkdownPreview(path, reloadKey)
        PreviewKind.CODE -> TextPreview(path, wrap, reloadKey)
        PreviewKind.PDF -> PdfPreview(path, reloadKey)
        PreviewKind.OFFICE -> OfficePreview(path)
        PreviewKind.DELIMITED -> DelimitedPreview(path, if (ext == "tsv") '\t' else ',')
        PreviewKind.OTHER -> UnsupportedPreview(path, note = unsupportedNoteOf(ext))
    }
}

private enum class PreviewKind { IMAGE, MARKDOWN, CODE, PDF, OFFICE, DELIMITED, OTHER }

private fun kindOf(ext: String): PreviewKind = when {
    ext in IMAGE_EXT -> PreviewKind.IMAGE
    ext == "pdf" -> PreviewKind.PDF
    ext in setOf("md", "markdown", "mdx") -> PreviewKind.MARKDOWN
    ext in OFFICE_EXT -> PreviewKind.OFFICE
    // dsh 的表格视图是 .csv/.tsv 的**默认**渲染器（纯文本只是可选项之一）
    ext in DELIMITED_EXT -> PreviewKind.DELIMITED
    ext in TEXT_EXT -> PreviewKind.CODE
    else -> PreviewKind.OTHER
}

/** 文档头要不要画换行开关（dsh：只有纯文本 / Markdown / 代码声明 wrap:true） */
internal fun previewSupportsWrap(path: String): Boolean = when (
    kindOf(File(path).name.substringAfterLast('.', "").lowercase())
) {
    PreviewKind.CODE, PreviewKind.MARKDOWN -> true
    else -> false
}

/** 不支持预览时那句说明：老式 Office 二进制格式解释「为什么」，其余类型用默认文案 */
internal fun unsupportedNoteOf(ext: String): String? = if (ext in LEGACY_OFFICE_EXT) {
    "This legacy Office format (.doc / .xls / .ppt) needs an Office engine (dsh converts it with LibreOffice, which Android does not have). Open it with another app."
} else {
    null
}

/** 扩展名 → 高亮语言标识（ReadBlock 的 read 卡也用它按语言着色） */
internal fun languageOf(path: String): String? = when (File(path).name.substringAfterLast('.', "").lowercase()) {
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

// ------------------------------------------------------------------------------------------------
// 通用状态（dsh 的 LoadingIndicator / TextPreview.module.css 空态与失败行）
// ------------------------------------------------------------------------------------------------

/** 整页加载：28dp spinner + 14sp「文档渲染中...」，gap 12、padding 24 */
@Composable
internal fun PreviewLoading() {
    val palette = LocalDshPalette.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.dp,
            color = palette.labelSecondary,
        )
        Spacer(Modifier.height(12.dp))
        Text(PREVIEW_LOADING_TEXT, fontSize = 14.sp, lineHeight = 20.sp, color = palette.labelSecondary)
    }
}

/**
 * 无内容空态（dsh 的 TextPreview.module.css 空态）：36dp 类型图标（60% 透明）+ 13sp 说明，
 * 可选「重试」胶囊；末尾 12% 的弹性空白把整列**上抬**（dsh 的 ::after）。
 */
@Composable
internal fun PreviewEmpty(path: String, message: String, retry: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    val palette = LocalDshPalette.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.alpha(0.6f)) { FileTypeIcon(File(path).name, size = 36.dp) }
        Spacer(Modifier.height(16.dp))
        Text(message, fontSize = 13.sp, lineHeight = 21.sp, color = palette.labelSecondary, textAlign = TextAlign.Center)
        if (retry != null) {
            Spacer(Modifier.height(16.dp))
            RetryPill(onClick = retry)
        }
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
        Spacer(Modifier.weight(0.12f))
        Spacer(Modifier.height(24.dp))
    }
}

/** dsh 的空态按钮：32dp 高、padding 0 14 0 12、gap 6、圆角 12、0.5dp borderL2 */
@Composable
private fun RetryPill(onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .border(0.5.dp, palette.borderL2, shape)
            .dshClickable(interactionSource = dshInteraction(), onClick = onClick)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Refresh, contentDescription = null, tint = palette.labelPrimary, modifier = Modifier.size(14.dp))
        Text("重试", fontSize = 13.sp, lineHeight = 20.sp, color = palette.labelPrimary)
    }
}


// ------------------------------------------------------------------------------------------------
// 代码 / 纯文本：11sp / 19sp 等宽 + 行号 + 复制 + 换行开关（开关在文档头，见 FileWorkspacePanel）
// ------------------------------------------------------------------------------------------------

@Composable
private fun TextPreview(path: String, wrap: Boolean, reloadKey: Int) {
    var text by remember(path, reloadKey) { mutableStateOf<String?>(null) }
    var note by remember(path, reloadKey) { mutableStateOf<String?>(null) }
    var failure by remember(path, reloadKey) { mutableStateOf<String?>(null) }
    var retry by remember(path, reloadKey) { mutableStateOf(0) }

    LaunchedEffect(path, reloadKey, retry) {
        text = null
        note = null
        failure = null
        val result = withContext(Dispatchers.IO) { runCatching { readTextPreview(path) } }
        result.fold(
            onSuccess = { (body, extra) ->
                note = extra
                text = body
            },
            onFailure = { failure = it.message ?: it::class.java.simpleName },
        )
    }

    when {
        failure != null -> PreviewEmpty(path, "failed to read: " + failure, retry = { retry++ })
        text == null -> PreviewLoading()
        else -> {
            val body = text!!
            val language = languageOf(path)
            Column(Modifier.fillMaxSize()) {
                CodeBanner(language ?: CODE_BLOCK_LABEL, payload = { body })
                note?.let {
                    Text(
                        text = it,
                        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 6.dp),
                        fontSize = 11.sp,
                        lineHeight = 18.sp,
                        color = LocalDshPalette.current.labelTertiary,
                    )
                }
                TextPreviewBody(lines = remember(body) { body.split("\n") }, language = language, wrap = wrap)
            }
        }
    }
}

/** dsh 的代码横幅：padding 10 18 8 22、语言 11/18 等宽三级色、右侧 24×24 复制 */
@Composable
private fun CodeBanner(label: String, payload: () -> String) {
    val palette = LocalDshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.codeBlockBanner)
            .padding(start = 22.dp, top = 10.dp, end = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 18.sp,
            color = palette.labelTertiary,
        )
        Spacer(Modifier.weight(1f))
        RailCopyAction(payload = payload)
    }
}

/** 行号槽宽：dsh 的 max(2, 位数)ch + 12px */
internal fun lineNumberGutterWidth(lineCount: Int): androidx.compose.ui.unit.Dp {
    val digits = lineCount.toString().length.coerceAtLeast(2)
    return (digits * 7 + 12).dp
}

@Composable
private fun TextPreviewBody(lines: List<String>, language: String?, wrap: Boolean) {
    val palette = LocalDshPalette.current
    val gutter = lineNumberGutterWidth(lines.size)
    if (wrap) {
        val listState = rememberLazyListState()
        LazyColumn(
            modifier = Modifier.fillMaxSize().dshVerticalScrollbar(listState),
            state = listState,
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            itemsIndexed(lines) { index, line ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Top) {
                    LineNumber(index + 1, gutter)
                    SelectionContainer {
                        Text(
                            text = highlightCode(line, language, palette),
                            modifier = Modifier.weight(1f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 19.sp,
                        )
                    }
                }
            }
        }
    } else {
        // 不折行：整块横向滚动。宽度按最长行的近似字宽算，LazyColumn 必须拿到有界宽度（否则无法测量）
        val charWidth = with(LocalDensity.current) { 11.sp.toDp() * 0.62f }
        val contentWidth = gutter + 32.dp + charWidth * ((lines.maxOfOrNull { it.length } ?: 0).coerceAtLeast(1))
        val listState = rememberLazyListState()
        val horizontal = rememberScrollState()
        // 两条轴都挂外层可视区：LazyColumn 的宽度等于内容宽，竖轴挂在它身上会画到可视区外
        Box(
            Modifier
                .fillMaxSize()
                .dshVerticalScrollbar(listState)
                .dshHorizontalScrollbar(horizontal)
                .horizontalScroll(horizontal),
        ) {
            LazyColumn(
                modifier = Modifier.width(contentWidth).fillMaxHeight(),
                state = listState,
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                itemsIndexed(lines) { index, line ->
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Top) {
                        LineNumber(index + 1, gutter)
                        SelectionContainer {
                            Text(
                                text = highlightCode(line, language, palette),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 19.sp,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 行号（dsh 的 CodeBlock 行号：三级色、右对齐、不参与复制） */
@Composable
private fun LineNumber(number: Int, gutter: androidx.compose.ui.unit.Dp) {
    Text(
        text = number.toString(),
        modifier = Modifier.width(gutter).padding(end = 12.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 19.sp,
        color = LocalDshPalette.current.labelTertiary,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

// ------------------------------------------------------------------------------------------------
// Markdown：容器 10/12 + frontmatter 键值盒（dsh 的 MarkdownBody / frontmatter-fields）
// ------------------------------------------------------------------------------------------------

/**
 * 拆出 YAML frontmatter（dsh 的 markdown preview）：首行 `---` 起、`---` 或 `...` 收尾。
 *
 * 只认**顶层 key: value** 这种映射；不是映射（或未闭合）时整块按原文回退（dsh 同样回退渲染）。
 *
 * @return 字段列表（null = 没有 frontmatter / 不是映射）与正文
 */
internal fun splitFrontmatter(text: String): Pair<List<Pair<String, String>>?, String> {
    val lines = text.split("\n")
    if (lines.firstOrNull()?.trim() != "---") return null to text
    var close = -1
    for (index in 1 until lines.size) {
        val trimmed = lines[index].trim()
        if (trimmed == "---" || trimmed == "...") {
            close = index
            break
        }
    }
    if (close < 0) return null to text
    val fields = ArrayList<Pair<String, String>>()
    for (index in 1 until close) {
        val raw = lines[index]
        if (raw.isBlank()) continue
        val separator = raw.indexOf(':')
        if (separator <= 0) return null to text
        val key = raw.substring(0, separator).trim()
        if (key.isEmpty() || key.any { it.isWhitespace() }) return null to text
        fields += key to raw.substring(separator + 1).trim()
    }
    return fields to lines.drop(close + 1).joinToString("\n")
}

@Composable
private fun MarkdownPreview(path: String, reloadKey: Int) {
    var text by remember(path, reloadKey) { mutableStateOf<String?>(null) }
    var failure by remember(path, reloadKey) { mutableStateOf<String?>(null) }
    var retry by remember(path, reloadKey) { mutableStateOf(0) }
    LaunchedEffect(path, reloadKey, retry) {
        text = null
        failure = null
        val result = withContext(Dispatchers.IO) { runCatching { readTextPreview(path).first } }
        text = result.getOrNull()
        failure = result.exceptionOrNull()?.message
    }
    when {
        failure != null -> PreviewEmpty(path, "failed to read: " + failure, retry = { retry++ })
        text == null -> PreviewLoading()
        else -> {
            val body = text!!
            val (frontmatter, markdown) = remember(body) { splitFrontmatter(body) }
            SelectionContainer {
                val listState = rememberLazyListState()
                LazyColumn(
                    modifier = Modifier.fillMaxSize().dshVerticalScrollbar(listState),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (frontmatter != null) {
                        item {
                            FrontmatterCard(frontmatter)
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                    item { MarkdownBody(markdown, modifier = Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

/** frontmatter 盒：margin 0 0 16、padding 10 12、0.5 borderL2、圆角 12、底 bgLayer1、12/20 等宽 */
@Composable
private fun FrontmatterCard(fields: List<Pair<String, String>>) {
    val palette = LocalDshPalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.bgLayer1)
            .border(0.5.dp, palette.borderL2, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        fields.forEach { (key, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = key,
                    modifier = Modifier.widthIn(min = 64.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    color = palette.labelSecondary,
                )
                Text(
                    text = value,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    color = palette.labelPrimary,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// PDF：连续页（dsh 的 PdfBody）：12dp 页间隙 + 12dp 外内边距 + 文档底色 + 3:4 未渲染占位
// ------------------------------------------------------------------------------------------------

@Composable
private fun PdfPreview(path: String, reloadKey: Int) {
    val palette = LocalDshPalette.current
    var pageCount by remember(path, reloadKey) { mutableStateOf<Int?>(null) }
    var failure by remember(path, reloadKey) { mutableStateOf<String?>(null) }
    var retry by remember(path, reloadKey) { mutableStateOf(0) }

    LaunchedEffect(path, reloadKey, retry) {
        pageCount = null
        failure = null
        withContext(Dispatchers.IO) {
            runCatching {
                val descriptor = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                descriptor.use { fd -> android.graphics.pdf.PdfRenderer(fd).use { it.pageCount } }
            }
        }.fold(
            onSuccess = { pageCount = it },
            onFailure = { failure = pdfFailureText(it) },
        )
    }

    when {
        failure != null -> PreviewEmpty(path, failure!!, retry = { retry++ })
        pageCount == null -> PreviewLoading()
        pageCount == 0 -> PreviewEmpty(path, "This path is not a regular file; there is nothing to preview.")
        else -> {
            val listState = rememberLazyListState()
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.bgDocumentPreview)
                    .dshVerticalScrollbar(listState),
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pageCount!!) { index -> PdfPage(path, index, reloadKey) }
            }
        }
    }
}

private fun pdfFailureText(error: Throwable): String = when (error) {
    is SecurityException -> "This PDF is password-protected and cannot be previewed."
    else -> "cannot display the PDF: " + (error.message ?: error::class.java.simpleName)
}

@Composable
private fun PdfPage(path: String, index: Int, reloadKey: Int) {
    var bitmap by remember(path, index, reloadKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, index, reloadKey) {
        bitmap = withContext(Dispatchers.IO) { runCatching { renderPdfPage(path, index) }.getOrNull() }
    }
    val image = bitmap
    if (image == null) {
        // dsh 的占位：宽 100%、3:4、bg-skeleton，不叠 spinner
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(2.dp))
                .background(LocalDshPalette.current.bgSkeleton),
        )
    } else {
        Image(
            bitmap = image,
            contentDescription = "PDF 第 " + (index + 1) + " 页",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .shadow(2.dp)
                .clip(RoundedCornerShape(2.dp)),
        )
    }
}

/** 渲染 PDF 的一页（2 倍像素密度，白底；解析器每页开一次，页数多时按懒加载逐页来） */
private fun renderPdfPage(path: String, index: Int): ImageBitmap {
    val descriptor = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    return descriptor.use { fd ->
        android.graphics.pdf.PdfRenderer(fd).use { renderer ->
            renderer.openPage(index).use { page ->
                val width = (page.width * 2).coerceAtLeast(1)
                val height = (page.height * 2).coerceAtLeast(1)
                val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                created.eraseColor(android.graphics.Color.WHITE)
                page.render(created, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                created.asImageBitmap()
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// 语法高亮（与聊天里的代码卡共用同一份实现）
// ------------------------------------------------------------------------------------------------

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

// ------------------------------------------------------------------------------------------------
// 不支持 / 居中说明：OfficePreview 与表格预览共用
// ------------------------------------------------------------------------------------------------

@Composable
internal fun UnsupportedPreview(path: String, note: String? = null) {
    val file = File(path)
    PreviewEmpty(
        path = path,
        message = buildString {
            append(note ?: PREVIEW_UNVIEWABLE_TEXT)
            if (note == null) append("\n").append(fileSizeText(file.length()))
            append("\n\nYou can open this file with another app.")
        },
        action = { OpenWithOtherAppButton(path) },
    )
}

@Composable
internal fun CenteredNote(text: String) {
    val palette = LocalDshPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, lineHeight = 21.sp, color = palette.labelSecondary)
    }
}

/**
 * 读文本：限长 + 二进制探测（含 NUL 就当作二进制，不再吐乱码）；表格预览也复用它读源文件。
 *
 * @return 正文与「仅显示前 N」的截断说明（完整读完时为 null —— 路径与大小在文档头里）
 */
internal fun readTextPreview(path: String): Pair<String, String?> {
    val file = File(path)
    if (!file.isFile) throw IllegalStateException("file not found")
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
        if (bytes[index].toInt() == 0) throw IllegalStateException("binary file; cannot preview as text")
    }
    val text = String(bytes, 0, read, Charsets.UTF_8)
    val note = if (length > read) "仅显示前 " + fileSizeText(read.toLong()) + "，文件共 " + fileSizeText(length) else null
    return text to note
}

// 大小文案统一走 ui/FileSizeText.kt 的 fileSizeText（dsh 的 fileSizeText 规则；附件卡片用同一份）
