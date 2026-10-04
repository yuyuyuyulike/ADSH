package com.adsh.app.island

import com.adsh.app.core.tools.ApprovalChannel
import com.adsh.app.core.tools.UserQuestionChannel
import com.adsh.app.ui.ChatUiState
import com.adsh.app.ui.toolRowSummary

/**
 * 灵动岛那五格（用户口径：agent **实际**在干什么）。
 *
 * | 状态 | 显示 |
 * |---|---|
 * | [THINKING] | 「思考」 |
 * | [TOOL] | 「#代码」（正在调用工具） |
 * | [ASK] | 卡住的**工具名**（ask_user / 等你批准的那个工具） |
 * | [OUTPUT] | 「输出中」 |
 * | [DONE] | 「已结束」（跑完之后还挂 [ISLAND_DONE_DWELL_MS]） |
 *
 * 画它的是 **MIUI 的超级岛**（见 IslandService：把前台服务通知做成媒体通知，由系统画在状态栏那一条里），
 * 所以这一层只负责「哪一格 + 哪一行文字」；样式 / 动效 / 点击展开都归系统。
 */
internal enum class IslandPhase { THINKING, TOOL, ASK, OUTPUT, DONE }

/** 岛要画的一帧：[phase] 是右半格，[lines] 是展开态那两三行（最多 [ISLAND_DETAIL_LINES] 行） */
internal data class IslandWork(val phase: IslandPhase, val lines: List<String> = emptyList())

/**
 * 卡在「等你回答」这一步时的详情。ask_user_question 工具的 [label] 就是**注册给模型的那个
 * 工具名**；权限确认卡住时是**等批准的那个工具**的名字（扫一眼岛就知道卡在哪）。
 */
internal data class IslandWaiting(val label: String, val lines: List<String> = emptyList())

/** 展开态显示几行（用户口径：最新的两三行就够） */
internal const val ISLAND_DETAIL_LINES = 3

/** 每行最多几个字符（岛的宽度只有屏幕顶部那一小块，超了按字符截断加省略号） */
internal const val ISLAND_LINE_CHARS = 48

/**
 * 「等你回答」那一格的标签：**用户点名的写法**（选项是「直接显示工具名」，原话就是 ask_user）。
 *
 * 注册给模型的完整名字是 ask_user_question（见 TurnRail 的 specOf）；岛上用短名是因为收起态的
 * 宽度**固定 150dp**（用户口径：宽度不跟着文案变），完整名字在那里放不下会被截成省略号。
 */
internal const val ASK_TOOL_LABEL = "ask_user"

/** 每一格的颜色：等模型的两格是白的，等你回答是琥珀，跑完是绿的（媒体岛左半格那张图的着色） */
internal fun phaseTint(phase: IslandPhase): Int = when (phase) {
    IslandPhase.ASK -> 0xFFFFC66D.toInt()
    IslandPhase.DONE -> 0xFF7BE0A3.toInt()
    else -> 0xFFFFFFFF.toInt()
}

/** 那一格的文案（用户口径的五格）—— 通知标题就是它，MIUI 的岛上显示的也是它 */
internal fun phaseLabel(phase: IslandPhase): String = when (phase) {
    IslandPhase.THINKING -> "思考"
    IslandPhase.TOOL -> "#代码"
    IslandPhase.ASK -> ASK_TOOL_LABEL
    IslandPhase.OUTPUT -> "输出中"
    IslandPhase.DONE -> "已结束"
}

/**
 * 从正文/思考里只留最后这么多字符再切行。
 *
 * 流式正文可以有几万字符，而岛跟着状态重画：整串 lines() 每帧切一遍是白烧 CPU。切之前先
 * 砍尾巴，代价是「第一行可能是半行」—— 那行本来就滚出屏幕了，不影响观感。
 */
internal const val ISLAND_TAIL_CHARS = 2000

/** 一轮跑完（sending 落回 false）之后，岛还挂多久显示「已结束」 */
internal const val ISLAND_DONE_DWELL_MS = 1200L

/** 代码围栏那一行（展开态不显示它） */
private const val FENCE_LINE = "```"

/**
 * 取一段文本的最后几行当展开态：去空行、去代码围栏、每行裁到 [ISLAND_LINE_CHARS]。
 * 纯函数（单测直接打 IslandStatusTest）。
 */
internal fun islandLines(text: String, limit: Int = ISLAND_DETAIL_LINES): List<String> {
    if (text.isEmpty()) return emptyList()
    val tail = if (text.length <= ISLAND_TAIL_CHARS) {
        text
    } else {
        val cut = text.takeLast(ISLAND_TAIL_CHARS)
        val newline = cut.indexOf('\n')
        if (newline >= 0) cut.substring(newline + 1) else cut
    }
    return tail.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != FENCE_LINE }
        .toList()
        .takeLast(limit)
        .map { clip(it) }
}

/** 单行裁剪（展开态那一行、以及工具行的摘要都用它） */
internal fun clip(line: String): String =
    if (line.length <= ISLAND_LINE_CHARS) line else line.take(ISLAND_LINE_CHARS - 1) + "…"

/**
 * 这一份界面状态该让岛显示什么。**纯函数**（单测直接打 IslandStatusTest）。
 *
 * null = 没有正在跑的一轮 —— 服务据此进入「已结束」的收尾期（[ISLAND_DONE_DWELL_MS] 后收岛）。
 *
 * 优先级 = 「用户此刻最该知道哪件事」：
 *  1. [IslandWaiting]（ask_user_question / 等批准）：agent 根本动不了，别的都不重要；
 *  2. 有工具在跑（[LiveCall.running]）：正在执行；
 *  3. 模型正在吐**工具调用的参数**（[ChatUiState.toolArgsFlowing]）：这一格按用户口径也算
 *     「在调用工具」—— 参数可能是几千字符的 run_code 程序，按「输出中」显示与实际动作不符；
 *  4. [ChatUiState.reasoningRunning]：在思考；
 *  5. 正文已经在流：输出中；
 *  6. 一轮刚起、什么都还没来：算思考（模型还没吐第一个字，用户看到的是「在等它」）。
 */
internal fun islandWorkOf(state: ChatUiState, waiting: IslandWaiting? = null): IslandWork? {
    if (!state.sending) return null
    val running = state.liveTurn.calls.lastOrNull { it.running }
    return when {
        waiting != null -> IslandWork(IslandPhase.ASK, waiting.lines)
        running != null -> IslandWork(IslandPhase.TOOL, toolLines(running.name, running.arguments))
        state.toolArgsFlowing -> IslandWork(IslandPhase.TOOL, islandLines(state.streaming))
        state.reasoningRunning -> IslandWork(IslandPhase.THINKING, islandLines(state.reasoning))
        state.streaming.isNotEmpty() -> IslandWork(IslandPhase.OUTPUT, islandLines(state.streaming))
        else -> IslandWork(IslandPhase.THINKING, islandLines(state.reasoning))
    }
}

/**
 * 卡住的那一步是谁：提问通道优先（它一定在等用户），否则看权限确认。两个通道都是「问用户」
 * 这一个语义，岛上不区分（用户口径第三格就是「等你回答的工具」）。
 */
internal fun islandWaitingOf(
    question: UserQuestionChannel.Pending?,
    approval: ApprovalChannel.Pending?,
): IslandWaiting? = when {
    question != null ->
        IslandWaiting(ASK_TOOL_LABEL, islandLines(question.questions.firstOrNull()?.question.orEmpty()))
    approval != null -> IslandWaiting(approval.toolName)
    else -> null
}

/**
 * 工具那几行：工具名 + 会话里那一行同款的摘要（[toolRowSummary]，dsh 的 deriveSummary）。
 * 摘要用**同一个函数**算，于是岛上的字与对话里那一行永远一致。
 */
private fun toolLines(name: String, arguments: String): List<String> {
    val summary = runCatching { toolRowSummary(name, arguments) }.getOrDefault("")
    return if (summary.isBlank() || summary == name) listOf(clip(name)) else listOf(clip(name), clip(summary))
}
