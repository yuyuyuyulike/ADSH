package com.adsh.app.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * 自动滚动的**状态与判定**（R13 搬出判定，R14 连状态一起搬出来：原先长在 ChatScreen 主函数里）。
 *
 * 为什么单独一份：这套东西的每一条都是真机上一轮轮踩出来的 ——「会话结束后自动滚动还在跑」
 * 「极小幅度快速上滑被一帧一帧拽回底部」「往下滑了按钮还在」—— 却全长在 958 行的主函数里，
 * 一行都测不了。现在的分工：[ChatScrollState] 持有状态、这里的纯函数做判定、[PinFixEffect] 是
 * 那条兜底补钉；读 LazyListState 与发滚动请求的动作只剩这几处（外加 ChatScreen 里贴底那一发）。
 *
 * 整套口径照 dsh 的 use-scroll-follow / use-chat-viewport / use-chat-reading / use-chat-scroll：
 *
 * ① **状态只有一个「跟随意图」**（[ScrollFollow]）：它与「现在离底部多少像素」无关 —— 内容每帧
 *    长高都不会让它翻转，所以 dsh 的「回到底部」判据就是 `!followingTail`，一个像素迟滞都不需要。
 * ② **只有读者自己移动过才重算意图**（[ChatScrollState.sampled]）：手势 / 惯性滚动让「第一条可见行
 *    + 行内偏移」变了才按 [ChatScrollState.slopPx]（25dp）重算；只是点一下、按住不动都不改意图。
 * ③ **贴底请求只由「变化」触发**（[ChatScrollState.pinRequest]）：内容变了（`items` 换了实例 =
 *    列表的任何一个输入变了）或视口高度变了（键盘 / 输入区高度）。**不再「每重组一次就贴一次底」**：
 *    会话停下来之后本屏仍会因为别的事重组（后台任务轮询、任务横窗、连接状态、用量…），
 *    每重组一次贴一次 = 「会话结束后自动滚动还在跑」。静止的会话现在一次请求都不发。
 * ④ 请求在 ChatScreen 的 `SideEffect` 里发出：它是「下一次测量用的位置」而不是一次滚动，
 *    落在**本帧测量之前**，新内容第一次被画出来位置就是对的。
 * ⑤ **读者动作的优先级最低**（[ChatScrollState.pause]）：展开 / 收起会话里的任何一行、打开任务列表，
 *    一律先交出跟随。这一条是本 App 特有的：dsh 的展开体在**限高的内层滚动区**里，外层视口本来
 *    就不动；这里展开体直接长在会话流里，不交出去就会被流式贴底顶上去。
 * ⑥ 用户自己发的一条消息（[ChatScrollState.ownInput]）与点「回到底部」（[ChatScrollState.resume]）
 *    无条件恢复跟随。
 * ⑦ **读者正在滚（拖拽 / 惯性）时这一拍一律不贴底**（[pinAllowed]）。
 * ⑧ **视口其实已经贴着内容末端时，把跟随意图收回来**（[shouldRestoreFollow]）：折叠一个展开体不会
 *    改变「第一条可见行 + 行内偏移」，② 那一支采样根本不会响 —— 少了这一条，意图会一直停在
 *    「读者翻过历史」，可内容早就缩到贴底了：右侧的「回到底部」按钮白留在屏幕上、点它也不滚。
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
 * 这一次移动是朝**会话开头**（往上翻）还是朝底部。第 96 轮用户点名：右上角那两个快捷导航按钮
 * 只在往上翻时出现，往下滚的那一下立刻收起来、停在半路也不会自己冒出来。
 *
 * 判据是**位置增量**（第一条可见行 + 行内偏移的变化方向），不是 `lastScrolledBackward`：
 * 那两个布尔在「这一帧没动」时可能同时为 true，按「先看 backward」读会把停住当成往上滚
 * （真机上就是「往下滑了按钮还在」）。位置增量没有这个歧义，惯性滑动同样会逐帧上报。
 */
internal fun scrollTowardTop(previous: Pair<Int, Int>, current: Pair<Int, Int>): Boolean =
    current.first < previous.first ||
        (current.first == previous.first && current.second < previous.second)

/**
 * 离「整个会话真正的底部」还有多少像素。0 = 就在底部；Int.MAX_VALUE = 最后一项还没露出来。
 *
 * 判据必须落在**列表最后一项**上，不能只看「当前露出来的最后一项」：一轮回答的底边正好
 * 落到视口底边时，后者会算出 0（「已到底」）—— 于是「回到底部」按钮在会话中间一闪一灭，
 * 手指一松开还会被当成已贴底而弹回底部（用户实测反馈）。
 *
 *  - 滚不动了（含底部 contentPadding）⇒ 0（这就是「整个会话的底部」）；
 *  - 最后一项没露出来（或视口里一行都没有）⇒ 后面还有没看到的内容 ⇒ Int.MAX_VALUE；
 *  - 否则 = 最后一项底边到「视口底边 - 底部内边距」的距离（在底部时正好 0，可为负）。
 */
internal fun bottomGapOf(
    canScrollForward: Boolean,
    lastIndex: Int?,
    lastOffset: Int,
    lastSize: Int,
    totalItemsCount: Int,
    viewportEndOffset: Int,
    afterContentPadding: Int,
): Int {
    if (!canScrollForward) return 0
    if (lastIndex == null) return Int.MAX_VALUE
    if (lastIndex != totalItemsCount - 1) return Int.MAX_VALUE
    return (lastOffset + lastSize) - (viewportEndOffset - afterContentPadding)
}

/**
 * 贴底请求的第一道门（主路径与兜底补钉共用）：**「不是读者造成的位移」才允许贴**。
 *
 *  - [following]：跟随意图已经交出去（读者动作 / 显式导航）就不贴；
 *  - [touching]：手指还在屏幕上就不贴（dsh 的 `pending`）；
 *  - [scrolling]：读者正在滚（拖拽 / 惯性）—— dsh 的 `onScroll` 只在 `!scroll.movedByReader`
 *    时才 `followTail()`，读者的每一次滚动投递都带 movedByReader，那一拍**不贴底**。
 *    少了这道门，流式期间内容每长高一点都会发一次贴底请求，把读者那一下「极小幅度快速上滑」
 *    的惯性按回去：真机上就是「agent 思考时上滑被拉回底部、自动滚动没停下」（其他时候没有内容
 *    在长，所以看不出来）。
 */
internal fun pinAllowed(following: Boolean, touching: Boolean, scrolling: Boolean): Boolean =
    following && !touching && !scrolling

/**
 * 兜底补钉这一拍该不该动手：门（[pinAllowed]）过了之后，只有「**视口没动、布局却变了**」才补 ——
 * 位置动了就是读者自己滚出来的位移（dsh 的 `movedByReader`：只重算跟随意图、**不贴底**）；
 * gap 没变说明这一拍什么都没长，更不用动。
 */
internal fun shouldPinFix(moved: Boolean, gapChanged: Boolean): Boolean = !moved && gapChanged

/**
 * ⑧ 「折叠回去之后视口已经落在会话末端」这一拍要不要**恢复跟随意图**（右侧「回到底部」按钮据此消失）。
 *
 * 为什么需要它：跟随意图只在**读者移动**（[ChatScrollState.sampled]）与 ⑥ 两处被重算，而折叠一个
 * 展开体**不会改变第一条可见行与行内偏移**（视口锚在它上面），采样那一支根本不会响 —— 于是意图一直
 * 停在「读者翻过历史」，可内容其实已经缩到贴底了：按钮留在屏幕上，点它也不会滚（真机反馈的形状：
 * 「展开思考 → 折叠回去 → 底部早回到输入框上方了，按钮还在」）。
 *
 * 判据用**能不能往前滚**（`LazyListState.canScrollForward == false` = 视口已经贴着内容末端）——
 * 这是「会话底部就在输入框上方」的直接读数，与 25dp 阈值无关（那个阈值答的是「读者算不算还贴着底」，
 * 这里问的是「还有没有内容可滚」）。读者还在滑 / 手指还在屏幕上时一律不抢（与 [pinAllowed] 同一规矩），
 * 空列表也不算。
 */
internal fun shouldRestoreFollow(
    following: Boolean,
    touching: Boolean,
    scrolling: Boolean,
    canScrollForward: Boolean,
    itemCount: Int,
): Boolean = !following && !touching && !scrolling && !canScrollForward && itemCount > 0

/**
 * 自动滚动的**状态**（R14：原先散在 ChatScreen 主函数里的五个 `remember(conversationId)`）。
 *
 * 一份状态一条会话、跨页面存活：跟随意图、手指、方向、两个触发指纹合成一个对象之后，
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

    /**
     * 读者最近一次滚动是朝**会话开头**方向（往上翻）还是朝底部方向。
     *
     * 第 96 轮用户点名：右上角那两个快捷导航按钮只在往上翻时出现，往下滚的那一下立刻收起来、
     * 停在半路也不会自己冒出来（「回到底部」不看它，它只看跟随意图）。判据见 [scrollTowardTop]。
     */
    var towardTop by mutableStateOf(false)
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

    /** 手指按下 / 抬起（消息区那个 pointerInput 每一下都报，见 ChatScreen） */
    fun touch(down: Boolean) {
        touching = down
    }

    /** dsh 的 `pauseFollowing()`：释放底部跟随（读者动作、显式导航），见上面 ⑤ */
    fun pause() {
        follow.pause()
    }

    /** dsh 的 `toBottom()` / `followTail()`：无条件恢复跟随，见上面 ⑥ */
    fun resume() {
        follow.follow()
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
     * ② 读者位置采样（dsh 的 `onScroll` / `flushSample`）：位置一变就
     *  ① 记方向（[towardTop]，上面那两个按钮的判据）；
     *  ② 按 [slopPx] 阈值重算跟随意图 —— 这一条就是 dsh 的 `sample(metrics, movedByReader)`。
     *
     * 位置**没变**（点一下、按住不动）时什么都不做：意图由读者真实的移动决定，不由手指决定。
     * dsh 那一侧的等价规则是：读者滑到整个会话底部（`top >= floor`）就恢复跟随，其余交给滚动落定的采样。
     *
     * @return 位置是否真的移动过（调用方据此推进自己的 `previous`）
     */
    fun sampled(previous: Pair<Int, Int>, current: Pair<Int, Int>, atBottom: Boolean): Boolean {
        if (current == previous) return false
        towardTop = scrollTowardTop(previous, current)
        follow.sample(atBottom = atBottom)
        return true
    }

    /**
     * ③ 这一拍该不该发贴底请求（请求本身在 ChatScreen 的 `SideEffect` 里发，见上面 ④）。
     *
     * [content] 传 `items`（列表任何一个输入变了它就换实例）、[viewport] 传 IME 下边距。
     * 指纹**先记后判**：即使这一拍被 [pinAllowed] 挡掉，下一次也不该拿旧指纹去比。
     *
     * @return true = 内容或视口变了、且 [pinAllowed] 的门过了 ⇒ 调用方贴底
     */
    fun pinRequest(content: Any?, viewport: Int, scrolling: Boolean): Boolean {
        val contentChanged = pinnedContent !== content
        val viewportChanged = pinnedViewport != viewport
        pinnedContent = content
        pinnedViewport = viewport
        pinReason = (if (contentChanged) "content" else "") + (if (viewportChanged) "vp" else "")
        if (!pinAllowed(follow.following, touching, scrolling)) return false
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
        return !first && following && !touching && total > 0
    }
}

/**
 * 贴底的**兜底补钉**：只挡「**视口没动、布局却变了**」的那一类 —— 最典型的是图片 / LaTeX 异步
 * 解码完成之后那一行变高（那时没有任何状态写进本屏，上面的指纹不会变）。这是 dsh 的
 * `ResizeObserver(column)` 那一条的等价物，只是这里读的是**刚量出来的** `layoutInfo`，
 * 所以它天生晚一帧：layoutInfo 由 LazyColumn 在测量时写入，要等它写完才读得到差值，
 * 而 scrollBy 要下一次测量才生效 —— 它只是兜底，不再是主路径（主路径已经把那一帧的错位去掉）。
 *
 * **位置动了就一律不补** —— 这是 dsh 的 `movedByReader`（`ChatReading.onScroll`：读者自己滚出来的
 * 位移只重算跟随意图，**不贴底**）。少了这一条，读者那一下「极小幅度快速上滑」会被一帧一帧地
 * 拽回底部：内容一上一下地抖，而且会话结束之后也停不下来（用户第 122 轮报的就是这个形状）。
 * 位置没变而 gap 变了 = 内容自己长高/变矮（图片解码、字体度量）⇒ 那才是这一条要补的。
 *
 * 补法是 scrollBy(差值)（像素级），不是「滚到最后一项」的请求（那个按旧行高推、会过头）。
 *
 * 容差用 [PIN_SLOP_PX]（1px，只吸收取整误差），**不用** [ChatScrollState.slopPx] 那 25dp ——
 * 后者是「读者算不算已经贴着底部」的判据（dsh 的 atBottom 阈值），而一行子调用只有 22dp：
 * 拿它当补钉容差时，新长出来的一行根本够不着阈值（第 105 轮第 2 条就是从这个形状来的）。
 *
 * 收敛性：补完 gap 归零 ⇒ 没有新的测量 ⇒ snapshotFlow 不再发；内容继续长才继续补。
 * 读者交出跟随（[ChatScrollState.following]）或手指还在屏幕上（[ChatScrollState.touching]）时一律不动。
 */
@Composable
internal fun PinFixEffect(listState: LazyListState, scroll: ChatScrollState) {
    LaunchedEffect(listState) {
        var lastPosition: Pair<Int, Int>? = null
        var lastGap = 0
        snapshotFlow { listState.layoutInfo }.collect {
            val count = listState.layoutInfo.totalItemsCount
            if (count == 0) return@collect
            // ⑧ 折叠之后视口已经贴到内容末端：把跟随意图收回来（按钮据此消失，见 shouldRestoreFollow）
            if (
                shouldRestoreFollow(
                    following = scroll.following,
                    touching = scroll.touching,
                    scrolling = listState.isScrollInProgress,
                    canScrollForward = listState.canScrollForward,
                    itemCount = count,
                )
            ) {
                scroll.resume()
            }
            if (!pinAllowed(scroll.following, scroll.touching, listState.isScrollInProgress)) return@collect
            val position = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            val gap = listState.bottomGap()
            val moved = position != lastPosition
            val changed = gap != lastGap
            lastPosition = position
            lastGap = gap
            // 读者自己滚出来的位移一律不补 —— 判据见 [shouldPinFix]
            if (!shouldPinFix(moved, changed)) return@collect
            when {
                // 最后一项还没露面：按像素补不了，退回「滚到最后一项」
                gap == Int.MAX_VALUE -> listState.requestScrollToItem(count - 1, BOTTOM_REQUEST_OFFSET)
                gap > PIN_SLOP_PX -> {
                    if (com.adsh.app.BuildConfig.DEBUG) {
                        trace(
                            "pin fix",
                            "gap=" + gap + " items=" + count + " canFwd=" + listState.canScrollForward +
                                " first=" + listState.firstVisibleItemIndex + "+" + listState.firstVisibleItemScrollOffset,
                        )
                    }
                    listState.scrollBy(gap.toFloat())
                }
            }
        }
    }
}

// 「展开工具详情」不做任何补偿滚动（第 87 轮用户点名，第 122 轮并入上面的 ⑤）。
//
// 这里原先有一套 revealAfterExpand：展开后等这一行重新量过，再 animateScrollBy 把它的底边带进
// 视口。真机上的表现是用户报的两条 bug —— ①运行中展开会「向上展开」：那一行常常正贴着视口底边，
// reveal 把行首滚到视口顶边，上面的内容整段被推上去；②详情很长时滚动落点不对、还和流式重排打架，
// 闪一下。现在的口径：**列表正序 + 读者动作交出跟随**，展开天然向下长、上面的行一动不动
//（「向下展开，上方不动」）。展开体落到视口下面时由读者自己滑 —— 不替读者滚。
