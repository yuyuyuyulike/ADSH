package com.adsh.app.ui

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.delay

/**
 * dsh 的代码块（assistant 消息里的 **card 变体**，见 dsh-markdown-spec §8）：
 *
 * ```
 * .block.md-code-block.card  radius 12px、底色 markdown-code-block
 * .header                    padding 10px 18px 8px 22px、11px/18px 系统字体、label-secondary
 * .infostring                语言标签：等宽字体 11px、label-tertiary
 * .copyButton                24×24、圆角 6px；点一下变「复制成功」，1 秒后还原
 * pre                        padding 6px 22px 20px、等宽 11px/19px、
 *                            white-space:pre-wrap、word-break:normal、overflow-wrap:anywhere、
 *                            横向可滚（overflow-x:auto）
 * ```
 *
 * 工具行的代码展开体用的是**同一个组件**（dsh 里 ToolRow 的 code 变体也是 CodeBlock），
 * 只是外面套一层 max-height 260px 的滚动区。
 *
 * @param lang 围栏信息串（```bash 里的 bash）。不认识的语言按纯文本渲染 ——
 *   dsh 用 Shiki 按语言着色，这里只有一套 TS/JS 规则，硬套会把别的语言标错颜色。
 * @param maxHeight 非空时限制代码区高度并在内部滚动（dsh 的 .bodyScroll 是 260px）
 */
@Composable
fun DshCodeBlock(
    code: String,
    lang: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    maxHeight: Dp? = null,
) {
    val palette = LocalDshPalette.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1000)
            copied = false
        }
    }
    val highlighted = remember(code, lang, palette, compact) {
        CodeHighlightCache.get(code, lang, palette) {
            buildAnnotatedString {
                code.lineSequence().forEachIndexed { index, line ->
                    if (index > 0) append("\n")
                    if (compact) append(line) else append(highlightCode(line, lang, palette))
                }
            }
        }
    }
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.codeBlock),
    ) {
        // 横幅：语言标签在左、复制在右（dsh 的 CodeCard .header）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, top = 10.dp, end = 18.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = lang.orEmpty(),
                fontSize = 11.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                color = palette.labelTertiary,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("adsh", code))
                        copied = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (copied) "复制成功" else "复制",
                    fontSize = 11.sp,
                    lineHeight = 18.sp,
                    color = palette.labelSecondary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        val vertical = Modifier.then(
            if (maxHeight != null) Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()) else Modifier,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(vertical)
                .horizontalScroll(rememberScrollState())
                .padding(start = 22.dp, top = 6.dp, end = 22.dp, bottom = 20.dp),
        ) {
            Text(
                text = highlighted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 19.sp,
            )
        }
    }
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
