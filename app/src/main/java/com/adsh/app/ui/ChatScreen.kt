package com.adsh.app.ui

import android.content.ClipData
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
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
import com.adsh.app.core.tools.TodoStore
import com.adsh.app.core.tools.UserQuestionChannel
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 跨页面存活的对话滚动状态（dsh 的 chatScroll 是模块级、按会话存档的）。
 *
 * 如果 LazyListState 建在 ChatScreen 里，去设置页 / 文件页再回来会重新建一个：
 * 位置丢失，只能先画一帧错的位置再滚 —— 就是「切回来闪一下」。
 * 这里实现成**单槽**（只记住最后一条会话的 state）：覆盖的是「去设置页 / 文件页再回来」这条
 * 最常见的路径，回来时位置原样还在、要贴底时同一帧就落到底。在多个会话之间来回切仍会重建，
 * 那一步的位置恢复没有实现（dsh 那边是模块级、真按会话存档的）。
 */
internal object ChatScrollStore {
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
 * 瞬时贴底 —— dsh 的 `el.scrollTop = el.scrollHeight`：
 * 把最后一项「滚到视口顶部」再按一个超大偏移量补齐，于是无论最后一项多高，视口都真正落到底部。
 * 不用 animateScrollToItem：动画既慢、又会在每一项上重排，看起来就是「来回闪」。
 */
private suspend fun LazyListState.scrollToBottom() {
    val count = layoutInfo.totalItemsCount
    if (count > 0) scrollToItem(count - 1, Int.MAX_VALUE)
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

/**
 * 「展开之后向下展示」要滚多少像素（纯函数，单测从这里进）。
 *
 * 判据一句话：**把这一行的底边带进视口，但绝不把它的行首推过视口顶边**。
 *  - 底边本来就在视口里（overflow <= 0）⇒ 0，不动；
 *  - 行比视口矮 ⇒ 正好补上超出的那一点（整行完整可见）；
 *  - 行比视口高 ⇒ 滚到「行首贴住视口顶边」为止 —— 能露多少露多少，
 *    展开体从它自己的开头往下铺（而不是整段跳到屏幕顶部以外）。
 */
internal fun revealScrollDelta(itemOffset: Int, itemSize: Int, viewportStart: Int, viewportEnd: Int): Int {
    val overflow = (itemOffset + itemSize) - viewportEnd
    val headroom = itemOffset - viewportStart
    return minOf(overflow, headroom).coerceAtLeast(0)
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
 * 「展开之后向下展示」的一次请求。
 *
 * @param key 那一行的 LazyColumn item key
 * @param beforeSize 展开**之前**它的高度（-1 = 当时没在视口里）：等到高度变了才说明展开体
 *   已经量过，这时算出来的滚动量才是对的（早一帧量到的是旧高度，滚了也白滚）
 * @param seq 序号：同一个 key 连点两次展开也要能重新触发（状态相等 LaunchedEffect 不会重启）
 */
private data class RevealRequest(val key: String, val beforeSize: Int, val seq: Int)

/** dsh 的空态文案（hero.headline / hero.preview） */
/** 往上滑出这么多才浮出「回到底部」（dsh 只有 25px 的判据，这里留出迟滞，见 showToBottom） */
private const val SHOW_TO_BOTTOM_DP = 64f

private const val HERO_HEADLINE = "探索未至之境"
private const val HERO_BADGE = "预览版"

// 对话流节点模型（dsh 的 Chat Node）与整形逻辑在 TurnList.kt

@Composable
fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onNewConversation: () -> Unit,
    onClearError: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
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

    /** dsh 的 command-input：斜杠命令在会话里留一行（模型看不到） */
    onCommand: (String) -> Unit = {},
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
    /** 清空排队发送的消息（dsh 的 queue chip） */
    onClearQueued: () -> Unit = {},
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
    // 流式正文按 ~33ms 采样一次再进列表。**采样状态就放在这里（ChatScreen 自己的作用域）**，
    // 不能抽成一个「返回采样值」的子 composable：那种写法下写入只让子作用域失效，
    // 而 items 的 remember 与贴底的 SideEffect 都在**这一层** —— 采样落地后没人重组这一层，
    // 表现就是流式正文一直不动、直到这一轮定稿才整段出现（用户实测的「全文一次展示」）。
    // 放在这里，写 sampledStreaming 就是写这一层读的 state：采样一落地必定重组这一层。
    var sampledStreaming by remember(state.conversationId, state.liveTurnId) {
        mutableStateOf(state.streaming)
    }
    val latestStreaming = rememberUpdatedState(state.streaming)
    val latestSending = rememberUpdatedState(state.sending)
    /**
     * 流式文本**被清零 / 换了一段**时立刻跟上（不等 33ms 的采样节拍）——第 81 轮修。
     *
     * 少这一条就会看到「同一个正文出现两次、下面的工具行被顶下去再弹回来」（用户实测的
     * 「工具调用展示闪烁/撕裂」，而且只在模型**在工具调用之间说话**时出现）。机制：
     * AgentLoop 是一步一落库的 —— assistant 行先写库，再发 `StepCommitted`；轮到界面处理时
     * `state.streaming` 已经清零、`state.messages` 已经带上这一步的那一行，**但
     * [sampledStreaming] 要等下一次采样（最多 33ms ≈ 两帧）才清**。这几帧里 `buildChatItems`
     * 会同时把「库里那一步的正文」与「还没清掉的流式尾巴」放进同一轮 —— 于是正文重复一行，
     * 排在它下面的工具行位置整个跳一下。增长仍走 33ms 节拍（那是重绘节流），只有
     * 「变短 / 换段」这一种变化立刻生效。
     */
    LaunchedEffect(state.conversationId, state.liveTurnId) {
        snapshotFlow { latestStreaming.value }.collect { live ->
            if (live.length < sampledStreaming.length || !live.startsWith(sampledStreaming)) {
                sampledStreaming = live
            }
        }
    }
    LaunchedEffect(state.sending, state.conversationId, state.liveTurnId) {
        while (latestSending.value) {
            kotlinx.coroutines.delay(33)
            if (sampledStreaming != latestStreaming.value) sampledStreaming = latestStreaming.value
        }
        // 定稿时立刻补齐最后一帧，不能等下一次采样（那时循环已经退出了）
        if (sampledStreaming != latestStreaming.value) sampledStreaming = latestStreaming.value
    }
    // 对话流：库里的消息 + 正在流式的这一轮（dsh 的 Chat Node 列表）
    //
    // 用 derivedStateOf（而不是 remember(各种 key)）：折叠状态是 SnapshotStateMap，
    // 读它就会被记账 —— 某一轮展开/收起时这里自动重算，不需要再维护一个「修订号」
    // 手动把它踢醒（旧写法的 foldRevision++ 就是这么来的）。
    val items by remember(
        state.messages,
        sampledStreaming,
        state.reasoning,
        state.reasoningRunning,
        state.liveCalls,
        state.liveSubCalls,
        state.liveTurnId,
        contentCompact,
    ) {
        derivedStateOf {
            buildChatItems(
                messages = state.messages,
                streaming = sampledStreaming,
                reasoning = state.reasoning,
                liveCalls = state.liveCalls,
                liveSubCalls = state.liveSubCalls,
                reasoningRunning = state.reasoningRunning,
                liveTurnId = state.liveTurnId,
                compact = contentCompact,
                foldOpen = { foldOpen[it] == true },
            )
        }
    }
    val total = items.size
    val hasConversation = items.isNotEmpty()
    // 最后一轮的 key（只有它能从轮尾分叉）
    val lastTurnKey = items.lastOrNull { it is ChatItem.TurnTail }?.let { (it as ChatItem.TurnTail).view.key } ?: -1L

    // ---------------------------------------------------------------- 自动滚动
    // 规则（按真机反馈重写，见「第 49 轮」）：
    //  - 手指碰到消息区就**立刻**停止自动滚动 —— 包括只是点一下、以及消息很少一直贴底的情况；
    //  - 手指离开屏幕后，只有「会话真的在最底部」才恢复自动跟随（手动滑到底、点「回到底部」
    //    按钮也一样，两者都会让底部判据成立）；
    //  - 用户发送消息：无条件贴底并恢复跟随。
    //
    // 判「最底部」必须落在**列表最后一项**上，不能只看「当前露出来的最后一项」：
    // 一轮回答的底边正好落到视口底边时，后者会算出「已到底」—— 表现就是「回到底部」按钮
    // 在会话中间一闪一灭，松手还会被当成已贴底而弹回底部（用户实测反馈）。
    //
    // 关键做法：列表是**正序**的（与 dsh 的 DOM 顺序一致），贴底靠下面的 SideEffect
    // 用 requestScrollToItem(total - 1, Int.MAX_VALUE) 在每一帧测量之前把视口钉到底。
    // 正序下新内容长在视口**下面**，贴底只是把视口跟着往下推，已经画好的行一动不动；
    // 展开/收起同理，只影响被点那一行下面的内容。
    //
    // 每条会话一份**跨页面存活**的滚动状态（初值 = 第 0 项 = 最新的一条）
    // 初值给一个超大索引：Compose 会把它夹到最后一项，于是**进会话就是底部**
    // （dsh 打开会话也是直接贴底，不会先闪一下顶部）
    val listState = ChatScrollStore.stateFor(state.conversationId, Int.MAX_VALUE)
    /** 自动跟随：为 true 时内容长出来就钉在底部。关掉它的唯一入口是手指碰屏幕或读者的展开动作 */
    var follow by remember { mutableStateOf(true) }
    /**
     * 读者是否已经**接管**滚动（展开 / 收起过会话里的某一行）——第 77 轮加。
     *
     * 只把 `follow` 置 false 是不够的：手指抬起时还有两条「恢复跟随」的规则（见下面手势的
     * finally）——「按之前就在跟随、且这一下没滑走」和「抬手时人就在整个会话的最底部」。
     * 而读者点的那一行通常正好贴着视口底边（流式输出正把视口钉在底部），于是同一套手势
     * 结束时 follow 又被置回 true，下一次流式增量（几十毫秒后）就把视口重新钉到底 ——
     * 刚展开的内容被顶到视口上面去。用户看到的就是「点了展开，自动滚动还在跑、内容往上跑」。
     *
     * 所以读者一动手就立一个**粘住**的标记：两处自动恢复都先问它。解除的时机只有三个 ——
     * 读者自己滑到（或点回）整个会话的底部、发出一条消息、换一条会话。
     */
    var readerHold by remember(state.conversationId) { mutableStateOf(false) }
    /**
     * 读者动作（展开 / 收起会话里的某一行）：**自动滚动的优先级最低**。
     *
     * dsh 的对照实现是 `pauseFollowing()`（显式导航时释放底部跟随）+ `navigation.cancel()`（读者一
     * 交互就取消正在排队的滚动）。这里就是立起 [readerHold] 并停止跟随 —— 否则流式输出
     * 每 33ms 把视口钉回底部，刚展开的那一行就被顶出视口（用户报的「消息被向上顶起」）。
     * 想恢复跟随：滑回底部，或点右下角的「回到底部」。
     */
    val readerAction: () -> Unit = remember { { readerHold = true; follow = false } }
    /** 「展开之后向下展示」的请求（见 [RevealRequest]）：列表量完这一行的高度后把它滚进视口 */
    var revealRequest by remember { mutableStateOf<RevealRequest?>(null) }
    /** 请求序号：同一个 key 连续展开两次也要能重新触发（状态相等就不会重启 LaunchedEffect） */
    val revealSeq = remember { intArrayOf(0) }
    fun revealAfterExpand(itemKey: String) {
        val before = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey }?.size ?: -1
        revealRequest = RevealRequest(itemKey, before, ++revealSeq[0])
    }
    /** 手指是否按在消息区。按着的时候（松手与否）自动滚动都必须停 */
    var touching by remember { mutableStateOf(false) }
    // 25dp 的余量用来吸收取整误差（dsh 的 atBottom 阈值就是 25px）
    val bottomSlopPx = with(LocalDensity.current) { 25.dp.toPx() }.toInt()
    /** 离整个会话真正的底部还有多少像素（0 = 就在底部，见 [LazyListState.bottomGap]） */
    val bottomGap by remember(listState, bottomSlopPx) { derivedStateOf { listState.bottomGap() } }

    /**
     * 「回到底部」按钮的可见性（dsh 的 toBottomSlot）：
     *  - 就在会话底部（差不到 25dp）立刻收起；
     *  - 只有读者**自己**往上滑出 [SHOW_TO_BOTTOM_DP] 之后才重新浮出来。
     *    dsh 是一离开底部就浮出来（同样的 25px 判据），但流式输出时每一帧内容都在长高，
     *    那一瞬间的「不在底部」会让按钮每帧闪一下 —— 迟滞把这种闪动挡掉。
     *    读者在会话中间（后面还有没露出来的行）时 [LazyListState.bottomGap] 返回 Int.MAX_VALUE，
     *    所以按钮在整个会话范围内都稳稳定在「不在底部」这一侧。
     */
    var showToBottom by remember(state.conversationId) { mutableStateOf(false) }
    val showToBottomSlop = with(LocalDensity.current) { SHOW_TO_BOTTOM_DP.dp.toPx() }.toInt()
    LaunchedEffect(listState, showToBottomSlop) {
        snapshotFlow { bottomGap }.collect { gap ->
            showToBottom = when {
                gap <= bottomSlopPx -> false
                // 还在自动跟随（读者没动过）：不浮出来
                follow -> false
                gap >= showToBottomSlop -> true
                else -> showToBottom
            }
        }
    }
    val scope = rememberCoroutineScope()
    /** 点消息区空白处时把光标与键盘收起来（见下面手势里的 clearFocus） */
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // 手势结束（跟手拖动停在底部、或甩到底）且已经贴底 → 恢复跟随。
    // 读者刚展开过某一行（readerHold）时不恢复：见 readerHold 的注释。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && !touching && !readerHold && bottomGap <= bottomSlopPx) follow = true
        }
    }
    // 用户消息落库 / 切换会话：无条件贴底（读者接管的状态也一并交还）
    val lastUserId = remember(state.messages) { state.messages.lastOrNull { it.role == "user" }?.id ?: 0L }
    LaunchedEffect(lastUserId) {
        readerHold = false
        follow = true
        listState.scrollToBottom()
    }
    // 贴底必须发生在**这一帧测量之前**（dsh 的 followRef：`el.scrollTop = el.scrollHeight`）。
    //
    // 之前这里是一个 `while (sending) { withFrameNanos {}; scrollToBottom() }` 的循环：
    // 它先等一帧、再滚 —— 于是新追加的那一行会先被**按旧的滚动位置画出来**（在视口下面，
    // 或者只露出一角），下一帧视口才补到底，看起来就是「从下面冒出来，然后跳到上面该在的位置」。
    // 内容越长、行越高，这一跳越明显。
    //
    // requestScrollToItem 的语义正是「下一次测量时用这个位置」：在 SideEffect 里调用，
    // 它就落在本帧的测量之前，新行第一次被画出来时视口已经在底部了 —— 与 dsh 一样是「直接出现」。
    //
    // **只在「内容真的往后长」时贴底**（用户要求：自动滚动的优先级最低）：
    //  - appended：末尾追加了新的一行（行数变了，或最后一行的 key 变了）；
    //  - tailGrew：正在流式的尾巴变长了（正文 / 思考 / 子调用）；
    //  - viewportMoved：**视口**变了（键盘弹出/收起，见下面的 imeBottom）。
    // 读者展开/收起某一行**不算** —— 那会让行数或高度变化，旧写法（每帧无条件贴底）就会把
    // 刚展开的内容顶上去。这一条与 dsh 的判定同构：它在 Node 列表结构变化（order.length /
    // lastKey / running）时才 followTail()，纯粹的尺寸变化只走 resize/reconcile。
    val liveVersion = sampledStreaming.length + state.reasoning.length +
        state.liveCalls.size + state.liveSubCalls.size
    val lastItemKey = items.lastOrNull()?.let { transcriptKey(it) }.orEmpty()
    // 键盘弹出/收起时，消息必须跟着输入框一起上移。
    //
    // 键盘改变的是列表**视口高度**（根布局的 imePadding 把输入框顶上去、列表变矮），
    // 那是纯布局事件：不会触发重组，也就不会让 SideEffect 再跑一遍 ——
    // 视口矮了而滚动位置没变，最后几条消息就被键盘盖住（用户看到的正是
    // 「点输入框消息不动，一打字才突然自己往上滚」，因为打字触发了重组）。
    // 所以 IME 的下内边距也当成「尾部要跟着长」的信号之一，一起丢给下面那个 SideEffect：
    // 键盘动画的每一帧它都在变，每一帧都把视口重新钉到底，消息与输入框严丝合缝地同步上移。
    // 读者已经滑上去看历史（follow = false）时不动 —— 与「自动滚动优先级最低」同一条规矩。
    //
    // 旧写法是一个 `LaunchedEffect(imeBottom) { withFrameNanos {}; scrollToBottom() }`：
    // 它先等一帧再滚，而 imeBottom 每帧都在变 —— 每次变化都**取消**上一个还没跑到
    // scrollToBottom 的协程，于是滚动永远比键盘慢一拍（用户实测的「呼出键盘不跟手」）。
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    /**
     * 「刚刚钉过一次底」的信号：下一帧再核对一次。
     *
     * 为什么需要（用户实测：run_code 和它的子调用一起蹦出来时，上面的内容会**上滚过头**、
     * 然后快速下滚到正确位置）：贴底请求发生在**本帧测量之前**，这时新长出来的那几行还没被量过，
     * LazyColumn 是按尚未更新的行高把视口推到底的 —— 位置会过头；旧实现要等**下一次内容变化**
     * （下一个流式增量，几十到几百毫秒之后）才把它纠回来，所以那一下看得见。
     * 这里在钉完之后隔一帧（真实测量已经就位）核对一次：还在跟随、又不在底部就补一钉。
     * 收敛的：补正的依据是真实测量，最多补一次，不会来回抖。
     */
    val pinSignal = remember { kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED) }
    LaunchedEffect(listState) {
        for (unused in pinSignal) {
            withFrameNanos { }
            if (!follow || touching) continue
            val count = listState.layoutInfo.totalItemsCount
            if (count == 0) continue
            val gap = listState.bottomGap()
            // 第 81 轮：补正改成**按这一帧量出来的差值**滚（scrollBy），不再发第二个
            // 「滚到最后一项」的请求。请求是按上一帧的行高算的 —— 新长出来的内容比旧的高多少，
            // 它就偏多少，于是「先过头、下一帧再弹回来」（旧注释里记的那次用户实测）。
            // gap == Int.MAX_VALUE（最后一项还没露面）时没法按像素补，仍用请求。
            when {
                gap == Int.MAX_VALUE -> listState.requestScrollToItem(count - 1, Int.MAX_VALUE)
                gap > bottomSlopPx -> listState.scrollBy(gap.toFloat())
            }
        }
    }
    // 四个格子放在数组里（而不是 MutableState）：SideEffect 里写它不该触发重组
    val pinMark = remember(state.conversationId) { intArrayOf(-1, -1, 0, -1) }
    SideEffect {
        val appended = items.size != pinMark[0] || lastItemKey.hashCode() != pinMark[2]
        val tailGrew = liveVersion != pinMark[1]
        val viewportMoved = imeBottom != pinMark[3]
        pinMark[0] = items.size
        pinMark[1] = liveVersion
        pinMark[2] = lastItemKey.hashCode()
        pinMark[3] = imeBottom
        if (follow && !touching && total > 0 && (appended || tailGrew || viewportMoved)) {
            listState.requestScrollToItem(total - 1, Int.MAX_VALUE)
            pinSignal.trySend(Unit)
        }
    }

    /**
     * 「展开之后向下展示」。
     *
     * 展开是**向下长**的（列表正序，见上面的注释），而读者点的那一行常常正好贴着视口底边
     * （流式输出正把视口钉在底部）—— 新长出来的那一块整个落在视口下面，看起来像「点了没反应」。
     * 这里等这一行真的重新量过（高度变了），再**只滚必要的量**把它的底边带进视口：
     *   - 行比视口矮：滚到整行完整可见为止（最多就是这一行的展开体）；
     *   - 行比视口高：滚到行首贴住视口顶边为止 —— 展开体从它自己的开头往下铺。
     * 只认「展开」，收起不滚（收起不需要展示什么新东西）。
     */
    LaunchedEffect(revealRequest) {
        val request = revealRequest ?: return@LaunchedEffect
        fun item() = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == request.key }
        // 等到高度变化 = 展开体已经参与测量；超时（行被移出视口等）就按当前值算
        val info = withTimeoutOrNull(500) {
            snapshotFlow { item() }.first { it != null && it.size != request.beforeSize }
        } ?: item() ?: return@LaunchedEffect
        val layout = listState.layoutInfo
        val viewportStart = layout.viewportStartOffset + layout.beforeContentPadding
        val viewportEnd = layout.viewportEndOffset - layout.afterContentPadding
        val delta = revealScrollDelta(info.offset, info.size, viewportStart, viewportEnd)
        if (delta > 0) listState.animateScrollBy(delta.toFloat())
    }

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
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // dsh 会话统计入口（IconGaugeOutline16），点开是统计弹窗（不再整页跳转）
                Box {
                    IconTap(DshIcons.Gauge, "会话统计与 Token 用量", tint = palette.labelPrimary) {
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
                // 右上角只保留「工作区文件预览」（dsh 的 IconPanelLeftOutline16 镜像 = 分栏线在右）
                IconTap(DshIcons.PanelRight, "工作区文件", tint = palette.labelPrimary) { onOpenWorkspaceFiles() }
            }
        }

        // 手指碰到消息区（滑动或点击）就**立刻**停止自动滚动；手指离开屏幕时，如果人就在
        // 整个会话的最底部（消息少、一直贴底的情况），也立刻恢复 —— 用户要求：只有手指
        // 在屏幕上时才停自动滚动。
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // 键用 listState：换会话时 LazyListState 会重建，这里跟着重启才能拿到新的
                // 底部判据（bottomGap 是 remember(listState, ...) 出来的）
                .pointerInput(listState) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        val startY = down.position.y
                        // 手指按下之前本来就在跟随（= 就在会话最底部）
                        val wasFollowing = follow
                        // 这一次手势是不是真的把内容滑走了（超过 touch slop）
                        var dragged = false
                        touching = true
                        follow = false
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
                            // 恢复规则：
                            //  - 读者这一下真把视口滑走了（dragged）⇒ 读者接管结束，下面两条规则照旧；
                            //  - 本来就在贴底跟随，而且这次只是点/按住、没真滑走（消息少一直在底部、
                            //    或 AI 的消息一直在屏幕里）→ 立刻恢复；
                            //  - 真滑走了（或本来就滑在上面）→ 只有回到整个会话的最底部才恢复。
                            //    读者停在会话中间时绝不动视口，这正是「一松手就被拽回底部」的根因。
                            //  - **读者刚展开过某一行（readerHold）→ 一条都不恢复**：那一下通常就落在
                            //    最底部，若不拦住，这里的「抬手时在底部」会立刻把跟随打开（第 77 轮
                            //    用户报的「展开后自动滚动还在跑」就是这么来的）。
                            touching = false
                            if (dragged) readerHold = false
                            val restore = !readerHold && ((wasFollowing && !dragged) || bottomGap <= bottomSlopPx)
                            if (restore) follow = true
                            // 点在消息区（没滑动的那一下）= 离开输入状态：收起光标与键盘
                            // （用户要求：点其他空白地方，输入框里的光标就消失）。滑动不算 ——
                            // 滑历史的时候键盘不该自己收起来。
                            if (!dragged) focusManager.clearFocus()
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
                        when (item) {
                            // dsh 的 system-prompt 节点：系统提示词 / 系统提示词更新
                            is ChatItem.SystemPrompt ->
                                Box(Modifier.padding(top = item.gap)) { SystemPromptRow(item.text, item.update) }
                            // dsh 的 context 节点：上下文注入
                            is ChatItem.Context -> Box(Modifier.padding(top = item.gap)) {
                                ContextInjectionRow(label = item.label, form = item.form, text = item.text)
                            }
                            is ChatItem.User -> Box(Modifier.padding(top = item.gap)) {
                                UserMessage(item.text, item.time, item.attachments)
                            }
                            is ChatItem.Compact -> Box(Modifier.padding(top = item.gap)) { CompactCard(item.text) }
                            // 折叠行：展开 / 收起（状态在 foldOpen 里，items 是 derivedStateOf，
                            // 写它就会重算列表 —— 不需要额外的「修订号」
                            is ChatItem.TurnFold -> TurnFoldRow(
                                view = item.view,
                                open = item.open,
                                // expanded = 这一下是「展开」（收起不需要向下展示什么）
                                onToggle = { expanded ->
                                    // 读者动作：停止自动跟随（否则这一展开会被流式贴底顶上去）
                                    readerAction()
                                    if (expanded) revealAfterExpand(transcriptKey(item))
                                    foldOpen[item.view.key] = expanded
                                },
                                modifier = Modifier.padding(top = item.gap),
                            )
                            // 过程里的一条：一行一个 item（展开时只组合看得见的那几行）
                            is ChatItem.TurnEntry -> TurnEntryRow(
                                view = item.view,
                                index = item.index,
                                // 行里的展开/收起是行内部状态：它一动就停止自动跟随；
                                // 展开的那一下再把这一行向下带进视口（见 revealAfterExpand）
                                onReaderAction = { expanded ->
                                    readerAction()
                                    if (expanded) revealAfterExpand(transcriptKey(item))
                                },
                                modifier = Modifier.padding(top = item.gap),
                            )
                            // 轮尾：只有最后一轮可以从轮尾分叉（dsh 的 branchUnavailable = 后面还有节点）
                            is ChatItem.TurnTail -> TurnTailRow(
                                view = item.view,
                                branchable = item.view.closed && item.view.key == lastTurnKey && !state.sending,
                                onBranch = onBranch,
                                modifier = Modifier.padding(top = item.gap),
                            )
                        }
                    }
                }
            }
            }
            // dsh 的 toBottomSlot：不在底部时右下角浮一个 34px 的圆形按钮（sticky bottom 16px）
            if (hasConversation && showToBottom) {
                BackToBottomButton(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
                ) {
                    // 点一下立刻收起（不等下一帧的滚动结果），再瞬移到底；读者接管的状态也交还
                    showToBottom = false
                    readerHold = false
                    follow = true
                    scope.launch { listState.scrollToBottom() }
                }
            }
        }

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
            TodoDock(conversationId = state.conversationId)
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
        DshComposer(
                draft = draft,
                draftRevision = draftRevision,
                onDraftChange = { draft = it },
                sending = state.sending,
                busyEnter = state.busyEnter,
                queuedCount = state.queuedCount,
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

    // 原图预览（dsh 的 lightbox）：对话里任何一张缩略图点开都挂到这里。
    // 状态在 ImagePreviewState 里 —— 图片散在消息与工具行深处，逐层传 lambda 会把每一层都污染。
    val previewPath by ImagePreviewState.path.collectAsStateWithLifecycle()
    previewPath?.let { path ->
        ImagePreviewDialog(path = path, onDismiss = { ImagePreviewState.close() })
    }
}

/**
 * dsh 的「回到底部」（.EvIC1a_toBottom + chat.toBottom）：
 * 34px 圆形、100px 圆角、浮在内容右下角（bottom 16px）、图标是 ChevronDown14，
 * 只在不在底部时出现；点一下瞬移到底（不做动画滚动）。
 */
@Composable
private fun BackToBottomButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Box(
        modifier = modifier
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

/** 检查点卡片：历史被压缩后的摘要，默认折叠 */
@Composable
private fun CompactCard(text: String) {
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
            ) { expanded = !expanded }
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
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
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
internal val MESSAGE_IMAGE_MAX_HEIGHT = 320.dp

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
internal fun fileSizeText(bytes: Long): String {
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
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1000)
            copied = false
        }
    }
    RailIconAction(
        icon = if (copied) DshIcons.Check else DshToolIcons.Copy,
        label = if (copied) "复制成功" else "复制",
    ) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("adsh", text))
        copied = true
    }
}

/**
 * dsh 的 formatMessageClock（中文字典 clock.md / clock.ymd）：
 * 同一天只给 HH:mm；同一年给「M月d日 HH:mm」；跨年给「y年M月d日 HH:mm」。
 */
internal fun formatMessageClock(time: Long): String {
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
private fun TodoDock(conversationId: Long?) {
    if (conversationId == null) return
    val todos by remember(conversationId) { TodoStore.flow(conversationId) }.collectAsStateWithLifecycle()
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

