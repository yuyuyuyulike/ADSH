package com.adsh.app.ui

/**
 * 「正在跑的那一轮」的状态归属（第 181 轮，用户口径：参照 dsh）。
 *
 * dsh 里一条会话的实时状态**跟着会话走**：换的是「现在看哪一条」，正在跑的那一条在服务端继续跑，
 * 切回来看到的就是它此刻的样子。这里照做 —— 但真机上「切走再切回来 = 那一轮停了」反复出现，
 * 根因不在渲染，而在**状态归谁**：
 *
 *  - 旧实现把「正在跑那一轮的样子」存进 `liveRuns`，靠 [ChatViewModel.stashLiveRun] 在切走时**抓一帧**。
 *    抓的判据是「_state.conversationId == 正在跑的那条」；而**命中正文缓存**的那条快路径会先把
 *    `conversationId` 同步改成目标会话，紧接着才起协程跑 openConversation —— 于是 openConversation
 *    开头那次 stash 看到的「当前会话」已经是目标会话：它把**刚读出来的库快照**（sending=false、
 *    streaming=""）写进了 `liveRuns[目标]`，正在流的那一份被就地覆盖。切回正在跑的会话时
 *    restoreLiveRun 读到的正是这份被覆盖的垃圾 —— 正文尾巴没了、工具行没了、岛也不再亮，
 *    看着就是「切一下它停了」（真实的那一轮其实还在后台跑）。
 *  - 抓帧天然还会漏：任何没经过 stash 的入口（openWorkspace / fork / delete 后的回落）都把
 *    实时状态留在原地。
 *
 * 所以这里换掉「抓帧」模型，改成**登记表**模型（[ChatViewModel.updateTurn] + [liveRuns]）：
 * 一轮在跑期间，它的状态永远有一份住在 `liveRuns[会话]` 里，看没看它都在；
 * 「看哪一条」只是一层投影 —— 切过去时把这些字段**盖**到刚从库里读出来的状态上。
 * 这两个函数就是那两件事，纯函数、桌面上直接测（[LiveRunStateTest]）。
 */

/**
 * 一条会话「没有正在跑的一轮」时的样子：轮内痕迹整块复位。
 *
 * **必须连 turnEvents 一起清**：那份事件日志属于**某一条会话的某一轮**，跟着视图换会话就会串 ——
 * 切到新会话时留着上一条的工具行，用户看到的是「别人的工具卡片长在我的会话里」
 * （第 180 轮报的「工作提示跑到新会话里」就是这一族）。
 *
 * 故意的例外：
 *  - `messages` / `stats` / `workspaceId` / `planMode` …是**会话级**的，由调用方从库里重新填；
 *  - `liveTurnId` 清掉：它是「哪一轮是活的」，属于轮内；
 *  - `compacting` 不动：压缩是一次独立的后台动作，切会话不该把它抹掉。
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
