package com.adsh.app.ui.chat

import com.adsh.app.core.llm.SessionStats

/**
 * 「正在跑的那一轮」的状态归属。dsh 里一条会话的实时状态**跟着会话走**：换的只是「现在看哪一条」，正在跑
 * 的那一条继续跑。这里用**登记表**模型（[ChatViewModel.updateTurn] + [liveRuns]）：一轮在跑期间它的状态
 * 永远有一份住在 `liveRuns[会话]` 里，「看哪一条」只是一层投影 —— **不要回到「切走时抓一帧」**。
 */

/**
 * 一条会话「没有正在跑的一轮」时的样子：轮内痕迹整块复位。**必须连 turnEvents 一起清** ——
 * 那份事件日志属于某一条会话的某一轮，跟着视图换会话就会串（上一条的工具卡片长在新会话里）。
 * 例外：`messages` / `stats` / `workspaceId` / `planMode` 等会话级字段由调用方从库里重新填；`compacting` 不动。
 */
internal fun ChatUiState.resetTurnFields(queuedCount: Int = 0): ChatUiState = copy(
    streaming = "",
    reasoning = "",
    reasoningRunning = false,
    toolArgsFlowing = false,
    sending = false,
    liveTurnId = null,
    turnEvents = emptyList(),
    liveTurn = LiveTurn(),
    error = null,
    runStartedAt = 0L,
    queuedCount = queuedCount,
)

/**
 * 回到「没有会话」的空态（dsh 的 clearMain）：删掉当前会话、或新会话没有可落的工作区时。
 *
 * 空态 = 没有会话、也没有工作区归属 —— 界面因此回到首页，输入框切成「选择工作区」的惰性态
 * （见 ChatScreen 的 workspaceTrigger）。会话级字段整块清掉、轮内字段走 [resetTurnFields]；
 * 与具体会话无关的偏好（主题 / 字号 / 模型 / 权限…）原样保留。
 */
internal fun ChatUiState.clearedMain(): ChatUiState = copy(
    conversationId = null,
    messages = emptyList(),
    workspaceId = null,
    workspacePath = null,
    stats = SessionStats(),
    // 空态没有胶囊，也就没有「入场动画」要武装（下一条会话载入时按它的统计重新决定）
    dockEntranceArmed = false,
    planMode = false,
    context = ContextUsage(),
    pendingAttachments = emptyList(),
    pendingSteering = emptyList(),
    compacting = false,
).resetTurnFields()

/**
 * 把「那一条会话正在跑的那一轮」盖到刚摆好的视图状态上（切回来接上，见文件头）。
 *
 * [live] 为 null（这条会话没有在跑的轮）= 原样返回复位后的状态 —— 调用方不需要先判空。
 *
 * **不盖 `messages`**：已定稿的行以**库**为准（切回来时刚读过一次），流式尾巴在 [streaming] 里，
 * 两者一起才拼出完整的一轮 —— 库里的比 liveRuns 里那份更全（后者只在 Step / InboxClaimed 时重读）。
 */
internal fun ChatUiState.withLiveRun(live: ChatUiState?): ChatUiState = if (live == null) {
    this
} else {
    copy(
        streaming = live.streaming,
        reasoning = live.reasoning,
        reasoningRunning = live.reasoningRunning,
        toolArgsFlowing = live.toolArgsFlowing,
        sending = live.sending,
        liveTurnId = live.liveTurnId,
        turnEvents = live.turnEvents,
        liveTurn = live.liveTurn,
        runStartedAt = live.runStartedAt,
        queuedCount = live.queuedCount,
        // 统计与上下文占用是**会话级**投影，但一轮在跑时它们比库里的新：库里的 stats 只在收尾写
        // （见 ChatViewModel.finishTurn），切回来读库会把输入框下面那三个胶囊换成上一轮的旧值
        // （首轮就是零值 = 整排消失），要等下一次 ChatEvent.Stats 才回来。跟着登记表盖回来。
        stats = live.stats,
        context = live.context,
    )
}
