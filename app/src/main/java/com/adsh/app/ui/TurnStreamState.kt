package com.adsh.app.ui

import com.adsh.app.core.agent.AgentLoop
import com.adsh.app.core.agent.estimateTokens
import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.llm.ChatEvent
import com.adsh.app.core.llm.SessionStats
import com.adsh.app.core.session.SessionBody
import com.adsh.app.core.session.SessionEvent

/**
 * 一轮对话里的**纯状态投影**（从 [ChatViewModel.startTurn] 搬出来的 104 行判定）。
 *
 * 为什么是无状态函数、而不是 state holder：[ChatUiState] 在 startTurn 之外还有 6+ 个写者
 * （clearQueued / cancel / applyConnection / refreshContext / refreshMessageTokens / setPlan），
 * holder 里存一份副本再写回去就会把那些写入覆盖掉。所以这里只做 `ChatUiState -> ChatUiState`，
 * CAS 形状（`_state.update { … }`）留在调用点。
 *
 * 帧级不变量（搬家时一个字没改；要改先读这几条）：
 *  - **一次事件 = 一次 update**：Step 定稿的「重读库 + 清流式尾巴」必须同帧，否则会出现
 *    「同一段正文既在库行里、又当尾巴挂着」的一帧 —— 第 111 轮删掉逐字比对去重后，判据只剩这一条；
 *  - 收尾那一次 update 要一口气换掉库行、流式尾巴与 stats（用户看到的是「不闪」）；
 *  - `liveTurnId` 只在开轮与收尾清空，`cancel()` 故意不清 —— 这里也不许顺手清；
 *  - 开轮**不复位 stats**：那是会话级累计，清掉会让状态栏数字归零。
 */

/** 开轮复位：一次换掉 12 个字段。[now] 由调用方给（测试才能打表）。 */
internal fun turnOpened(current: ChatUiState, now: Long): ChatUiState = current.copy(
    sending = true,
    // 新一轮还没落库：这一瞬间界面必须把上一轮维持成「已结束」，
    // 否则上一轮的最终回答会先缩回过程里、等用户消息落库再弹出来
    liveTurnId = null,
    error = null,
    streaming = "",
    reasoning = "",
    reasoningRunning = false,
    toolArgsFlowing = false,
    turnEvents = emptyList(),
    liveTurn = LiveTurn(),
    pendingAttachments = emptyList(),
    runStartedAt = now,
    // 新的一轮：重连条从零开始（上一轮的「已恢复」不该挂到这一轮上）
    connection = ConnectionState.Idle,
)

/** 这一轮的身份：**用户消息落库之后**才算（所以它单独一次 update）。 */
internal fun turnIdentityResolved(
    current: ChatUiState,
    messages: List<MessageEntity>,
    turnKey: Long?,
): ChatUiState = current.copy(
    messages = messages,
    // 这一轮的身份：人类消息或目标轮注入行（dsh 里两者都是 turn 的起点）。
    // 带 name 的 user 行（插话 / 权限切换通知）默认不是轮首，不能当这一轮的身份；
    // 唯一的例外是调用方点名的 turnKey（任务完成通知唤醒的那一轮）。
    liveTurnId = turnKey ?: lastOwnUserId(messages).takeIf { it > 0L },
)

/**
 * 收尾时的轮 / 步统计：**绝对值**口径（轮数与 AgentLoop 一致 —— 带 name 的 user 行不算一轮；
 * steps 是全库的 tool 行数，不是本轮步数，与 dsh 每轮上报绝对值一致）。
 */
internal fun turnStatsOf(current: SessionStats, messages: List<MessageEntity>): SessionStats = current.copy(
    turns = messages.count { isOwnUserMessage(it) },
    steps = messages.count { it.role == "tool" },
)

/** 收尾：一次 update 收口 10 个字段（库行、流式尾巴、轮内事件与 stats 同帧换掉）。 */
internal fun turnFinished(
    current: ChatUiState,
    messages: List<MessageEntity>,
    stats: SessionStats,
): ChatUiState = current.copy(
    sending = false,
    liveTurnId = null,
    streaming = "",
    reasoning = "",
    reasoningRunning = false,
    toolArgsFlowing = false,
    turnEvents = emptyList(),
    liveTurn = LiveTurn(),
    messages = messages,
    stats = stats,
)

/**
 * 事件 → 状态：一轮里除「掉线重连」（归 setConnection 的计时器）与「读库」（调用方传 [messages]）
 * 之外的全部判定。
 *
 * [messages] 只有两类事件用得上：Appended(Step) 与 InboxClaimed —— 调用方读库后传进来，其余传 null。
 * [modelLabel] 故意是惰性的：除 Failed 之外的分支一次都不会读它（Delta 是每 token 一次的事件，
 * 这里绝不能去读 SharedPreferences）。
 */
internal fun turnProjected(
    current: ChatUiState,
    event: ChatEvent,
    messages: List<MessageEntity>? = null,
    modelLabel: () -> String = { "" },
): ChatUiState = when (event) {
    is ChatEvent.Delta -> current.copy(
        // 正文一开始，思考就结束了（dsh 的 ReasoningRow：running = 本块是最后一块）
        // 又说回正文了 ⇒ 参数那条「正文已写完」的判据作废，撤回补齐
        streaming = current.streaming + event.text,
        reasoningRunning = false,
        toolArgsFlowing = false,
    )
    is ChatEvent.Reasoning -> current.copy(
        // 本步最后流出来的又是思考 → 思考行回到「运行中」的形态
        reasoning = current.reasoning + event.text,
        reasoningRunning = true,
    )
    // 模型开始写工具调用了：**思考在这一刻就结束**，不必等工具行出现。
    // 少了这一条，思考行的摘要要等这一步落库（工具行出现）才从「最后一行」
    // 变回「第一行」—— 用户看到的就是「思考完了没反应，等工具调用蹦出来时才一起变」。
    is ChatEvent.ToolCallDelta -> current.copy(
        // 模型开始写工具调用的参数：这一步的正文已经写完（见 toolArgsFlowing 的注释）
        reasoningRunning = false,
        toolArgsFlowing = true,
    )
    is ChatEvent.Appended -> turnAppended(current, event.event, messages)
    // 收件箱认领（dsh 的 preStep claim）：行已经写进库，重读一次把它们画出来。
    is ChatEvent.InboxClaimed -> current.copy(messages = messages ?: current.messages)
    is ChatEvent.Stats -> current.copy(stats = current.stats + event.stats)
    // 重连会重新生成这一步：把上一次尝试的流式尾巴清掉
    // （dsh 的「新的一代以完整快照开场」；这里没有宿主可补，只能丢弃）
    is ChatEvent.StreamReset -> current.copy(streaming = "", reasoning = "", reasoningRunning = false)
    is ChatEvent.Failed -> current.copy(error = event.message + imageRouteHint(current, modelLabel()))
    // Reconnecting / Reconnected 归 setConnection；Usage 归 AgentLoop 的 token 账本
    else -> current
}

/**
 * 会话日志追加了一条（dsh 的 session/event 广播）：`turnEvents` 与 `liveTurn`**同帧**折叠，
 * 并按 body 形态补几处状态（每一支的注释就是它的判据）。
 */
private fun turnAppended(
    current: ChatUiState,
    appended: SessionEvent,
    messages: List<MessageEntity>?,
): ChatUiState {
    val next = current.turnEvents + appended
    val extras = when (val body = appended.body) {
        is SessionBody.ToolCall -> current.copy(
            // 参数已经流完、工具开始跑了：这条判据完成使命
            toolArgsFlowing = false,
        )
        is SessionBody.ToolResult -> current.copy(
            // 上下文占用：这一条结果马上就会落库（AgentLoop 用的是同一个 take(MAX_TOOL_CONTENT_CHARS)），
            // 先按落库口径估一笔；紧接着的 Step 事件会用库里的权威值整体覆盖，不会漂
            context = current.context.copy(
                messages = current.context.messages +
                    estimateTokens(body.output.take(AgentLoop.MAX_TOOL_CONTENT_CHARS)),
            ),
        )
        is SessionBody.Step -> current.copy(
            messages = messages ?: current.messages,
            streaming = "",
            reasoning = "",
            reasoningRunning = false,
            // 这一步定稿了：正文尾巴交给库里那一行，参数那条判据跟着复位
            toolArgsFlowing = false,
        )
        else -> current
    }
    return extras.copy(turnEvents = next, liveTurn = foldLiveTurn(next))
}

/**
 * 失败文案后面补的「可识别图片」指路（第八十三轮，用户点名）。
 *
 * 只在**这一轮真的带了图片附件**时才追加：模型目录里没收录的新模型默认按「能收图」发，
 * 万一它其实不识图，提供方会直接报错 —— 用户需要一个立刻能操作的出口，而不是去猜。
 * 没带图的失败一个字都不加（免得把网络 / 余额问题说成「模型不识图」）。
 *
 * [modelLabel] 由调用方给（原来是 `settings.modelLabel()`）—— 于是这条判定完全纯。
 */
internal fun imageRouteHint(state: ChatUiState, modelLabel: String): String {
    val turn = lastOwnUserMessage(state.messages) ?: return ""
    if (!com.adsh.app.core.agent.hasImageAttachment(turn.attachmentsJson)) return ""
    return "\n\n" + com.adsh.app.core.agent.imageRouteHintText(modelLabel)
}
