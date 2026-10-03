package com.adsh.app.ui.panels

/**
 * 终端**输入行**的纯逻辑：历史翻阅、回车提交、附加键分派。
 *
 * 为什么单独一份：这些决定全是「给定状态与按键，下一个状态是什么」，与 Compose 无关，
 * 却原来长在 `TerminalPanel` 这个 548 行的 `@Composable` 里 —— 那是全库最长的手写函数，
 * 而它一行都测不了（纯 JVM 单测起不了 Compose，也没有 Robolectric）。
 * 拆出来之后 [TerminalInputTest] 直接打表盯着它们：这几个地方错一个，用户看到的是
 * 「退格删不掉」「↑ 翻不出历史」「Ctrl-C 不起作用」。
 *
 * 留在 Composable 里的只有「把结果写进 state」这一步。
 */

/** ↑ / ↓ 翻本地历史的结果（和「当前在历史里翻到第几条」一起返回） */
internal data class HistoryMove(val draft: String, val index: Int, val live: String)

/**
 * 翻一条历史。
 *
 * @param index 当前索引，-1 表示「不在历史里」（正在命令行上编辑）
 * @param live 开始翻历史之前手里那半行 —— ↓ 翻回底时还给它
 * @return 新的一行正文与新的索引；历史为空时原样返回
 */
internal fun nextHistory(
    history: List<String>,
    index: Int,
    live: String,
    current: String,
    delta: Int,
): HistoryMove {
    if (history.isEmpty()) return HistoryMove(current, index, live)
    var idx = index
    var restored = live
    if (idx < 0) {
        // 还没进历史：只有往回翻（delta < 0）才进去；往下翻什么也不做
        if (delta > 0) return HistoryMove(current, index, live)
        restored = current
        idx = history.size - 1
    } else {
        idx += delta
        if (idx >= history.size) return HistoryMove(restored, -1, restored)   // 翻过底：回到那半行
        if (idx < 0) idx = 0
    }
    return HistoryMove(history[idx], idx, restored)
}

/** 回车提交：返回「这次要送进 PTY 的每一行」 */
internal data class DraftCommit(val lines: List<String>, val remaining: String)

/**
 * 输入行的取值：**换行即执行**。
 *
 * 一次可能落进来多个换行（粘贴、输入法上屏、以及最后那个回车），换行之前的每一段都是一条命令，
 * 最后一段留在输入行里继续编辑。空输入行上单独一个回车也要提交一次（就是「回车」本身）。
 */
internal fun commitDraft(updated: String): DraftCommit {
    if (!updated.contains('\n')) return DraftCommit(emptyList(), updated)
    val parts = updated.split('\n')
    val lines = parts.dropLast(1)
    return DraftCommit(lines, parts.last())
}

/** 把一条命令记进历史（连续重复的不记；空行不记） */
internal fun historyWith(history: List<String>, line: String): List<String> =
    if (line.isNotBlank() && history.lastOrNull() != line) history + line else history

/** 附加键里需要**直接写进 PTY** 的控制键（行规程负责把 \x03 变成 SIGINT） */
internal val CONTROL_KEYS: Map<String, String> = mapOf(
    "Esc" to "\u001b",
    "Tab" to "\t",
    "PgUp" to "\u001b[5~",
    "PgDn" to "\u001b[6~",
)

/** Ctrl 粘滞键按下之后，落进输入行的下一个字符要送的控制字节（Ctrl-C = \x03） */
internal fun controlByte(ch: Char): String = (ch.uppercaseChar().code and 0x1F).toChar().toString()

/** 不需要时把字符插到光标处的替换区间（含选中态时替换选中内容） */
internal fun insertRange(
    text: String,
    selStart: Int,
    selEnd: Int,
    inserted: String,
): Pair<String, Int> {
    val start = minOf(selStart, selEnd).coerceIn(0, text.length)
    val end = maxOf(selStart, selEnd).coerceIn(0, text.length)
    val next = text.replaceRange(start, end, inserted)
    return next to (start + inserted.length)
}

/** 光标左右移一格，夹在 [0, length] 内 */
internal fun moveCursorTo(pos: Int, delta: Int, length: Int): Int = (pos + delta).coerceIn(0, length)

/**
 * 这一次编辑**插进来的第一个字符**；没有插入（删除 / 替换成更短的）就是 null。
 *
 * 给 Ctrl 粘滞键用：它要的是「光标处落进来的那一个字符」，**不能假设插在末尾** ——
 * 旧实现取 `updated.text[draft.text.length]`，光标在行中时拿到的是别人家的字符，
 * 于是「先点 Ctrl 再按 X」会按那个字符发控制字节（行尾正好是 c 就发成了 Ctrl-C = SIGINT）。
 * 取两个串的共同前缀之后那一位即可：纯插入时它就是插入点。
 */
internal fun firstInsertedChar(before: String, after: String): Char? {
    if (after.length <= before.length) return null
    var i = 0
    while (i < before.length && before[i] == after[i]) i++
    return after.getOrNull(i)
}

/**
 * 输入行这一次变化要做的事（纯判定）：[TerminalPanel.onDraftChange] 只负责把它落成副作用。
 *
 * 以前这三条分支（粘滞 Ctrl / 换行即执行 / 普通编辑）连状态写入一起长在 Composable 里 ——
 * 于是「Ctrl 取的是哪一个字符」「空行要不要提交」这类判定只能靠真机试。搬出来之后它们是一张表：
 *  - [Control]：粘滞 Ctrl 消费这一次输入，控制字节进 PTY，输入行**保持原样**；
 *  - [Submit]：换行即执行 —— 换行之前的每一段都送进 PTY，最后一段留在行里继续编辑；
 *    单独一个回车（只有一行且为空）也算一次提交（就是「回车」本身），空行夹在多行里则不提交；
 *  - [Typed]：普通编辑，输入行直接用输入法给的值（**保留光标与组合区**，别只写 text）。
 */
internal sealed interface DraftOutcome {
    /** 粘滞 Ctrl：[bytes] 写进 PTY（Ctrl-C = \u0003 → 行规程给 SIGINT） */
    data class Control(val bytes: String) : DraftOutcome

    /** 换行即执行：[lines] 逐行写进 PTY，[remaining] 留在输入行里 */
    data class Submit(val lines: List<String>, val remaining: String) : DraftOutcome

    /** 普通编辑：输入行就是 updated */
    data object Typed : DraftOutcome
}

/**
 * 输入行变化的判定（纯函数）。[ctrl] = 附加键行的粘滞 Ctrl 是否按下。
 *
 * Ctrl 只看「这次是否**插进来**了字符」，取的必须是**插入点**那一个（见 [firstInsertedChar]）——
 * 光标在行中时它不等于末尾那一位，取错的后果是按用户的键发出别的控制字节（R38 修的那个 bug）。
 */
internal fun draftOutcome(before: String, after: String, ctrl: Boolean): DraftOutcome {
    if (ctrl && after.length > before.length) {
        return DraftOutcome.Control(firstInsertedChar(before, after)?.let { controlByte(it) } ?: "")
    }
    val commit = commitDraft(after)
    if (commit.lines.isEmpty()) return DraftOutcome.Typed
    // 单独一个回车（commit.lines 只有一行）即使为空也要提交；多行里的空行不提交（粘贴里的空行）
    val lines = if (commit.lines.size == 1) commit.lines else commit.lines.filter { it.isNotEmpty() }
    return DraftOutcome.Submit(lines, commit.remaining)
}
