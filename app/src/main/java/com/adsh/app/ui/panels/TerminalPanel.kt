package com.adsh.app.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay
import com.adsh.app.runtime.termux.PtySession
import com.adsh.app.ui.BOTTOM_REQUEST_OFFSET
import com.adsh.app.ui.DshSpacing

/**
 * 终端页：Termux 那种「一整块终端 + 一条引导符」的样子。
 *
 * 与旧实现的区别（用户点名的两处）：
 *  - 进来就自动起 bash（旧版要先点「启动」）；
 *  - 没有输入框、没有发送按钮：正文直接接一行同款等宽字体的输入行，
 *    bash 自己打出来的提示符就是那一串引导符，回车即送进 PTY。
 *
 * R10 之后这个函数只管**本页的状态机**（PTY 会话 + 输入行）：三块布局在 TerminalParts.kt，
 * 会话与 I/O 在 TerminalSession.kt，输入行的纯逻辑在 TerminalInput.kt。
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
    /**
     * 输入行。**带光标**（TextFieldValue）：方向键、Home / End、历史召回全在这一行上做 ——
     * 以前这些键是把字节直接写进 PTY 交给远端的 readline，翻出来的那一行只活在远端，
     * 本地输入行还是空的，所以退格删不掉（用户第 117 轮第二轮反馈）。
     */
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    /** 提交过的命令（↑ / ↓ 翻，最近的在最后）与当前翻到第几条（-1 = 不在历史里） */
    var history by remember { mutableStateOf(listOf<String>()) }
    var historyIndex by remember { mutableIntStateOf(-1) }
    /** 开始翻历史之前手里那半行，↓ 翻回底时还给它 */
    var historyLive by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<PtySession?>(null) }
    var state by remember { mutableStateOf(TerminalState.STARTING) }
    var note by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    /** Ctrl 粘滞键（底部附加键行）：按下之后落进输入行的下一个字符按控制字符送 */
    var ctrl by remember { mutableStateOf(false) }
    /**
     * 转录区的滚动状态。**必须是 LazyListState**（第 123 轮）：只有它能在**本帧测量之前**接受
     * 一个「滚到哪儿」的请求（`requestScrollToItem`），见下面贴底那一段。
     */
    val listState = rememberLazyListState()
    /** 上一次贴底时的「buffer 长度 + IME 下边距」（非 State：写它不该触发重组） */
    val pinned = remember { intArrayOf(-1, -1) }
    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current

    // PTY 尺寸：还没测到视口时先按默认值起会话，TerminalTranscript 测出来之后立刻 resize
    var ptyRows by remember { mutableIntStateOf(DEFAULT_PTY_ROWS) }
    var ptyCols by remember { mutableIntStateOf(DEFAULT_PTY_COLS) }
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
        // `ViewCompat.getWindowInsetsController` 被标成 deprecated（平台 API 30 起有
        // View.getWindowInsetsController），但 minSdk 26 上只有这个兼容入口能用 —— 别换掉。
        runCatching {
            ViewCompat.getWindowInsetsController(view)?.show(WindowInsetsCompat.Type.ime())
        }
    }

    // 顶栏「已结束 · 点此重开」用它再触发一次启动（会话本身已经随着进程退出被清空）
    var startKey by remember { mutableStateOf(0) }

    // 进页面就起：不用再点「启动」。fork/exec 在 IO 线程上做（见 openTerminalSession 的注释），
    // 主线程只负责把结果写回 Compose 状态 —— 点「终端」时那 220ms 的滑入动画不会再顿一下。
    LaunchedEffect(launch.command, startKey) {
        if (session != null) return@LaunchedEffect
        state = TerminalState.STARTING
        note = ""
        val started = runCatching { openTerminalSession(launch, ptyRows, ptyCols) }
        val opened = started.getOrElse { error ->
            state = TerminalState.FAILED
            note = "启动失败：" + (error.message ?: error::class.java.simpleName)
            return@LaunchedEffect
        }
        session = opened
        state = TerminalState.RUNNING
        startPtyPump(
            session = opened,
            onFrame = { buffer = it },
            onExited = {
                state = TerminalState.EXITED
                session = null
            },
        )
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

    /**
     * 贴底：把视口钉到转录区末端（新输出、聚焦都要）。
     *
     * **用 `requestScrollToItem` 而不是 `scrollTo(maxValue)`**（第 123 轮，从 60fps 连拍里挖出来的）：
     * 前者是「**下一次测量用的位置**」—— LazyColumn 在本帧测量时读到它，于是新行第一次被画出来
     * 位置就是对的。旧写法是 `LaunchedEffect(buffer.length) { withFrameNanos { }; scrollTo(maxValue) }`：
     * 它要等一帧、而且是在**测量之后**才滚，那一帧会先按旧滚动位置把新行画出来（真机连拍证据：
     * Enter 那一帧可见行数 22 → **23**，下一帧又回到 22）—— 用户看到的就是「残影在正确行的下一行
     * 闪一下再消失」。这不是加一层补偿能盖住的：偏差就发生在那一次绘制里。
     */
    fun pinToBottom() {
        listState.requestScrollToItem(0, BOTTOM_REQUEST_OFFSET)
    }
    // 键盘弹出 / 收起时贴底也要跟着做一次：**矮下去的是视口、buffer 一个字都没动** ——
    // 少了这一条，原来停在底部的提示符会留在键盘下面，直到下一次有输出才滚回来
    // （用户报的「自动翻滚不够，还在键盘下面；一敲新命令又滚到位了」）。
    // 在组合里读一次 IME 下边距：键盘动画的每一帧都在改它 ⇒ 键盘动一帧、本屏重组一帧、
    // 贴底请求跟着发一帧 —— 与 ChatScreen 的消息区同一套做法（那边也叫 imeBottom）。
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    SideEffect {
        if (buffer.length != pinned[0] || imeBottom != pinned[1]) {
            pinned[0] = buffer.length
            pinned[1] = imeBottom
            pinToBottom()
        }
    }

    // 聚焦（点输入行 / 点正文）也贴底：光标紧跟在提示符后面，键盘弹出来也看得见
    LaunchedEffect(focused) {
        if (focused) pinToBottom()
    }

    /**
     * 往 master fd 写**原始字节**（附加键行里的控制键用它：Ctrl-C 的 \x03、Esc、Tab、PgUp / PgDn）。
     * 行规程负责把 \x03 变成 SIGINT，所以 Ctrl-C 不需要我们自己发信号（Termux 也是这么做的）。
     */
    fun sendRaw(text: String) {
        val target = session ?: return
        runCatching {
            target.output.write(text.toByteArray())
            target.output.flush()
        }
    }

    fun writeToPty(text: String) = sendRaw(text + "\n")

    /**
     * 输入行的取值：**换行即执行**。
     *
     * 之前这里是 ImeAction.Send + KeyboardActions(onSend)。中文输入法在拼音组合状态下按回车，
     * 这一下既被输入法用来上屏、又会触发我们的 onSend —— 结果是「刚打的几个拼音被当成英文发出去了」。
     * 现在不挂任何 IME action：回车就是往输入行里落一个换行（输入法先上屏拼音，不会误解），
     * 我们在这个换行落进来的时候才把整行写进 PTY。这也是终端类应用的常规做法。
     *
     * 具体拆分（哪几段要提交、最后一段怎么留下）由 TerminalInput.commitDraft 决定，这里只写状态。
     */
    fun onDraftChange(updated: TextFieldValue) {
        // 判定全在 [draftOutcome] 里（纯函数，表在 TerminalInputTest）；这里只把它落成副作用。
        when (val outcome = draftOutcome(draft.text, updated.text, ctrl)) {
            // Ctrl 是**粘滞修饰键**（Termux 的 extra keys）：它消费掉这一次输入，输入行保持原样
            is DraftOutcome.Control -> {
                ctrl = false
                sendRaw(outcome.bytes)
            }
            // 换行即执行：换行之前的每一段都送进 PTY，最后一段留在行里继续编辑
            is DraftOutcome.Submit -> {
                draft = TextFieldValue(outcome.remaining)
                historyIndex = -1
                outcome.lines.forEach { line ->
                    writeToPty(line)
                    history = historyWith(history, line)
                }
                // 回车之后输入法常会把键盘收走（见 wantKeyboard 的注释），把它要回来
                if (focused) wantKeyboard++
            }
            // 普通编辑：直接用输入法给的值（保留光标与组合区）
            DraftOutcome.Typed -> {
                draft = updated
                historyIndex = -1
            }
        }
    }

    /** 把字符插到光标处（附加键行里的 `- / | ~` 与普通键入走同一条路，退格自然删得掉） */
    fun insertText(text: String) {
        val (next, caret) = insertRange(draft.text, draft.selection.min, draft.selection.max, text)
        draft = TextFieldValue(next, TextRange(caret))
    }

    /** 光标左右移一格 */
    fun moveCursor(delta: Int) {
        draft = draft.copy(selection = TextRange(moveCursorTo(draft.selection.end, delta, draft.text.length)))
    }

    /** ↑ / ↓ 翻**本地**历史：召回的那一行就是输入行的正文，所以照样能改、能删 */
    fun moveHistory(delta: Int) {
        val moved = nextHistory(history, historyIndex, historyLive, draft.text, delta)
        historyIndex = moved.index
        historyLive = moved.live
        draft = TextFieldValue(moved.draft)
    }

    /**
     * 附加键行的分发。**只有真正属于 PTY 的控制键**才写字节（Ctrl-C / Esc / Tab / PgUp / PgDn）；
     * 编辑类（方向键 / Home / End / ↑ / ↓）与字符类（`- / | ~`）全在本地输入行上做 ——
     * 这样「点出来的」与「打出来的」是同一份文本，退格删得掉、回车提交的也是它。
     */
    fun onExtraKey(key: String) {
        when (key) {
            "Ctrl" -> ctrl = !ctrl
            // 需要写进 PTY 的控制键都在 TerminalInput.CONTROL_KEYS 里（Esc / Tab / PgUp / PgDn）
            in CONTROL_KEYS -> sendRaw(CONTROL_KEYS.getValue(key))
            "↑" -> moveHistory(-1)
            "↓" -> moveHistory(1)
            "←" -> moveCursor(-1)
            "→" -> moveCursor(1)
            "Home" -> draft = draft.copy(selection = TextRange(0))
            "End" -> draft = draft.copy(selection = TextRange(draft.text.length))
            else -> insertText(key)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        TerminalTopBar(
            state = state,
            foreground = foreground,
            dim = dim,
            onRestart = {
                // 已结束 / 未启动时点状态那一段＝重开：清空转录再触发一次启动（见 startKey）
                buffer = ""
                startKey++
            },
            onBack = onBack,
        )

        TerminalTranscript(
            listState = listState,
            buffer = buffer,
            foreground = foreground,
            focusRequester = focusRequester,
            // 视口尺寸 → PTY 尺寸：变了才写状态（PtySession.resize 自己也会去重）
            onGridChange = { rows, cols ->
                if (rows != ptyRows) ptyRows = rows
                if (cols != ptyCols) ptyCols = cols
            },
        ) { tail ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                if (tail.isNotEmpty()) {
                    Text(
                        // tail 按构造不含换行符（它是最后一个 \n 之后的内容），就是提示符那一行本身；
                        // maxLines = 1 只可能裁掉超长行折行后的后半截，裁不到行首的提示符。
                        text = tail,
                        style = TERMINAL_TEXT_STYLE,
                        color = foreground,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { onDraftChange(it) },
                    // 与转录区**同一份样式**（见 TERMINAL_TEXT_STYLE 的注释：两条路径的默认值不一样）
                    textStyle = TERMINAL_TEXT_STYLE.copy(color = foreground),
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
                            if (draft.text.isEmpty() && !focused && tail.isBlank() && state != TerminalState.STARTING) {
                                Text(
                                    text = "点这里输入命令",
                                    style = TERMINAL_TEXT_STYLE,
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
                    modifier = Modifier.fillMaxWidth().padding(vertical = DshSpacing.Lg),
                    style = TERMINAL_TEXT_STYLE,
                    color = Color(0xFFF25A5A),
                )
            }
        }

        TerminalExtraKeys(
            ctrl = ctrl,
            accent = accent,
            foreground = foreground,
            onKey = { onExtraKey(it) },
        )
    }
}
