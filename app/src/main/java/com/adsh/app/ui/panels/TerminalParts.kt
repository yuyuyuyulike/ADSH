package com.adsh.app.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.blocks.dshHorizontalScrollbar
import com.adsh.app.ui.blocks.dshVerticalScrollbar
import com.adsh.app.ui.kit.DshSpacing
import com.adsh.app.ui.kit.DshSettingIcons
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.kit.dshInteraction

/**
 * 终端页的三块布局：顶栏、转录区、附加键行。
 *
 * 这一层只画东西：会话状态机与输入行的文本 / 光标都留在 TerminalPanel 那一层。
 * 转录区顺带负责「视口 → PTY 网格」的测量（[terminalGrid] 是纯函数，有单测），
 * 测出来的行列回调给调用方去重。
 */

/**
 * 底部附加键的**标签**（怎么按由 [TerminalPanel] 的 onExtraKey 分派）。
 *
 * 取值就是 Termux 那套 extra keys 里对这个终端有用的那些：方向键与 Home / End 编辑输入行，
 * ↑ / ↓ 翻本地历史，PgUp / PgDn 给 less / vim 这类翻页的程序，
 * `- / | ~` 是敲命令行时最常打又最难在手机键盘上找的几个字符。
 */
private val EXTRA_KEYS = listOf(
    "Ctrl", "Esc", "Tab",
    "↑", "↓", "←", "→",
    "Home", "End", "PgUp", "PgDn",
    "-", "/", "|", "~",
)

/** 终端字体（列数就是按它算的，见 terminalCharWidthPx：12sp 等宽 = 每列 23.4px） */
private val TERMINAL_FONT_SP = 12.sp
private val TERMINAL_LINE_HEIGHT_SP = 17.sp

/**
 * 终端正文与输入行的排版：**一份显式样式，两个渲染路径共用**。转录区是 `Text`（合 `LocalTextStyle`）、
 * 输入行是 `BasicTextField`（传进去的 `textStyle` **整份替换**）—— 有一项没写明就各按各的默认值渲染：
 * 同一行打字时在输入行、回车后进转录区，基线差 5px（「命令一执行，字往下跳一点」）。字距 0 是 dsh 口径。
 */
internal val TERMINAL_TEXT_STYLE = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = TERMINAL_FONT_SP,
    lineHeight = TERMINAL_LINE_HEIGHT_SP,
    letterSpacing = 0.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/**
 * 顶栏：返回 + 「终端」+ 状态（Termux 没有顶栏，但这一页要能退出去）。
 *
 * 状态那一段同时是「重开」的入口：已结束 / 未启动时点它清空转录、重建会话（[onRestart]）。
 */
@Composable
internal fun TerminalTopBar(
    state: TerminalState,
    foreground: Color,
    dim: Color,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(start = DshSpacing.Md, end = DshSpacing.Xxxl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Md),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .dshClickable(interactionSource = dshInteraction(), onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DshSettingIcons.ChevronLeft,
                contentDescription = "返回",
                tint = foreground,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = "终端",
            fontSize = 14.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            color = foreground,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = when (state) {
                TerminalState.STARTING -> "启动中…"
                // 跑起来之后顶栏不挂状态：提示符本身就是「已就绪」的信号（Termux 也没有这一行）
                TerminalState.RUNNING -> ""
                TerminalState.EXITED -> "已结束 · 点此重开"
                TerminalState.FAILED -> "未启动"
            },
            fontSize = 11.sp,
            lineHeight = TERMINAL_LINE_HEIGHT_SP,
            color = dim,
            modifier = Modifier.dshClickable(interactionSource = dshInteraction()) {
                if (state == TerminalState.EXITED || state == TerminalState.FAILED) {
                    onRestart()
                }
            },
        )
    }
}

/**
 * 转录区：整篇正文（按行分块渲染）+ 尾行插槽 [content]（输入行、提示与报错都在里面）。正文与输入行
 * 在**同一个滚动列**里，点这一片任意位置都聚焦输入行。**用 LazyColumn 而不是 Column + verticalScroll**：
 * 转录仍是一个 item，要的只是 `requestScrollToItem`（Compose 里唯一「本帧测量前就能指定滚动位置」的入口）。
 */
@Composable
internal fun ColumnScope.TerminalTranscript(
    listState: LazyListState,
    buffer: String,
    foreground: Color,
    focusRequester: FocusRequester,
    onGridChange: (rows: Int, cols: Int) -> Unit,
    content: @Composable (tail: String) -> Unit,
) {
    /**
     * PTY 尺寸 = **视口**的尺寸（不是写死的 40×100）。
     *
     * 终端里的程序（apt 的进度条、`ls` 的分栏、`less` 的分页、`top` 的整屏刷新）全按 PTY 尺寸
     * 排版：PTY 说自己 100 列宽、屏幕其实只有 45 列，输出就会在屏幕中间硬折行。官方 Termux 也是
     * 「视口多大，PTY 就多大」（TerminalView 每次 onSizeChanged 调 setPtyWindowSize）。
     *
     * 尺寸从**等宽字体的实际度量**算：列 = 视口宽 / 一个字符的宽度，行 = 视口高 / 行高。
     * 变了才通知内核（[PtySession.resize] 去重）—— 每发一次 SIGWINCH，readline 就会重画提示符。
     */
    val density = LocalDensity.current
    val charWidthPx = remember(density.density, density.fontScale) {
        terminalCharWidthPx(with(density) { TERMINAL_FONT_SP.toPx() })
    }
    val lineHeightPx = remember(density.density, density.fontScale) {
        with(density) { TERMINAL_LINE_HEIGHT_SP.toPx() }
    }
    val horizontalPaddingPx = remember(density.density) { with(density) { 10.dp.toPx() } } * 2f
    LazyColumn(
        state = listState,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .dshVerticalScrollbar(listState, elevated = true)
            // 视口尺寸 → PTY 尺寸（见 ptyRows/ptyCols 的注释）。这一层就是转录区的视口：
            // LazyColumn 让它有确定的高度，onSizeChanged 给的正是「终端能显示多少」。
            .onSizeChanged { size ->
                val grid = terminalGrid(
                    widthPx = size.width,
                    heightPx = size.height,
                    charWidthPx = charWidthPx,
                    lineHeightPx = lineHeightPx,
                    horizontalPaddingPx = horizontalPaddingPx,
                )
                onGridChange(grid.rows, grid.cols)
            }
            .pointerInput(Unit) {
                detectTapGestures { runCatching { focusRequester.requestFocus() } }
            },
        // overscrollEffect = null：安卓 12+ 的「拉伸越界」会把整页内容物理拉伸再弹回，
        // 终端里一眼就能看出「文字被拉大了一下，然后弹回去」—— 用户实测反馈这一条。
        // 触发它的是贴底/光标跟随这类**程序化**滚动：动画甩过边界时剩余位移会交给
        // overscroll 效果。终端不需要这个效果（Termux 也没有），直接关掉。
        overscrollEffect = null,
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
        item {
        Column(Modifier.fillMaxWidth()) {
        // 最后一行（通常就是 bash 刚打出来的提示符 "~ $ "，没有换行符）单独取出来，
        // 和输入框放在**同一行**：光标于是正好落在引导符后面，而不是另起一行。
        //
        // head 要停在这个换行符**之前**（不含它）：head 与下面的 Row 本来就是 Column 里
        // 两个块级元素，Row 自己会另起一行；而 Compose 的 Text 会为结尾的换行符多排出一行
        // 空行（lineHeight 那么高），于是整条输入行被压低一行 —— 就是「光标多往下一行」。
        // buffer 恰好以换行结尾时 tail 为空、head 不含结尾换行：Row 依旧落在 head 的下一行
        // （Column 的块级排布），也就是那个空行上 —— 这正是终端该有的样子，不必补回换行符。
        val lastBreak = buffer.lastIndexOf('\n')
        val head = if (lastBreak >= 0) buffer.substring(0, lastBreak) else ""
        val tail = if (lastBreak >= 0) buffer.substring(lastBreak + 1) else buffer
        // 转录正文**按行分块**渲染，而不是整段塞进一个 Text。
        //
        // 真机实测（logcat 的 BufferQueueProducer）：整段（上限 20 万字）一个 Text 时，
        // 每来一批 PTY 输出都要把整段重新排一遍版 —— 一帧 ~500ms、整页掉到 2fps
        // （连续几十秒 max≈506ms）。分块之后只有最后一块会变，前面的块文本相等，
        // Compose 直接跳过测量。
        val headChunks = remember(head) { transcriptChunks(head) }
        if (headChunks.isNotEmpty()) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth()) {
                    headChunks.forEach { chunk ->
                        Text(
                            text = chunk,
                            modifier = Modifier.fillMaxWidth(),
                            style = TERMINAL_TEXT_STYLE,
                            color = foreground,
                        )
                    }
                }
            }
        }
        content(tail)
        }
        }
    }
}

/**
 * 底部附加键行（Termux 的 extra keys）：终端页是**转录式**的行输入，Ctrl-C / Tab 补全 / 上下键翻历史
 * 原本一个都按不出来。这里把按键的字节直接写进 master fd —— 行规程会把 \x03 变成 SIGINT。它放在最底部、
 * 跟着 imePadding 走，键比一屏多所以**横向可滚**；[onKey] 收的是标签，分派在 TerminalPanel 的 onExtraKey。
 */
@Composable
internal fun TerminalExtraKeys(
    ctrl: Boolean,
    accent: Color,
    foreground: Color,
    onKey: (String) -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .dshHorizontalScrollbar(scroll)
            .horizontalScroll(scroll)
            .padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Lg),
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
    ) {
        EXTRA_KEYS.forEach { key ->
            val armed = key == "Ctrl" && ctrl
            Box(
                Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (armed) accent.copy(alpha = 0.22f) else Color(0xFF1C1C1E))
                    .dshClickable(interactionSource = dshInteraction()) { onKey(key) }
                    .padding(horizontal = DshSpacing.Xxxl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = key,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    color = if (armed) accent else foreground,
                )
            }
        }
    }
}
