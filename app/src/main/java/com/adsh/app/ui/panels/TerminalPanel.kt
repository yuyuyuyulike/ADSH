package com.adsh.app.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.adsh.app.runtime.termux.PtySession
import com.adsh.app.ui.DshSettingIcons

/**
 * 终端页：Termux 那种「一整块终端 + 一条引导符」的样子。
 *
 * 与旧实现的区别（用户点名的两处）：
 *  - 进来就自动起 bash（旧版要先点「启动」）；
 *  - 没有输入框、没有发送按钮：正文直接接一行同款等宽字体的输入行，
 *    bash 自己打出来的提示符就是那一串引导符，回车即送进 PTY。
 */
@Composable
fun TerminalPanel(
    onBack: () -> Unit,
    /** 启动描述：argv + 环境（TermuxRuntime.shellLaunch；前缀就是官方路径，见方案 A） */
    launch: com.adsh.app.runtime.termux.ShellLaunch,
) {
    // Termux 的配色是固定的深色：黑底、浅灰字、亮色光标
    val background = Color(0xFF000000)
    val foreground = Color(0xFFE6E6E6)
    val dim = Color(0xFF81858C)
    val accent = Color(0xFF7EE787)

    var buffer by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<PtySession?>(null) }
    var state by remember { mutableStateOf(TerminalState.STARTING) }
    var note by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current

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
        android.graphics.Paint().apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = with(density) { TERMINAL_FONT_SP.toPx() }
        }.measureText("M").takeIf { it > 0f } ?: 1f
    }
    val lineHeightPx = remember(density.density, density.fontScale) {
        with(density) { TERMINAL_LINE_HEIGHT_SP.toPx() }
    }
    var ptyRows by remember { mutableIntStateOf(DEFAULT_PTY_ROWS) }
    var ptyCols by remember { mutableIntStateOf(DEFAULT_PTY_COLS) }
    val horizontalPaddingPx = remember(density.density) { with(density) { 10.dp.toPx() } } * 2f
    /**
     * 「把键盘要回来」的计数：每执行一行命令 +1。
     *
     * 真机 logcat 证据：豆包输入法在**回车**上会自己收起键盘
     * （`ImeTracker(7308): onRequestHide at ORIGIN_IME reason HIDE_SOFT_INPUT_FROM_IME`，
     * 就在回车键抬起后 ~11ms），紧接着系统放 ~430ms 的收键盘动画。终端里回车之后还要接着
     * 打字，键盘本来也不该收，所以这里等收起请求先发出来（~11ms），再把它顶回去一次。
     *
     * **只在回车这一刻要一次键盘**（计数变了才动手）：第 70 轮曾经改成「盯着 IME inset，
     * 只要键盘不在就 show()」—— 那样用户自己收起键盘也会立刻被拉回来（用户实测反馈），
     * 所以这里回到「只有提交命令才要键盘」的旧写法，字体大小那条不再处理。
     */
    var wantKeyboard by remember { mutableStateOf(0) }
    LaunchedEffect(wantKeyboard) {
        if (wantKeyboard == 0) return@LaunchedEffect
        // 40ms：要等输入法的收起请求先发出来（实测 ~11ms），但要在收起动画刚起步时就把它顶回去
        delay(40)
        // 直接走 WindowInsetsController。Compose 的 SoftwareKeyboardController 上一版实测
        // 连一条 show 请求都没打出来（current 为 null / 已认为「显示中」就不再 show），这里不赌它。
        runCatching {
            ViewCompat.getWindowInsetsController(view)?.show(WindowInsetsCompat.Type.ime())
        }
    }

    /**
     * 起一个 PTY 会话。
     *
     * **必须在 IO 线程上做**：`PtySession` 的构造走 `fork/exec`（Pty.createSubprocess），
     * 在主线程上就是一次真实的进程创建 + PTY 配置 —— 之前它跑在 `LaunchedEffect` 的
     * Main 调度器里，正好卡在整页 220ms 的滑入动画中间，点「终端」就是一顿。
     */
    suspend fun openSession(): PtySession = withContext(Dispatchers.IO) {
        PtySession(
                command = launch.command,
                args = launch.args.toTypedArray(),
                env = (launch.env + listOf(
                    // bash 的默认提示符是 \s-\v\$，而 \s 取的是 argv[0] 的基名 —— 直接起 libbash.so
                    // 会显示成「libbash.so-5.3$」。这里给一个不带颜色转义的 PS1：路径 + $
                    // （本页没有 VT 解析，ANSI 颜色码会原样显示成 [0;32m 这种垃圾）
                    "PS1=" + "\\w" + " " + "\\" + "\$" + " ",
                )).toTypedArray(),
            cwd = launch.cwd,
            rows = ptyRows,
            cols = ptyCols,
        )
    }

    // 顶栏「已结束 · 点此重开」用它再触发一次启动（会话本身已经随着进程退出被清空）
    var startKey by remember { mutableStateOf(0) }

    // 进页面就起：不用再点「启动」。fork/exec 在 IO 线程上做（见 openSession 的注释），
    // 主线程只负责把结果写回 Compose 状态 —— 点「终端」时那 220ms 的滑入动画不会再顿一下。
    LaunchedEffect(launch.command, startKey) {
        if (session != null) return@LaunchedEffect
        state = TerminalState.STARTING
        note = ""
        val started = runCatching { openSession() }
        val opened = started.getOrElse { error ->
            state = TerminalState.FAILED
            note = "启动失败：" + (error.message ?: error::class.java.simpleName)
            return@LaunchedEffect
        }
        session = opened
        state = TerminalState.RUNNING
        Thread {
            val chunk = ByteArray(4096)
            // PTY 出来的原始字节日志先过一层清洗（增量 UTF-8 解码 + 吃掉 ANSI 转义序列），
            // 否则会看到「方框」（被切成两半的多字节字符 / 控制字符）和「[0;32m」这种乱码。
            val terminal = TerminalText()
            // 输出合流：一屏几十行时每条 chunk 都写一次 buffer，等于每个 chunk 都把整篇
            // 重新测量一遍。攒到一帧的量（16ms）再写一次，交互时的手感不变，
            // 但退出动画期间（面板还在，PTY 还在冒字）不会跟滑动抢主线程。
            var lastPush = 0L
            var dirty = false
            fun push(force: Boolean) {
                val now = System.currentTimeMillis()
                // 攒帧只能把这一帧「推迟」，不能把它「丢掉」：PTY 吐完一段就会静默
                // （命令跑完、bash 打出新提示符 "~ $ " 之后就在等下一个键），要是最后一片
                // 正好落进这个 16ms 窗口里被丢掉，就再也没有下一次 read 来触发它了 ——
                // 屏幕会永远停在上一帧，表现正是「命令执行完，提示符没了」。
                // 所以只在「后面还有数据、下一次 read 立刻就会返回」时才跳过这一帧：
                // 真到了 PTY 不说话的那一刻（available() == 0），这一帧必须现在就落进 buffer。
                // 探测拿不准时（异常 / 不支持）按 0 算：宁可多写一帧，也不能丢帧、更不能因此
                // 跳出 read 循环把还活着的会话标成已结束。
                val more = runCatching { opened.input.available() }.getOrDefault(0)
                if (!force && now - lastPush < BUFFER_PUSH_MS && more > 0) {
                    dirty = true
                    return
                }
                lastPush = now
                dirty = false
                buffer = terminal.snapshot()
            }
            try {
                while (true) {
                    val read = opened.input.read(chunk)
                    if (read < 0) break
                    terminal.feed(chunk, read)
                    push(force = false)
                }
            } catch (_: Throwable) {
            } finally {
                if (dirty) push(force = true)
                state = TerminalState.EXITED
                session = null
            }
        }.apply { isDaemon = true }.start()
    }

    DisposableEffect(Unit) {
        onDispose {
            // 关闭也必须离开主线程：这里正好卡在整页 220ms 的滑出动画末尾，
            // close() 要关 fd（可能等子进程收尾），放主线程上就是退出时“顿”一下。
            val closing = session
            session = null
            if (closing != null) {
                Thread { runCatching { closing.close() } }.apply { isDaemon = true }.start()
            }
        }
    }

    // 视口变了就把 PTY 尺寸改掉（键盘弹出、旋转、分屏都会走到这里）。
    // 会话刚起来、还没有测量结果时用的是默认尺寸，这里会立刻纠正过来。
    LaunchedEffect(session, ptyRows, ptyCols) {
        session?.resize(ptyRows, ptyCols)
    }

    // 有新输出就贴底（等这一帧布局完，maxValue 才是新的）
    LaunchedEffect(buffer.length) {
        withFrameNanos { }
        runCatching { scroll.scrollTo(scroll.maxValue) }
    }

    // 聚焦（点输入行 / 点正文）也贴底：光标紧跟在提示符后面，键盘弹出来也看得见
    LaunchedEffect(focused) {
        if (focused) {
            withFrameNanos { }
            runCatching { scroll.scrollTo(scroll.maxValue) }
        }
    }

    fun writeToPty(text: String) {
        val target = session ?: return
        runCatching {
            target.output.write((text + "\n").toByteArray())
            target.output.flush()
        }
    }

    /**
     * 输入行的取值：**换行即执行**。
     *
     * 之前这里是 ImeAction.Send + KeyboardActions(onSend)。中文输入法在拼音组合状态下按回车，
     * 这一下既被输入法用来上屏、又会触发我们的 onSend —— 结果是「刚打的几个拼音被当成英文发出去了」。
     * 现在不挂任何 IME action：回车就是往输入行里落一个换行（输入法先上屏拼音，不会误解），
     * 我们在这个换行落进来的时候才把整行写进 PTY。这也是终端类应用的常规做法。
     */
    fun onDraftChange(updated: String) {
        if (!updated.contains('\n')) {
            draft = updated
            return
        }
        val parts = updated.split('\n')
        val remainder = parts.last()
        val lines = parts.dropLast(1)
        draft = remainder
        lines.forEach { line ->
            if (line.isNotEmpty() || lines.size == 1) writeToPty(line)
        }
        // 回车之后输入法常会把键盘收走（见 wantKeyboard 的注释），把它要回来
        if (focused) wantKeyboard++
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // 顶栏：返回 + 「终端」+ 状态（Termux 没有顶栏，但这一页要能退出去）
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack,
                    ),
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
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (state == TerminalState.EXITED || state == TerminalState.FAILED) {
                        buffer = ""
                        startKey++
                    }
                },
            )
        }

        // 正文与输入行在**同一个滚动列**里：输入行紧接在 bash 打出来的提示符后面，
        // 所以光标就在引导符（~ $）后面，而不是飘在屏幕最底下。
        // 点这一片任意位置都聚焦输入行（Termux 就是「点终端就出键盘」）。
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // 视口尺寸 → PTY 尺寸（见 ptyRows/ptyCols 的注释）。这一层就是转录区的视口：
                // verticalScroll 让它有确定的高度，onSizeChanged 给的正是「终端能显示多少」。
                .onSizeChanged { size ->
                    val cols = ((size.width - horizontalPaddingPx) / charWidthPx).toInt()
                        .coerceIn(MIN_PTY_COLS, MAX_PTY_COLS)
                    val rows = (size.height / lineHeightPx).toInt()
                        .coerceIn(MIN_PTY_ROWS, MAX_PTY_ROWS)
                    if (cols != ptyCols) ptyCols = cols
                    if (rows != ptyRows) ptyRows = rows
                }
                // overscrollEffect = null：安卓 12+ 的「拉伸越界」会把整页内容物理拉伸再弹回，
                // 终端里一眼就能看出「文字被拉大了一下，然后弹回去」—— 用户实测反馈这一条。
                // 触发它的是贴底/光标跟随这类**程序化**滚动：动画甩过边界时剩余位移会交给
                // overscroll 效果。终端不需要这个效果（Termux 也没有），直接关掉。
                .verticalScroll(scroll, overscrollEffect = null)
                .pointerInput(Unit) {
                    detectTapGestures { runCatching { focusRequester.requestFocus() } }
                }
                .padding(horizontal = 10.dp),
        ) {
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
                                fontFamily = FontFamily.Monospace,
                                fontSize = TERMINAL_FONT_SP,
                                lineHeight = TERMINAL_LINE_HEIGHT_SP,
                                color = foreground,
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                if (tail.isNotEmpty()) {
                    Text(
                        // tail 按构造不含换行符（它是最后一个 \n 之后的内容），就是提示符那一行本身；
                        // maxLines = 1 只可能裁掉超长行折行后的后半截，裁不到行首的提示符。
                        text = tail,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TERMINAL_FONT_SP,
                        lineHeight = TERMINAL_LINE_HEIGHT_SP,
                        color = foreground,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { onDraftChange(it) },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = TERMINAL_FONT_SP,
                        lineHeight = TERMINAL_LINE_HEIGHT_SP,
                        color = foreground,
                    ),
                    cursorBrush = SolidColor(accent),
                    modifier = Modifier
                        .weight(1f)
                        // 触摸目标按 Material 的最小值给（44dp）：之前只有一行字高（约 17dp），
                        // 点在字缝里就没反应，看着就像「终端打不了字」
                        .heightIn(min = 44.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused },
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth()) {
                            // 提示只在「还没有提示符」时出现；有 "~ $" 时提示符本身就是输入口的信号
                            if (draft.isEmpty() && !focused && tail.isBlank() && state != TerminalState.STARTING) {
                                Text(
                                    text = "点这里输入命令",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = TERMINAL_FONT_SP,
                                    lineHeight = TERMINAL_LINE_HEIGHT_SP,
                                    color = dim.copy(alpha = 0.6f),
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }
            if (note.isNotEmpty()) {
                Text(
                    text = note,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = TERMINAL_FONT_SP,
                    lineHeight = TERMINAL_LINE_HEIGHT_SP,
                    color = Color(0xFFF25A5A),
                )
            }
        }
    }
}

private enum class TerminalState { STARTING, RUNNING, EXITED, FAILED }

/** 终端字体（与正文里那几处 Text 的 12sp / 17sp 必须一致，否则算出来的列数对不上实际排版） */
private val TERMINAL_FONT_SP = 12.sp
private val TERMINAL_LINE_HEIGHT_SP = 17.sp

/** 还没测量出视口时先按这个尺寸起会话（随后立刻按实际视口 resize） */
private const val DEFAULT_PTY_ROWS = 40
private const val DEFAULT_PTY_COLS = 100

/** PTY 尺寸的上下限：太小的值会让程序排版崩掉，太大的值多半是测量出错 */
private const val MIN_PTY_ROWS = 5
private const val MAX_PTY_ROWS = 200
private const val MIN_PTY_COLS = 20
private const val MAX_PTY_COLS = 300

/** 回滚缓冲的上限：终端输出可以无限长，留最后这些字符足够看 */
private const val MAX_TRANSCRIPT_CHARS = 200_000
/**
 * 转录正文分块的行数。
 *
 * 块越大 → 块数越少、每块的测量越贵；块越小 → 每次输出要重排的部分越少。200 行 ≈ 一屏多，
 * 实测足够把「每帧 500ms」压掉。
 */
private const val TRANSCRIPT_CHUNK_LINES = 200
/**
 * 把转录正文按行切成若干块（每块最多 [TRANSCRIPT_CHUNK_LINES] 行，**不含结尾换行符**）。
 *
 * `head` 按构造不含最后一个换行符，所以每块都能直接当段落排：补上结尾换行符会让 Compose
 * 为它多排一行空行（见上面 head / tail 的注释）。
 */
internal fun transcriptChunks(head: String): List<String> {
    if (head.isEmpty()) return emptyList()
    val chunks = ArrayList<String>(4)
    var start = 0
    var lines = 0
    var i = 0
    while (i < head.length) {
        if (head[i] == '\n') {
            lines++
            if (lines >= TRANSCRIPT_CHUNK_LINES) {
                chunks.add(head.substring(start, i))
                start = i + 1
                lines = 0
            }
        }
        i++
    }
    // 最后一段不足一个块：不补结尾换行符（head 本来就没有）
    if (start < head.length) chunks.add(head.substring(start))
    return chunks
}

/**
 * PTY 输出写回 Compose 状态的最小间隔（ms，≈一帧）。
 * 每条 chunk 都写一次的话，一屏几十行时每个 chunk 都会把整篇正文重新测量一遍。
 * 这是「最快多久写一次」，**不是**「窗口里的输出就不要了」：窗口末尾那一片照样要在
 * PTY 安静的这一刻写进去，否则提示符会一直停在屏幕外（见 read 循环里 push 的注释）。
 */
private const val BUFFER_PUSH_MS = 16L

/**
 * 终端输出的清洗流水线。三件事，都是「方框 / 乱码」的来源：
 *
 * 1. **增量 UTF-8 解码**：一次 read 很可能把一个汉字（3 字节）或 emoji（4 字节）从中间切开，
 *    直接 `String(bytes, UTF_8)` 会让两半各解成一个 U+FFFD —— 屏幕上就是一个方框。
 *    这里把没凑齐的尾巴留到下一次 read 一起解（跨 read 的转义序列同理，由下面的状态机兜住）。
 * 2. **吃掉转义序列**：这一页没有 VT 解析器，ESC 会按字面显示（Android 的字体会把控制字符
 *    画成方框），后面还跟着 "[0;32m" 这样的乱码。这里按终端惯例丢掉：
 *    CSI（ESC [ … 终止符 0x40–0x7E）、OSC（ESC ] … BEL 或 ST）、其余两字符转义。
 * 3. **\r 与 \b**：`\r\n` 当一个换行；单独的 `\r` 是「回到行首重写」（apt / pip 的进度条），
 *    于是把当前这一行清掉重来，而不是把每一帧都堆在屏幕上；`\b` 退一格。
 *
 *    `returnPending` 是**跨 chunk 的实例状态**（不是每次 feed 重置的局部量），所以 pty 把
 *    `\r\n` 拆在两次 read 里（前一片以 `\r` 结尾、后一片以 `\n` 开头）时，仍然只产生
 *    一个换行；反过来，一片以 `\r` 结尾再没有后续字节时，`\r` 也还没删掉任何一行 ——
 *    它只会在下一个可打印字符到来时才执行「回到行首重写」。bash 提示符不在这些路径上。
 *
 * internal（而不是 private）：这是终端页唯一有状态、也最容易出错的逻辑，
 * `TerminalTextTest` 直接盯着它（跨 read 的多字节字符 / 转义序列 / 进度条重写）。
 */
internal class TerminalText {
    private val buf = StringBuilder()
    /** 还没凑齐的多字节尾巴 */
    private val pending = java.io.ByteArrayOutputStream(4)
    /** 0 = 正文、1 = ESC、2 = CSI、3 = OSC、4 = OSC 里收到 ESC（等 ST 的 '\\'） */
    private var escape = 0
    /** 收到过 \r，还没决定它是「换行的一半」还是「行首重写」 */
    private var returnPending = false

    fun feed(bytes: ByteArray, length: Int) {
        val text = decode(bytes, length)
        for (ch in text) consume(ch)
    }

    fun snapshot(): String = buf.toString().takeLast(MAX_TRANSCRIPT_CHARS)

    /** 只解「完整」的 UTF-8 序列，剩下的留到下一次 */
    private fun decode(bytes: ByteArray, length: Int): String {
        pending.write(bytes, 0, length)
        val all = pending.toByteArray()
        pending.reset()
        val out = StringBuilder(all.size)
        var i = 0
        while (i < all.size) {
            val b = all[i].toInt() and 0xFF
            val need = when {
                b < 0x80 -> 1
                b in 0xC2..0xDF -> 2
                b in 0xE0..0xEF -> 3
                b in 0xF0..0xF4 -> 4
                // 不是合法的起始字节：按单字节解（会被替换成 U+FFFD，说明对方本来就不是 UTF-8）
                else -> 1
            }
            if (i + need > all.size) break
            out.append(String(all, i, need, Charsets.UTF_8))
            i += need
        }
        if (i < all.size) pending.write(all, i, all.size - i)
        return out.toString()
    }

    private fun consume(ch: Char) {
        when (escape) {
            1 -> escape = when (ch) {
                '[' -> 2
                ']' -> 3
                else -> 0
            }
            2 -> if (ch in '@'..'~') escape = 0
            3 -> when (ch) {
                '\u0007' -> escape = 0
                '\u001B' -> escape = 4
            }
            4 -> escape = if (ch == '\\') 0 else 3
            else -> when (ch) {
                '\u001B' -> {
                    escape = 1
                    returnPending = false
                }
                '\r' -> returnPending = true
                '\n' -> {
                    returnPending = false
                    buf.append('\n')
                }
                '\b' -> if (buf.isNotEmpty() && buf.last() != '\n') buf.deleteCharAt(buf.length - 1)
                else -> {
                    if (returnPending) {
                        returnPending = false
                        // 回到行首重写：把当前这一行（最后一个换行之后的内容）整段删掉
                        val start = buf.lastIndexOf("\n") + 1
                        if (buf.length > start) buf.delete(start, buf.length)
                    }
                    // 其余控制字符（响铃、制表以外的 0x00–0x1F）直接丢掉，不然渲染成方框
                    if (ch == '\t' || ch >= ' ') buf.append(ch)
                }
            }
        }
    }
}
