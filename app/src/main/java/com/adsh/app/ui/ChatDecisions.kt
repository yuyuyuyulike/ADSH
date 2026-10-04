package com.adsh.app.ui

import com.adsh.app.core.data.MessageEntity

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// 会话页的**纯判定与常量**（从 ChatScreen 搬出来：主函数之外剩下的全是这些）。
// 滚动那几条判定在 AutoScroll.kt，这里的常量就是它们的输入。
/** 离「整个会话真正的底部」还有多少像素（算式与三种情形见 [bottomGapOf]） */
internal fun LazyListState.bottomGap(): Int {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()
    return bottomGapOf(
        canScrollForward = canScrollForward,
        lastIndex = last?.index,
        lastOffset = last?.offset ?: 0,
        lastSize = last?.size ?: 0,
        totalItemsCount = info.totalItemsCount,
        viewportEndOffset = info.viewportEndOffset,
        afterContentPadding = info.afterContentPadding,
    )
}


/** LazyColumn 的 item key：一轮被拆成了多行，每一行都要有自己的稳定 key */
internal fun transcriptKey(item: ChatItem): String = when (item) {
    is ChatItem.SystemPrompt -> "sysprompt-" + item.key
    is ChatItem.Context -> "context-" + item.key
    is ChatItem.User -> "user-" + item.key
    is ChatItem.Compact -> "compact-" + item.key
    is ChatItem.TurnFold -> "turn-" + item.view.key + "-fold"
    is ChatItem.TurnEntry -> "turn-" + item.view.key + "-" + turnEntryKey(item.view, item.index)
    is ChatItem.TurnTail -> "turn-" + item.view.key + "-tail"
}

/**
 * 这一次消息区手势结束时要不要「点空白处收起输入状态」（clearFocus）。
 *
 * 真机实测的根因（第 85 轮，logcat 打的点）：长按正文选中时 SelectionContainer 会
 * `requestFocus()` 接管焦点（选中高亮 / 手柄 / 复制菜单都挂在这条焦点上），
 * 抬手时若照样 clearFocus，**4ms 后**焦点就被抢走 —— SelectionManager 的 onFocusChanged
 * 一见 `!hasFocus` 立刻 `onRelease()`，选中当场被清空，用户看到的就是「长按选不中、复制不了」。
 *
 * 所以规则收窄成一条：**只有「短按」（= 点击）才收键盘**；长按（≥ 系统长按阈值，无论落在
 * 正文还是空白）与滑动都不碰焦点。长按也不会把键盘留在屏幕上：焦点已经交给
 * SelectionContainer，输入法本来就是跟着焦点收起的。
 */
internal fun shouldClearComposerFocus(
    dragged: Boolean,
    heldMillis: Long,
    longPressTimeoutMillis: Long,
): Boolean = !dragged && heldMillis < longPressTimeoutMillis

/** dsh 的跟随阈值（`useScrollFollow(following, 25)`）：离底部 25dp 以内就算「读者还在底部」 */
internal const val FOLLOW_THRESHOLD_DP = 25f

/** 流式采样节拍（ms）：30fps 量级的重绘节流 */
internal const val STREAM_TICK_MS = 33L

/**
 * 「把视口钉到内容末端」用的请求偏移。
 *
 * **绝不能用 `Int.MAX_VALUE`**（第 105 轮从真机诊断日志里挖出来的根因）。`requestScrollToItem`
 * 只是把 `(index, offset)` 记进滚动状态，真正的解析在 `LazyListMeasure` 里，而它算的是
 * `toScrollBack = maxOffset - currentMainAxisOffset`，其中 `currentMainAxisOffset` 初值就是
 * `-offset`；`offset` 取 `Int.MAX_VALUE` 时这一步**加法溢出成负数**，后面一串加减按模 2³² 走 ——
 * 最终落点成了「最后一项 / 前一项的高度」的函数：行高凑巧时正好落在底部（所以这个写法一直被
 * 当成「能用」），不凑巧时偏出几百像素。
 *
 * 真机证据（`turn-92-call:0` 那一帧，items=18）：
 * ```
 * pin req items=18 live=2 appended=false tail=true gap=2147483647 first=17+2147483647
 * ```
 * 滚动状态里留下的是 `offset = 2147483647` —— 接下来那一帧解析到哪里全看行高，子行就落在
 * 「靠下、不对」的位置，等下一次内容变化才跳回底部（用户报的「bash 子行位置不对、卡一下才到位」）。
 *
 * 换成一个**大到不可能有任何内容超过、又不会让加法溢出**的偏移之后，同一段算术不再溢出，
 * 落点唯一确定：内容末端贴在视口末端（减掉底部内边距），也就是真正的最大滚动位置。
 */
internal const val BOTTOM_REQUEST_OFFSET = 1 shl 28

/**
 * 贴底补钉的容差（px）：只吸收取整误差。
 *
 * **不能**拿 [ChatScreen] 里那个 25dp 的 [followSlopPx] 当这个容差 —— 那是 dsh 的 atBottom
 * 阈值（「读者算不算已经在底部」），一行子调用才 22dp：用 25dp 判「要不要补钉」时，
 * 新长出来的一行根本够不着阈值，底部就停在「半行被切在视口外」，直到下一次更大的变化才跳回去
 * （用户第 105 轮第 2 条报的正是这个形状）。
 */
internal const val PIN_SLOP_PX = 1

/**
 * 平滑显现的**追平拍数**：积压按「这么多拍之内追平」计算每一步走多少字。
 *
 * 第 105 轮重做（用户点名：「流式输出呈现出先快后慢的感觉，这肯定不对，有时 agent 都开始
 * 调用工具了，输出才结束，就是输出顺畅即可，不一定非要一个字一个字的输出」）。
 *
 * 旧算法是「每一拍显现剩余那一截的十分之一」+「小积压时严格一拍 1 字」（第 95 轮的
 * `REVEAL_DIVISOR`，第 105 轮删掉），于是有两处必然的毛病：
 *  1. **先快后慢**：积压 100 字时一拍走 10 字（快），积压掉到 9 字时一拍只走 1 字（慢）——
 *     每一段增量的尾巴都是「30 字/秒」地滴出来的；
 *  2. **尾巴拖住交接**：模型说完正文、开始吐工具调用的参数（一段 run_code 程序可以吐好几秒），
 *     这段时间里正文已经不再增长，界面却还在按 30 字/秒滴剩下的那一截 —— 用户看到的就是
 *     「agent 都开始调用工具了，输出才结束」。
 *
 * 现在只有两条规矩：
 *  - **一拍至少显 [REVEAL_MIN_STEP] 个字**（≈90 字/秒），不再有逐字滴的那一段；
 *  - **积压不超过 [REVEAL_MAX_LAG] 个字**（超了立刻排到上限），尾巴最多 ~6 拍（[STREAM_TICK_MS]
 *    ×6 ≈ 200ms）排空；配合「模型开始写工具参数就把尾巴一次性补齐」（见 ChatScreen 的
 *    `toolArgsFlowing`），交接处不再有任何等待。
 *
 * 显示仍然比线上慢 1~3 拍（~100ms）：这是**重绘节流**要的效果（一帧画一段，而不是每个
 * token 重排一次），只是不再靠「越接近目标越慢」来实现。
 */
internal const val REVEAL_LAG_TICKS = 3

/** 一拍最少显多少个字（[STREAM_TICK_MS] = 33ms ⇒ ≈90 字/秒）：不允许再出现逐字滴 */
internal const val REVEAL_MIN_STEP = 3

/** 允许积压的字符上限：超了立刻排到这个数（尾巴最长 ~6 拍排空） */
internal const val REVEAL_MAX_LAG = 18

/**
 * 平滑显现的下一步长度（纯函数，单测直接打）。
 *
 * @param shown 已经显现的字符数
 * @param target 线上已经收到的字符数
 * @return 这一拍之后应该显现到多少（至少前进 [REVEAL_MIN_STEP] 或到 [target]，最多到 [target]）
 */
internal fun revealLength(shown: Int, target: Int): Int {
    if (target <= shown) return target
    val lag = target - shown
    // ① 追平：按 REVEAL_LAG_TICKS 拍追平当前积压（积压大就走得快 —— 与「积压大小」成正比，
    //    与旧的「剩余的十分之一」在积压大时同量级，但积压小时不再退化成 1 字/拍）
    var step = (lag + REVEAL_LAG_TICKS - 1) / REVEAL_LAG_TICKS
    // ② 下限：一拍至少 REVEAL_MIN_STEP 个字
    if (step < REVEAL_MIN_STEP) step = REVEAL_MIN_STEP
    // ③ 积压上限：这一拍走完之后剩下的不能超过 REVEAL_MAX_LAG（一个超长块一帧砸下来之后，
    //    最多再花 ~6 拍把尾巴排空）
    if (lag - step > REVEAL_MAX_LAG) step = lag - REVEAL_MAX_LAG
    return shown + step.coerceAtMost(lag)
}

/**
 * 把 [target] 往 [shown] 推进一步（[revealLength] 的字符串版）。
 * 两边不是前缀关系（换了一段 / 被清零）时直接换成 [target] —— 那一帧必须立刻跟上。
 */
internal fun revealFrom(shown: String, target: String): String = when {
    target.length <= shown.length || !target.startsWith(shown) -> target
    else -> target.substring(0, revealLength(shown.length, target.length))
}

// 对话流节点模型（dsh 的 Chat Node）与整形逻辑在 TurnList.kt


/**
 * 视口上方最近的一条用户消息（第 95 轮的快捷导航的目标）。
 *
 * 与 [LazyListState.firstVisibleItemIndex] 配合：[firstVisible] 是**第一条可见行**，严格在它
 * 之上找 —— 读者停在一轮中间时，这一轮开头的用户消息就在上面；已经在第一轮里时返回 null。
 */
internal fun previousUserItemIndex(items: List<ChatItem>, firstVisible: Int): Int? {
    val start = firstVisible.coerceAtMost(items.size) - 1
    for (index in start downTo 0) {
        if (items[index] is ChatItem.User) return index
    }
    return null
}

/**
 * 「用户自己发的消息」：`role = user` 且 **没有 name**。
 *
 * 通知（任务完成通知的两种形态、插话、权限预设切换…）在库里也是 `role = user`，但它们都带 `name`，
 * 不是人打的字。这条判定以前在六个地方各写了一遍（会话页的 ownInput、这一轮的身份、统计口径、
 * 附件指路、AgentLoop 的轮次统计、标题生成），**没有具名函数、也没有直接用例**；失效的后果是
 * 用户可见的：一条后台任务通知落库就把正在翻历史的读者一把拽回底部（ChatScreen 里那次实测的注释还在）。
 */
internal fun isOwnUserMessage(message: MessageEntity): Boolean =
    message.role == "user" && message.name == null

/** 最后一条真的用户消息；没有就是 null。 */
internal fun lastOwnUserMessage(messages: List<MessageEntity>): MessageEntity? =
    messages.lastOrNull { isOwnUserMessage(it) }

/** 最后一条真的用户消息的 id；没有就是 0（id 由库自增、从 1 起，所以 0 就是「没有」）。 */
internal fun lastOwnUserId(messages: List<MessageEntity>): Long =
    lastOwnUserMessage(messages)?.id ?: 0L
