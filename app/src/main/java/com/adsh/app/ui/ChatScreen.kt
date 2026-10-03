package com.adsh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsh.app.core.tools.Answer
import com.adsh.app.core.tools.TodoItem
import com.adsh.app.core.tools.TodoStore
import com.adsh.app.core.tools.UserQuestionChannel
import com.adsh.app.ui.theme.LocalDshPalette

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

@Composable
fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onClearError: () -> Unit,
    onOpenWorkspaceFiles: () -> Unit,
    /** 已绑定的工作区（dsh 侧栏的树）与「添加工作区」入口 */
    workspaces: List<com.adsh.app.core.data.WorkspaceEntity>,
    onAddWorkspace: () -> Unit,
    onPickWorkspace: (Long) -> Unit,
    modelGroups: List<ChatViewModel.ModelGroup>,
    onSelectModel: (String, String) -> Unit,
    /** 模型菜单里各提供方的余额（DeepSeek 专有）+ 打开菜单时的刷新入口 */
    balances: Map<String, ChatViewModel.BalanceState>,
    onRefreshBalance: () -> Unit,
    efforts: List<Pair<String, String>>,
    onSelectEffort: (String) -> Unit,
    onSelectPermission: (String) -> Unit,
    onImportAttachments: (List<android.net.Uri>) -> ImportResult,
    onRemoveAttachment: (String) -> Unit,
    onTogglePlan: () -> Unit,
    onSetPlan: (Boolean) -> Unit,
    onCompact: () -> Unit,
    question: UserQuestionChannel.Pending?,
    onAnswer: (List<Answer>) -> Unit,
    onSkipQuestion: () -> Unit,
    /** 待审批的越权请求（dsh 的 ApprovalPanel）：非空时输入框上方出现审批卡 */
    approval: com.adsh.app.core.tools.ApprovalChannel.Pending?,
    onAllowApproval: () -> Unit,
    onRejectApproval: () -> Unit,
    /** dsh 轮尾的「在新对话中分支」：从某一轮分叉出一条新会话 */
    onBranch: (Long) -> Unit,
    /**
     * 轮尾交付物卡片被点开（dsh 的 PresentedFileCard.onPreview → openFile(path)）：
     * 参数是交付物声明的路径（工作区相对路径，工作区外是绝对路径），由上层进文件预览。
     */
    onOpenDeliverable: (String) -> Unit,
    /** 清空排队发送的消息（dsh 的 queue chip） */
    onClearQueued: () -> Unit,
    /**
     * 掉线重连条被点了一下：立刻重发（dsh 的 `connection.reconnect()` —— 退避序列归零、
     * 马上重连，不等剩下的退避时间）。
     */
    onRetryConnection: () -> Unit,
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
    // 触发菜单（dsh 的 ui-input-trigger）、输入框的三个弹层（权限 / 模型 / 上下文）、顶栏的统计浮窗：
    // 开关与**互斥规则**都在 [ChatOverlays]（纯函数，表在 ChatOverlaysTest），这里只持有状态。
    // 三条规则值得记着（原来散在六个写入点）：统计浮窗与输入框弹层互斥；「+」打开的是全量菜单、
    // 草稿一变就交回「键入 /」那条路径；菜单关掉之后同一个 token + 同一份查询不再自动召回（dismissed）。
    var overlays by rememberSaveable(stateSaver = ChatOverlaysSaver) { mutableStateOf(ChatOverlays()) }
    // 附件选择器（系统文件选择器 → 复制进工作区附件目录）在 Composer.rememberAttachmentPicker
    val pickAttachments = rememberAttachmentPicker(onImport = onImportAttachments)
    // 哪些轮次的过程窗口被展开了。状态托管在这里（而不是折叠行内部），因为过程行是
    // **独立的 LazyColumn item**：展不展开决定列表里有哪几行（见 ChatItem 的注释）。
    val foldOpen = remember { mutableStateMapOf<Long, Boolean>() }
    // 流式正文与思考的采样（~33ms 一拍 + 平滑显现）整块在 StreamReveal.kt；**采样状态挂在
    // [rememberStreamReveal] 这个调用点上** —— 下面这一层读 reveal.text / reveal.thinking，
    // 理由（「全文一次展示」那个 bug）写在那里的注释里。
    val reveal = rememberStreamReveal(
        conversationId = state.conversationId,
        liveTurnId = state.liveTurnId,
        streaming = state.streaming,
        reasoning = state.reasoning,
        sending = state.sending,
        reasoningRunning = state.reasoningRunning,
        toolArgsFlowing = state.toolArgsFlowing,
    )

    // 会话流的 items 装配（含两个「尾巴原子门」）在 TurnList.rememberChatItems（R20 搬出主函数）
    val items = rememberChatItems(
        state = state,
        streaming = reveal.text,
        reasoning = reveal.thinking,
        compact = contentCompact,
        foldOpen = { foldOpen[it] == true },
    )
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
    // 整套口径（① 只有「跟随意图」一个状态；② 只有读者真的移动过才重算它；③ 贴底请求只由
    // 「内容 / 视口变了」触发；④ 请求在下面的 SideEffect 里发，落在本帧测量之前；⑤ 读者动作
    // 优先，一律先交出跟随；⑥ 自己发消息 / 点回到底部无条件恢复；⑦ 读者正在滚时一律不贴）
    // 连同**状态**都在 AutoScroll.kt（[ChatScrollState] / [PinFixEffect]），这里只剩接线。
    // 每条会话一份**跨页面存活**的滚动状态（初值 = 第 0 项 = 最新的一条）
    // 初值给一个超大索引：Compose 会把它夹到最后一项，于是**进会话就是底部**
    // （dsh 打开会话也是直接贴底，不会先闪一下顶部）
    val listState = ChatScrollStore.stateFor(state.conversationId, Int.MAX_VALUE)
    // 跟随意图 / 手指 / 方向 / 两个触发指纹 = 一份 [ChatScrollState]（R14 搬出主函数）
    val density = LocalDensity.current
    val scroll = remember(state.conversationId) {
        ChatScrollState(slopPx = with(density) { FOLLOW_THRESHOLD_DP.dp.toPx() }.toInt())
    }

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
    val readerAction: () -> Unit = remember(scroll) { { scroll.pause() } }
    /**
     * 读者位置采样（dsh 的 `onScroll` / `flushSample`）：位置一变就记方向、并按 25dp 阈值重算跟随
     * 意图 —— 两条都在 [ChatScrollState.sampled] 里（第 96 轮那两个快捷按钮的方向判据也在那儿）。
     */
    LaunchedEffect(listState, scroll.slopPx) {
        var previous = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { current ->
                val moved = scroll.sampled(previous, current, atBottom = listState.bottomGap() <= scroll.slopPx)
                if (moved) previous = current
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
    /**
     * 「用户自己发了一条消息」= 会话里最后一条**真的用户消息**（`role = user` 且没有 `name`）换了 id。
     *
     * 通知（任务完成通知的两种形态、插话、权限预设切换…）在库里也是 `role = user`，但它们都带
     * `name` —— 少了这个过滤，一条后台任务通知落库就会把正在翻历史的读者一把拽回底部
     * （dsh 的 `lastIsUser` 同样只认真正的用户节点）。
     */
    val lastUserId = remember(state.messages) { lastOwnUserId(state.messages) }
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
    SideEffect {
        // ⑥ 用户自己发的一条消息刚落进会话：无条件恢复跟随（dsh 的 `ownInput` -> `followTail()`）
        if (scroll.ownInput(lastUserId)) scroll.resume()
        // ③ 内容 / 视口变了才发贴底请求；⑦ 读者正在滚（拖拽 / 惯性）时这一拍一律不贴 ——
        // 两条判据都在 [ChatScrollState.pinRequest] 里（口径见 AutoScroll.kt 的文件注释）。
        // 请求是「下一次测量用的位置」，本帧测量时它已经生效，所以新内容第一次被画出来就落在正确的
        // 位置，不存在「先画错、下一帧再补」的那一帧。
        if (!scroll.pinRequest(items, imeBottom, listState.isScrollInProgress)) return@SideEffect
        if (com.adsh.app.BuildConfig.DEBUG) {
            trace(
                "pin req",
                "why=" + scroll.pinReason + " items=" + total + " text=" + reveal.text.length +
                    " think=" + reveal.thinking.length +
                    " calls=" + state.liveTurn.calls.size + " subs=" + state.liveTurn.subCalls.size +
                    " gap=" + listState.bottomGap() +
                    " first=" + listState.firstVisibleItemIndex + "+" + listState.firstVisibleItemScrollOffset,
            )
        }
        pinToBottom()
    }
    // 兜底补钉（图片 / LaTeX 解码长高那一类）与「展开不做补偿滚动」的口径都在 AutoScroll.kt
    PinFixEffect(listState, scroll)

    // 触发菜单的五条命令（字典在 Palette.sessionPaletteCommands，R19 搬出主函数）
    val commands = sessionPaletteCommands(
        onPickFile = pickAttachments,
        onPlan = { writeDraft("/plan ") },
        onCompact = onCompact,
        onPermission = { requestPermission = true },
        onExport = { toastPath(context, exportSessionZip(context, state)) },
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
    // 三条规则与两条路径的优先级都在 [paletteViewOf] 里（纯函数，表在 PaletteTest）
    val paletteView = paletteViewOf(overlays, draft, commands)
    // 点空白 / 返回键关掉：把当前草稿标记为已忽略，别立刻又弹出来（dsh 的 dismissed）
    val dismissPalette = { overlays = overlays.paletteDismissed(draft) }
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
            // 顶栏整块在 ChatBars.kt（R14 第三步搬出主函数）
            ChatTopBar(
                state = state,
                overlays = overlays,
                onOverlaysChange = { overlays = it },
                draft = draft,
                readerAction = readerAction,
                onOpenWorkspaceFiles = onOpenWorkspaceFiles,
            )
        }

        // 会话流 + 读者手势 + 右上角快捷导航：整块在 ChatTranscript.kt（R14 第二步搬出主函数）
        ChatTranscript(
            state = state,
            items = items,
            listState = listState,
            scroll = scroll,
            atTop = atTop,
            previousUserId = previousUserId,
            lastTurnKey = lastTurnKey,
            foldOpen = foldOpen,
            readerAction = readerAction,
            pinToBottom = { pinToBottom() },
            onBranch = onBranch,
            onOpenDeliverable = onOpenDeliverable,
        )


        // 错误条 … 输入框这一整摞包一层 `Modifier.layout`：它拿到的是这块内容的**测量高度**，
        // 而 Column 是「先量所有非 weight 子节点、最后才量 weight 的消息列表」—— 所以这里比
        // 消息列表**先跑**，在列表测量之前把贴底请求发出去，同一帧就落到新的底部。
        //
        // 第 107 轮起这是**第二道保险**（主路径是上面那个跟内容 / 视口指纹走的 SideEffect）：它专门补
        // 「chrome 长高了、但本屏没有重组」那一种 —— 最典型的是**输入框自己长高**（草稿折行），
        // 那是 DshComposer 的内部状态，ChatScreen 并不知道自己该重组。
        // 与 dsh 的 ResizeObserver(column / composer) → followTail() 是同一条语义，只是这里落在同一帧。
        // 「上一次高度」与判据都在 [ChatScrollState.chromeMeasured]（同样不是 State：写它不该触发重组）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    if (scroll.chromeMeasured(placeable.height, total)) {
                        if (com.adsh.app.BuildConfig.DEBUG) {
                            trace("pin chrome", "h=" + placeable.height + " items=" + total)
                        }
                        pinToBottom()
                    }
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
        ) {
        // 输入框上方那一摞（错误条 … 掉线条）整块在 ChatBars.kt（R14 第三步搬出主函数）
        ChatBottomBars(
            state = state,
            question = question,
            approval = approval,
            todos = todos,
            onClearError = onClearError,
            onAnswer = onAnswer,
            onSkipQuestion = onSkipQuestion,
            onAllowApproval = onAllowApproval,
            onRejectApproval = onRejectApproval,
            onClearQueued = onClearQueued,
            onRetryConnection = onRetryConnection,
        )
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
                paletteCommands = paletteView.rows,
                paletteVisible = paletteView.visible,
                onPaletteVisibleChange = { want ->
                    // dsh 的 toggleCommandMenu（ui-conversation/src/client/apply.ts:505-517）：
                    // 打开的是**全量菜单**（查询传空），关掉则记下 dismissed —— 按同一 token 同一查询
                    // 再 track 一次不会把它召回来；再按一次「+」是新的意图，dismissed 清掉。
                    overlays = overlays.paletteVisibility(want, draft)
                },
                onPaletteDismiss = dismissPalette,
                menu = overlays.composerMenu,
                onMenuChange = { value -> overlays = overlays.menuChanged(value, draft) },
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
                    // 「这条草稿想干什么」是纯函数（[commandIntentOf]，表在 PaletteTest）；
                    // 这里只管派发。顺序仍是「先算意图 → 清草稿 → 关菜单」。
                    val intent = commandIntentOf(draft)
                    writeDraft("")
                    overlays = overlays.sent()
                    when (intent) {
                        is CommandIntent.Plan -> {
                            onSetPlan(intent.on)
                            if (intent.rest.isNotEmpty()) onSend(intent.rest)
                        }
                        CommandIntent.Compact -> onCompact()
                        // dsh 的 matchEnter：精确命中的命令由目录解析后派发（/permission 是 popupSelect）
                        CommandIntent.Permission -> requestPermission = true
                        CommandIntent.Export -> toastPath(context, exportSessionZip(context, state))
                        is CommandIntent.Send -> onSend(intent.text)
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
