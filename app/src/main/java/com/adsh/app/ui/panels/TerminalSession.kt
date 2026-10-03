package com.adsh.app.ui.panels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.adsh.app.runtime.termux.PtySession
import com.adsh.app.runtime.termux.ShellLaunch

/**
 * 终端页的**会话侧**（R10 从 538 行的 TerminalPanel 里按「状态与 I/O」/「渲染」切出来的第一刀）：
 * PTY 的启动与读循环、输出清洗流水线、视口算出来的网格，以及只被这些逻辑用到的常量。
 * 这个文件里一行 Compose UI 都没有 —— 三块布局在 TerminalParts.kt，页面状态机在 TerminalPanel.kt。
 *
 * 有单测盯着：[TerminalText]（跨 read 的多字节 / 转义序列 / 进度条重写）、[transcriptChunks]、
 * [terminalGrid]（视口 → PTY 行列）。
 */

/** 终端会话的生命周期（顶栏那行状态文案与「已结束 · 点此重开」都看它） */
internal enum class TerminalState { STARTING, RUNNING, EXITED, FAILED }

/** 视口尺寸（px）算出来的 PTY 网格：终端程序排版用的行列数 */
internal data class PtyGrid(val rows: Int, val cols: Int)

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
internal fun terminalGrid(
    widthPx: Int,
    heightPx: Int,
    charWidthPx: Float,
    lineHeightPx: Float,
    horizontalPaddingPx: Float,
): PtyGrid {
    val cols = ((widthPx - horizontalPaddingPx) / charWidthPx).toInt()
        .coerceIn(MIN_PTY_COLS, MAX_PTY_COLS)
    val rows = (heightPx / lineHeightPx).toInt()
        .coerceIn(MIN_PTY_ROWS, MAX_PTY_ROWS)
    return PtyGrid(rows = rows, cols = cols)
}

/**
 * 等宽字体一个字符的宽度（px）：终端列数就是拿它算的（12sp 等宽 = 每列 23.4px）。
 * 测量拿不准时按 1px 算，免得除零。
 */
internal fun terminalCharWidthPx(fontSizePx: Float): Float =
    android.graphics.Paint().apply {
        typeface = android.graphics.Typeface.MONOSPACE
        textSize = fontSizePx
    }.measureText("M").takeIf { it > 0f } ?: 1f

/** 还没测量出视口时先按这个尺寸起会话（随后立刻按实际视口 resize） */
internal const val DEFAULT_PTY_ROWS = 40
internal const val DEFAULT_PTY_COLS = 100

/** PTY 尺寸的上下限：太小的值会让程序排版崩掉，太大的值多半是测量出错 */
private const val MIN_PTY_ROWS = 5
private const val MAX_PTY_ROWS = 200
private const val MIN_PTY_COLS = 20
private const val MAX_PTY_COLS = 300

/**
 * 起一个 PTY 会话。
 *
 * **必须在 IO 线程上做**：`PtySession` 的构造走 `fork/exec`（Pty.createSubprocess），
 * 在主线程上就是一次真实的进程创建 + PTY 配置 —— 之前它跑在 `LaunchedEffect` 的
 * Main 调度器里，正好卡在整页 220ms 的滑入动画中间，点「终端」就是一顿。
 */
internal suspend fun openTerminalSession(launch: ShellLaunch, rows: Int, cols: Int): PtySession =
    withContext(Dispatchers.IO) {
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
            rows = rows,
            cols = cols,
        )
    }

/**
 * PTY 输出写回 Compose 状态的最小间隔（ms，≈一帧）。
 * 每条 chunk 都写一次的话，一屏几十行时每个 chunk 都会把整篇正文重新测量一遍。
 * 这是「最快多久写一次」，**不是**「窗口里的输出就不要了」：窗口末尾那一片照样要在
 * PTY 安静的这一刻写进去，否则提示符会一直停在屏幕外（见 read 循环里 push 的注释）。
 */
private const val BUFFER_PUSH_MS = 16L

/**
 * 读 PTY 的输出：清洗之后攒到一帧的量写回界面（[onFrame]），会话结束（EOF / 出错）时回调 [onExited]。
 *
 * 跑在自己的守护线程上（原来是就地 Thread { … }.apply { isDaemon = true }.start()）：
 * 读循环会一直阻塞在 read 上，不能占着 Compose 的协程调度器。
 */
internal fun startPtyPump(
    session: PtySession,
    onFrame: (String) -> Unit,
    onExited: () -> Unit,
) {
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
            val more = runCatching { session.input.available() }.getOrDefault(0)
            if (!force && now - lastPush < BUFFER_PUSH_MS && more > 0) {
                dirty = true
                return
            }
            lastPush = now
            dirty = false
            onFrame(terminal.snapshot())
        }
        try {
            while (true) {
                val read = session.input.read(chunk)
                if (read < 0) break
                terminal.feed(chunk, read)
                push(force = false)
            }
        } catch (_: Throwable) {
        } finally {
            if (dirty) push(force = true)
            onExited()
        }
    }.apply { isDaemon = true }.start()
}

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
