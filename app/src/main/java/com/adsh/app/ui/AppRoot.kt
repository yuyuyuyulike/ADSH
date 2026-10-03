package com.adsh.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsh.app.core.data.SettingsStore
import com.adsh.app.ui.panels.TerminalPanel
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

sealed interface Dest {
    data object Conversation : Dest
    data object Settings : Dest
    data object Terminal : Dest
    data object WorkspaceFiles : Dest
}

/**
 * App 根：抽屉 + 覆盖式页面导航。
 * 抽屉按 dsh 侧栏的信息架构：品牌行 / 新会话 / 工作区（下挂会话列表）/ 设置。
 * 手势挂在中间内容区（边缘让给系统返回），拖动 55% 抽屉宽度即开满，并带速度吸附。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRoot(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val question by viewModel.question.collectAsStateWithLifecycle()
    /** 待审批的越权请求（dsh 的 ApprovalPanel；失败关闭，没人应答就不放行） */
    val approval by viewModel.approval.collectAsStateWithLifecycle()
    /** 模型菜单里的余额（DeepSeek 专有；每次打开菜单刷新） */
    val balances by viewModel.balances.collectAsStateWithLifecycle()
    val workspace by viewModel.workspaceInfo.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val workspaceList by viewModel.workspaceList.collectAsStateWithLifecycle()
    val searchState by viewModel.search.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val stack = remember { mutableStateListOf<Dest>(Dest.Conversation) }
    // 设置页当前分栏：设置页会被终端/文件预览页顶掉（组合销毁），状态必须托管在这里，
    // 否则从「功能 → 终端 → 终端会话」返回时会跳回第 0 栏（通用设置）。
    var settingsTab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    /**
     * 抽屉进度（0 = 关到底，1 = 全开）。
     *
     * 这里刻意用一个**普通 float state**（不是 Animatable）：
     *  - 拖动时直接写它，读它的地方全在 `offset {}` / `graphicsLayer {}` 的延迟 lambda 里，
     *    于是每一帧只是重新放置 + 重新绘制，**不会让会话页那一整棵子树重组**；
     *    以前进度是在组合期读的（还顺手算台阶），手指一动就重组一遍整页，帧率自然不如
     *    纯位移的整页覆盖动画；
     *  - 也不再每个拖动事件起一个协程去 snapTo（一次触摸上百个事件 = 上百个协程），
     *    拖动是同步写状态，只有「松手后的归位」才交给 [animate] 跑动画。
     */
    var progress by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val hazeState = dev.chrisbanes.haze.rememberHazeState()
    val density = LocalDensity.current
    // 抽屉宽度 = 主内容位移 = 拖动跨度，**三者必须相等**（历史教训：位移比抽屉窄 52dp 时
    // 拉开后抽屉被主内容盖住一角；拖动跨度乘 0.55 时手指走 1px 内容走 1.8px，轻轻一动就滑一大截）。
    val drawerPx = with(density) { DRAWER_WIDTH_DP.dp.toPx() }

    /**
     * 正在退场的覆盖页。pop 时先从栈里摘掉，但要留在组合里把滑出动画放完才释放
     * （终端的 PTY 会话、预览页的资源都是在真正被释放那一帧才收）。
     */
    val leaving = remember { mutableStateListOf<Dest>() }

    fun push(dest: Dest) {
        // 同一个覆盖页正好在退场：把它从退场名单里拿掉，让它重新滑进来
        leaving.remove(dest)
        stack.add(dest)
    }

    fun pop() {
        if (stack.size <= 1) return
        val gone = stack.removeAt(stack.lastIndex)
        leaving.add(gone)
        scope.launch {
            delay(SLIDE_MS + 32L)
            leaving.remove(gone)
        }
    }

    /**
     * 松手后的归位（把进度推到 0 或 1；新的一次拖动会先把它取消）。
     *
     * **就是一条匀速直线**（[DRAWER_SLIDE_MS] 毫秒，**`LinearEasing`，不做任何缓动**）：
     * 用户对这块的最终口径是「不要有什么缓动，就是一个普普通通的动画」——
     * 第 82 轮试过弹簧 + 手势初速度（末尾抖动、花哨），第 83 轮试过 `FastOutSlowInEasing`
     * 的定长缓动（那种「先慢后快再慢」在抽屉上一样读成怪），两次都退回来了。
     * 所以这里保持最笨的一条：定长、匀速、与手势速度无关。
     *
     * 插值不会越出 `0..1`（起点与终点都在区间内，线性插值当然也在），所以这里
     * **不需要夹取**，圆角那边也不需要「防负数」的保险 —— 那是弹簧时代才成立的兜底。
     *
     * @param afterContentSwap 这一下归位是不是紧跟着**换正文**（点会话 / 新建 / 分叉）。
     *   是的话先**等两帧**再起步（不是延迟，是让最重的那两帧先过去）：新内容要在下一帧组合
     *   （可见行全部重建）、再下一帧才测量定位（首帧定位到会话底部）；动画若从同一帧起步，
     *   头几帧会被这两件事拖长 —— 同一个 spring 一旦掉帧，看起来就是「一跳就到、回拉过快」。
     *   两帧 ≈33ms 落在抽屉还完全盖着主界面的时候，感知不到。
     *   **别的手势路径（拖动松手、点空白、返回键）没有这件事要等** —— 一律等两帧的话，
     *   动画会在手指离开的那一刻先冻住两帧再起步，那才是「怪怪的」。
     */
    fun settleDrawer(target: Float, afterContentSwap: Boolean = false) {
        settleJob?.cancel()
        settleJob = scope.launch {
            if (afterContentSwap) {
                withFrameNanos { }
                withFrameNanos { }
            }
            animate(
                initialValue = progress,
                targetValue = target,
                // 匀速：不做缓动（见上面的 KDoc）
                animationSpec = tween(DRAWER_SLIDE_MS, easing = LinearEasing),
            ) { value, _ -> progress = value }
        }
    }

    /**
     * 收起抽屉（拖拽 / 点空白 / 选了会话 / 返回键）。
     *
     * 正文那边不用另外等：命中缓存时 [ConversationRepository.cachedMessages] 是同步换完的，
     * 剩下要等的只有「新内容的组合 + 测量」那两帧 —— 见 [settleDrawer] 的 afterContentSwap。
     *
     * @param afterContentSwap 选了会话 / 新建 / 分叉传 true；点空白、返回键不传。
     */
    fun closeDrawer(afterContentSwap: Boolean = false) = settleDrawer(0f, afterContentSwap = afterContentSwap)

    // 绑定工作区 = 系统文件管理器里选一个文件夹（与导入附件同一套 SAF 流程，只选文件夹、不列文件）。
    // 用户取消选择（uri == null）就是「不绑定」：什么都不做，也不弹任何提示 ——
    // 之前这里会提示「改用应用内浏览选择」并弹出一个内置目录浏览器，那套兜底已经删掉了。
    val workspacePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = folderPathFromTreeUri(uri)
        if (path != null) {
            persistTreePermission(context, uri)
            viewModel.bindWorkspaceFolder(path)
            // 绑了手机文件夹但还没给「所有文件访问」：真实路径一律读不到（listFiles 返回空），
            // 文件浏览 / 导入附件 / 导出 ZIP 全都会失败，这里立刻把话说明白
            if (!hasAllFilesAccess()) {
                android.widget.Toast.makeText(
                    context,
                    "还需要在系统设置里允许「所有文件访问」，否则读不到手机里的文件",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val addWorkspace = {
        workspacePicker.launch(android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A"))
    }

    // 文件预览的「窗口」：打开一个文件加一个标签，✕ 关掉；最后一个关掉就退出预览页
    val previewTabs = remember { mutableStateListOf<String>() }
    var previewIndex by remember { mutableStateOf(0) }   // 0 = 工作区文件（目录树）

    fun openPreview(path: String) {
        var index = previewTabs.indexOf(path)
        if (index < 0) {
            previewTabs.add(path)
            index = previewTabs.size - 1
        }
        previewIndex = index + 1
        if (stack.last() != Dest.WorkspaceFiles) push(Dest.WorkspaceFiles)
    }

    fun closePreviewTab(index: Int) {
        if (index <= 0 || index > previewTabs.size) return
        previewTabs.removeAt(index - 1)
        previewIndex = 0
    }

    val drawerDrag = Modifier.draggable(
        orientation = Orientation.Horizontal,
        // 覆盖页（设置 / 终端 / 文件预览）在最上面时**不接**抽屉手势：抽屉与主内容都被不透明的
        // 覆盖页盖住，右滑只会把看不见的抽屉悄悄拉开，退回主界面才看到它已经被拉出来（用户实测
        // 的「文件预览页中心右滑后返回，抽屉是开着的」）。手势只在会话页（stack 只剩它）时生效。
        enabled = stack.size == 1,
        state = rememberDraggableState { delta ->
            settleJob?.cancel()
            // 拖动 1:1 跟手（跨度就是抽屉宽度，见 drawerPx 的注释）
            progress = (progress + delta / drawerPx).coerceIn(0f, 1f)
        },
        onDragStopped = { velocity ->
            // 方向判定在 DrawerSettle.kt（纯函数，用例盯着）：速度够就按方向、不够就按位置过半。
            // 速度本身不进动画（见那里的注释）。
            settleDrawer(if (drawerOpensAfterRelease(progress, velocity)) 1f else 0f)
        },
    )

    // 这两个只在「跨过台阶」时变：返回键的启用条件、模糊层的存在与否都不该跟着每一帧重组
    val drawerHalfOpen by remember { derivedStateOf { progress > 0.5f } }
    val blurVisible by remember { derivedStateOf { progress > 0f } }

    BackHandler(enabled = stack.size > 1 || drawerHalfOpen) {
        // 先退覆盖页、再关抽屉：从设置返回时会话页还在，抽屉也还开着
        if (stack.size > 1) pop() else closeDrawer()
    }

    // 浮层登记处：所有 DshPopup 一打开就登记自己，「点别处 / 返回键」由下面这一层统一关闭
    // （覆盖层、抽屉、设置页里的菜单一视同仁，见 OverlayDismiss.kt）
    val overlayDismiss = remember { OverlayDismissRegistry() }
    // 边缘防误触的带宽（dp）：第 99 轮起固定 12dp（设置项已撤，见 SettingsStore.EDGE_GUARD_WIDTH_DP）
    val edgeGuardDp = SettingsStore.EDGE_GUARD_WIDTH_DP

    OverlayDismissHost(overlayDismiss) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 边缘防误触（见 EdgeTouchGuard.kt）：贴边那一条里的手指整根不参与手势。
            // 必须放在抽屉手势**之前**：同一元素上的多个 pointerInput 按链序派发，
            // Initial 阶段最外层先跑 —— 先摘掉的手指，后面的手势节点就再也看不到它了。
            // 第 99 轮起宽度固定 12dp（设置项已撤，见 SettingsStore.EDGE_GUARD_WIDTH_DP）。
            .edgeTouchGuard(edgeGuardDp.dp)
            // 抽屉手势挂在根层：抽屉拉出后左滑落在抽屉那一侧也能关回去（之前手势只挂在会话内容区，
            // 抽屉占了大半屏，左滑等于滑在抽屉上，完全没反应）。覆盖页在最上面时手势自动停用
            // （见 drawerDrag 的 enabled）—— 否则右滑会把看不见的抽屉悄悄拉开。
            .then(drawerDrag)
            // 弹层打开时，任何一次「点击」都先关掉它（不消费按下，所以滚动照常；只吃掉那次抬起）
            .dismissOverlaysOnPress(overlayDismiss),
    ) {
        // 抽屉常驻在最底层：不再是拖动第一帧才组合（那是「开始滑动卡顿」的主因），
        // 也不会盖住主页面 —— 现在是主页面带着圆角压在上面。
        Drawer(
            modifier = Modifier
                .width(DRAWER_WIDTH_DP.dp)
                .fillMaxHeight()
                .offset { IntOffset(((progress - 1f) * drawerPx).roundToInt(), 0) },
            workspaces = workspaceList,
            conversations = conversations,
            blankIds = state.blankConversationIds,
            currentId = state.conversationId,
            currentWorkspaceId = state.workspaceId,
            // 这四条都会**换掉主界面里的会话内容**。切会话那条要**先等内容就位再收起**：
            // switchConversation 是 suspend 的，缓存未命中时它会等正文读出来（正常几毫秒）——
            // 收起动画期间没有正文换入换出，剩下的起步时机交给 settleDrawer 的两帧等齐。
            onNewConversation = { viewModel.newConversation(); closeDrawer(afterContentSwap = true) },
            onNewConversationIn = { id -> viewModel.newConversationIn(id); closeDrawer(afterContentSwap = true) },
            onSelectConversation = { id ->
                scope.launch { viewModel.switchConversation(id); closeDrawer(afterContentSwap = true) }
            },
            onRenameConversation = viewModel::renameConversation,
            onForkConversation = { id -> viewModel.forkConversation(id); closeDrawer(afterContentSwap = true) },
            onDeleteConversation = { id -> viewModel.deleteConversation(id) },
            onAddWorkspace = { closeDrawer(); addWorkspace() },
            onDeleteWorkspace = viewModel::deleteWorkspace,
            // 点「设置」不收起抽屉：设置页会从右侧盖上来，退出后抽屉还在原处
            onOpenSettings = { push(Dest.Settings) },
            // 终端同理：覆盖页从右侧盖上来，退出后抽屉还在原处
            onOpenTerminal = { push(Dest.Terminal) },
            bootstrapError = workspace.bootstrapError,
            search = searchState,
            onSearch = viewModel::searchSessions,
            // 抽屉收起（点到主内容 / 划走）时把搜索整块复位 —— dsh 的 WorkspaceBrowser 收起后
            // 组件卸载，搜索自然回到收起态；ADSH 的抽屉是常驻的，得自己复位
            open = drawerHalfOpen,
        )

        // 主内容层：位移 + 圆角 + 投影 + 模糊都在同一层里，**四项都跟着 progress 连续走**。
        // 位移 / 圆角 / 投影在 graphicsLayer 的延迟读取里，每帧改它们不触发重组；
        // 模糊半径（hazeBlur 的样式）在组合期，以前怕每帧重建 RenderEffect 而按 1/8 台阶走 ——
        // 台阶在动画末尾会一跳一档地"卡"，那正是用户说的抖动来源；现在跟 progress 连续走。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 这一层里的读取都是延迟的：拖动时只更新图层属性，不触发重组
                    // 主内容与抽屉等速反向位移：拉开后抽屉正好占满让出来的那块（见 drawerPx 的注释）
                    translationX = progress * drawerPx
                    // 圆角从 0 长到 20dp：progress 由 tween 在 0..1 之间插值，不会越界，
                    // 所以这里不需要 coerceIn（那是弹簧会过冲的时代留下的）
                    shape = RoundedCornerShape((20f * progress).dp)
                    clip = progress > 0f
                    shadowElevation = 12.dp.toPx() * progress
                    ambientShadowColor = Color.Black
                    spotShadowColor = Color.Black
                }
        ) {
            Box(Modifier.fillMaxSize().hazeSource(state = hazeState)) {
            ChatScreen(
                        state = state,
                        onSend = viewModel::send,
                        onCancel = viewModel::cancel,
                        onClearError = viewModel::clearError,
                        onOpenWorkspaceFiles = { push(Dest.WorkspaceFiles) },
                        workspaces = workspaceList,
                        onAddWorkspace = addWorkspace,
                        onPickWorkspace = viewModel::openWorkspace,
                        modelGroups = viewModel.availableModelGroups,
                        onSelectModel = viewModel::setModel,
                        balances = balances,
                        onRefreshBalance = viewModel::refreshBalances,
                        efforts = viewModel.availableEfforts,
                        onSelectEffort = viewModel::setEffort,
                        onSelectPermission = viewModel::setPermission,
                        onImportAttachments = { uris -> viewModel.importAttachments(uris) },  // 复制进会话私有目录 → 待发附件
                        onRemoveAttachment = viewModel::removeAttachment,
                        onTogglePlan = viewModel::togglePlan,       // Plan chip
                        onSetPlan = viewModel::setPlan,             // /plan、/plan off
                        onCompact = viewModel::compact,             // /compact
                        question = question,
                        onAnswer = viewModel::answerQuestion,
                        onSkipQuestion = viewModel::skipQuestion,
                        approval = approval,
                        onAllowApproval = viewModel::allowApprovalOnce,
                        onRejectApproval = viewModel::rejectApproval,
                        onBranch = viewModel::branchAt,
                        onClearQueued = viewModel::clearQueued,
                        // 掉线重连条被点了一下 → 立刻重发（dsh 的 connection.reconnect()）
                        onRetryConnection = viewModel::retryConnection,
                        // 轮尾交付物卡片：dsh 的 PresentedFileCard.onPreview → openFile(path)。
                        // 交付物存的是**声明时**的路径（工作区相对路径，工作区外是绝对路径），
                        // 这里按当前工作区解析成绝对路径再进预览（dsh 的 resolveWorkspacePath(cwd, path)）。
                        onOpenDeliverable = { path -> openPreview(resolveDeliverablePath(workspace.path, path)) },
            )
            }

            // 拖动时主页面逐渐变糊（上限 4dp），点击空白处收起抽屉；模糊层只覆盖会话页，不会糊到抽屉
            if (blurVisible) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .hazeBlur(
                            input = HazeInput.Sources(hazeState),
                            style = HazeBlurStyle.Material3 {
                                // 只保留一点点模糊：Material3 预设默认会再蒙一层 surface 底色，
                                // 那一层才是「虚化过重、像起雾」的主因（dsh 的侧栏也没有毛玻璃）
                                blurRadius((MAX_DRAWER_BLUR_DP * progress).dp)
                                backgroundColor(Color.Transparent)
                                noiseFactor(0f)
                            },
                        )
                        .pointerInput(Unit) { detectTapGestures { closeDrawer() } }
                )
            }
        }

        // 覆盖层：设置 / 终端 / 工作区文件。**从栈底到栈顶逐层叠放，被盖住的页面不销毁。**
        //
        // 原来这里是 AnimatedContent(targetState = panel)：push 终端时底下的设置页会被组合
        // 销毁，pop 回来再从零组合一遍。真机实测的后果有两个：退出终端会卡出一个 ~500ms 的帧
        // （整棵设置页要重建），设置页里插件卡片的展开态也跟着没了。改成叠层之后：
        //  - 底下的页面一直是活的（展开态 / 滚动位置 / pager 分栏都在，不需要任何托管）；
        //  - 退出只是把最上面那层滑走，不需要重新组合任何东西。
        //
        // 动画只用位移、不用淡入淡出，并且每层垫一个不透明底：整页覆盖最容易「撕裂/闪烁」的
        // 就是半透明叠加 + 透出下面正在动的会话页。
        val layers: List<Dest> = stack.drop(1) + leaving.filter { it !in stack }
        // 最上面那个**还没在退场**的层：它下面（不含它自己）的层都被完全盖住
        val frontIndex = layers.indexOfLast { it !in leaving }
        // 「可以塌陷下面那层」的许可。栈一变必须**在同一帧**就把许可收回：
        //   - 用 LaunchedEffect 置 false 会慢一帧，那一帧里下面的设置页已经被压成 0×0，
        //     用户看到的就是「打开终端时设置页闪一下，像刷新了一样」（第一版就是这么错的）；
        //   - 用 remember(layerStamp) 重新初始化则是在**组合期**发生的，同一帧就生效，
        //     而且不需要在组合期写 state（那会多一次重组）。
        // 动画放完（220+48ms）再给回许可。
        val layerStamp = stack.size * 31 + leaving.size
        val coverReady = remember(layerStamp) { mutableStateOf(false) }
        LaunchedEffect(layerStamp) {
            delay(SLIDE_MS.toLong() + 48L)
            coverReady.value = true
        }
        // 画布：把 stack 里的每一层铺出来（R21 搬成 AppLayerStack，见文件末尾）
        AppLayerStack(
            viewModel = viewModel,
            state = state,
            layers = layers,
            leaving = leaving,
            coverReady = coverReady.value,
            frontIndex = frontIndex,
            settingsTab = settingsTab,
            onSettingsTabChange = { settingsTab = it },
            previewTabs = previewTabs,
            previewIndex = previewIndex,
            onPreviewSelect = { previewIndex = it },
            onPreviewClose = { closePreviewTab(it) },
            onPreviewOpen = { openPreview(it) },
            workspaceRootPath = workspace.path ?: "/storage/emulated/0",
            onPop = { pop() },
        )
    }
    }

    // 首次启动的隐私说明：**强制同意一次**（本地一个布尔，见 ui/PrivacyPolicy.kt）。
    // 放在最外层：抽屉、画布、覆盖页都在它下面，同意之前不给任何操作入口。
    var privacyAccepted by remember { mutableStateOf(viewModel.settingsStore.privacyAccepted) }
    if (!privacyAccepted) {
        PrivacyConsentGate {
            viewModel.settingsStore.privacyAccepted = true
            privacyAccepted = true
        }
    }
}

/**
 * 覆盖页滑入/滑出的时长（ms）。
 * dsh 的 Web 客户端里这类面板是直接出现的（CSS 只有 .14s 的 dock/scrim 入场），
 * 移到触屏上再快一点也不会「看不清」，所以取 220ms 而不是之前的几百毫秒。
 */
/** 覆盖页（设置 / 终端 / 文件预览）滑入滑出的时长 */
private const val SLIDE_MS = 220

/**
 * 抽屉归位的时长（ms）。**刻意比覆盖页短**：
 *
 * 覆盖页是「点一下才出现」的整页，220ms 有足够时间让人看清它从哪滑进来；抽屉是**手指直接拉**的
 * 表面 —— 手指已经把它推到某个位置了，松手后只是「补完剩下那一段」，走 220ms 会读成慢吞吞。
 * 用户实测的收敛过程：220（与覆盖页共用）→「慢了点」→ 170 →「还是慢了」→ **130**。
 *
 * 它是这套动画**唯一该调的旋钮**（第 82–85 轮把弹簧、缓动曲线、手势初速度都试过并被否掉，
 * 详见 DrawerSettle.kt 与 settleDrawer 的注释）：觉得还慢就调小这个数，别的别动。
 */
private const val DRAWER_SLIDE_MS = 130
/**
 * 覆盖层里的一层：进入时从右边滑进来，退场时原路滑回右边；被上面那层盖住时只保留组合、
 * 不测量也不绘制（见 `placed`）。
 *
 * 用 [Animatable] 而不是 AnimatedVisibility：这一层被 push 出来时就已经在组合里了
 * （它的 visible 一直是 true），AnimatedVisibility 首次组合不会播进入动画。
 */
@Composable
private fun PanelLayer(
    onScreen: Boolean,
    /** false = 被上面那层完全盖住：不测量、不放置（于是不画、也不参与命中测试），组合留着 */
    placed: Boolean,
    content: @Composable () -> Unit,
) {
    // 1 = 完全在屏幕右边（屏幕外），0 = 就位
    val slide = remember { Animatable(1f) }
    LaunchedEffect(onScreen) {
        slide.animateTo(
            targetValue = if (onScreen) 0f else 1f,
            animationSpec = tween(SLIDE_MS, easing = FastOutSlowInEasing),
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            // 覆盖层必须**自己吃掉落在空白处的触摸**。
            //
            // 这一层和下面的主界面是同一个 Box 的兄弟节点，而 Compose 的命中测试只在
            // 「这一层里有一个落在该点的指针节点」时才把它算作命中：设置页标题栏左 / 右两端的
            // 空白处没有任何控件，那一下就会继续往下找到主界面顶栏的「会话统计」与
            // 「工作区文件」两个图标，把它们点开（第 77 轮用户实测：主界面明明已经被抽屉推到
            // 右边、还被这一层盖住，却仍然能被点到）。
            //
            // 铺一层什么都不画的 clickable 就够：子节点（设置页里的按钮 / 滚动 / 分页）在
            // 主阶段先拿到事件、消费掉自己那一下，落不到这里；没被消费的（也就是空白处那一下）
            // 在这里被吃掉。它**不消费移动事件**，所以「从设置页 / 终端页右滑把抽屉拉出来」
            // 照常有效（手势挂在根层）。
            .dshClickable(interactionSource = dshInteraction()) {}
            // 被盖住时把尺寸压成 0×0：子节点一次都不测量、不放置 —— 既不会被点到，
            // 也不参与绘制（叠层方案最大的风险就是「点上面那层，下面那层跟着响应」）。
            // clickable 在 fillMaxSize 之内、这个 layout 之外 ⇒ 压成 0×0 时它也一起吃不到触摸。
            .then(
                if (placed) Modifier
                else Modifier.layout { _, _ -> layout(0, 0) { } },
            )
            // 位移放在绘制层里：只重放置 + 重绘，不触发重组（与抽屉位移同一套做法）
            .graphicsLayer { translationX = slide.value * size.width },
    ) {
        content()
    }
}

/**
 * 抽屉拉到底时的最大模糊半径（dp）。
 *
 * dsh 的侧栏是「抽屉 + 半透明遮罩」，没有毛玻璃；这里保留一点点虚化做层次，
 * 但 4dp 在高密度屏上过重（整页像蒙了一层雾），降到 1.5dp 只当作轻微景深。
 */
private const val MAX_DRAWER_BLUR_DP = 1.5f

/** 抽屉宽度（dp）：位移与之等宽，拉开后两边都完整可见 */
private const val DRAWER_WIDTH_DP = 288f


// ------------------------------------------------------------------ 抽屉（dsh 侧栏结构）

/**
 * 画布：把 [layers] 里的每一层都铺出来（正序 → 最后一项在最上面），并保留**被盖住那一层的组合**。
 *
 * R21 从 `AppRoot` 主函数搬出来（原来 53 行）。三条口径随代码一起搬：
 *  - 覆盖页的四个分支都在这里（设置 / 工作区文件 / 终端；会话页 stack[0] 不出现在覆盖层里）；
 *  - [leaving] 里的层 `onScreen = false` —— 滑出动画放完才真正释放（PTY 会话、预览页资源都在那一帧收）；
 *  - [coverReady] 为假时只有最上面那一层被测量与放置，下面的层压成 0×0 但**组合一直在**：
 *    展开态 / 滚动位置 / pager 分栏都留着，回来时不需要重新组合。
 */
@Composable
private fun AppLayerStack(
    viewModel: ChatViewModel,
    state: ChatUiState,
    layers: List<Dest>,
    leaving: List<Dest>,
    coverReady: Boolean,
    frontIndex: Int,
    settingsTab: Int,
    onSettingsTabChange: (Int) -> Unit,
    previewTabs: List<String>,
    previewIndex: Int,
    onPreviewSelect: (Int) -> Unit,
    onPreviewClose: (Int) -> Unit,
    onPreviewOpen: (String) -> Unit,
    workspaceRootPath: String,
    onPop: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
    layers.forEachIndexed { index, dest ->
        key(dest) {
            PanelLayer(
                onScreen = dest !in leaving,
                // 被盖住的层不测量也不放置：不画、不吃触摸，但**组合一直在** ——
                // 展开态 / 滚动位置 / pager 分栏都留着，回来时不需要重新组合。
                placed = !coverReady || index >= frontIndex,
            ) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    when (dest) {
                        // 覆盖层里不会出现会话页（stack[0] 就是它）
                        is Dest.Conversation -> Unit

                        is Dest.Settings -> SettingsScreen(
                            settings = viewModel.settingsStore,
                            bindings = SettingsBindings(
                                themePreference = state.themePreference,
                                contentFontSize = state.contentFontSize,
                                transcriptView = state.transcriptView,
                                busyEnter = state.busyEnter,
                                permission = state.permission,
                                onTheme = viewModel::setThemePreference,
                                onFontSize = viewModel::setContentFontSize,
                                onTranscriptView = viewModel::setTranscriptView,
                                onBusyEnter = viewModel::setBusyEnter,
                                onPermission = viewModel::setPermission,
                            ),
                            onBack = onPop,
                            tab = settingsTab,
                            onTabChange = onSettingsTabChange,
                        )

                        // 工作区文件：顶部常驻「工作区文件」窗口，点文件在同一标签条里新开窗口
                        is Dest.WorkspaceFiles -> FileWorkspacePanel(
                            rootPath = workspaceRootPath,
                            tabs = previewTabs,
                            activeIndex = previewIndex,
                            onSelect = onPreviewSelect,
                            onClose = onPreviewClose,
                            onOpenFile = onPreviewOpen,
                        )

                        is Dest.Terminal -> TerminalPanel(
                            onBack = onPop,
                            launch = viewModel.shellLaunch,
                        )
                    }
                }
            }
        }
    }
    }
}
