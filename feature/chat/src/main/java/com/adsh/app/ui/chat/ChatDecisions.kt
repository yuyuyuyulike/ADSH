package com.adsh.app.ui.chat

import com.adsh.app.core.data.MessageEntity

// 会话页的**纯判定与常量**（从 ChatScreen 搬出来：主函数之外剩下的全是这些）。
// 滚动那几条判定在 AutoScroll.kt，这里的常量就是它们的输入。

/** 会话流的 item key：一轮被拆成了多行，每一行都要有自己的稳定 key */
internal fun transcriptKey(item: ChatItem): String = when (item) {
    is ChatItem.SystemPrompt -> "sysprompt-" + item.key
    is ChatItem.Context -> "context-" + item.key
    is ChatItem.User -> "user-" + item.key
    is ChatItem.Compact -> "compact-" + item.key
    is ChatItem.TurnFold -> "turn-" + item.view.key + "-fold"
    is ChatItem.TurnEntry -> "turn-" + item.view.key + "-" + turnEntryKey(item.view, item.index)
    is ChatItem.TurnAnswer -> "turn-" + item.view.key + "-answer#" + item.chunk
    // 思考正文的块：头仍是 TurnEntry 那一行的 key，正文块在后面加后缀（见 reasoningKey）
    is ChatItem.ReasoningBody -> reasoningKey(item.view, item.index) + "-body#" + item.chunk
    is ChatItem.TurnTail -> "turn-" + item.view.key + "-tail"
    // dsh 的 RunningStatus 一行（同一时刻最多一条，key 固定）
    is ChatItem.Running -> "running"
}

/**
 * 思考行的稳定 key：既是 [ChatItem.ReasoningBody] 行 key 的前缀，也是界面里展开状态的 map 键。
 *
 * 与 [ChatItem.TurnEntry] 的 item key 同源（[turnEntryKey] 的「同类第几个」口径），所以正文块
 * 只会在它后面追加 `-body#<chunk>`，流式追加时前面那些行的 key 不变。
 */
internal fun reasoningKey(view: TurnView, index: Int): String =
    "turn-" + view.key + "-" + turnEntryKey(view, index)

/**
 * 这一次消息区手势结束时要不要「点空白处收起输入状态」（clearFocus）。规则只有一条：
 * **只有「短按」（= 点击）才收键盘** —— 长按选中时焦点归 SelectionContainer，抬手若照样 clearFocus，
 * 4ms 后焦点就被抢走、选中当场清空（「长按选不中、复制不了」）；长按与滑动都不碰焦点。
 */
internal fun shouldClearComposerFocus(
    dragged: Boolean,
    heldMillis: Long,
    longPressTimeoutMillis: Long,
): Boolean = !dragged && heldMillis < longPressTimeoutMillis

/** 流式采样节拍（ms）：30fps 量级的重绘节流 */
internal const val STREAM_TICK_MS = 33L

/**
 * 贴底的容差（px）：只吸收取整误差。它同时用于判定「读者是不是在底部」（[ChatScrollState.sampled]）
 * 与「要不要补钉」（[KeepPinnedEffect]）—— 后者不能放大：一行子调用才 22dp，容差一大就会把
 * 「半行被切在视口外」当成已贴底，直到下一次更大变化才补。
 */
internal const val PIN_SLOP_PX = 1

// 对话流节点模型（dsh 的 Chat Node）与整形逻辑在 TurnList.kt


/**
 * 「用户自己发的消息」：`role = user` 且 **没有 name**。
 *
 * 通知（任务完成通知的两种形态、插话、权限预设切换…）在库里也是 `role = user`，但它们都带 `name`，
 * 不是人打的字。这条判定必须是**唯一一份具名函数**（会话页的 ownInput、这一轮的身份、统计口径、
 * 附件指路、AgentLoop 的轮次统计、标题生成都调它）；失效的后果用户可见：一条后台任务通知
 * 落库就把正在翻历史的读者一把拽回底部。
 */
internal fun isOwnUserMessage(message: MessageEntity): Boolean =
    message.role == "user" && message.name == null

/** 最后一条真的用户消息；没有就是 null。 */
internal fun lastOwnUserMessage(messages: List<MessageEntity>): MessageEntity? =
    messages.lastOrNull { isOwnUserMessage(it) }

/** 最后一条真的用户消息的 id；没有就是 0（id 由库自增、从 1 起，所以 0 就是「没有」）。 */
internal fun lastOwnUserId(messages: List<MessageEntity>): Long =
    lastOwnUserMessage(messages)?.id ?: 0L
/**
 * 一条**已提交、还没被认领**的插话回显（dsh 的 `pendingSubmissions` 里 `placement = "steering"` 的那些）。
 *
 * 为什么要有它：dsh 在**按下发送的那一帧**就同步往客户端内存里塞一条回显
 * （`session.beginSubmission` → `markDirty`，见 dsh-api-session-controller 的 client），
 * 会话节点则要等下一步开头被收件箱认领才落库 —— 用户因此「点完立刻看到」。
 * 没有这条回显，插话要等当前工具跑完、收件箱认领落库之后才出现。
 *
 * 回显只在 ViewModel 内存里：刷新 / 重开 App 之后只信库里那一条（与 dsh 同一条取舍）。
 */
data class PendingSteer(
    /** 一对一身份，只在本机回显列表里用（dsh 是提交时铸的 requestId） */
    val id: Long,
    val text: String,
    val time: Long,
    /** 附件描述（与落库那一行同一种 JSON）；算出来之前是 null —— 算它要读文件，见 steer() */
    val attachmentsJson: String? = null,
)

/**
 * 还该画哪几条回显：**认领落库之后就把对应的回显去掉**（dsh 的渲染期去重 ——
 * 它按 `source.rpcId` 把已经出现在 durable 节点上的回显过滤掉）。
 *
 * ADSH 的 durable 行不带 rpcId，所以按「同内容一对一消耗」配对：库里每出现一条 steering 行，
 * 就抵掉**最早**的一条同内容回显。重复的同一句话（连发两次同样的插话）也因此各抵各的，
 * 不会两条一起消失。纯函数，桌面上直接测（PendingSteerTest）。
 */
internal fun pendingSteeringToShow(
    pending: List<PendingSteer>,
    messages: List<MessageEntity>,
): List<PendingSteer> {
    if (pending.isEmpty()) return pending
    val durable = messages
        .filter { it.role == "user" && it.name == com.adsh.app.core.data.ConversationRepository.STEERING }
        .map { it.content }
        .toMutableList()
    if (durable.isEmpty()) return pending
    return pending.filter { echo ->
        val at = durable.indexOf(echo.text)
        if (at < 0) {
            true
        } else {
            durable.removeAt(at)
            false
        }
    }
}
