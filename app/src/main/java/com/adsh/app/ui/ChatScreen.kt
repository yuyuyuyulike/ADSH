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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
    var paletteOpen by remember { mutableStateOf(false) }
    // 输入框里的三个弹层（权限 / 模型 / 上下文）由这里托管：它们互斥，
    // 并且只要是打开状态，消息区就要铺一层「点空白处关闭」的拦截层
    var composerMenu by remember { mutableStateOf<String?>(null) }
    // 同一个草稿只自动弹一次：面板被「点空白」关掉后，继续打字才会再次弹出
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
    var foldRevision by remember { mutableIntStateOf(0) }
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
    LaunchedEffect(state.sending, state.conversationId, state.liveTurnId) {
        while (latestSending.value) {
            kotlinx.coroutines.delay(33)
            if (sampledStreaming != latestStreaming.value) sampledStreaming = latestStreaming.value
        }
        // 定稿时立刻补齐最后一帧，不能等下一次采样（那时循环已经退出了）
        if (sampledStreaming != latestStreaming.value) sampledStreaming = latestStreaming.value
    }
    // 对话流：库里的消息 + 正在流式的这一轮（dsh 的 Chat Node 列表）
    val items = remember(
        state.messages,
        sampledStreaming,
        state.reasoning,
        state.liveCalls,
        state.liveSubCalls,
        state.sending,
        state.liveTurnId,
        contentCompact,
        foldRevision,
    ) {
        buildChatItems(
            messages = state.messages,
            streaming = sampledStreaming,
            reasoning = state.reasoning,
            liveCalls = state.liveCalls,
            liveSubCalls = state.liveSubCalls,
            sending = state.sending,
            liveTurnId = state.liveTurnId,
            compact = contentCompact,
            foldOpen = { foldOpen[it] == true },
        )
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
    /** 自动跟随：为 true 时内容长出来就钉在底部。关掉它的唯一入口是手指碰屏幕 */
    var follow by remember { mutableStateOf(true) }
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
    // 手势结束（跟手拖动停在底部、或甩到底）且已经贴底 → 恢复跟随
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && !touching && bottomGap <= bottomSlopPx) follow = true
        }
    }
    // 用户消息落库 / 切换会话：无条件贴底
    val lastUserId = remember(state.messages) { state.messages.lastOrNull { it.role == "user" }?.id ?: 0L }
    LaunchedEffect(lastUserId) {
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
    SideEffect {
        if (follow && total > 0) listState.requestScrollToItem(total - 1, Int.MAX_VALUE)
    }

    // 键盘弹出/收起时，消息必须跟着输入框一起上移。
    //
    // 键盘改变的是列表**视口高度**（根布局的 imePadding 把输入框顶上去、列表变矮），
    // 那是纯布局事件：不会触发重组，也就不会让上面的 SideEffect 再跑一遍 ——
    // 视口矮了而滚动位置没变，最后几条消息就被键盘盖住（用户看到的正是
    // 「点输入框消息不动，一打字才突然自己往上滚」，因为打字触发了重组）。
    // 这里盯着 IME 的下内边距：键盘动画的每一帧它都在变，每次都把视口重新钉到底，
    // 于是消息与输入框同步上移。读者已经滑上去看历史（follow = false）时不动。
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (!follow) return@LaunchedEffect
        withFrameNanos { }
        runCatching { listState.scrollToBottom() }
    }

    // 命令面板 = dsh 的指令清单（compact / export / permission / plan）
    val commands = listOf(
        PaletteCommand("compact", "压缩以上对话内容") { onCompact() },
        PaletteCommand("export", "将当前会话内容导出为 ZIP") {
            toastPath(context, exportSessionZip(context, state))
        },
        // dsh 的 leadingInput：/plan 把 token 写进输入框（着色），用户在后面直接输入内容
        PaletteCommand("permission", "切换权限预设（沙箱模式与审批策略）") { requestPermission = true },
        PaletteCommand("plan", "进入或退出计划模式") { writeDraft("/plan ") },
    )

    // 输入 "/" 自动弹出面板；面板可见性与草稿解耦，点空白处只关面板、不动草稿
    LaunchedEffect(draft) {
        if (draft.startsWith("/") && paletteMuted != draft) paletteOpen = true
    }
    val paletteVisible = paletteOpen && paletteMuted != draft
    val dismissPalette = {
        paletteMuted = draft
        paletteOpen = false
    }
    BackHandler(enabled = paletteVisible) { dismissPalette() }
    // 输入框弹层与会话统计都不抢窗口焦点（抢了输入法就会掉），所以返回键得自己接住：
    // 先关弹层，别让返回键直接退出应用
    BackHandler(enabled = composerMenu != null || statsOpen) {
        composerMenu = null
        statsOpen = false
    }

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
                    .padding(horizontal = 14.dp),
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
                            //  - 本来就在贴底跟随，而且这次只是点/按住、没真滑走（消息少一直在底部、
                            //    或 AI 的消息一直在屏幕里）→ 立刻恢复；
                            //  - 真滑走了（或本来就滑在上面）→ 只有回到整个会话的最底部才恢复。
                            //    读者停在会话中间时绝不动视口，这正是「一松手就被拽回底部」的根因。
                            touching = false
                            if ((wasFollowing && !dragged) || bottomGap <= bottomSlopPx) follow = true
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
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides contentDensity) {
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
                            // 折叠行：展开 / 收起（状态在 foldOpen 里，列表跟着重排）
                            is ChatItem.TurnFold -> TurnFoldRow(
                                view = item.view,
                                open = item.open,
                                onToggle = {
                                    foldOpen[item.view.key] = !item.open
                                    foldRevision++
                                },
                                modifier = Modifier.padding(top = item.gap),
                            )
                            // 过程里的一条：一行一个 item（展开时只组合看得见的那几行）
                            is ChatItem.TurnEntry -> TurnEntryRow(
                                view = item.view,
                                index = item.index,
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
                    // 点一下立刻收起（不等下一帧的滚动结果），再瞬移到底
                    showToBottom = false
                    follow = true
                    scope.launch { listState.scrollToBottom() }
                }
            }

            // 任一浮层（指令面板 / 权限 / 模型 / 上下文 / 会话统计）打开时，点消息区空白处关闭。
            // 用 pointerInput 而不是 clickable：clickable 会顺手把输入框的焦点抢走，键盘跟着就掉了。
            if (paletteVisible || composerMenu != null || statsOpen) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures {
                                statsOpen = false
                                composerMenu = null
                                dismissPalette()
                            }
                        },
                )
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
            TodoDock()
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
                commands = commands,
                paletteVisible = paletteVisible,
                onPaletteVisibleChange = { visible ->
                    paletteOpen = visible
                    if (visible) paletteMuted = null
                },
                menu = composerMenu,
                onMenuChange = { value ->
                    composerMenu = value
                    if (value != null) {
                        statsOpen = false
                        dismissPalette()
                    }
                },
                onAttach = { attachLauncher.launch(arrayOf("*/*")) },
                requestPermission = requestPermission,
                onRequestHandled = { requestPermission = false },
                showWorkspace = !hasConversation,
                workspaces = workspaces,
                workspaceId = state.workspaceId,
                onPickWorkspace = onPickWorkspace,
                onAddWorkspace = onAddWorkspace,
                context = if (hasConversation) state.context else null,
                planMode = state.planMode,
                onTogglePlan = onTogglePlan,
                attachments = state.pendingAttachments,
                onRemoveAttachment = onRemoveAttachment,
                onSend = {
                    val text = draft.trim()
                    writeDraft("")
                    paletteOpen = false
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
                        text == "/export" -> toastPath(context, exportSessionZip(context, state))
                        else -> onSend(text)
                    }
                },
                onCancel = onCancel,
        )
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
 */
@Composable
private fun UserMessage(
    content: String,
    time: Long,
    attachments: List<com.adsh.app.core.agent.UserAttachment> = emptyList(),
) {
    val palette = LocalDshPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
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
private fun fitImageSize(
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
private fun AttachmentImageContent(
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
private fun cachedAttachmentBitmap(path: String, targetPx: Int): ImageBitmap? {
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
    SelectionContainer { MarkdownBody(text = content) }
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
                MarkdownBody(text = plan)
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
 */
@Composable
private fun TodoDock() {
    val todos by TodoStore.items.collectAsStateWithLifecycle()
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
                onClick = { TodoStore.clear() },
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

