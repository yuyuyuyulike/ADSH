package com.adsh.app.ui.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * 自动滚动的状态与判定（手机端标准形态）。
 *
 * 规则只有一条：**读者在底部，新内容才把视口带到新末端**。
 *
 * - 「在底部」= 滚动容器没有剩余可滚距离（只留取整容差 [PIN_SLOP_PX]）。读者上滑立即变成
 *   不在底部；滑回末端、点「回到底部」、自己发消息时再变回在底部。
 * - 内容（流式正文 / 图片解码）与视口（键盘、footer 高度）变化只在「在底部」时贴底；
 *   静止的会话不发任何贴底请求。
 * - 手指在屏上、或读者正在拖拽 / 惯性滚动的那一拍不贴 —— 不和读者抢视口。
 * - 展开会话里的行、打开任务面板属于读者动作：只让内容按原布局变化，不替读者滚。
 */

/**
 * 贴底的闸门：读者在底部、手不在屏上、也没有正在滚，三者同时成立才允许替读者移动视口。
 *
 * [scrolling] 挡住拖拽与惯性：那几帧里读者正在决定位置，任何贴底都会把刚滑开的视口按回去。
 */
internal fun pinAllowed(
    following: Boolean,
    touching: Boolean,
    scrolling: Boolean,
): Boolean = following && !touching && !scrolling

/**
 * 内容缩到不足以再滚动时把「在底部」收回来（「回到底部」按钮据此消失）。
 *
 * 判据是**还有没有可滚距离**而不是读者上次停在哪里：对话被折叠 / 删减到贴底之后，视口确实
 * 已经在末端，跟随状态要跟上这个事实，否则按钮会一直留着、点它也不会动。
 * 读者还在滑或手还在屏上时一律不抢（与 [pinAllowed] 同一规矩）；空会话不算。
 */
internal fun shouldRestoreFollow(
    following: Boolean,
    touching: Boolean,
    scrolling: Boolean,
    atBottom: Boolean,
    hasContent: Boolean,
): Boolean = !following && !touching && !scrolling && atBottom && hasContent

/**
 * 自动滚动的状态（一份 `remember(conversationId)` 一个对象）。
 *
 * 只有 [following] 一个状态：它在读者真正移动过位置时更新（[sampled]），以及用户自己发消息
 * （[ownInput]）、点回到底部（[resume]）时恢复。内容长高本身**不**改它 —— 流式追加时
 * `maxValue` 每帧都在变，若拿「离底部的距离」参与判定，跟随会在第一帧就被判成离开底部。
 */
internal class ChatScrollState {

    /** 读者是不是还跟着底部；自动滚动唯一的状态。 */
    var following by mutableStateOf(true)
        private set

    /** 手指是否按在消息区：按着的时候一下都不许贴（避免内容在手指下方跳动）。 */
    var touching by mutableStateOf(false)
        private set

    /** 贴底触发指纹：内容（`items` 的实例身份）。非 State：写它不该触发重组。 */
    private var pinnedContent: Any? = null

    /** 上一次「用户自己的消息」的 id；-1 = 这一屏刚进来。 */
    private var ownInputMark = -1L

    /** 最近一次 [pinRequest] 是不是内容变了，只给 DEBUG 追踪用。 */
    var pinReason: String = ""
        private set

    /** 手指按下 / 抬起（消息区那个 pointerInput 每一下都报，见 ChatScreen）。 */
    fun touch(down: Boolean) {
        touching = down
    }

    /** 显式离开底部（读者动作 / 导航）：之后的任何内容变化都不再贴底，直到 [resume]。 */
    fun pause() {
        following = false
    }

    /** 回到跟随（点「回到底部」/ 自己发消息 / 内容缩到贴底）。 */
    fun resume() {
        following = true
    }

    /**
     * 读者自己发的一条消息刚落进会话 ⇒ 返回 true（调用方接 [resume]）。
     *
     * 带 `name` 的 user 行是系统通知（任务完成、插话、权限切换），不是人打的字：它们落库不该
     * 把正在翻历史的读者拽回底部。刚进这一屏时只记不跟随。
     */
    fun ownInput(lastUserId: Long): Boolean {
        if (lastUserId == ownInputMark) return false
        val previous = ownInputMark
        ownInputMark = lastUserId
        return previous >= 0L
    }

    /**
     * 读者位置采样：位置一变，按「读者落点是不是在底部」更新 [following]。
     *
     * 只有读者滚动（[readerScrolling] = 拖拽 / 惯性）才算；程序位移（贴底、内容变短后的回夹）
     * 不改跟随 —— 否则贴底自己会把状态写回、回夹会被误判成读者上滑。
     *
     * @return 位置是否真的移动过（调用方据此推进自己的 `previous`）
     */
    fun sampled(
        previous: Int,
        current: Int,
        atBottom: Boolean,
        readerScrolling: Boolean = false,
    ): Boolean {
        if (current == previous) return false
        if (readerScrolling) following = atBottom
        return true
    }

    /**
     * 这一拍该不该发贴底请求（请求本身在 ChatScreen 的 `SideEffect` 里发，落在本帧测量之前）。
     *
     * 指纹**先记后判**：即使这一拍因为读者在动被挡掉，下一次也不会拿旧指纹去比 —— 否则松手后
     * 会补发一次贴底，把读者刚滑开的位置按回去。
     */
    fun pinRequest(content: Any?, scrolling: Boolean): Boolean {
        val contentChanged = pinnedContent !== content
        pinnedContent = content
        pinReason = if (contentChanged) "content" else ""
        if (!pinAllowed(following, touching, scrolling)) return false
        return contentChanged
    }
}

/**
 * 贴底保活：**跟随中但视口不在内容末端**时，把视口补到刚量出来的末端。
 *
 * 覆盖「内容在本帧之后才长高」那一类：流式正文追加、图片 / LaTeX 异步解码。天生晚一帧，
 * 容差 [PIN_SLOP_PX] 只吸收取整误差。位移不是读者滚出来的才贴（门在 [pinAllowed]）。
 *
 * 内容缩到贴底时把跟随收回来：按钮据此消失（见 [shouldRestoreFollow]）。
 */
@Composable
internal fun KeepPinnedEffect(scrollState: ScrollState, scroll: ChatScrollState) {
    LaunchedEffect(scrollState) {
        snapshotFlow {
            Triple(scrollState.value, scrollState.maxValue, scrollState.isScrollInProgress)
        }.collect { (value, maxValue, scrolling) ->
            if (maxValue <= 0) return@collect
            // 用绝对值：value 超出 maxValue（视口变高后没回夹的旧值）时 gap 为负，不能算“贴底”，
            // 否则下面这一拍永远不会把它拉回内容末端（界面表现是内容被绘制偏移往下推）。
            val atBottom = kotlin.math.abs(maxValue - value) <= PIN_SLOP_PX
            if (
                shouldRestoreFollow(
                    following = scroll.following,
                    touching = scroll.touching,
                    scrolling = scrolling,
                    atBottom = atBottom,
                    hasContent = true,
                )
            ) {
                scroll.resume()
            }
            if (!pinAllowed(scroll.following, scroll.touching, scrolling)) return@collect
            if (atBottom) return@collect
            if (com.adsh.app.ui.chat.ChatDebug.enabled) {
                trace("pin keep", "gap=" + (maxValue - value) + " max=" + maxValue)
            }
            scrollState.scrollTo(maxValue)
        }
    }
}

// 「展开工具详情」不做任何补偿滚动：读者动作先交出跟随，展开向下长、上面的行一动不动。
// 补偿滚动（revealAfterExpand）会「向上展开」并在流式重排时闪一下，不要再加回来。
