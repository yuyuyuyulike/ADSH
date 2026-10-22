package com.adsh.app.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette
import com.adsh.app.ui.kit.DshSpacing

/**
 * dsh 的代码块（对话里是 **card / 工具栏变体**，见 CodeCard.module.css 与 CodeBlock.module.css）：
 *
 * ```
 * .card                      圆角 16（--dsw-radius-lg）、底色 markdown-code-block、11/19 等宽
 * .header                    padding 10px 18px 8px 22px、11px/18px 系统字体、label-secondary
 * .language                  等宽 11px、label-tertiary；没有语言时显示「代码块」
 * .body                      padding 6px 22px 20px、等宽 11px/19px
 * ```
 *
 * 正文**恒为折行**（dsh 的默认状态；工具栏上那个「取消自动换行」在手机上去掉，理由见
 * CodeToolbar.kt 的注释：横向滚动会和根层抽屉的横向拖动抢手势）。
 *
 * 工具行的代码展开体用的是**同一个组件**（dsh 里 ToolRow 的 code 变体也是 CodeBlock），
 * 只是外面套一层 max-height 260px 的滚动区。
 *
 * @param lang 围栏信息串（```bash 里的 bash）。不认识的语言按纯文本渲染 ——
 *   dsh 用 Shiki 按语言着色，这里只有一套 TS/JS 规则，硬套会把别的语言标错颜色。
 * @param maxHeight 非空时限制代码区高度并在内部滚动（dsh 的 .bodyScroll 是 260px）
 * @param streaming 围栏还在长：已完成的整行按行复用高亮，只给新增/变化的部分着色
 *   （dsh 的 IncrementalHighlighter：每个 token 只重扫追加的文本）。
 */
@Composable
fun DshCodeBlock(
    code: String,
    lang: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    maxHeight: Dp? = null,
    streaming: Boolean = false,
) {
    val palette = LocalDshPalette.current
    val streamingHighlighter = if (streaming && !compact) {
        remember(lang, palette) { StreamingCodeHighlighter() }
    } else {
        null
    }
    val highlighted = if (streamingHighlighter != null) {
        remember(code, lang, palette) { streamingHighlighter.highlight(code, lang, palette) }
    } else {
        remember(code, lang, palette, compact) {
            CodeHighlightCache.get(code, lang, palette) {
                buildAnnotatedString {
                    code.lineSequence().forEachIndexed { index, line ->
                        if (index > 0) append("\n")
                        if (compact) append(line) else append(highlightCode(line, lang, palette))
                    }
                }
            }
        }
    }
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(palette.codeBlock),
    ) {
        // 横幅：语言标签在左、复制在右（dsh 的 CodeToolbar .header —— 手机上去掉换行开关，见 KDoc）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, top = DshSpacing.Xxl, end = 18.dp, bottom = DshSpacing.Xl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxxl),
        ) {
            Text(
                text = lang?.takeIf { it.isNotBlank() } ?: CODE_BLOCK_LABEL,
                fontSize = 11.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                color = palette.labelTertiary,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            RailCopyAction(payload = { code })
        }
        val scroll = rememberScrollState()
        val vertical = Modifier.then(
            if (maxHeight != null) {
                Modifier
                    .heightIn(max = maxHeight)
                    .dshVerticalScrollbar(scroll, elevated = true, bottomInset = 8.dp)
                    .verticalScroll(scroll)
            } else {
                Modifier
            },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(vertical)
                .padding(start = 22.dp, top = DshSpacing.Lg, end = 22.dp, bottom = DshSpacing.Page),
        ) {
            CodeText(highlighted)
        }
    }
}

/**
 * 流式围栏的渐进高亮（dsh 的 IncrementalHighlighter 语义）：已完成的整行按行缓存，
 * 追加一个新 token 时只给「上次的末行 + 新行」着色，其余行直接复用。
 *
 * 契约：[highlight] 的入参只允许**追加**（新文本以上一次为前缀）；不是追加（定稿 /
 * 换块）就整体重算。高亮规则本身就是逐行的（[highlightCode]），所以按行复用不会改变结果。
 */
private class StreamingCodeHighlighter {
    private var previous = ""
    private var lines: List<AnnotatedString> = emptyList()

    fun highlight(code: String, lang: String?, palette: com.adsh.app.ui.theme.DshPalette): AnnotatedString {
        val append = code.startsWith(previous)
        val raw = code.split('\n')
        // 上次的末行可能还在长，不能复用；前面那些整行已经不会再变
        val reusable = if (append) (lines.size - 1).coerceAtLeast(0) else 0
        val painted = ArrayList<AnnotatedString>(raw.size)
        raw.forEachIndexed { index, line ->
            painted += if (index < reusable && index < lines.size) {
                lines[index]
            } else {
                highlightCode(line, lang, palette)
            }
        }
        previous = code
        lines = painted
        return joinHighlightedLines(painted)
    }
}

/** 把逐行的高亮结果拼成整块：文本用 \n 连接，行内 span 的偏移按行起点平移 */
private fun joinHighlightedLines(lines: List<AnnotatedString>): AnnotatedString {
    val text = StringBuilder()
    val spans = ArrayList<AnnotatedString.Range<androidx.compose.ui.text.SpanStyle>>()
    lines.forEachIndexed { index, line ->
        if (index > 0) text.append('\n')
        val start = text.length
        text.append(line.text)
        line.spanStyles.forEach { range ->
            spans += AnnotatedString.Range(range.item, start + range.start, start + range.end)
        }
    }
    return AnnotatedString(text.toString(), spanStyles = spans)
}

/** 代码正文：等宽 11/19（dsh 的 --dsw-font-markdown-code-block） */
@Composable
private fun CodeText(text: AnnotatedString) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 19.sp,
    )
}

/**
 * 代码高亮的小 LRU 缓存。
 *
 * 展开一轮会把里面每个代码块重新组合一遍，高亮是逐行正则 + 拼 AnnotatedString；
 * 同一段代码（同一轮反复展开、或者回到会话再展开）没必要重算。
 */
private object CodeHighlightCache {
    private const val MAX_ENTRIES = 64
    private val entries = object : LinkedHashMap<String, AnnotatedString>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnnotatedString>): Boolean =
            size > MAX_ENTRIES
    }

    fun get(
        code: String,
        lang: String?,
        palette: com.adsh.app.ui.theme.DshPalette,
        compute: () -> AnnotatedString,
    ): AnnotatedString {
        val key = (if (palette === com.adsh.app.ui.theme.DarkDshPalette) "d:" else "l:") + lang + ":" + code
        synchronized(entries) { entries[key] }?.let { return it }
        val value = compute()
        synchronized(entries) { entries[key] = value }
        return value
    }
}
