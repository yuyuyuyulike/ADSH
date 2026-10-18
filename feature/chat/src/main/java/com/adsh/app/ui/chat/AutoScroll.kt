package com.adsh.app.ui.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * 自动滚动的**状态与判定**。
 *
 * 判定全部是纯函数、能脱离 Compose 测试：分工是 [ChatScrollState] 持有状态、这里的纯函数做判定、
 * [KeepPinnedEffect] 是贴底保活；读滚动容器与发滚动请求的动作只剩这几处
 * （外加 ChatScreen 里贴底那一发）。
 *
 * 整套口径照 dsh 的 use-scroll-follow / use-chat-viewport / use-chat-reading / use-chat-scroll：
 *
 * ① **状态只有一个「跟随意图」**（[ScrollFollow]）：它与「现在离底部多少像素」无关 —— 内容每帧
 *    长高都不会让它翻转，所以 dsh 的「回到底部」判据就是 `!followingTail`，一个像素迟滞都不需要。
 * ② **只有读者自己移动过才重算意图**（[ChatScrollState.sampled]）：滚动偏移变了才按
 *    [ChatScrollState.slopPx]（25dp）重算；只是点一下、按住不动都不改意图。
 * ③ **贴底请求只由「变化」触发**（[ChatScrollState.pinRequest]）：内容变了（`items` 换了实例 =
 *    列表的任何一个输入变了）或视口高度变了（键盘 / 输入区高度）。**不再「每重组一次就贴一次底」**：
 *    会话停下来之后本屏仍会因为别的事重组（后台任务轮询、任务横窗、连接状态、用量…），
 *    每重组一次贴一次 = 「会话结束后自动滚动还在跑」。静止的会话现在一次请求都不发。
 * ④ 请求在 ChatScreen 的 `SideEffect` 里发出：它是「下一次测量用的位置」，落在**本帧测量之前**。
 * ⑤ **读者动作的优先级最低**（[ChatScrollState.pause]）：展开 / 收起会话里的任何一行、打开任务列表，
 *    一律先交出跟随。这一条是本 App 特有的：dsh 的展开体在**限高的内层滚动区**里，外层视口本来
 *    就不动；这里展开体直接长在会话流里，不交出去就会被流式贴底顶上去。
 * ⑥ 用户自己发的一条消息（[ChatScrollState.ownInput]）与点「回到底部」（[ChatScrollState.resume]）
 *    无条件恢复跟随。
 * ⑦ **读者正在滚（拖拽 / 惯性）时这一拍一律不贴底**（[pinAllowed]）。
 * ⑧ **视口其实已经贴着内容末端时，把跟随意图收回来**（[shouldRestoreFollow]）：折叠一个展开体不会
 *    改变滚动偏移，② 那一支采样根本不会响 —— 少了这一条，意图会一直停在「读者翻过历史」，
 *    可内容早就缩到贴底了：右侧的「回到底部」按钮白留在屏幕上、点它也不滚。
 * ⑨ **读者往上滑（偏移变小）立刻交出跟随**，哪怕只有几个像素（[ChatScrollState.sampled] 的
 *    readerScrolling 那一支）：25dp 迟滞只判「还在不在底部」，不吞读者的上滑意图。
 * ⑩ **自动贴底有生命周期**（[ChatScrollState.autoScroll]）：只在**这一轮进行期间**（`sending ||`
 *    `liveTurnId != null`）活着，轮次结束的那一拍做一次收尾贴底（轮尾动作行 / 文件卡要落在屏幕里），
 *    随后关掉 —— 之后图片解码、输入区高度变化、流式残留都不再自动滚。下一轮 / 自己发消息 /
 *    点「回到底部」重新打开。跟随意图与它分开：意图留在原地，读者的「回到底部」按钮不因此常驻。
 */

/**
 * 跟随意图（dsh 的 `ScrollFollow`，见 use-scroll-follow.ts；阈值 25dp 见 ChatScreen）。
 *
 * dsh 把「内容长出来要不要跟着走」做成一个**独立于当前滚动位置**的开关：它只在
 * `sample(metrics, movedByReader)` 里、且读者**真的移动过**时才重算 —— 内容每帧长高都不会让它
 * 翻转，所以浏览器那边一个迟滞都不需要，「回到底部」按钮的判据也就是 `!followingTail`。
 *
 * 这里照搬这一条：[following] 只在三处被写 —— 读者的位置采样（[sample]）、读者动作与显式导航
 * （[pause]）、用户自己发消息与点回到底部（[follow]）。**别的地方一律只读**。
 */
internal class ScrollFollow(initial: Boolean = true) {
    var following by mutableStateOf(initial)
        private set

    /** dsh 的 `sample(metrics, movedByReader)`：读者自己移动过才重算，落在阈值以内就继续跟随。 */
    fun sample(atBottom: Boolean) {
        following = atBottom
    }

    /** dsh 的 `toBottom()` / `followTail()`：无条件恢复跟随。 */
    fun follow() {
        following = true
    }

    /** dsh 的 `pauseFollowing()`：释放底部跟随（读者动作、显式导航）。 */
    fun pause() {
        following = false
    }
}

/**
 * 贴底请求的门（主路径与贴底保活共用）：**「不是读者造成的位移」才允许贴**。
 *
 *  - [following]：跟随意图已经交出去（读者动作 / 显式导航）就不贴；
 *  - [touching]：手指还在屏幕上就不贴（dsh 的 `pending`）；
 *  - [scrolling]：读者正在滚（拖拽 / 惯性）—— dsh 的 `onScroll` 只在 `!scroll.movedByReader`
 *    时才 `followTail()`，读者的每一次滚动投递都带 movedByReader，那一拍**不贴底**。
 *    少了这道门，流式期间内容每长高一点都会发一次贴底请求，把读者那一下「极小幅度快速上滑」
 *    的惯性按回去。
 *  - [autoScroll]：这一轮的自动贴底生命周期（[ChatScrollState.autoScroll]）已经结束就不贴 ——
 *    跟随意图还在（「回到底部」按钮不因此常驻），但不再替读者滚（⑩）。
 */
internal fun pinAllowed(
    following: Boolean,
    touching: Boolean,
    scrolling: Boolean,
    autoScroll: Boolean = true,
): Boolean = following && autoScroll && !touching && !scrolling

/**
 * ⑧ 「折叠回去之后视口已经落在会话末端」这一拍要不要**恢复跟随意图**（右侧「回到底部」按钮据此消失）。
 *
 * 为什么需要它：跟随意图只在**读者移动**（[ChatScrollState.sampled]）与 ⑥ 两处被重算，而折叠一个
 * 展开体**不会改变滚动偏移**（视口锚在它上面），采样那一支根本不会响 —— 于是意图一直停在
 * 「读者翻过历史」，可内容其实早就缩到贴底了：按钮留在屏幕上，点它也不会滚。
 *
 * 判据用**还有没有可滚距离**（`maxValue - value == 0` = 视口已经贴着内容末端）——
 * 这是「会话底部就在输入框上方」的直接读数，与 25dp 阈值无关（那个阈值答的是「读者算不算还贴着底」，
 * 这里问的是「还有没有内容可滚」）。读者还在滑 / 手指还在屏幕上时一律不抢（与 [pinAllowed] 同一规矩），
 * 空会话也不算。
 */
internal fun shouldRestoreFollow(
    following: Boolean,
    touching: Boolean,
    scrolling: Boolean,
    atBottom: Boolean,
    hasContent: Boolean,
): Boolean = !following && !touching && !scrolling && atBottom && hasContent

/**
 * 自动滚动的**状态**（一份 `remember(conversationId)` 一个对象）。
 *
 * 一份状态一条会话、跨页面存活：跟随意图、手指、两个触发指纹都在这个对象里，
 * 「自动滚动」这台小状态机就能脱离 Compose 宿主测（见 AutoScrollTest 与 TestListState 之类）。
 */
internal class ChatScrollState(
    /** dsh 的 `useScrollFollow(state.followingTail, 25)`：25dp 以内算「读者还在底部」 */
    val slopPx: Int,
) {
    private val follow = ScrollFollow()

    /** 手指是否按在消息区：按着的时候一下都不许贴（dsh 的 `pending`：读者输入还没采样完就不动视口） */
    var touching by mutableStateOf(false)
        private set

    /** 贴底的**触发指纹**：内容（`items` 的实例身份）+ 视口下边距（键盘）。非 State：写它不该触发重组。 */
    private var pinnedContent: Any? = null
    private var pinnedViewport = -1

    /** 上一次「用户自己的消息」的 id（dsh 的 `ownInput` 比的也是上一次提交）；-1 = 这一屏刚进来 */
    private var ownInputMark = -1L

    /** 输入区（输入框 + 状态行 + 提示条 + 待办 dock）上一次量出来的高度；-1 = 还没量过 */
    private var chromeHeight = -1

    /** 最近一次 [pinRequest] 变了什么（`content` / `vp` 的组合），只给 DEBUG 追踪用 */
    var pinReason: String = ""
        private set

    /** 跟随意图（dsh 的 `ScrollFollow`）：**自动滚动唯一的状态**，只有 ②⑤⑥ 会写它 */
    val following: Boolean get() = follow.following

    /**
     * 这一轮的**自动贴底**还开着吗（⑩）——与 [following] 是两件事。
     *
     * [following] 答的是「内容长高要不要跟着走」；这里答的是「这一轮还要不要自动贴」。轮次结束后
     * 意图留在原地（读者没有离开底部，「回到底部」按钮不因此常驻），但自动贴底一律停掉：图片 /
     * LaTeX 解码、输入区高度变化、流式残留都不再把读者拽回底部。下一轮开始 / 自己发消息 /
     * 点「回到底部」时 [resume] 重新打开。
     */
    var autoScroll by mutableStateOf(true)
        private set

    /** 这一轮是否还在跑：用来抓「结束的那一拍」（[turnRunning]） */
    private var turnActive = false

    /** 手指按下 / 抬起（消息区那个 pointerInput 每一下都报，见 ChatScreen） */
    fun touch(down: Boolean) {
        touching = down
    }

    /** dsh 的 `pauseFollowing()`：释放底部跟随（读者动作、显式导航），见上面 ⑤ */
    fun pause() {
        follow.pause()
    }

    /** dsh 的 `toBottom()` / `followTail()`：无条件恢复跟随并重新打开自动贴底，见上面 ⑥ */
    fun resume() {
        follow.follow()
        autoScroll = true
    }

    /**
     * ⑩ 轮次状态上报（true = 这一轮还在跑：`sending || liveTurnId != null`）。
     *
     * @return true = 刚结束的那一拍：调用方据此做**收尾贴底**，贴完调 [settleDone] 把自动贴底关掉
     */
    fun turnRunning(active: Boolean): Boolean {
        val ended = turnActive && !active
        turnActive = active
        if (active) autoScroll = true
        return ended
    }

    /** ⑩ 收尾贴底已经落地：这一轮之后的自动贴底全部停掉（[resume] / 下一轮会重新打开） */
    fun settleDone() {
        autoScroll = false
    }

    /**
     * ⑥ 用户自己发的一条消息刚落进会话 ⇒ 返回 true（调用方接 [resume]）。
     *
     * 通知（任务完成通知的两种形态、插话、权限预设切换…）在库里也是 `role = user`，但它们都带
     * `name` —— 少了 ChatScreen 那一层的过滤，一条后台任务通知落库就会把正在翻历史的读者一把
     * 拽回底部（dsh 的 `lastIsUser` 同样只认真正的用户节点）。刚进这一屏时只记不跟随。
     */
    fun ownInput(lastUserId: Long): Boolean {
        if (lastUserId == ownInputMark) return false
        val previous = ownInputMark
        ownInputMark = lastUserId
        return previous >= 0L
    }

    /**
     * ② 读者位置采样（dsh 的 `onScroll` / `flushSample`）：偏移一变就按 [slopPx] 阈值重算跟随意图
     * —— 这一条就是 dsh 的 `sample(metrics, movedByReader)`。
     *
     * 位置**没变**（点一下、按住不动）时什么都不做：意图由读者真实的移动决定，不由手指决定。
     * dsh 那一侧的等价规则是：读者滑到整个会话底部（`top >= floor`）就恢复跟随，其余交给滚动落定的采样。
     *
     * ⑨ **读者往上滑（偏移变小）立刻交出跟随**，哪怕只有几个像素：25dp 迟滞只用来判「读者是不是
     * 还在底部」，不用来吞掉读者真实的上滑意图 —— 否则流式 / 图片解码的下一次贴底会把读者按回去。
     * 只有**读者滚动**（[readerScrolling]）造成的位移才算：回夹 / 贴底这些程序位移（内容变短时
     * value 也会变小）不能把跟随关掉。
     *
     * @return 位置是否真的移动过（调用方据此推进自己的 `previous`）
     */
    fun sampled(
        previous: Int,
        current: Int,
        atBottom: Boolean,
        /** 这一下位移是不是读者滚出来的（拖拽 / 惯性） */
        readerScrolling: Boolean = false,
    ): Boolean {
        if (current == previous) return false
        if (readerScrolling && current < previous) follow.pause() else follow.sample(atBottom = atBottom)
        return true
    }

    /**
     * ③ 这一拍该不该发贴底请求（请求本身在 ChatScreen 的 `SideEffect` 里发，见上面 ④）。
     *
     * [content] 传 `items`（列表任何一个输入变了它就换实例）、[viewport] 传 IME 下边距。
     * 指纹**先记后判**：即使这一拍被 [pinAllowed] 挡掉，下一次也不该拿旧指纹去比。
     *
     * @param allowWhenStopped 收尾那一拍为 true：允许在自动贴底已经关掉之后还贴一次
     * @return true = 内容或视口变了、且 [pinAllowed] 的门过了 ⇒ 调用方贴底
     */
    fun pinRequest(
        content: Any?,
        viewport: Int,
        scrolling: Boolean,
        allowWhenStopped: Boolean = false,
    ): Boolean {
        val contentChanged = pinnedContent !== content
        val viewportChanged = pinnedViewport != viewport
        pinnedContent = content
        pinnedViewport = viewport
        pinReason = (if (contentChanged) "content" else "") + (if (viewportChanged) "vp" else "")
        if (!autoScroll && !allowWhenStopped) return false
        if (!pinAllowed(follow.following, touching, scrolling, autoScroll || allowWhenStopped)) return false
        return contentChanged || viewportChanged
    }

    /**
     * 输入区高度变了要不要补一次贴底（ChatScreen 的 `layout` 里量出来就报）。
     *
     * 键盘之外的第三类「视口变了」：提示条 / 状态行 / 待办 dock 长出来会把列表压矮，那时没有任何
     * 状态写进本屏、上面的指纹也不会变。首次测量不算变化 —— 进会话那一下由 ③ 负责。
     */
    fun chromeMeasured(height: Int, total: Int): Boolean {
        if (height == chromeHeight) return false
        val first = chromeHeight < 0
        chromeHeight = height
        return !first && following && autoScroll && !touching && total > 0
    }
}

/**
 * 贴底保活：**只在跟随意图还在、视口却离开了内容末端**时，把视口补到刚量出来的内容末端。
 *
 * 覆盖「内容在本帧之后才长高」那一类：图片 / LaTeX 异步解码、流式正文追加。结构上就是 dsh 的
 * `ResizeObserver(column)` → `followTail()` 等价物，天生晚一帧（容差 [PIN_SLOP_PX]，只吸收取整误差）。
 * 位置不是读者滚出来的就不动 —— 门在 [pinAllowed]；读者滚出来的位移只重算意图、绝不贴底。
 *
 * ⑧ 折叠之后视口已经贴到内容末端：把跟随意图收回来（按钮据此消失，见 [shouldRestoreFollow]）。
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
            if (!pinAllowed(scroll.following, scroll.touching, scrolling, scroll.autoScroll)) return@collect
            if (atBottom) return@collect
            if (com.adsh.app.ui.chat.ChatDebug.enabled) {
                trace("pin keep", "gap=" + (maxValue - value) + " max=" + maxValue)
            }
            scrollState.scrollTo(maxValue)
        }
    }
}

// 「展开工具详情」不做任何补偿滚动：**列表正序 + 读者动作交出跟随**，展开天然向下长、上面的行
// 一动不动（「向下展开，上方不动」）。展开体落到视口下面时由读者自己滑 —— 不替读者滚。
// 补偿滚动（revealAfterExpand）会「向上展开」并在流式重排时闪一下，不要再加回来。
