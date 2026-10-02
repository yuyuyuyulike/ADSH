package com.adsh.app.ui

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowCircleUp
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.adsh.app.core.tools.Answer
import com.adsh.app.core.tools.PlanReview
import com.adsh.app.core.tools.Question
import com.adsh.app.core.tools.TodoItem
import com.adsh.app.core.tools.TodoStore
import com.adsh.app.core.tools.UserQuestionChannel
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.delay

/**
 * 跨页面存活的对话滚动状态（dsh 的 chatScroll 是模块级、按会话存档的）。
 *
 * 如果 LazyListState 建在 ChatScreen 里，去设置页 / 文件页再回来会重新建一个：
 * 位置丢失，只能先画一帧错的位置再滚 —— 就是「切回来闪一下」。
 * 这里实现成**单槽**（只记住最后一条会话的 state）：覆盖的是「去设置页 / 文件页再回来」这条
 * 最常见的路径，回来时位置原样还在、要贴底时同一帧就落到底。在多个会话之间来回切仍会重建，
 * 那一步的位置恢复没有实现（dsh 那边是模块级、真按会话存档的）。
 */
private object ChatScrollStore {
    private var conversationId: Long? = null
    private var state: LazyListState? = null

    fun stateFor(conversationId: Long?, initialIndex: Int): LazyListState {
        val existing = state
        if (existing != null && this.conversationId == conversationId) return existing
        return LazyListState(firstVisibleItemIndex = initialIndex.coerceAtLeast(0)).also {
            this.conversationId = conversationId
            state = it
        }
    }
}

/**
 * 跟随意图（dsh 的 `ScrollFollow`，见 use-scroll-follow.ts；构造处传的阈值 = 25px）。
 *
 * dsh 把「内容长出来要不要跟着走」做成一个**独立于当前滚动位置**的开关：它只在
 * `sample(metrics, movedByReader)` 里、且读者**真的移动过**时才重算 —— 内容每帧长高都不会让它
 * 翻转，所以浏览器那边一个迟滞都不需要，「回到底部」按钮的判据也就是 `!followingTail`。
 *
 * 这里照搬这一条：[following] 只在三处被写 —— 读者的位置采样（[sample]）、读者动作与显式导航
 * （[pause]）、用户自己发消息与点回到底部（[follow]）。**别的地方一律只读**。
 */
private class ScrollFollow(initial: Boolean = true) {
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
 * 离「整个会话真正的底部」还有多少像素。0 = 就在底部；Int.MAX_VALUE = 最后一项还没露出来。
 *
 * 判据必须落在**列表最后一项**上，不能只看「当前露出来的最后一项」：一轮回答的底边正好
 * 落到视口底边时，后者会算出 0（「已到底」）—— 于是「回到底部」按钮在会话中间一闪一灭，
 * 手指一松开还会被当成已贴底而弹回底部（用户实测反馈）。
 *
 *  - 滚不动了（含底部 contentPadding）⇒ 0（这就是「整个会话的底部」）；
 *  - 最后一项没露出来 ⇒ 后面还有没看到的内容 ⇒ Int.MAX_VALUE；
 *  - 否则 = 最后一项底边到「视口底边 - 底部内边距」的距离（在底部时正好 0，可为负）。
 */
private fun LazyListState.bottomGap(): Int {
    val info = layoutInfo
    if (!canScrollForward) return 0
    val last = info.visibleItemsInfo.lastOrNull() ?: return Int.MAX_VALUE
    if (last.index != info.totalItemsCount - 1) return Int.MAX_VALUE
    return (last.offset + last.size) - (info.viewportEndOffset - info.afterContentPadding)
}


/** LazyColumn 的 item key：一轮被拆成了多行，每一行都要有自己的稳定 key */
private fun transcriptKey(item: ChatItem): String = when (item) {
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

/** dsh 的空态文案（hero.headline / hero.preview） */
/** dsh 的跟随阈值（`useScrollFollow(following, 25)`）：离底部 25dp 以内就算「读者还在底部」 */
private const val FOLLOW_THRESHOLD_DP = 25f

/** 流式采样节拍（ms）：30fps 量级的重绘节流 */
private const val STREAM_TICK_MS = 33L

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
private const val PIN_SLOP_PX = 1

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

private const val HERO_HEADLINE = "探索未至之境"
private const val HERO_BADGE = "预览版"

// 对话流节点模型（dsh 的 Chat Node）与整形逻辑在 TurnList.kt

@Composable
fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onClearError: () -> Unit = {},
    onOpenWorkspaceFiles: () -> Unit = {},
    /** 已绑定的工作区（dsh 侧栏的树）与「添加工作区」入口 */
    workspaces: List<com.adsh.app.core.data.WorkspaceEntity> = emptyList(),
    onAddWorkspace: () -> Unit = {},
    onPickWorkspace: (Long) -> Unit = {},
    modelGroups: List<ChatViewModel.ModelGroup> = emptyList(),
    onSelectModel: (String, String) -> Unit = { _, _ -> },
    /** 模型菜单里各提供方的余额（DeepSeek 专有）+ 打开菜单时的刷新入口 */
    balances: Map<String, ChatViewModel.BalanceState> = emptyMap(),
    onRefreshBalance: () -> Unit = {},
    efforts: List<Pair<String, String>> = emptyList(),
    onSelectEffort: (String) -> Unit = {},
    onSelectPermission: (String) -> Unit = {},
    onImportAttachments: (List<android.net.Uri>) -> ImportResult = { ImportResult() },
    onRemoveAttachment: (String) -> Unit = {},
    onTogglePlan: () -> Unit = {},
    onSetPlan: (Boolean) -> Unit = {},
    onCompact: () -> Unit = {},
    question: UserQuestionChannel.Pending? = null,
    onAnswer: (List<Answer>) -> Unit = {},
    onSkipQuestion: () -> Unit = {},
    /** 待审批的越权请求（dsh 的 ApprovalPanel）：非空时输入框上方出现审批卡 */
    approval: com.adsh.app.core.tools.ApprovalChannel.Pending? = null,
    onAllowApproval: () -> Unit = {},
    onRejectApproval: () -> Unit = {},
    /** dsh 轮尾的「在新对话中分支」：从某一轮分叉出一条新会话 */
    onBranch: (Long) -> Unit = {},
    /**
     * 轮尾交付物卡片被点开（dsh 的 PresentedFileCard.onPreview → openFile(path)）：
     * 参数是交付物声明的路径（工作区相对路径，工作区外是绝对路径），由上层进文件预览。
     */
    onOpenDeliverable: (String) -> Unit = {},
    /** 清空排队发送的消息（dsh 的 queue chip） */
    onClearQueued: () -> Unit = {},
    /**
     * 掉线重连条被点了一下：立刻重发（dsh 的 `connection.reconnect()` —— 退避序列归零、
     * 马上重连，不等剩下的退避时间）。
     */
    onRetryConnection: () -> Unit = {},
) {
    val context = LocalContext.current
    val palette = LocalDshPalette.current
    // dsh 的 --dsh-content-font-size（12..17，默认 14）：只缩放会话内容
    val contentFontSize = state.contentFontSize
    // dsh 的 compactTranscript：紧凑 = 已完成轮次折成一行摘要
    val contentCompact = state.transcriptView == com.adsh.app.core.data.SettingsStore.TRANSCRIPT_COMPACT
    var draft by rememberSaveable { mutableStateOf("") }
    /**
     * 父层「主动改写草稿」的次数。输入框（Composer）自己持有文本，只有这个计数变化时
     * 才会把 draft 回灌进去 —— 见 DshComposer 里 fieldValue 的注释（中文输入法的组合区
     * 就是在回灌时被丢掉的）。
     */
    var draftRevision by remember { mutableIntStateOf(0) }
    fun writeDraft(text: String) {
        draft = text
        draftRevision++
    }
    var requestPermission by remember { mutableStateOf(false) }
    // 触发菜单（dsh 的 ui-input-trigger）：「+」按下的那一次打开的是**全量菜单**，
    // 记下按下时的草稿 —— 草稿一变（用户接着打字）就交回「键入 `/`」那条路径，
    // 也就是 dsh 的 track 语义（launcher 打开的那一次 track 不关菜单，之后打字接管）。
    var launcherDraft by remember { mutableStateOf<String?>(null) }
    // 输入框里的三个弹层（权限 / 模型 / 上下文）由这里托管：它们互斥，
    // 并且只要是打开状态，消息区就要铺一层「点空白处关闭」的拦截层
    var composerMenu by remember { mutableStateOf<String?>(null) }
    // dsh 的 dismissed（input-trigger 的 controller.ts:148-157）：同一个 token、同一个查询被关掉之后
    // 不再自动重开；输入新的查询（或换到另一个 token）才重新武装。
    var paletteMuted by remember { mutableStateOf<String?>(null) }
    val attachLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        // 选中的文件复制进「工作区/.adsh/attachments/<会话>/」，成为这条会话的附件（删会话时一起删）
        // 导入成功是「看得见的结果」（附件卡片会出现在输入框里），不再弹提示；
        // 只有失败才提示，因为那是用户看不出来的
        val result = onImportAttachments(uris)
        if (result.failures.isNotEmpty()) {
            android.widget.Toast.makeText(context, "导入失败：" + result.failures.first(), android.widget.Toast.LENGTH_LONG).show()
        }
    }
    var statsOpen by rememberSaveable { mutableStateOf(false) }
    // 哪些轮次的过程窗口被展开了。状态托管在这里（而不是折叠行内部），因为过程行是
    // **独立的 LazyColumn item**：展不展开决定列表里有哪几行（见 ChatItem 的注释）。
    val foldOpen = remember { mutableStateMapOf<Long, Boolean>() }
    // 流式正文与思考都按 ~33ms 采样一次、并且**平滑显现**再进列表。
    // **采样状态就放在这里（ChatScreen 自己的作用域）**，
    // 不能抽成一个「返回采样值」的子 composable：那种写法下写入只让子作用域失效，
    // 而 items 的 remember 与贴底的 SideEffect 都在**这一层** —— 采样落地后没人重组这一层，
    // 表现就是流式正文一直不动、直到这一轮定稿才整段出现（用户实测的「全文一次展示」）。
    // 放在这里，写 sampledStreaming 就是写这一层读的 state：采样一落地必定重组这一层。
    var sampledStreaming by remember(state.conversationId, state.liveTurnId) {
        mutableStateOf(state.streaming)
    }
    /**
     * 思考的采样值（与正文同一套节拍）。
     *
     * 以前思考是**逐 token 直接进列表**的：模型每吐一个字就重组整屏、重排一次思考行 ——
     * 思考慢的时候（一次只来几个字）看起来就是一顿一顿的（用户第 95 轮第 1 条）。
     * 现在与正文一样先采样再显现，两条尾巴的节拍也一致。
     */
    var sampledReasoning by remember(state.conversationId, state.liveTurnId) {
        mutableStateOf(state.reasoning)
    }

    val latestStreaming = rememberUpdatedState(state.streaming)
    val latestReasoning = rememberUpdatedState(state.reasoning)

    val latestSending = rememberUpdatedState(state.sending)
    /** 本步的思考块是不是已经结束（见上面那个采样循环：结束就一次性补齐，不再滴） */
    val latestReasoningRunning = rememberUpdatedState(state.reasoningRunning)
    /** 模型是不是已经在写工具调用的参数了（= 这一段的正文已经写完，见 [ChatUiState.toolArgsFlowing]） */
    val latestToolArgs = rememberUpdatedState(state.toolArgsFlowing)
    /**
     * 流式文本**被清零 / 换了一段**时立刻跟上（不等 33ms 的采样节拍）——第 81 轮修。
     *
     * 少这一条就会看到「同一个正文出现两次、下面的工具行被顶下去再弹回来」（用户实测的
     * 「工具调用展示闪烁/撕裂」，而且只在模型**在工具调用之间说话**时出现）。机制：
     * AgentLoop 是一步一落库的 —— assistant 行先写库，再往日志里追加 `SessionBody.Step`；轮到界面
     * 处理时 `state.streaming` 已经清零、`state.messages` 已经带上这一步的那一行，**但
     * [sampledStreaming] 要等下一次采样（最多 33ms ≈ 两帧）才清**。这几帧里 `buildChatItems`
     * 会同时把「库里那一步的正文」与「还没清掉的流式尾巴」放进同一轮 —— 于是正文重复一行，
     * 排在它下面的工具行位置整个跳一下。增长仍走 33ms 节拍（那是重绘节流），只有
     * 「变短 / 换段」这一种变化立刻生效。思考那一支同理（见 sampledReasoning）。
     */
    LaunchedEffect(state.conversationId, state.liveTurnId) {
        snapshotFlow { latestStreaming.value }.collect { live ->
            if (live.length < sampledStreaming.length || !live.startsWith(sampledStreaming)) {
                sampledStreaming = live
            }
        }
    }
    LaunchedEffect(state.conversationId, state.liveTurnId) {
        snapshotFlow { latestReasoning.value }.collect { live ->
            if (live.length < sampledReasoning.length || !live.startsWith(sampledReasoning)) {
                sampledReasoning = live
            }
        }
    }

    LaunchedEffect(state.sending, state.conversationId, state.liveTurnId) {
        while (latestSending.value) {
            kotlinx.coroutines.delay(STREAM_TICK_MS)
            // **正文**：模型开始写工具调用的参数（这一段正文已经写完，模型不会再往它后面加字）
            // ⇒ 剩下的尾巴一次性补齐，不再按拍滴完。少了这一条，模型吐一段 run_code 程序的参数
            // 可能要好几秒，而界面这段时间只是在滴正文尾巴 —— 用户看到的就是「agent 都开始调用
            // 工具了，输出才结束」（第 105 轮第 1 条）。
            sampledStreaming = if (latestToolArgs.value) {
                latestStreaming.value
            } else {
                revealFrom(sampledStreaming, latestStreaming.value)
            }
            // **思考**：本步的思考块结束（模型开始写正文或工具参数，见 ChatState.reasoningRunning）
            // ⇒ 同样补齐：思考行的形态在这一刻已经要切回「第一行摘要」，尾巴不该继续滴
        // （旧写法要等这一步定稿把 sampledReasoning 清零，中间那几百毫秒是白等的）。
            sampledReasoning = if (!latestReasoningRunning.value) {
                latestReasoning.value
            } else {
                revealFrom(sampledReasoning, latestReasoning.value)
            }

        }
        // 定稿时立刻补齐最后一帧，不能等下一次采样（那时循环已经退出了）
        if (sampledStreaming != latestStreaming.value) sampledStreaming = latestStreaming.value
        if (sampledReasoning != latestReasoning.value) sampledReasoning = latestReasoning.value
    }

    // 对话流：库里的消息 + 正在流式的这一轮（dsh 的 Chat Node 列表）
    //
    // 用 derivedStateOf（而不是 remember(各种 key)）：折叠状态是 SnapshotStateMap，
    // 读它就会被记账 —— 某一轮展开/收起时这里自动重算，不需要再维护一个「修订号」
    // 手动把它踢醒（旧写法的 foldRevision++ 就是这么来的）。
    //
    // streamingActive 读的是**线上**（未采样）的 state.streaming：它与 state.messages 出自
    // 同一次状态更新（ChatViewModel 收到 `SessionBody.Step` 时「从库里重读 + 清流式缓冲」一起做），
    // 于是「库行到位」与「尾巴关门」原子发生 —— 采样值落后最后几个 token 也不会把同一段正文挂两行
    // （第 87 轮报错工具行「出现时很大」的根因，详见 TurnList.addLive 的注释）。
    // 它只是个布尔，每 token 翻转不了几次，放进 key 不会破坏采样节流。
    val streamingActive = state.streaming.isNotEmpty()
    /** 思考尾巴的原子门（与 [streamingActive] 同一个理由，见 addLive 的注释）：与 messages 同一次更新。 */
    val reasoningActive = state.reasoning.isNotEmpty()
    val items by remember(
        state.messages,
        sampledStreaming,
        streamingActive,
        sampledReasoning,
        reasoningActive,
        state.reasoningRunning,
        state.liveTurn.calls,
        state.liveTurn.subCalls,
        state.liveTurnId,
        contentCompact,
    ) {
        derivedStateOf {
            buildChatItems(
                messages = state.messages,
                streaming = sampledStreaming,
                reasoning = sampledReasoning,
                liveCalls = state.liveTurn.calls,
                liveSubCalls = state.liveTurn.subCalls,
                reasoningRunning = state.reasoningRunning,
                liveTurnId = state.liveTurnId,
                compact = contentCompact,
                foldOpen = { foldOpen[it] == true },
                streamingActive = streamingActive,
                reasoningActive = reasoningActive,
            )
        }
    }
    val total = items.size
    val hasConversation = items.isNotEmpty()
    // 最后一轮的 key（只有它能从轮尾分叉）
    val lastTurnKey = items.lastOrNull { it is ChatItem.TurnTail }?.let { (it as ChatItem.TurnTail).view.key } ?: -1L
    /**
     * 任务横窗的清单**提前收到 ChatScreen 这一层**（以前只在 [TodoDock] 内部收集）。
     *
     * 目的只有一个：任务横窗出现 / 变高 / 变矮时，**这一层要在同一帧重组**。贴底请求是在重组
     * 那一帧、测量之前发出去的（第 107 轮起无条件，见下面「自动滚动」那段），所以只要本屏知道
     * 「自己该重组了」，这一下就落在同一帧里；如果只在子 composable 里收集，dock 高度变了而
     * ChatScreen 不知道自己该重组，贴底就要等下一帧 —— 用户看到的就是「任务栏一出现，底部那行
     * 先被盖住再跳上来」。同一条 StateFlow 多收一次没有额外成本（它是 StateFlow，不会重放）。
     */
    val todos by remember(state.conversationId) {
        state.conversationId?.let { TodoStore.flow(it) }
            ?: kotlinx.coroutines.flow.MutableStateFlow<List<TodoItem>>(emptyList())
    }.collectAsStateWithLifecycle()

    // ---------------------------------------------------------------- 自动滚动
    //
    // 整套口径照 dsh 的 use-scroll-follow / use-chat-viewport / use-chat-reading / use-chat-scroll：
    //
    // ① **状态只有一个「跟随意图」**（dsh 的 `ScrollFollow.following` = `reading.state.followingTail`）：
    //    「内容长出来时要不要把视口钉到内容末端」。它与「现在离底部多少像素」无关 —— 内容每帧长高
    //    都不会让它翻转，所以 dsh 的「回到底部」判据就是 `!followingTail`，一个像素迟滞都不需要。
    // ② **只有读者自己移动过才重算意图**（dsh 的 `sample(metrics, movedByReader)`）：手势 / 惯性滚动让
    //    「第一条可见行 + 行内偏移」变了，才按 25dp 阈值重算（`nearBottom`：落在阈值以内就继续跟随）。
    //    只是点一下、按住不动都不改意图 —— 与 dsh 一样（`movedByReader` 为假时意图原样留着）。
    // ③ **贴底请求（dsh 的 `followTail()` = `el.scrollTop = el.scrollHeight`）只由「变化」触发**：
    //    内容变了（`items` 换了实例 = 列表的任何一个输入变了），或视口高度变了（键盘）。dsh 那边是
    //    `processContent`（内容提交）+ `ResizeObserver(column / scroller / composer)`（尺寸变化）。
    //    **不再「每重组一次就贴一次底」**：会话停下来之后本屏仍会因为别的事重组（后台任务轮询、
    //    任务横窗、连接状态、用量…），每重组一次贴一次 = 「会话结束后自动滚动还在跑」。
    //    静止的会话现在一次请求都不发。
    // ④ 请求在 `SideEffect` 里发出：它是「下一次测量用的位置」而不是一次滚动，落在**本帧测量之前**，
    //    新内容第一次被画出来位置就是对的（dsh 靠 ResizeObserver 回调落在本次布局之后、绘制之前）。
    // ⑤ **读者动作的优先级最低**：展开 / 收起会话里的任何一行、打开任务列表，一律先交出跟随。
    //    这一条是本 App 特有的：dsh 的展开体在**限高的内层滚动区**里（ChatGroupSeat 的
    //    `data-step-process-body`），外层视口本来就不动；这里展开体直接长在会话流里，不交出去就会被
    //    流式贴底顶上去。第 108 轮用户口径：「展开天然向下长、上方一动不动」。
    // ⑥ 用户自己发的一条消息（dsh 的 `ownInput`）与点「回到底部」（`returnToBottom`）无条件恢复跟随。

    // 每条会话一份**跨页面存活**的滚动状态（初值 = 第 0 项 = 最新的一条）
    // 初值给一个超大索引：Compose 会把它夹到最后一项，于是**进会话就是底部**
    // （dsh 打开会话也是直接贴底，不会先闪一下顶部）
    val listState = ChatScrollStore.stateFor(state.conversationId, Int.MAX_VALUE)
    /** 跟随意图（dsh 的 ScrollFollow）：**自动滚动唯一的状态**，只有上面 ②⑤⑥ 会写它 */
    val follow = remember(state.conversationId) { ScrollFollow() }
    /** 手指是否按在消息区：按着的时候一下都不许贴（dsh 的 `pending`：读者输入还没采样完就不动视口） */
    var touching by remember(state.conversationId) { mutableStateOf(false) }
    /** dsh 的 `useScrollFollow(state.followingTail, 25)`：25dp 以内算「读者还在底部」 */
    val followSlopPx = with(LocalDensity.current) { FOLLOW_THRESHOLD_DP.dp.toPx() }.toInt()
    /** dsh 的 `followTail()`：把视口钉到内容末端（本帧测量之前生效，见上面 ④） */
    fun pinToBottom() {
        if (total > 0) listState.requestScrollToItem(total - 1, BOTTOM_REQUEST_OFFSET)
    }
    /**
     * 读者动作（展开 / 收起会话里的任何一行、打开任务列表）：**先把跟随交出去**，见上面 ⑤。
     * dsh 的对照是 `pauseFollowing()`（显式导航时释放底部跟随）。
     *
     * 这里以前还立一个粘住的 `readerHold`（挡住松手时那两条「恢复跟随」的规则）—— 现在不需要了：
     * 意图只由**读者的真实移动**重算，展开这一下没有移动，所以它自己就会一直停到读者滑回底部为止。
     */
    val readerAction: () -> Unit = remember(follow) { { follow.pause() } }
    /**
     * 读者最近一次滚动是朝**会话开头**方向（往上翻）还是朝底部方向。
     *
     * 第 96 轮用户点名：右上角那两个快捷导航按钮只在往上翻时出现，往下滚的那一下立刻收起来、
     * 停在半路也不会自己冒出来（「回到底部」不看它，它只看跟随意图）。
     *
     * 判据是**位置增量**（第一条可见行 + 行内偏移的变化方向），不是
     * [LazyListState.lastScrolledBackward]：那两个布尔在「这一帧没动」时**可能同时为 true**，
     * 按「先看 backward」读会把停住当成往上滚（真机上就是「往下滑了按钮还在」）。位置增量没有这个
     * 歧义，而且惯性滑动同样会逐帧上报。
     */
    var scrollTowardTop by remember(state.conversationId) { mutableStateOf(false) }
    /**
     * 读者位置采样（dsh 的 `onScroll` / `flushSample`）：位置一变就
     *  ① 记方向（上面那个按钮判据）；
     *  ② 按 25dp 阈值重算跟随意图 —— 这一条就是 dsh 的 `sample(metrics, movedByReader)`。
     *
     * 位置**没变**（点一下、按住不动）时什么都不做：意图由读者真实的移动决定，不由手指决定。
     * dsh 那一侧的等价规则是：读者滑到整个会话底部（`top >= floor`）就恢复跟随，其余交给滚动落定的采样。
     */
    LaunchedEffect(listState, followSlopPx) {
        var previous = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { current ->
                if (current == previous) return@collect
                val towardTop = current.first < previous.first ||
                    (current.first == previous.first && current.second < previous.second)
                if (towardTop != scrollTowardTop) scrollTowardTop = towardTop
                previous = current
                follow.sample(atBottom = listState.bottomGap() <= followSlopPx)
            }
    }
    /**
     * 读者是否已经滚到**会话最顶上**（第 95 轮：右上角那两个快捷导航按钮到顶就收起来）。
     *
     * 用 derivedStateOf 而不是直接读 `listState.firstVisibleItemIndex`：它只在**布尔值真的翻转**
     * 时才让 ChatScreen 重组，滚动过程中每帧重算但不会每帧重组（返回值没变就直接丢弃）。
     */
    val atTop by remember(listState) {
        derivedStateOf { !listState.canScrollBackward }
    }
    /**
     * 视口上方最近的一条用户消息在 [items] 里的下标（「跳转到上一轮的用户消息」的目标）。
     *
     * 判据是**严格在第一条可见行之上**：读者停在某一轮中间时，那一轮开头的用户消息就在上面；
     * 没有（= 已经在会话开头这一轮里）时返回 null，那个按钮就不画。
     */
    val previousUserId by remember(listState, items) {
        derivedStateOf { previousUserItemIndex(items, listState.firstVisibleItemIndex) }
    }
    /** 点消息区空白处时把光标与键盘收起来（判据见 [shouldClearComposerFocus]） */
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    /**
     * 「用户自己发了一条消息」= 会话里最后一条**真的用户消息**（`role = user` 且没有 `name`）换了 id。
     *
     * 通知（任务完成通知的两种形态、插话、权限预设切换…）在库里也是 `role = user`，但它们都带
     * `name` —— 少了这个过滤，一条后台任务通知落库就会把正在翻历史的读者一把拽回底部
     * （dsh 的 `lastIsUser` 同样只认真正的用户节点）。
     */
    val lastUserId = remember(state.messages) {
        state.messages.lastOrNull { it.role == "user" && it.name == null }?.id ?: 0L
    }
    // 键盘弹出 / 收起时，消息必须跟着输入框一起上移。
    //
    // 键盘改变的是列表**视口高度**（根布局的 imePadding 把输入框顶上去、列表变矮），那是纯布局
    // 事件 —— 它不会让本屏重组。**这一读**是必须的：读一次 IME 的下内边距，键盘动画的每一帧都在
    // 改它，于是键盘动一帧、本屏就重组一帧，下面那个贴底指纹跟着变一帧，贴底请求也就跟着发出去，
    // 消息与输入框严丝合缝地同步上移。
    //
    // 旧写法是一个 `LaunchedEffect(imeBottom) { withFrameNanos {}; scrollToBottom() }`：它先等一帧
    // 再滚，而 imeBottom 每帧都在变 —— 每次变化都**取消**上一个还没跑到 scrollToBottom 的协程，
    // 于是滚动永远比键盘慢一拍（用户实测的「呼出键盘不跟手」）。
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    /**
     * 上一次贴底的**触发指纹**：[0] = `items` 的实例身份（列表的任何一个输入变了它就换实例），
     * [1] = 视口下边距（键盘）。**非 State**：写它不该触发重组。
     */
    val pinKey = remember(state.conversationId) { arrayOfNulls<Any>(2) }
    /** 上一次「用户自己的消息」的 id（dsh 的 `ownInput` 比的也是上一次提交）；-1 = 这一屏刚进来 */
    val ownInputMark = remember(state.conversationId) { longArrayOf(-1L) }
    SideEffect {
        // ⑥ 用户自己发的一条消息刚落进会话：无条件恢复跟随（dsh 的 `ownInput` -> `followTail()`）
        if (lastUserId != ownInputMark[0]) {
            if (ownInputMark[0] >= 0L) follow.follow()
            ownInputMark[0] = lastUserId
        }
        // ③ 内容 / 视口变了才发贴底请求（见上面「自动滚动」的长注释）。
        // 请求是「下一次测量用的位置」，本帧测量时它已经生效，所以新内容第一次被画出来就落在正确的
        // 位置，不存在「先画错、下一帧再补」的那一帧。
        val contentChanged = pinKey[0] !== items
        val viewportChanged = pinKey[1] != imeBottom
        pinKey[0] = items
        pinKey[1] = imeBottom
        if ((!contentChanged && !viewportChanged) || !follow.following || touching) return@SideEffect
        if (com.adsh.app.BuildConfig.DEBUG) {
            trace(
                "pin req",
                "why=" + (if (contentChanged) "content" else "") + (if (viewportChanged) "vp" else "") +
                    " items=" + total + " text=" + sampledStreaming.length +
                    " think=" + sampledReasoning.length +
                    " calls=" + state.liveTurn.calls.size + " subs=" + state.liveTurn.subCalls.size +
                    " gap=" + listState.bottomGap() +
                    " first=" + listState.firstVisibleItemIndex + "+" + listState.firstVisibleItemScrollOffset,
            )
        }
        pinToBottom()
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
     * 容差用 [PIN_SLOP_PX]（1px，只吸收取整误差），**不用** [followSlopPx] 那 25dp ——
     * 后者是「读者算不算已经贴着底部」的判据（dsh 的 atBottom 阈值），而一行子调用只有 22dp：
     * 拿它当补钉容差时，新长出来的一行根本够不着阈值（第 105 轮第 2 条就是从这个形状来的）。
     *
     * 收敛性：补完 gap 归零 ⇒ 没有新的测量 ⇒ snapshotFlow 不再发；内容继续长才继续补。
     * 读者交出跟随（[follow]）或手指还在屏幕上（[touching]）时一律不动 —— 与「读者优先级最高」同一条规矩。
     */
    LaunchedEffect(listState) {
        var lastPosition: Pair<Int, Int>? = null
        var lastGap = 0
        snapshotFlow { listState.layoutInfo }.collect {
            if (!follow.following || touching) return@collect
            val count = listState.layoutInfo.totalItemsCount
            if (count == 0) return@collect
            val position = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            val gap = listState.bottomGap()
            val moved = position != lastPosition
            val changed = gap != lastGap
            lastPosition = position
            lastGap = gap
            if (moved || !changed) return@collect
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
    // 「展开工具详情」不做任何补偿滚动（第 87 轮用户点名，第 122 轮并入上面的 ⑤）。
    //
    // 这里原先有一套 revealAfterExpand：展开后等这一行重新量过，再 animateScrollBy 把它的底边带进
    // 视口。真机上的表现是用户报的两条 bug —— ①运行中展开会「向上展开」：那一行常常正贴着视口底边，
    // reveal 把行首滚到视口顶边，上面的内容整段被推上去；②详情很长时滚动落点不对、还和流式重排打架，
    // 闪一下。现在的口径：**列表正序 + 读者动作交出跟随**，展开天然向下长、上面的行一动不动
    // （「向下展开，上方不动」）。展开体落到视口下面时由读者自己滑 —— 不替读者滚。

    // 触发菜单 = dsh 的触发候选项（`+` 与键入 `/` 打开的是同一个菜单）：
    // 标题、说明、图标、小节都取自 dsh 的字典与 SECTION_ROWS（添加: file/goal/plan/feedback；
    // 指令: compact/permission/model/export），本 App 支持的这几条按 dsh 的顺序排列。
    val commands = listOf(
        // 「文件」= dsh 的 input.file（回形针图标）：把系统文件选择器搬进菜单里
        PaletteCommand(
            name = "file",
            label = "文件",
            description = "",
            icon = DshIcons.Paperclip,
            section = PALETTE_SECTION_ADD,
        ) { attachLauncher.launch(arrayOf("*/*")) },
        PaletteCommand(
            name = "plan",
            label = "计划",
            description = "进入或退出计划模式",
            icon = DshSettingIcons.ListPen,
            section = PALETTE_SECTION_ADD,
            // dsh 判的是「触发词在不在行首」（service.ts:247）：不在行首就滤掉带 hint 的行，
            // 也就是要占用输入框的 leadingInput 命令 —— 计划正是这一类（它把 `/plan ` 写进草稿）。
            // dsh 的 leadingInput：/plan 把 token 写进输入框（着色），用户在后面直接输入内容
            usableWithDraft = false,
        ) { writeDraft("/plan ") },
        PaletteCommand(
            name = "compact",
            label = "压缩",
            description = "压缩以上对话内容",
            icon = DshMenuIcons.Compact,
            section = PALETTE_SECTION_COMMANDS,
        ) { onCompact() },
        PaletteCommand(
            name = "permission",
            label = "权限",
            description = "切换权限预设（沙箱模式与审批策略）",
            icon = DshIcons.ShieldAlert,
            section = PALETTE_SECTION_COMMANDS,
            // 模式切换类：草稿非空时不在这个菜单里出现（输入框左下角本来就有常驻的权限入口）。
            // 详见 Palette.paletteUsableWith 的注释。
            usableWithDraft = false,
        ) { requestPermission = true },
        PaletteCommand(
            name = "export",
            label = "下载日志",
            description = "将当前会话内容导出为 ZIP",
            icon = DshMenuIcons.Download,
            section = PALETTE_SECTION_COMMANDS,
        ) { toastPath(context, exportSessionZip(context, state)) },
    )

    // ------------------------------------------------------------------ 触发菜单的可见性
    //
    // 「+」与键入 `/` 打开的是同一个菜单（dsh 的 input.commands），可见性完全由下面三条 dsh 规则
    // 算出来（不再有「弹过一次」这种状态）：
    //  1) 触发词得是**活的**：dsh 从光标往回扫，碰到空白就没有活触发词（core/detect.ts:63）——
    //     所以 `/plan 写一个页面` 不再挂着菜单（见 Palette.slashQueryOf）；
    //  2) 查询**精确命中**一条命令时命令已经完整，菜单让位，直接回车就能执行
    //     （dsh 的 resolveCommand：`descriptor.name === token`；见 Palette.paletteQueryComplete）；
    //  3) 一条候选都不剩时自动关闭（dsh 的 menuReduce：allReadyEmpty → closed，core/menu.ts:118）。
    val paletteQuery = slashQueryOf(draft)
    val paletteComplete = paletteQuery != null && paletteQueryComplete(paletteQuery, commands)
    val paletteTyped = paletteQuery != null && !paletteComplete && paletteMuted != draft
    // 「+」打开的全量菜单：查询固定为空（dsh 的 toggleCommandMenu 传的就是 query: ''），
    // 只按「能不能和已有草稿共存」过滤行（dsh 的 position === 'leading' || hint === undefined）。
    val paletteLauncher = launcherDraft != null && launcherDraft == draft
    val paletteRows = when {
        paletteLauncher -> commands.filter { paletteUsableWith(draft, it) }
        paletteTyped -> commands.filter { paletteMatches(paletteQuery.orEmpty(), it) }
        else -> emptyList()
    }
    val paletteVisible = paletteRows.isNotEmpty() && (paletteLauncher || paletteTyped)
    // 点空白 / 返回键关掉：把当前草稿标记为已忽略，别立刻又弹出来（dsh 的 dismissed）
    val dismissPalette = {
        launcherDraft = null
        paletteMuted = draft
    }
    // 指令面板 / 输入框弹层 / 会话统计 / 轮尾的用量与用时都是**不抢焦点**的浮层，
    // 「点别处关闭」与返回键由根布局的 OverlayDismissRegistry 统一接管（见 OverlayDismiss.kt）——
    // 它们一组合就登记自己，这里不再各写一层拦截层与 BackHandler。

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(
                WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)
            ),
    ) {
        if (hasConversation) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(48.dp)
                    .padding(horizontal = 14.dp)
                    // 顶栏的空白处也算「别处」：点一下就收起输入框的光标与键盘。
                    // 子节点（三个图标）自己会消费掉点击，所以这里只会接住落在空白上的那一下。
                    .pointerInput(Unit) {
                        detectTapGestures { focusManager.clearFocus() }
                    },
                verticalAlignment = Alignment.CenterVertically,
                // 第 117 轮：右上角那个图标在 Spacer 之后，改这一行的间距只会让「上下文」
                // 那一块再左移 8（用户点名「上下文窗口的图标再整块左移 8」）。
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // dsh 会话统计入口（IconGaugeOutline16），点开是统计弹窗（不再整页跳转）
                Box {
                    // 第 117 轮用户要求：顶栏三个图标**各小一点点**（20 → 18）；Gauge 的线宽也跟着
                    // 调细（见 DshIcons.Gauge），顶栏看起来才不是一排水桶。
                    IconTap(DshIcons.Gauge, "会话统计与 Token 用量", size = 18.dp, tint = palette.labelPrimary) {
                        statsOpen = !statsOpen
                        composerMenu = null
                    }
                    if (statsOpen) {
                        DshPopup(
                            onDismiss = { statsOpen = false },
                            alignStart = true,
                            below = true,
                        ) { SessionStatsPanels(state.stats) }
                    }
                }
                // 上下文占用紧挨在会话统计右侧（dsh 的 ContextMeter 就是「会话统计右侧的圆环 +
                // 百分比」）。放在这里还有一个副作用是想要的：它不再出现在输入框里，
                // 于是会话一开始（上下文用量可用）时，模型按钮不会被它顶走一格。
                // （state.context 不是可空的：没有用量时它就是一个 0 用量的默认值，
                //  这里原先写的 `state.context?.let` 恒等于直接调用。）
                ContextMeter(
                    usage = state.context,
                    open = composerMenu == "context",
                    below = true,
                    onOpenChange = { open ->
                        composerMenu = if (open) "context" else null
                        if (open) {
                            statsOpen = false
                            dismissPalette()
                        }
                    },
                )
                Spacer(Modifier.weight(1f))
                // dsh 的会话头部 actions 区：后台任务列表（第 118 轮）。**没有任务时整个控件不出现**
                // —— 与 dsh 的 JobListAction 一样，不为一个没用到的能力常驻一个入口。
                JobsControl(
                    conversationId = state.conversationId,
                    onReaderAction = readerAction,
                )
                // 右上角只保留「工作区文件预览」（dsh 的 IconPanelLeftOutline16 镜像 = 分栏线在右）
                IconTap(DshIcons.PanelRight, "工作区文件", size = 18.dp, tint = palette.labelPrimary) { onOpenWorkspaceFiles() }
            }
        }

        // 手指按在消息区期间一下都不贴底（[touching]，= dsh 的 `pending`：读者输入还没采样完
        // 就不动视口）。**手势本身不改跟随意图** —— 意图由上面那次位置采样按「读者到底移动没移动」
        // 决定（dsh 的 `movedByReader`），所以点一下、按住不动都不会把跟随关掉。
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // 键用 listState：换会话时 LazyListState 会重建，这里跟着重启
                .pointerInput(listState) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        val startY = down.position.y
                        // 这一次手势是不是真的把内容滑走了（超过 touch slop）
                        var dragged = false
                        touching = true
                        try {
                            // 等这一套手势彻底结束（松手或取消）
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (!dragged && change != null &&
                                    kotlin.math.abs(change.position.y - startY) > viewConfiguration.touchSlop
                                ) {
                                    dragged = true
                                }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            // finally 而不是顺序执行：手势被取消（节点回收）时手指状态也必须复位。
                            touching = false
                            // 点在消息区（没滑动的那一下）= 离开输入状态：收起光标与键盘
                            // （用户要求：点其他空白地方，输入框里的光标就消失）。滑动不算 ——
                            // 滑历史的时候键盘不该自己收起来。长按也不算（第 85 轮修的真机 bug，
                            // 判据见 shouldClearComposerFocus）。
                            val heldMillis = android.os.SystemClock.uptimeMillis() - down.uptimeMillis
                            if (shouldClearComposerFocus(dragged, heldMillis, viewConfiguration.longPressTimeoutMillis)) {
                                focusManager.clearFocus()
                            }
                        }
                    }
                },
        ) {
            // dsh 的 --dsh-content-font-size：会话内容区按设置里的字号缩放
            // （只影响会话内容，顶栏 / 输入栏不动，与 dsh 的「仅影响会话内容的字号」一致）
            val baseDensity = LocalDensity.current
            val contentDensity = remember(baseDensity, contentFontSize) {
                androidx.compose.ui.unit.Density(
                    density = baseDensity.density,
                    fontScale = baseDensity.fontScale * (contentFontSize / 14f),
                )
            }
            // Markdown 里的相对路径（图片 / 文件链接）按会话工作区解析
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides contentDensity,
                LocalMarkdownRoot provides state.workspacePath,
            ) {
            if (!hasConversation) {
                Hero()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    // 正序（和 dsh 的 DOM 顺序一致）：展开/收起只影响被点那一行**下面**的内容，
                    // 上面已经画好的行一动不动 —— 展开天然就是「向下展开」，不需要任何补偿滚动。
                    // 对话比一屏短时按 dsh 的样子贴底（对齐方式交给 Arrangement）。
                    // 行距由每一行自己带（一轮拆成了折叠行 / 过程行 / 轮尾三行）。
                    // 不设 Arrangement.Bottom：dsh 的会话是普通块级流，内容比一屏短时
                    // 从**顶部**开始排（之前贴底会让新会话空出一大截）；比一屏长时由
                    // 下面的贴底跟随负责钉住底部。
                ) {
                    items(items, key = { item -> transcriptKey(item) }) { item ->
                        // 只在 debug 里挂这个诊断副作用：release 里 `BuildConfig.DEBUG` 是常量 false，
                        // 整段 if 会被折掉 —— 否则每一行都会多出一个 DisposableEffect（本行进出组合时的日志）。
                        if (com.adsh.app.BuildConfig.DEBUG) {
                            val rowKey = transcriptKey(item)
                            androidx.compose.runtime.DisposableEffect(rowKey) {
                                trace("row+", rowKey)
                                onDispose { trace("row-", rowKey) }
                            }
                        }
                        when (item) {
                            // dsh 的 system-prompt 节点：系统提示词 / 系统提示词更新
                            // （会话里任何一行的展开都要停自动跟随，见 NOTES 的界面不变量）
                            is ChatItem.SystemPrompt ->
                                Box(Modifier.padding(top = item.gap)) {
                                    SystemPromptRow(item.text, item.update, onReaderAction = readerAction)
                                }
                            // dsh 的 context 节点：上下文注入
                            is ChatItem.Context -> Box(Modifier.padding(top = item.gap)) {
                                ContextInjectionRow(
                                    label = item.label,
                                    form = item.form,
                                    text = item.text,
                                    onReaderAction = readerAction,
                                )
                            }
                            is ChatItem.User -> Box(Modifier.padding(top = item.gap)) {
                                UserMessage(item.text, item.time, item.attachments)
                            }
                            is ChatItem.Compact -> Box(Modifier.padding(top = item.gap)) {
                                CompactCard(item.text, onReaderAction = readerAction)
                            }
                            // 折叠行：展开 / 收起（状态在 foldOpen 里，items 是 derivedStateOf，
                            // 写它就会重算列表 —— 不需要额外的「修订号」
                            is ChatItem.TurnFold -> TurnFoldRow(
                                view = item.view,
                                open = item.open,
                                // 读者动作：停止自动跟随（否则这一展开会被流式贴底顶上去）。
                                // 展开天然向下长、上方不动，不再做补偿滚动（见上面 readerAction 附近的注释）
                                onToggle = { expanded ->
                                    readerAction()
                                    foldOpen[item.view.key] = expanded
                                },
                                modifier = Modifier.padding(top = item.gap),
                            )
                            // 过程里的一条：一行一个 item（展开时只组合看得见的那几行）
                            is ChatItem.TurnEntry -> TurnEntryRow(
                                view = item.view,
                                index = item.index,
                                // 行里的展开/收起是行内部状态：它一动就停止自动跟随（readerAction）；
                                // 展开向下长、上方不动，不替读者滚（见上面 readerAction 附近的注释）
                                onReaderAction = { readerAction() },
                                // 点工具行摘要里的文件路径 → 进文件预览（与轮尾交付物卡片同一条路径解析）
                                onOpenFile = onOpenDeliverable,
                                modifier = Modifier.padding(top = item.gap),
                            )
                            // 轮尾：只有最后一轮可以从轮尾分叉（dsh 的 branchUnavailable = 后面还有节点）
                            is ChatItem.TurnTail -> TurnTailRow(
                                view = item.view,
                                branchable = item.view.closed && item.view.key == lastTurnKey && !state.sending,
                                onBranch = onBranch,
                                modifier = Modifier.padding(top = item.gap),
                                workspacePath = state.workspacePath,
                                onOpenDeliverable = onOpenDeliverable,
                                // 交付文件卡的展开 / 收起与工具行同一条规矩（读者动作）：展开天然
                                // 向下长、上方一动不动，也不做补偿滚动 —— 见下面 readerAction 的注释
                                onCardToggle = { readerAction() },
                            )
                        }
                    }
                }
            }
            }
            // dsh 的 toBottomSlot：**只要不在跟随就浮出来**（判据就是 `!scroll.followingTail`）——
            // 跟随意图与「现在离底部多少像素」无关，所以内容长高不会让它闪，也不需要旧版那套 64dp 迟滞。
            //
            // 第 95 轮：它上面再加两个**同形状**的快捷导航（本 App 的补充）。第三个导航按钮的位置与
            // 可见性口径是用户点名重排过的（第 96 轮）：
            //  - 从上到下 = 「回会话顶部」（向上的倒角，与底部那个向下倒角正好相反）
            //    → 「上一条用户消息」（放**二者中间**）→ 「回到底部」；
            //  - 上面两个只在**读者往会话开头方向滚**（往上翻）时出现：往下滚的那一下立刻收起来，
            //    停在半路也不会自己冒出来（用户点名：「其余时间均隐藏」）；
            //  - 到会话顶部时只收上面两个，「回到底部」留着（此刻正需要它）。
            if (hasConversation && !follow.following) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!atTop && scrollTowardTop) {
                        RoundRailButton(
                            // 倒角向上 = DshIcons.ChevronDown 转 180°（与「回到底部」那一个相反）
                            icon = DshIcons.ChevronDown,
                            iconRotation = 180f,
                            label = "回到会话顶部",
                        ) {
                            // 显式导航 = dsh 的 `pauseFollowing()`：先把跟随交出去，再跳
                            follow.pause()
                            listState.requestScrollToItem(0, 0)
                        }
                        val target = previousUserId
                        if (target != null) {
                            RoundRailButton(
                                icon = Icons.Outlined.ArrowCircleUp,
                                label = "跳到上一条用户消息",
                            ) {
                                // 同上：读者动作优先，否则流式贴底会立刻把视口拽回底部
                                follow.pause()
                                listState.requestScrollToItem(target, 0)
                            }
                        }
                    }
                    BackToBottomButton {
                        // dsh 的 `returnToBottom()`：无条件贴底（`toBottom()` 自己会把跟随打开）
                        follow.follow()
                        pinToBottom()
                    }
                }
            }
        }

        /**
         * 底部 chrome 的**上一次测量高度**（-1 = 还没量过）。不是 MutableState：它只在布局里比对，
         * 不该触发重组。
         */
        val chromeHeightMark = remember(state.conversationId) { intArrayOf(-1) }
        // 错误条 … 输入框这一整摞包一层 `Modifier.layout`：它拿到的是这块内容的**测量高度**，
        // 而 Column 是「先量所有非 weight 子节点、最后才量 weight 的消息列表」—— 所以这里比
        // 消息列表**先跑**，在列表测量之前把贴底请求发出去，同一帧就落到新的底部。
        //
        // 第 107 轮起这是**第二道保险**（主路径是上面那个跟内容 / 视口指纹走的 SideEffect）：它专门补
        // 「chrome 长高了、但本屏没有重组」那一种 —— 最典型的是**输入框自己长高**（草稿折行），
        // 那是 DshComposer 的内部状态，ChatScreen 并不知道自己该重组。
        // 与 dsh 的 ResizeObserver(column / composer) → followTail() 是同一条语义，只是这里落在同一帧。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    if (placeable.height != chromeHeightMark[0]) {
                        val firstMeasure = chromeHeightMark[0] < 0
                        chromeHeightMark[0] = placeable.height
                        if (!firstMeasure && follow.following && !touching && total > 0) {
                            if (com.adsh.app.BuildConfig.DEBUG) {
                                trace("pin chrome", "h=" + placeable.height + " items=" + total)
                            }
                            pinToBottom()
                        }
                    }
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
        ) {
        state.error?.let { message -> ErrorBar(message = message, onDismiss = onClearError) }

        // 提问卡放在输入框**上面**，而不是替掉输入框（dsh 里提问卡就是会话流里的一个节点，
        // 输入框一直在）。之前是二选一：AI 一提问，输入框连同焦点、键盘、正在组合的拼音
        // 一起被拆掉 —— 用户看到的就是「打字打一半键盘被抢走、回车把拼音变成字母」。
        if (question != null) {
            // dsh 的 PlanReviewPanel：intent 是 plan-review 的问题渲染成「计划待审」卡
            val review = question.questions.firstOrNull()?.takeIf { it.intent == PlanReview.KIND }
            if (review != null) {
                PlanReviewCard(
                    plan = review.detail.orEmpty(),
                    onApprove = { onAnswer(listOf(Answer(review.id, listOf(PlanReview.APPROVE)))) },
                    onDecline = { onAnswer(listOf(Answer(review.id, listOf(PlanReview.KEEP_PLANNING)))) },
                    onDiscuss = onSkipQuestion,
                )
            } else {
                QuestionCard(questions = question.questions, onAnswer = onAnswer, onSkip = onSkipQuestion)
            }
        }
        // 审批卡（dsh 的 ApprovalPanel .mna1RW_card）：越权请求要用户点一下才继续。
        // dsh 里它直接顶掉输入框（composer takeover）；手机上保持输入框在场（键盘/焦点不拆），
        // 卡片贴在输入框上方 —— 与提问卡、计划待审卡同一条竖列。
        approval?.let { pending ->
            ApprovalCard(
                toolName = pending.toolName,
                reason = pending.reason,
                detail = pending.detail,
                onAllow = onAllowApproval,
                onReject = onRejectApproval,
            )
        }
        if (question == null) {
            if (state.compacting) CompactingBar()
            if (state.queuedCount > 0) QueuedBar(count = state.queuedCount, onClear = onClearQueued)
            TodoDock(conversationId = state.conversationId, todos = todos)
            // dsh 的 TurnStatus：全程钉在输入框左上角（绑定文件夹那一行的位置），
            // 本轮输出完或被打断就随 sending 一起消失（计时跟着停），下一次发送重新起算；
            // 它在输入框上方的这一列里，所以呼出键盘时会跟着输入框一起上移。
            if (state.sending) {
                TurnStatusRow(
                    startedAt = state.runStartedAt,
                    modifier = Modifier.padding(start = 14.dp, bottom = 2.dp),
                )
            }
        }
        // 掉线重连条（dsh 的 ConnectionIndicator）：断线 / 连接中 / 已恢复三态，
        // 钉在输入框上面那一列（与 TurnStatus 同一个位置）。它有话要说时**替换**掉
        // TurnStatus 的那一行 —— 两行一起挂着反而看不清现在到底是「在跑」还是「断了」。
        if (state.connection !is ConnectionState.Idle) {
            ConnectionBar(
                state = state.connection,
                modifier = Modifier.padding(start = 14.dp, bottom = 2.dp),
                onRetry = onRetryConnection,
            )
        }
        DshComposer(
                draft = draft,
                draftRevision = draftRevision,
                onDraftChange = { draft = it },
                sending = state.sending,
                busyEnter = state.busyEnter,
                modelGroups = modelGroups.ifEmpty {
                    listOf(ChatViewModel.ModelGroup("", state.providerName, listOf(state.modelLabel)))
                },
                currentModel = state.modelLabel,
                currentProviderId = state.providerId,
                balances = balances,
                onRefreshBalance = onRefreshBalance,
                efforts = efforts,
                currentEffort = state.reasoningEffort,
                onSelectModel = onSelectModel,
                onSelectEffort = onSelectEffort,
                permission = state.permission,
                onSelectPermission = onSelectPermission,
                paletteCommands = paletteRows,
                paletteVisible = paletteVisible,
                onPaletteVisibleChange = { want ->
                    // dsh 的 toggleCommandMenu（ui-conversation/src/client/apply.ts:505-517）：
                    // 打开的是**全量菜单**（查询传空），关掉则记下 dismissed —— 按同一 token 同一查询
                    // 再 track 一次不会把它召回来；再按一次「+」是新的意图，dismissed 清掉。
                    launcherDraft = if (want) draft else null
                    paletteMuted = if (want) null else draft
                },
                onPaletteDismiss = dismissPalette,
                menu = composerMenu,
                onMenuChange = { value ->
                    composerMenu = value
                    if (value != null) {
                        statsOpen = false
                        dismissPalette()
                    }
                },
                requestPermission = requestPermission,
                onRequestHandled = { requestPermission = false },
                showWorkspace = !hasConversation,
                workspaces = workspaces,
                workspaceId = state.workspaceId,
                onPickWorkspace = onPickWorkspace,
                onAddWorkspace = onAddWorkspace,
                planMode = state.planMode,
                onTogglePlan = onTogglePlan,
                attachments = state.pendingAttachments,
                onRemoveAttachment = onRemoveAttachment,
                onSend = {
                    val text = draft.trim()
                    writeDraft("")
                    launcherDraft = null
                    paletteMuted = null
                    val token = claimTokenOf(text)
                    val rest = if (token == null) text else text.removePrefix(token).trim()
                    when {
                        token == "/plan" -> if (rest == "off") {
                            onSetPlan(false)
                        } else {
                            onSetPlan(true)
                            if (rest.isNotEmpty()) onSend(rest)
                        }
                        text == "/compact" -> onCompact()
                        // dsh 的 matchEnter：精确命中的命令由目录解析后派发（/permission 是 popupSelect）
                        text == "/permission" -> requestPermission = true
                        text == "/export" -> toastPath(context, exportSessionZip(context, state))
                        else -> onSend(text)
                    }
                },
                onCancel = onCancel,
        )
        }
    }

    // 原图预览（dsh 的 lightbox）：对话里任何一张缩略图点开都挂到这里。
    // 状态在 ImagePreviewState 里 —— 图片散在消息与工具行深处，逐层传 lambda 会把每一层都污染。
    val previewPath by ImagePreviewState.path.collectAsStateWithLifecycle()
    previewPath?.let { path ->
        ImagePreviewDialog(path = path, onDismiss = { ImagePreviewState.close() })
    }
}

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
 * 「回到底部」上面那两个快捷导航按钮：与 dsh 的 toBottomSlot **同一个形状**
 * （34dp 圆形、圆角 100、menu 底、.5px border-l3、图标 16dp），只换图标与语义。
 */
@Composable
private fun RoundRailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    /** 图标旋转角度（「回会话顶部」就是把底部那个倒角转 180°） */
    iconRotation: Float = 0f,
    onClick: () -> Unit,
) {
    val palette = LocalDshPalette.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(palette.menu)
            .border(0.5.dp, palette.borderL3, RoundedCornerShape(100.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = palette.labelPrimary,
            modifier = Modifier.size(16.dp).rotate(iconRotation),
        )
    }
}

/**
 * dsh 的「回到底部」（.EvIC1a_toBottom + chat.toBottom）：
 * 34px 圆形、100px 圆角、浮在内容右下角（bottom 16px）、图标是 ChevronDown14，
 * 只在不在底部时出现；点一下瞬移到底（不做动画滚动）。
 */
@Composable
private fun BackToBottomButton(onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(palette.menu)
            .border(0.5.dp, palette.borderL3, RoundedCornerShape(100.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            DshIcons.ChevronDown,
            contentDescription = "回到底部",
            tint = palette.labelPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** dsh 的 /compact 进行中提示 */
@Composable
private fun CompactingBar() {
    val palette = com.adsh.app.ui.theme.LocalDshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.menu)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("正在压缩上下文…", fontSize = 12.sp, color = palette.labelSecondary)
    }
}

/**
 * 排队发送的可见交代（dsh 的 queue chip）：运行中按下的消息正排着队，
 * 这一轮结束就发出去。点一下清空队列。
 */
@Composable
private fun QueuedBar(count: Int, onClear: () -> Unit) {
    val palette = com.adsh.app.ui.theme.LocalDshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.menu)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClear,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("已排队 " + count + " 条，本轮结束后发出", fontSize = 12.sp, color = palette.labelSecondary)
        Spacer(Modifier.weight(1f))
        Text("清空", fontSize = 12.sp, color = palette.labelTertiary)
    }
}

/**
 * 掉线重连条（dsh 的 ConnectionIndicator：断线 / 连接中 / 已恢复三态）。
 *
 * dsh 的形状是「警告色图标 + 文案」的行内控件，**断开与连接中两个状态都可以点**（点了立刻重连），
 * 已恢复是成功色、不可点。这里照做：
 *  - 正在重连：warning 色的刷新图形 + 「连接已断开，正在重连（第 N 次）」+ 一至三个点每 500ms
 *    推进（dsh 的 ongoing loader 就是这个 500ms 节奏，与重试时序无关）；整条可点。
 *  - 已恢复：成功色的对勾 + 「连接已恢复」。
 */
@Composable
private fun ConnectionBar(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val recovered = state is ConnectionState.Recovered
    val tint = if (recovered) palette.success else palette.warnLabel
    val label = when (state) {
        is ConnectionState.Recovered -> "连接已恢复"
        is ConnectionState.Reconnecting -> "连接已断开，正在重连（第 " + state.attempt + " 次）"
        ConnectionState.Idle -> ""
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .then(
                if (recovered) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onRetry,
                    )
                },
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (recovered) DshIcons.Check else DshIcons.Refresh,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Text(text = label, fontSize = 12.sp, lineHeight = 18.sp, color = tint, maxLines = 1)
        if (!recovered) {
            LoadingDots(tint)
            // 失败原因（dsh 的 outage label 由持有方提供）：一行、超出省略 ——
            // 没有它用户只知道「断了」，分不清超时 / DNS / 服务端 5xx
            val reason = (state as? ConnectionState.Reconnecting)?.message.orEmpty()
            if (reason.isNotBlank()) {
                Text(
                    text = reason,
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = palette.labelCaption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("点击立即重试", fontSize = 12.sp, lineHeight = 18.sp, color = palette.labelCaption, maxLines = 1)
        }
    }
}

/**
 * 「连接中」的一至三个点，每 500ms 推进一格（dsh 的 ongoing loader：
 * 「一至三个点以独立于 retry 时序的 500ms 节奏推进」）。
 */
@Composable
private fun LoadingDots(tint: Color) {
    val step by produceState(1) {
        while (true) {
            kotlinx.coroutines.delay(500)
            value = value % 3 + 1
        }
    }
    Text(
        text = ".".repeat(step).padEnd(3, ' '),
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = tint,
        modifier = Modifier.width(12.dp),
    )
}

/** 检查点卡片：历史被压缩后的摘要，默认折叠 */@Composable
private fun CompactCard(text: String, onReaderAction: () -> Unit = {}) {
    val palette = com.adsh.app.ui.theme.LocalDshPalette.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val body = text.substringAfter("<compacted-summary>", text).substringBefore("</compacted-summary>").trim()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.menu)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                onReaderAction()
                expanded = !expanded
            }
            .padding(12.dp),
    ) {
        // dsh 的 CompactionItem 文案：message.compaction =「上下文已压缩」，
        // message.compaction.expand =「点击查看压缩摘要」
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("上下文已压缩", fontSize = 13.sp, lineHeight = 20.sp, color = palette.labelSecondary)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (expanded) "收起摘要" else "点击查看压缩摘要",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = palette.labelCaption,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = palette.labelSecondary,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    // 卡片滚到头之后剩下的位移/惯性留在卡片里（第 115 轮，见 ScrollEdgeEater）
                    .nestedScroll(ScrollEdgeEater)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** dsh 的空态：鲸鱼 + 「探索未至之境」+「预览版」 */
@Composable
private fun Hero() {
    Column(
        Modifier.fillMaxSize().padding(bottom = 72.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DshWhale(width = 62.dp, tint = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = HERO_HEADLINE,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = HERO_BADGE,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 消息

/**
 * 用户消息（dsh 的 UserStyleBubble / MessageItem.module.css）：
 *
 *  - 右对齐的一列：气泡在上、动作行在下，两者间距 8px（.Sixlwa_userStack gap:8px）；
 *    整列最大宽度 = min(内容宽 * .702, 82%)，手机上就是 82%；
 *  - 气泡：`--dsw-specific-bubble`（浅色 #EDF3FE / 深色 #2C2C2E，**不是**主色蓝）、
 *    圆角 22px、内边距 10px 16px、正文 14/22、颜色 label-primary；
 *  - 动作行（dsh 的 MessageIconActions，clock: "start"）：先时间（13/24 三级色、右侧 12px）
 *    再复制按钮（28×28 圆形、图标 15px，点一下变对勾，1 秒后还原）。
 *
 * internal（而不是 private）：插话（dsh 的 steering 节点）用的是**同一个气泡**，
 * 它作为一轮过程里的条目由 TurnRail 渲染。
 */
@Composable
internal fun UserMessage(
    content: String,
    time: Long,
    attachments: List<com.adsh.app.core.agent.UserAttachment> = emptyList(),
) {
    val palette = LocalDshPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            // dsh 的 .userStack：`max-width: min(0.702×列宽, 82%)` —— 是**上限**不是定宽，
            // 气泡本身按内容收缩（短消息就是一个窄胶囊），超出 82% 才折行。
            // 这里用「列宽 82% + 气泡对齐到 End」等价表达：Column 定宽不影响观感，
            // 气泡（Box）自己 wrap 内容 —— 前提是里面那层 Markdown 不 fillMaxWidth（见 Markdown.kt）。
            modifier = Modifier.fillMaxWidth(0.82f),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // dsh 的 .attachmentRow：附件排在气泡**上面**（userStack 里 attachmentRow 在前），
            // 右对齐、间距 8、可换行
            if (attachments.isNotEmpty()) UserAttachmentRow(attachments)
            // dsh 的 showBubble：正文与附件至少有一个才画气泡
            if (content.isNotBlank()) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(palette.userBubble)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    // 对话正文也是 Markdown（dsh 的 MarkdownText 语义），不再原样吐 ## / ** / 表格
                    MarkdownBody(text = content)
                }
            }
            Row(
                modifier = Modifier.height(28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UserClock(time)
                // 只发附件（没打字）时没有可复制的东西：不画那个按钮，免得点一下只换来一个空对勾
                if (content.isNotBlank()) UserCopyAction(content)
            }
        }
    }
}

/**
 * 用户消息的附件行（dsh 的 .Sixlwa_attachmentRow: flex-wrap + gap 8 + justify-content flex-end）。
 *
 * 图片走 dsh 的 MessageImage 画廊：**整条消息只有一张图**时铺开显示（宽度占满这一列），
 * 多于一个附件时退化成 64dp 的方格（dsh 的 compact = attachments.length > 1）；
 * 文件走 dsh 的 fileCard：240x64 的圆角卡片，前面一枚类型图标，右边文件名 + 「扩展名 大小」。
 */
@Composable
private fun UserAttachmentRow(attachments: List<com.adsh.app.core.agent.UserAttachment>) {
    val compact = attachments.size > 1
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            if (attachment.isImage) {
                UserImageAttachment(attachment, compact)
            } else {
                UserFileCard(attachment)
            }
        }
    }
}

/**
 * 单张图片的最大显示尺寸。
 *
 * dsh 的 `.fNh4Da_image` 是 `max-width: min(100%, 1600px); max-height: calc(100vh - 80px)`，
 * 元素跟着图片自己的宽高比走、**不放大**（max-width 只是上限）。ADSH 原来写的是
 * `fillMaxWidth().heightIn(max = 320.dp)` + `ContentScale.Fit` —— 那个 Box 永远占满整行、
 * 高度永远顶到 320dp，横图上下留一大片空白、小图也被撑成一张大卡片，就是「面积太大」的来源。
 * 这里按 dsh 的语义来：宽度给到这一列的宽度、高度上限 320dp（手机上的合理值），
 * 按宽高比反推实际尺寸，小图保持原始大小。
 */
private val MESSAGE_IMAGE_MAX_HEIGHT = 320.dp

/**
 * 按原始像素尺寸算展示尺寸：只缩不放。
 * 图片的原始像素直接当 dp 用（dsh 的 <img> 不带 width/height，就是按自然像素渲染）。
 */
internal fun fitImageSize(
    naturalWidthPx: Int,
    naturalHeightPx: Int,
    maxWidth: androidx.compose.ui.unit.Dp,
    maxHeight: androidx.compose.ui.unit.Dp,
): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
    if (naturalWidthPx <= 0 || naturalHeightPx <= 0) {
        return maxWidth to maxHeight
    }
    val scale = minOf(
        1f,
        maxWidth.value / naturalWidthPx.toFloat(),
        maxHeight.value / naturalHeightPx.toFloat(),
    )
    return (naturalWidthPx * scale).dp to (naturalHeightPx * scale).dp
}

/** dsh 的 MessageImage：单图按宽高比铺开（不放大），多图 64×64 方格 */
@Composable
private fun UserImageAttachment(
    attachment: com.adsh.app.core.agent.UserAttachment,
    compact: Boolean,
) {
    val palette = LocalDshPalette.current
    val targetPx = if (compact) 160 else 960
    val bitmap by produceState<ImageBitmap?>(initialValue = null, attachment.path, targetPx) {
        value = withContext(Dispatchers.IO) { cachedAttachmentBitmap(attachment.path, targetPx) }
    }
    val frame = Modifier
        .clip(RoundedCornerShape(16.dp))
        .border(0.5.dp, palette.borderL2, RoundedCornerShape(16.dp))
        .background(palette.selector)
        // 点一下打开原图预览（dsh 的 lightbox：缩略图 → 原图）
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { ImagePreviewState.open(attachment.path) }
    if (compact) {
        Box(frame.size(64.dp), contentAlignment = Alignment.Center) {
            AttachmentImageContent(bitmap, attachment, compact)
        }
        return
    }
    // 单图：先量出这一列能给的宽度，再按图片自己的宽高比算出实际尺寸
    androidx.compose.foundation.layout.BoxWithConstraints(frame) {
        val size = fitImageSize(
            naturalWidthPx = attachment.width.takeIf { it > 0 } ?: (bitmap?.width ?: 0),
            naturalHeightPx = attachment.height.takeIf { it > 0 } ?: (bitmap?.height ?: 0),
            maxWidth = maxWidth,
            maxHeight = MESSAGE_IMAGE_MAX_HEIGHT,
        )
        Box(Modifier.size(size.first, size.second), contentAlignment = Alignment.Center) {
            AttachmentImageContent(bitmap, attachment, compact)
        }
    }
}

/**
 * 图片本体（或加载失败时的文件名兜底）。
 * 尺寸已经由外层的 Box 定好，这里只负责「填满并等比内嵌」。
 */
@Composable
internal fun AttachmentImageContent(
    bitmap: ImageBitmap?,
    attachment: com.adsh.app.core.agent.UserAttachment,
    compact: Boolean,
) {
    val palette = LocalDshPalette.current
    Box(contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = attachment.name,
                contentScale = if (compact) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = attachment.name,
                modifier = Modifier.padding(8.dp),
                fontSize = 12.sp,
                color = palette.labelTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** dsh 的 .Sixlwa_fileCard：240×64、圆角 16、.5px 边框、图标 28 + 文件名 + 扩展名与大小 */
@Composable
private fun UserFileCard(attachment: com.adsh.app.core.agent.UserAttachment) {
    val palette = LocalDshPalette.current
    val meta = fileMetaText(attachment)
    Row(
        modifier = Modifier
            .width(240.dp)
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(0.5.dp, palette.borderL2, RoundedCornerShape(16.dp))
            .background(palette.inputMajor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = null,
            tint = palette.labelSecondary,
            modifier = Modifier.size(28.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium,
                color = palette.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    color = palette.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** dsh 的 fileMeta 文案：扩展名（大写，最多 8 字）+ 文件大小 */
private fun fileMetaText(attachment: com.adsh.app.core.agent.UserAttachment): String {
    val dot = attachment.name.lastIndexOf('.')
    val extension = if (dot > 0 && dot < attachment.name.length - 1) {
        attachment.name.substring(dot + 1).uppercase().take(8)
    } else {
        ""
    }
    return listOf(extension, fileSizeText(attachment.bytes)).filter { it.isNotEmpty() }.joinToString(" ")
}

/** 字节数文案：dsh 的 fileSizeText（B / KB / MB / GB，保留一位小数） */
private fun fileSizeText(bytes: Long): String {
    if (bytes < 1024) return bytes.toString() + " B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    val text = if (rounded >= 100.0) rounded.toInt().toString() else rounded.toString()
    return text + " " + units[index]
}

/**
 * 附件封面缓存（按字节数计价，32MB）。
 *
 * 之前每次滑回视口都会重新 decodeFile 一遍 960px 的整图（一张 = 约 3.7MB 位图），
 * 上下滑动遇到图片就明显掉帧。dsh 的图片节点是「解码一次、按节点缓存」，
 * 这里等价地按 path@目标像素 缓存住。
 */
private val attachmentBitmaps = object : android.util.LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        value.width.coerceAtLeast(1) * value.height.coerceAtLeast(1) * 4
}

/** 带缓存的附件封面解码 */
internal fun cachedAttachmentBitmap(path: String, targetPx: Int): ImageBitmap? {
    val key = path + "@" + targetPx
    attachmentBitmaps.get(key)?.let { return it }
    val decoded = loadAttachmentBitmap(path, targetPx) ?: return null
    attachmentBitmaps.put(key, decoded)
    return decoded
}

/** 附件封面：按目标像素做 2 的幂下采样（图片很大时避免整张解码进内存） */
private fun loadAttachmentBitmap(path: String, targetPx: Int): ImageBitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
        sample *= 2
    }
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    android.graphics.BitmapFactory.decodeFile(path, options)?.asImageBitmap()
}.getOrNull()

/** dsh 的 .xzv4MW_timeStart：13/24 三级色，右侧 12px 间距 */
@Composable
private fun UserClock(time: Long) {
    val palette = LocalDshPalette.current
    if (time <= 0L) return
    Text(
        text = formatMessageClock(time),
        modifier = Modifier.padding(end = 12.dp),
        fontSize = 13.sp,
        lineHeight = 24.sp,
        color = palette.labelTertiary,
    )
}

/** dsh 的 MessageIconActions 里的复制按钮：点一下换成 IconCheckOutline16，1 秒后还原 */
@Composable
private fun UserCopyAction(text: String) {
    val context = LocalContext.current
    var copied by rememberCopiedFlag()
    RailIconAction(
        icon = if (copied) DshIcons.Check else DshToolIcons.Copy,
        label = if (copied) "复制成功" else "复制",
    ) {
        copyToClipboard(context, text)
        copied = true
    }
}

/**
 * dsh 的 formatMessageClock（中文字典 clock.md / clock.ymd）：
 * 同一天只给 HH:mm；同一年给「M月d日 HH:mm」；跨年给「y年M月d日 HH:mm」。
 */
private fun formatMessageClock(time: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = time }
    val clock = String.format("%02d:%02d", then.get(java.util.Calendar.HOUR_OF_DAY), then.get(java.util.Calendar.MINUTE))
    if (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    ) {
        return clock
    }
    val month = then.get(java.util.Calendar.MONTH) + 1
    val day = then.get(java.util.Calendar.DAY_OF_MONTH)
    if (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)) {
        return month.toString() + "月" + day + "日 " + clock
    }
    return then.get(java.util.Calendar.YEAR).toString() + "年" + month + "月" + day + "日 " + clock
}

/**
 * 助手正文（dsh 的 AssistantMarkdown）。
 *
 * 流式期间按 ~33ms 采样重绘一次（与自动滚动同一节奏），避免每个 token 都重解析整篇 Markdown。
 * 注意不能写成 `LaunchedEffect(content) { delay(120); shown = content }` ——
 * 那样每次内容变化都会取消并重启延时，token 持续到达时节流永远不触发，
 * 结果整段文字在结束时一次性蹦出来（旧实现的「看不到流式渲染」）。
 */
@Composable
internal fun AssistantText(content: String) {
    // 不再用「流式走一条分支、定稿走另一条分支」的写法 —— 那样一段正文在定稿的瞬间会被整棵树
    // 重建（SelectionContainer 与 MarkdownBody 的层级变了），视觉上就是正文闪一下、行高重排一次。
    // 现在两条路径共用同一棵树；流式期间的重绘节流在**列表外面**做（rememberSampledStreaming）。
    SelectionContainer { MarkdownBody(text = content, modifier = Modifier.fillMaxWidth()) }
}

@Composable
private fun ErrorBar(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss) { Text("忽略") }
        }
    }
}

// ------------------------------------------------------------------ 越权审批卡

/**
 * 越权审批卡，逐项对齐 dsh 的 ApprovalPanel（dsh-client-ui-approval 的 ApprovalPanel.module.css）：
 *
 *  - .root{padding:8px calc(side+16px) 12px}：贴在输入框那一条竖列上；
 *  - .card{border:1px solid state-warn-secondary;background:--dsw-specific-input-major;
 *    border-radius:20px;overflow:hidden}（窄屏 16，与计划待审卡一致）；
 *  - .strip{background:state-warn-tertiary;color:state-warn-primary;gap:8px;padding:10px 16px;13/18}
 *    + 8x8 的圆点，文案是 approval.waiting「等待审批」；
 *  - .body{padding:12px 16px 0;gap:6px;max-height;可滚动}：
 *    标题 15/24 500 label-primary = reason（缺省时是 approval.escalation「工具 {toolName} 请求越权执行」），
 *    下面一行 .command 13/20 label-tertiary 等宽 = 工具给的细节（命令行 / 路径）；
 *  - .actionRow{justify-content:flex-end;gap:8px;padding:14px 16px}：
 *    拒绝（outline，悬停是 danger）+ 允许一次（primary）。
 *
 * 手机适配：卡片占满宽度、左右各留 10dp（和计划待审卡/提问卡同一列宽），
 * 按钮沿用 DshButton 的 36dp 高（dsh 的按钮也是 36px）。
 */
@Composable
private fun ApprovalCard(
    toolName: String,
    reason: String,
    detail: String?,
    onAllow: () -> Unit,
    onReject: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val cardShape = RoundedCornerShape(16.dp)
    val stripShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val maxBodyHeight = minOf(320, (configuration.screenHeightDp * 0.4f).toInt()).dp
    // dsh 的 answered：按过一下就地把两个按钮都置灰（客户端不再接受第二次结论，
    // 也挡掉「连点两下 = 拒绝 + 允许」这种竞态）。结论落定后卡片立刻卸载，状态不必重置。
    var answered by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 6.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(palette.inputMajor)
                .border(1.dp, palette.warnLabel.copy(alpha = 0.45f), cardShape),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(stripShape)
                    .background(palette.warnBg)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(palette.warnLabel))
                Text("等待审批", fontSize = 13.sp, lineHeight = 18.sp, color = palette.warnLabel)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBodyHeight)
                    // 卡片滚到头之后剩下的位移/惯性留在卡片里（第 115 轮，见 ScrollEdgeEater）
                    .nestedScroll(ScrollEdgeEater)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = reason.ifBlank { "工具 " + toolName + " 请求越权执行" },
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.labelPrimary,
                )
                detail?.takeIf { it.isNotBlank() }?.let { text ->
                    Text(
                        text = text,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = palette.labelTertiary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DshButton(
                        text = "拒绝",
                        onClick = {
                            answered = true
                            onReject()
                        },
                        enabled = !answered,
                    )
                    DshButton(
                        text = "允许一次",
                        onClick = {
                            answered = true
                            onAllow()
                        },
                        kind = DshButtonKind.Primary,
                        enabled = !answered,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 计划待审（dsh 的 PlanReviewPanel）

/**
 * 计划待审卡，逐项对齐 dsh 的 PlanReviewPanel（ui-user-questions 的 PlanReviewPanel.module.css）：
 *
 *  - .card{border:1px solid state-warn-secondary;background:--dsw-specific-input-major;
 *    max-height:min(60vh,520px);border-radius:20px（窄屏 16）}
 *  - .strip{background:state-warn-tertiary;color:state-warn-primary;gap:8px;padding:10px 16px;13/18}
 *    + 8x8 的圆点，文案是 plan.header「计划待审」
 *  - .body{padding:12px 16px 4px;14/22;可滚动}：整份计划的 Markdown
 *  - .footer{padding:8px 16px 12px;justify-content:space-between}：
 *    左边 feedback（报错文案），右边 gap 8 的三个按钮 —— 去聊天里说（ghost + 编辑图标）、
 *    拒绝（outline）、确认执行（primary）
 */
@Composable
private fun PlanReviewCard(
    plan: String,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onDiscuss: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val cardShape = RoundedCornerShape(16.dp)
    val stripShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val maxBodyHeight = minOf(520, (configuration.screenHeightDp * 0.6f).toInt()).dp

    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 6.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(palette.inputMajor)
                .border(1.dp, palette.warnLabel.copy(alpha = 0.45f), cardShape),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(stripShape)
                    .background(palette.warnBg)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(palette.warnLabel))
                Text("计划待审", fontSize = 13.sp, lineHeight = 18.sp, color = palette.warnLabel)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxBodyHeight)
                    // 卡片滚到头之后剩下的位移/惯性留在卡片里（第 115 轮，见 ScrollEdgeEater）
                    .nestedScroll(ScrollEdgeEater)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
            ) {
                MarkdownBody(text = plan, modifier = Modifier.fillMaxWidth())
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable(interactionSource = interaction, indication = null, onClick = onDiscuss)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = DshSettingIcons.Edit,
                            contentDescription = null,
                            tint = palette.labelSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text("去聊天里说", fontSize = 14.sp, lineHeight = 22.sp, color = palette.labelSecondary)
                    }
                    DshButton(text = "拒绝", onClick = onDecline)
                    DshButton(text = "确认执行", onClick = onApprove, kind = DshButtonKind.Primary)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 提问卡片

/** dsh 的 parseRecommendedLabel：选项末尾的「（推荐）/(Recommended)」拆成独立徽标，答案值不变 */
private val RECOMMENDED_SUFFIX =
    Regex("\\s*(?:\\((?:recommended|推荐)\\)|（(?:recommended|推荐)）)\\s*$", RegexOption.IGNORE_CASE)

/** 一道题的作答草稿（dsh 的 draft：selected / custom / skipped） */
private data class QuestionDraft(
    val selected: List<String> = emptyList(),
    val custom: String = "",
    val skipped: Boolean = false,
) {
    val answered: Boolean get() = selected.isNotEmpty() || custom.isNotBlank()
    val completed: Boolean get() = answered || skipped
}

/**
 * 提问卡，逐项对齐 dsh 的 QuestionComposer（client/ui-questions 的 QuestionComposer.module.css）：
 *
 *  - .frame{padding:6px ... 10px} 里一张 .card：圆角 16（窄屏）、底 --dsw-specific-input-major、
 *    max-height min(60vh,520px)，底部 10px 内边距；
 *  - .header：eyebrow 11/16 三级色 + 标题 15/21 500 + 右上角 24x24 圆形按钮（收起 / 放弃整组问题）；
 *  - 选项行：min-height 40、圆角 12、选中底色 interactive-bg-hover；单选左边是 20x20 的序号方块，
 *    多选是复选框；标签 14/500/24，后面可以跟「推荐」徽标，描述 14/24 三级色；
 *  - 最后一行的「输入你的答案」：有选项时跟选项同一列（左边是编辑图标或复选框），
 *    没有选项时是一整块 textarea（border .5px border-l4、圆角 10、最小高 64）；
 *  - .footer：左分页（上一题 / 1 / 2 / 下一题）、中间报错文案、右边「跳过本题」+「下一题 / 提交」。
 *
 * 单选点一下会自动翻到下一题（dsh 的 choose 就是这么做的），多选只切换勾选。
 */
@Composable
private fun QuestionCard(questions: List<Question>, onAnswer: (List<Answer>) -> Unit, onSkip: () -> Unit) {
    val palette = LocalDshPalette.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    var index by remember(questions) { mutableIntStateOf(0) }
    var drafts by remember(questions) { mutableStateOf(questions.map { QuestionDraft() }) }
    var minimized by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val question = questions.getOrNull(index) ?: return
    val draft = drafts.getOrNull(index) ?: QuestionDraft()
    val multi = question.multiSelect
    val last = index == questions.size - 1

    fun replaceDraft(next: QuestionDraft, nextIndex: Int = index, clearError: Boolean = true) {
        drafts = drafts.toMutableList().also { it[index] = next }
        if (nextIndex != index) index = nextIndex
        if (clearError) error = null
    }

    fun submit(values: List<QuestionDraft>) {
        val missing = values.indexOfFirst { !it.completed }
        if (missing >= 0) {
            index = missing
            error = "请先完成这道问题。"
            return
        }
        error = null
        onAnswer(
            questions.mapIndexed { at, item ->
                val value = values[at]
                if (value.skipped) {
                    Answer(id = item.id, selected = emptyList())
                } else {
                    val custom = value.custom.trim()
                    Answer(
                        id = item.id,
                        selected = if (custom.isEmpty() || item.multiSelect) value.selected else emptyList(),
                        custom = custom.ifEmpty { null },
                    )
                }
            },
        )
    }

    fun continueFlow() {
        if (!draft.answered) {
            error = "请选择一个选项或填写自定义答案。"
            return
        }
        if (!last) {
            index += 1
            error = null
            return
        }
        submit(drafts)
    }

    fun choose(label: String) {
        if (multi) {
            val picked = if (label in draft.selected) draft.selected - label else draft.selected + label
            replaceDraft(draft.copy(selected = picked, skipped = false))
        } else {
            val nextIndex = if (!last) index + 1 else index
            replaceDraft(QuestionDraft(selected = listOf(label)), nextIndex)
        }
    }

    fun skipQuestion() {
        val next = drafts.toMutableList().also { it[index] = QuestionDraft(skipped = true) }
        drafts = next
        error = null
        if (last) submit(next) else index += 1
    }

    val cardShape = RoundedCornerShape(16.dp)
    val rowShape = RoundedCornerShape(12.dp)
    val maxBodyHeight = minOf(520, (configuration.screenHeightDp * 0.6f).toInt()).dp

    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 6.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(palette.inputMajor)
                .border(0.5.dp, palette.borderL1, cardShape)
                .padding(bottom = 10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    question.header?.takeIf { it.isNotBlank() }?.let { header ->
                        Text(
                            text = header,
                            modifier = Modifier.padding(bottom = 5.dp),
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = palette.labelTertiary,
                        )
                    }
                    Text(
                        text = question.question,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.labelPrimary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DshIconButton(
                        icon = if (minimized) DshSettingIcons.ChevronUp else DshSettingIcons.ChevronDown,
                        description = if (minimized) "展开问题卡片" else "收起问题卡片",
                        onClick = { minimized = !minimized },
                        size = 24.dp,
                    )
                    DshIconButton(
                        icon = DshSettingIcons.Close,
                        description = "放弃整组问题",
                        onClick = onSkip,
                        size = 24.dp,
                    )
                }
            }

            if (!minimized) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxBodyHeight)
                        // 卡片滚到头之后剩下的位移/惯性留在卡片里（第 115 轮，见 ScrollEdgeEater）
                        .nestedScroll(ScrollEdgeEater)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        question.options.forEachIndexed { optionIndex, option ->
                            val picked = option.label in draft.selected
                            val display = option.label.replace(RECOMMENDED_SUFFIX, "")
                            val recommended = RECOMMENDED_SUFFIX.containsMatchIn(option.label)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(rowShape)
                                    .background(if (picked && !multi) palette.hover else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (picked && !multi) palette.borderL2 else Color.Transparent,
                                        rowShape,
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { choose(option.label) }
                                    .padding(horizontal = 6.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                if (multi) {
                                    Box(Modifier.size(20.dp).padding(top = 2.dp)) {
                                        DshCheckbox(
                                            checked = picked,
                                            onCheckedChange = { choose(option.label) },
                                            size = 14.dp,
                                        )
                                    }
                                } else {
                                    Box(
                                        Modifier
                                            .padding(top = 2.dp)
                                            .size(20.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(palette.bgModulePlatform),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = (optionIndex + 1).toString(),
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = palette.labelSecondary,
                                        )
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = display,
                                            fontSize = 14.sp,
                                            lineHeight = 24.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = palette.labelPrimary,
                                        )
                                        if (recommended) {
                                            Box(
                                                Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(palette.navActive)
                                                    .padding(horizontal = 4.dp),
                                            ) {
                                                Text(
                                                    text = "推荐",
                                                    fontSize = 11.sp,
                                                    lineHeight = 18.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = palette.accent,
                                                )
                                            }
                                        }
                                    }
                                    option.description?.takeIf { it.isNotBlank() }?.let { description ->
                                        Text(
                                            text = description,
                                            fontSize = 14.sp,
                                            lineHeight = 24.sp,
                                            color = palette.labelTertiary,
                                        )
                                    }
                                }
                            }
                        }

                        if (question.options.isEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .heightIn(min = 64.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.bgModulePlatform)
                                    .border(0.5.dp, palette.borderL4, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                QuestionAnswerField(
                                    value = draft.custom,
                                    placeholder = "输入你的答案",
                                    modifier = Modifier.fillMaxWidth(),
                                    onValue = { typed ->
                                        replaceDraft(
                                            draft.copy(
                                                custom = typed,
                                                selected = if (multi) draft.selected else emptyList(),
                                                skipped = false,
                                            ),
                                        )
                                    },
                                )
                            }
                        } else {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(rowShape)
                                    .background(if (draft.custom.isNotEmpty()) palette.hover else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (draft.custom.isNotEmpty()) palette.borderL2 else Color.Transparent,
                                        rowShape,
                                    )
                                    .padding(horizontal = 6.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Box(Modifier.size(20.dp).padding(top = 2.dp)) {
                                    if (multi) {
                                        DshCheckbox(
                                            checked = draft.custom.isNotEmpty(),
                                            onCheckedChange = { checked ->
                                                replaceDraft(
                                                    draft.copy(
                                                        custom = if (checked) draft.custom else "",
                                                        skipped = false,
                                                    ),
                                                )
                                            },
                                            size = 14.dp,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = DshSettingIcons.Edit,
                                            contentDescription = null,
                                            tint = palette.labelTertiary,
                                            modifier = Modifier.padding(top = 3.dp).size(12.dp),
                                        )
                                    }
                                }
                                QuestionAnswerField(
                                    value = draft.custom,
                                    placeholder = "输入你的答案",
                                    modifier = Modifier.weight(1f),
                                    onValue = { typed ->
                                        replaceDraft(
                                            draft.copy(
                                                custom = typed,
                                                selected = if (multi) draft.selected else emptyList(),
                                                skipped = false,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DshIconButton(
                            icon = DshSettingIcons.ChevronLeft,
                            description = "上一题",
                            onClick = {
                                index -= 1
                                error = null
                            },
                            size = 24.dp,
                            enabled = index > 0,
                        )
                        Text(
                            text = (index + 1).toString() + " / " + questions.size,
                            modifier = Modifier.padding(horizontal = 4.dp),
                            fontSize = 14.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.labelSecondary,
                        )
                        DshIconButton(
                            icon = DshIcons.ChevronRight,
                            description = "下一题",
                            onClick = {
                                index += 1
                                error = null
                            },
                            size = 24.dp,
                            enabled = !last,
                        )
                    }
                    Text(
                        text = error.orEmpty(),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = palette.errorLabel,
                        textAlign = TextAlign.End,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DshButton(text = "跳过本题", onClick = { skipQuestion() })
                        DshButton(
                            text = if (last) "提交" else "下一题",
                            onClick = { continueFlow() },
                            kind = DshButtonKind.Primary,
                            enabled = draft.answered,
                        )
                    }
                }
            }
        }
    }
}

/** dsh 的 AnswerField：占位符压在输入框上（textarea 的 placeholder 是 14/24 的 label-caption） */
@Composable
private fun QuestionAnswerField(
    value: String,
    placeholder: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDshPalette.current
    Box(modifier) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                fontSize = 14.sp,
                lineHeight = 24.sp,
                color = palette.labelCaption,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 14.sp, lineHeight = 24.sp, color = palette.labelPrimary),
            cursorBrush = SolidColor(palette.business),
        )
    }
}

// ------------------------------------------------------------------ 任务 dock（dsh 的 TodoPanel）

/** dsh 的 CompletedGlyph 里的对勾（figma 14x14 画板） */
private const val TODO_CHECK_PATH =
    "M10.9631 5.71411L7.70154 8.97571C7.48011 9.19714 7.27736 9.40099 7.09229 9.54993C6.89742 9.70669 " +
        "6.66314 9.85279 6.3634 9.90027C6.2049 9.92534 6.04339 9.92534 5.88489 9.90027C5.58515 9.85279 " +
        "5.35087 9.70669 5.15601 9.54993C4.97093 9.40099 4.76818 9.19714 4.54675 8.97571L3.03516 7.46411L3.96313 " +
        "6.53613L5.47473 8.04773C5.7169 8.28989 5.86196 8.43389 5.97888 8.52795C6.08597 8.61409 6.10875 " +
        "8.60701 6.08997 8.604C6.11259 8.60758 6.13571 8.60758 6.15833 8.604C6.13954 8.60701 6.16232 " +
        "8.61409 6.26941 8.52795C6.38633 8.43389 6.53139 8.28989 6.77356 8.04773L10.0352 4.78613L10.9631 5.71411Z"

/**
 * 输入框上方的任务横窗，逐项对齐 dsh 的 TodoPanel（dsh-client-ui-conversation 的 skeleton/TodoPanel）：
 *
 *  - .root{border .5px border-l1;background:--dsw-specific-tip;border-radius:12px;overflow:hidden}
 *  - .body{flex column;gap:8px;padding:6px 12px}
 *  - .header{height:36px;padding:4px 12px;gap:10px}：清单图标 + 「任务」13/500/24 + 进度 13/400 三级色 + 折叠箭头
 *  - 进度是「N 已完成 / N 进行中 / N 待处理」中非零项用「 · 」连接（dsh 的 progressLabel）
 *  - .list{max-height:180px;gap:8px}：14x14 状态字形 + 13/20 正文，单行省略
 *  - 默认收起（dsh 的 collapsed 初值就是 true）
 *
 * 比 dsh 多一个「清除」按钮（用户要求）：dsh 的任务面板只能等模型下一次 todo 调用才消失。
 *
 * 清单是**会话级**的（dsh 的 session projection「todos」，见 [TodoStore]）：它只在自己的会话里
 * 显示 —— 旧实现是全局单例，换个会话还能看到上一个会话的任务。
 */
@Composable
private fun TodoDock(conversationId: Long?, todos: List<TodoItem>) {
    if (conversationId == null) return
    val palette = LocalDshPalette.current
    if (todos.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val done = todos.count { it.status == "completed" }
    val active = todos.count { it.status == "in_progress" }
    val pending = todos.size - done - active
    val progress = buildList {
        if (done > 0) add(done.toString() + " 已完成")
        if (active > 0) add(active.toString() + " 进行中")
        if (pending > 0) add(pending.toString() + " 待处理")
    }.joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.tip)
            .border(0.5.dp, palette.borderL1, RoundedCornerShape(12.dp))
            // 收起时整条与目标横窗一样高（36dp）：dsh 的 body 上下各 6px 内边距，
            // 手机上比目标条高一截，所以纵向内边距去掉，展开时再给列表补 6dp
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = DshDockIcons.Checklist,
                    contentDescription = null,
                    tint = palette.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Text("任务", fontSize = 13.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = palette.labelPrimary)
                Text(
                    text = progress,
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = palette.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (expanded) DshSettingIcons.ChevronDown else DshSettingIcons.ChevronUp,
                    contentDescription = null,
                    tint = palette.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
            }
            DshIconButton(
                icon = DshSidebarIcons.Trash,
                description = "清除任务",
                onClick = { TodoStore.clear(conversationId) },
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .heightIn(max = 180.dp)
                    // 卡片滚到头之后剩下的位移/惯性留在卡片里（第 115 轮，见 ScrollEdgeEater）
                    .nestedScroll(ScrollEdgeEater)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                todos.forEach { todo ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TodoStatusGlyph(todo.status, Modifier.size(16.dp))
                        Text(
                            text = todo.content,
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = palette.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * dsh 的三种状态字形（14x14 画板，外面套 16x16 的格子）：
 *  - completed：1.2 描边的圆 + 对勾（--dsw-alias-state-success-primary）
 *  - in_progress：线性渐变（currentColor → 透明）的圆环，1s 匀速自转
 *  - pending：2.4/2.4 虚线圆（--dsw-alias-label-caption）
 */
@Composable
private fun TodoStatusGlyph(status: String, modifier: Modifier = Modifier) {
    val palette = LocalDshPalette.current
    val check = remember { PathParser().parsePathString(TODO_CHECK_PATH).toPath() }
    val transition = rememberInfiniteTransition(label = "todo-progress")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing)),
        label = "todo-progress-angle",
    )
    Canvas(modifier.padding(1.dp)) {
        val unit = size.minDimension / 14f
        val center = Offset(this.center.x, this.center.y)
        val radius = 6.4f * unit
        val stroke = Stroke(width = 1.2f * unit)
        when (status) {
            "completed" -> {
                drawCircle(color = palette.success, radius = radius, center = center, style = stroke)
                withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
                    drawPath(check, color = palette.success)
                }
            }
            "in_progress" -> rotate(angle, pivot = center) {
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(palette.business, palette.business.copy(alpha = 0f)),
                        start = Offset(2.5f * unit, 12f * unit),
                        end = Offset(10.5f * unit, 3.5f * unit),
                    ),
                    radius = radius,
                    center = center,
                    style = stroke,
                )
            }
            else -> drawCircle(
                color = palette.labelCaption,
                radius = radius,
                center = center,
                style = Stroke(
                    width = 1.2f * unit,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.4f * unit, 2.4f * unit)),
                ),
            )
        }
    }
}

