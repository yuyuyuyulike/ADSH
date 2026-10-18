package com.adsh.app.ui.chat

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
    connection = ConnectionState.Idle,
    runStartedAt = 0L,
    queuedCount = queuedCount,
)

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
        connection = live.connection,
        runStartedAt = live.runStartedAt,
        queuedCount = live.queuedCount,
    )
}
