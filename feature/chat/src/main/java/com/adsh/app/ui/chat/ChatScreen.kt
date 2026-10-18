package com.adsh.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.adsh.app.seam.tool.Answer
import com.adsh.app.core.tools.TodoItem
import com.adsh.app.core.tools.TodoStore
import com.adsh.app.seam.tool.UserQuestionChannel
import com.adsh.app.ui.blocks.ImagePreviewDialog
import com.adsh.app.ui.blocks.ImagePreviewState
import com.adsh.app.ui.theme.LocalDshPalette

/**
 * 跨页面存活的对话滚动状态（dsh 的 chatScroll 是模块级、按会话存档的）。
 *
 * **位置与跟随意图要一起存活**：只存 ScrollState 的话，去设置页 / 文件页再回来会新建一份
 * [ChatScrollState] —— 位置还在半途、跟随意图却被重置成「跟随」，回来的第一帧就被贴底保活
 * 拉回底部（会话早就结束了，看起来就是「自动滚动又自己触发了」）。
 * 这里实现成**单槽**（只记住最后一条会话）：覆盖「去设置页 / 文件页再回来」这条最常见的路径；
 * 在多个会话之间来回切仍会重建（dsh 那边是真按会话存档的）。
 */
private object ChatScrollStore {
    private var conversationId: Long? = null
    private var session: Session? = null

    class Session(val scroll: ScrollState, val chat: ChatScrollState)

    fun sessionFor(conversationId: Long?, initial: Int, slopPx: Int): Session {
        val existing = session
        if (existing != null && this.conversationId == conversationId) return existing
        return Session(ScrollState(initial.coerceAtLeast(0)), ChatScrollState(slopPx)).also {
            this.conversationId = conversationId
            session = it
        }
    }
}

/** footer 顶部渐变遮罩带的高度：dsh 的 composerSeat `linear-gradient(180deg, bg-base 0px, bg-base 36px)` */
internal val FOOTER_FADE_HEIGHT = 36.dp

/**
 * 静止时最后一条消息到 footer 上沿的间距：dsh 的 chat .scroll `padding-bottom: 16px`，
 * ADSH 沿用列表原有的 14dp 刻度（上下同一档）。
 */
internal val TRANSCRIPT_FOOTER_GAP = 14.dp

/** 遮罩背景最右侧不画的宽度：把最右边留给贴边滚动条（dsh 的 `scrollbar-gutter` 语义） */
private val FOOTER_MASK_RIGHT_INSET = 8.dp

/**
 * 会话列表的底部内边距（px）：footer 高度 + [TRANSCRIPT_FOOTER_GAP]。
 * 视口下沿就是 footer 下沿，所以静止时最后一条正好停在 footer 上沿 [TRANSCRIPT_FOOTER_GAP] 之上；
 * 滚动时内容从 footer 下面经过，由 footer 顶部的 [FOOTER_FADE_HEIGHT] 渐变淡出。
 */
internal fun transcriptBottomPaddingPx(footerHeightPx: Int, density: Density): Int =
    footerHeightPx + with(density) { TRANSCRIPT_FOOTER_GAP.roundToPx() }

/** [transcriptBottomPaddingPx] 的 Dp 形态（会话流底部内边距用） */
internal fun transcriptBottomPadding(footerHeightPx: Int, density: Density): Dp =
    with(density) { transcriptBottomPaddingPx(footerHeightPx, density).toDp() }

/**
 * footer（输入区整摞）的遮罩背景：顶部 [FOOTER_FADE_HEIGHT] 从透明淡到页面底色，往下实色 ——
 * 列表内容可以滚到输入卡下面与圆角后面，只会在这条带里淡出，不会出现硬切线。
 * 最右 [FOOTER_MASK_RIGHT_INSET] 不画，留给贴边滚动条。
 */
@Composable
private fun Modifier.footerSeatBackground(): Modifier {
    val base = MaterialTheme.colorScheme.background
    return drawBehind {
        if (size.height <= 0f || size.width <= 0f) return@drawBehind
        val solidWidth = (size.width - FOOTER_MASK_RIGHT_INSET.toPx()).coerceAtLeast(0f)
        if (solidWidth <= 0f) return@drawBehind
        val stop = (FOOTER_FADE_HEIGHT.toPx() / size.height).coerceIn(0f, 1f)
        drawRect(
            brush = Brush.verticalGradient(0f to Color.Transparent, stop to base, 1f to base),
            topLeft = Offset.Zero,
            size = Size(solidWidth, size.height),
        )
    }
}

@Composable
fun ChatScreen(
    state: ChatUiState,
    /** 当前会话标题（左上角显示；来自抽屉那份会话列表，见 AppRoot） */
    title: String,
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
    approval: com.adsh.app.seam.tool.ApprovalChannel.Pending?,
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
    /** 输入框的焦点：发消息、收起键盘这些「离开输入状态」的时机都要把它交出去（见下） */
    val focusManager = LocalFocusManager.current
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
    // 三条规则：统计浮窗与输入框弹层互斥；「+」打开的是全量菜单、
    // 草稿一变就交回「键入 /」那条路径；菜单关掉之后同一个 token + 同一份查询不再自动召回（dismissed）。
    var overlays by rememberSaveable(stateSaver = ChatOverlaysSaver) { mutableStateOf(ChatOverlays()) }
    // 会话一换就把草稿清掉（dsh 的输入框状态挂在会话上）。首帧不动：进程重建时 rememberSaveable
    // 恢复的那份草稿要留着，所以加非空判据；draftRevision 走 +1 是为了让输入框**回灌**一次。
    LaunchedEffect(state.conversationId) {
        if (draft.isNotEmpty()) {
            writeDraft("")
            overlays = overlays.sent()
        }
    }
    // 附件选择器（系统文件选择器 → 复制进工作区附件目录）在 Composer.rememberAttachmentPicker
    val pickAttachments = rememberAttachmentPicker(onImport = onImportAttachments)
    // 哪些轮次的过程窗口被展开了。状态托管在这里（而不是折叠行内部），因为过程行是
    // **独立的会话流一行**：展不展开决定列表里有哪几行（见 ChatItem 的注释）。
    val foldOpen = remember { mutableStateMapOf<Long, Boolean>() }
    // 哪些思考行的正文被展开了：键见 reasoningKey。同样托管在这里 —— 正文是**按块拆开的独立行**
    // （ChatItem.ReasoningBody），列表装配要知道它开没开（见那里的注释）。
    val reasoningOpen = remember { mutableStateMapOf<String, Boolean>() }
    // 流式正文与思考的重绘节流（~33ms 一拍，正文原样上屏）整块在 StreamReveal.kt；**采样状态挂在
    // [rememberStreamReveal] 这个调用点上** —— 下面这一层读 reveal.text / reveal.thinking，
    // 理由（「全文一次展示」那个 bug）写在那里的注释里。
    val reveal = rememberStreamReveal(
        conversationId = state.conversationId,
        liveTurnId = state.liveTurnId,
        streaming = state.streaming,
        reasoning = state.reasoning,
        sending = state.sending,
    )

    // 会话流的 items 装配（含两个「尾巴原子门」）在 TurnList.rememberChatItems
    val items = rememberChatItems(
        state = state,
        streaming = reveal.text,
        reasoning = reveal.thinking,
        compact = contentCompact,
        foldOpen = { foldOpen[it] == true },
        reasoningOpen = { reasoningOpen[it] == true },
    )
    val total = items.size
    val hasConversation = items.isNotEmpty()
    // 最后一轮的 key（只有它能从轮尾分叉）
    val lastTurnKey = items.lastOrNull { it is ChatItem.TurnTail }?.let { (it as ChatItem.TurnTail).view.key } ?: -1L
    /**
     * 任务横窗的清单**收到 ChatScreen 这一层**（不在 [TodoDock] 内部收集）：任务横窗出现 /
     * 变高 / 变矮时**这一层要在同一帧重组** —— 贴底请求在重组那一帧、测量之前发出。只在子 composable
     * 里收集的话，dock 高度变了而本屏不知道要重组，贴底要等下一帧（任务栏一出现底部那行先被盖住）。
     */
    val todos by remember(state.conversationId) {
        state.conversationId?.let { TodoStore.flow(it) }
            ?: kotlinx.coroutines.flow.MutableStateFlow<List<TodoItem>>(emptyList())
    }.collectAsStateWithLifecycle()

    // ---------------------------------------------------------------- 自动滚动
    // 整套口径（① 只有「跟随意图」一个状态；② 只有读者真的移动过才重算它；③ 贴底请求只由
    // 「内容 / 视口变了」触发；④ 请求在下面的 SideEffect 里发，落在本帧测量之前；⑤ 读者动作
    // 优先，一律先交出跟随；⑥ 自己发消息 / 点回到底部无条件恢复；⑦ 读者正在滚时一律不贴）
    // 连同**状态**都在 AutoScroll.kt（[ChatScrollState] / [KeepPinnedEffect]），这里只剩接线。
    // 每条会话一份**跨页面存活**的滚动状态（位置 + 跟随意图一起存档，见 [ChatScrollStore]）：
    // 初值给一个超大像素偏移，verticalScroll 首次测量会把它夹到内容末端 —— **进会话就是底部**
    // （dsh 打开会话也是直接贴底，不会先闪一下顶部）
    val density = LocalDensity.current
    val scrollSession = ChatScrollStore.sessionFor(
        conversationId = state.conversationId,
        initial = Int.MAX_VALUE,
        slopPx = with(density) { FOLLOW_THRESHOLD_DP.dp.toPx() }.toInt(),
    )
    val transcriptScroll = scrollSession.scroll
    // 跟随意图 / 手指 / 两个触发指纹 = 一份 [ChatScrollState]（与 ScrollState 同生命周期）
    val scroll = scrollSession.chat
    /** dsh 的 `followTail()`：把视口钉到内容末端（视觉上的「本帧就贴底」由 ChatTranscript 的
     * bottomCorrection 保证，这里只把 ScrollState 拨到真值）。
     *
     * **不经过计数状态**：那枚计数会在组合期被读到，自增又触发一次重组 —— 键盘动画期间每次
     * inset 变化都发一次请求，就成了「每帧两次重组」的环（真机 logcat 里是 8ms 一条的 pin req）。
     */
    val pinScope = rememberCoroutineScope()
    fun pinToBottom() {
        if (total > 0) pinScope.launch { transcriptScroll.scrollTo(transcriptScroll.maxValue) }
    }
    /**
     * 双击顶栏标题那一行 = 回到会话开头：显式导航，**先交出跟随**（否则流式贴底会立刻把人拽回底部），
     * 再跳到 0。与「回到底部」同一条口径：瞬移、不做滚动动画。
     */
    fun scrollToTop() {
        scroll.pause()
        pinScope.launch { transcriptScroll.scrollTo(0) }
    }
    /**
     * 读者动作（展开 / 收起会话里的任何一行、打开任务列表）：**先把跟随交出去**，见上面 ⑤。
     * dsh 的对照是 `pauseFollowing()`（显式导航时释放底部跟随）。
     *
     * **不要立粘住的 `readerHold`**（去挡松手时那两条「恢复跟随」的规则）：意图只由**读者的真实移动**
     * 重算，展开这一下没有移动，所以它自己就会一直停到读者滑回底部为止。
     */
    val readerAction: () -> Unit = remember(scroll) { { scroll.pause() } }
    /**
     * 读者位置采样（dsh 的 `onScroll` / `flushSample`）：位置一变就按 25dp 阈值重算跟随意图
     * —— 这一条就是 dsh 的 `sample(metrics, movedByReader)`。
     *
     * `isScrollInProgress` 一起采样：**读者自己往上滑的那一下（哪怕只有几个像素）立刻交出跟随**，
     * 见 [ChatScrollState.sampled] 的 ⑨；只有程序位移（回夹 / 贴底）不算。
     */
    LaunchedEffect(transcriptScroll, scroll.slopPx) {
        var previous = transcriptScroll.value
        snapshotFlow {
            Triple(transcriptScroll.value, transcriptScroll.maxValue, transcriptScroll.isScrollInProgress)
        }.collect { (value, maxValue, scrolling) ->
            val moved = scroll.sampled(
                previous = previous,
                current = value,
                atBottom = maxValue - value <= scroll.slopPx,
                readerScrolling = scrolling,
            )
            if (moved) previous = value
        }
    }
    /**
     * 「用户自己发了一条消息」= 会话里最后一条**真的用户消息**（`role = user` 且没有 `name`）换了 id。
     *
     * 通知（任务完成通知的两种形态、插话、权限预设切换…）在库里也是 `role = user`，但它们都带
     * `name` —— 少了这个过滤，一条后台任务通知落库就会把正在翻历史的读者一把拽回底部
     * （dsh 的 `lastIsUser` 同样只认真正的用户节点）。
     */
    val lastUserId = remember(state.messages) { lastOwnUserId(state.messages) }
    /**
     * footer（输入区整摞）的测量高度：它叠在列表上，**不占列表高度** —— 列表底部内边距 =
     * 它 + [TRANSCRIPT_FOOTER_GAP]，所以 footer 一长高（输入框折行、出现状态行 / 待办 dock），
     * 内容的静止位置也跟着上移。
     */
    var footerHeightPx by remember { mutableIntStateOf(0) }
    /**
     * 贴底指纹 = 内容 + footer 高度。**footer 高度不变时实例不变**：静止的会话不会因为重组就贴一次底
     * （⓪：贴底只由变化触发）。footer 高度变化 = 列表底部内边距变化 = 视口变化，与 dsh 的
     * ResizeObserver(composer) → followTail() 同义。
     */
    val layoutKey = remember(items, footerHeightPx) { items to footerHeightPx }
    // 键盘高度**不读进组合**：这一读会让整个会话页跟着 IME 动画的每一帧重组（全部行树），
    // 会话越长越卡 —— 用户报的「进入终端会话呼出键盘异常慢」就是它。视觉上的同帧贴底由
    // ChatTranscript 的 bottomCorrection 负责；ScrollState 由这条观察者补到真值（晚一帧，看不出来）。
    val imeDensity = LocalDensity.current
    // WindowInsets.ime 是 composable getter，只能在这里取一次；它返回的是**持有状态的对象**，
    // 每帧变化的 bottom 在 snapshotFlow 里读，组合不订阅，所以不会每帧重组。
    val imeInsets = WindowInsets.ime
    LaunchedEffect(transcriptScroll, scroll) {
        snapshotFlow { imeInsets.getBottom(imeDensity) }
            .collect {
                // ⑩ 键盘弹起 / 收起是「用户主动改视口」，跟着走的前提仍是**读者还在跟随**：
                // 贴着底时消息跟着键盘一起上移；读者翻过历史（按钮可见）时视口一动不动。
                if (!scroll.following || scroll.touching || transcriptScroll.isScrollInProgress) return@collect
                pinScope.launch { transcriptScroll.scrollTo(transcriptScroll.maxValue) }
            }
    }
    // 系统把键盘收起来（返回键 / 手势 / 切页）**不会**清焦点：不动它的话光标还会在输入框里闪。
    // 只认「显示 → 隐藏」这一跳；进屏时的初始 0 不算。
    LaunchedEffect(focusManager) {
        var imeVisible = imeInsets.getBottom(imeDensity) > 0
        snapshotFlow { imeInsets.getBottom(imeDensity) > 0 }
            .collect { visible ->
                if (imeVisible && !visible) focusManager.clearFocus()
                imeVisible = visible
            }
    }
    SideEffect {
        // ⑥ 用户自己发的一条消息刚落进会话：无条件恢复跟随（dsh 的 `ownInput` -> `followTail()`）
        if (scroll.ownInput(lastUserId)) scroll.resume()
        // ③ 内容 / 视口变了才发贴底请求；⑦ 读者正在滚（拖拽 / 惯性）时这一拍一律不贴 ——
        // 两条判据都在 [ChatScrollState.pinRequest] 里（口径见 AutoScroll.kt 的文件注释）。
        // 请求是「下一次测量用的位置」，本帧测量时它已经生效，所以新内容第一次被画出来就落在正确的
        // 位置，不存在「先画错、下一帧再补」的那一帧。
        // 视口指纹（IME 高度）不再参与：它那条由上面的观察者负责，内容变化走这里。
        // ⑩ 只有「读者还在跟随」才贴（reader 翻过历史时 pinRequest 内部就会挡住）。
        val pin = scroll.pinRequest(
            content = layoutKey,
            viewport = 0,
            scrolling = transcriptScroll.isScrollInProgress,
        )
        if (!pin) return@SideEffect
        if (com.adsh.app.ui.chat.ChatDebug.enabled) {
            trace(
                "pin req",
                "why=" + scroll.pinReason + " items=" + total + " text=" + reveal.text.length +
                    " think=" + reveal.thinking.length +
                    " calls=" + state.liveTurn.calls.size + " subs=" + state.liveTurn.subCalls.size +
                    " gap=" + (transcriptScroll.maxValue - transcriptScroll.value),
            )
        }
        pinToBottom()
    }
    // 贴底保活（图片 / LaTeX 解码长高、流式长高那一类）与「展开不做补偿滚动」的口径都在 AutoScroll.kt
    KeepPinnedEffect(transcriptScroll, scroll)

    // 触发菜单的五条命令（字典在 Palette.sessionPaletteCommands）
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

    // 同层浮层：宿主铺在内容层上面（不占布局），在 insets 内层 —— 键盘弹起时随可视区一起缩短，
    // 定位的夹取边界与 dsh 的 window.innerHeight 同义。
    val overlayHost = remember { DshOverlayHostState() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(
                WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)
            ),
    ) {
        CompositionLocalProvider(LocalOverlayHost provides overlayHost) {
        Column(
            Modifier
                .fillMaxSize(),
        ) {
        if (hasConversation) {
            // 顶栏整块在 ChatBars.kt
            ChatTopBar(
                title = title,
                conversationId = state.conversationId,
                readerAction = readerAction,
                onOpenWorkspaceFiles = onOpenWorkspaceFiles,
                onScrollToTop = { scrollToTop() },
            )
        }

        // 会话区 + 输入区，dsh 的 ConversationRoot .body：滚动体里 transcript 在下、composerSeat
        // sticky 在上。这里等价成「列表铺满整块、footer 叠上去」：内容可以滚到输入卡下面与圆角后面，
        // 由 footer 顶部的 36dp 渐变淡出，而不是在输入框上方被硬线截断。
        Box(Modifier.weight(1f).fillMaxWidth()) {
        // 会话流 + 读者手势 + 回到底部 / 右侧滚动条：整块在 ChatTranscript.kt
        ChatTranscript(
            state = state,
            items = items,
            scrollState = transcriptScroll,
            scroll = scroll,
            lastTurnKey = lastTurnKey,
            foldOpen = foldOpen,
            reasoningOpen = reasoningOpen,
            readerAction = readerAction,
            pinToBottom = { pinToBottom() },
            onBranch = onBranch,
            onOpenDeliverable = onOpenDeliverable,
            footerHeightPx = footerHeightPx,
        )

        // 输入框这一整摞叠在列表上（align Bottom），自己带 seat 的遮罩背景；它量出来的高度回灌给
        // 列表的底部内边距（见 footerHeightPx）。**第二道保险**：内容 / 视口指纹之外，再补
        // 「chrome 长高了但本屏没重组」那一种 —— 判据在 [ChatScrollState.chromeMeasured]。
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .footerSeatBackground()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    if (scroll.chromeMeasured(placeable.height, total)) {
                        if (com.adsh.app.ui.chat.ChatDebug.enabled) {
                            trace("pin chrome", "h=" + placeable.height + " items=" + total)
                        }
                        pinToBottom()
                    }
                    // footer 高度变化会改列表的底部内边距：写回 state，下一帧重组时生效（dsh 的 seat resize observer）
                    if (placeable.height != footerHeightPx) footerHeightPx = placeable.height
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
        ) {
        // 输入框上方那一摞（错误条 … 轮次状态行）整块在 ChatBars.kt
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
        )
        DshComposer(
                draft = draft,
                draftRevision = draftRevision,
                onDraftChange = {
                    draft = it
                    overlays = overlays.draftChanged(it)
                },
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
                // 输入框的弹层与下方那一排胶囊共用 ChatOverlays 的唯一一个槽；
                // 输入框只认自己那三个 id，别的 id 进来等于「三个都没开」。
                menu = overlays.open,
                onMenuChange = { value -> overlays = overlays.menuChanged(value, draft) },
                // 掉线重连条：挂在输入框那一行的右侧
                connection = state.connection,
                onRetryConnection = onRetryConnection,
                // 输入框下方那一排（dsh 的 composer dock）：会话统计 / Token 用量 / 上下文占用。
                // **它自己决定画不画**（[ChatStatsDock] 一条都没有时整排不存在、不占高度）：新会话、
                // 以及这一轮还没跑完时下方什么都没有，输入框停在原位、也不提前出现上下文图标。
                dock = {
                    ChatStatsDock(
                        stats = state.stats,
                        context = state.context,
                        open = overlays.open,
                        onToggle = { id -> overlays = overlays.toggled(id) },
                    )
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
                    // 发送 = 离开输入状态：光标不再留在清空后的输入框里（清焦点同时收起键盘）
                    focusManager.clearFocus()
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
        }
        }
        // 浮层宿主是根 Box 的最后一个孩子：盖在顶栏 / 消息流 / 输入区之上，且不参与布局
        DshOverlayLayer(overlayHost)
    }

    // 原图预览（dsh 的 lightbox）：对话里任何一张缩略图点开都挂到这里。
    // 状态在 ImagePreviewState 里 —— 图片散在消息与工具行深处，逐层传 lambda 会把每一层都污染。
    val previewPath by ImagePreviewState.path.collectAsStateWithLifecycle()
    previewPath?.let { path ->
        ImagePreviewDialog(path = path, onDismiss = { ImagePreviewState.close() })
    }
}
