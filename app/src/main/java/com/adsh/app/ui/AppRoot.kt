package com.adsh.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsh.app.R
import com.adsh.app.core.data.ConversationEntity
import com.adsh.app.core.data.WorkspaceEntity
import com.adsh.app.ui.panels.TerminalPanel
import com.adsh.app.ui.theme.LocalDshPalette
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
     *    以前进度是在组合期读的（算 stepped），手指一动就重组一遍整页，帧率自然不如
     *    纯位移的整页覆盖动画；
     *  - 也不再每个拖动事件起一个协程去 snapTo（一次触摸上百个事件 = 上百个协程），
     *    拖动是同步写状态，只有「松手后的归位」才交给 [animate] 跑动画。
     */
    var progress by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val hazeState = dev.chrisbanes.haze.rememberHazeState()
    val density = LocalDensity.current
    // 位移与抽屉等宽：拉开后抽屉完整可见，主界面不再压住它（之前 244 vs 296 会盖掉 52dp）
    val drawerPx = with(density) { DRAWER_WIDTH_DP.dp.toPx() }
    val shiftPx = drawerPx
    // 拖动 1:1 跟手（之前是 drawerPx * 0.55f：手指移动 1px 内容走 1.8px，
    // 轻轻一动就滑一大截，画面「跳」而不是「被手指推着走」）
    val openSpanPx = drawerPx

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
            delay(PANEL_SLIDE_MS + 32L)
            leaving.remove(gone)
        }
    }

    /** 松手后的归位动画：把进度推到 0 或 1（新的一次拖动会先把它取消） */
    /**
     * 松手后的归位。
     *
     * 用 spring 而不是 190ms 的 tween，并把手指离开时的速度当成初速度接上去 ——
     * 位移与速度都连续，手感是「甩出去自己停下来」而不是「走完一段固定动画」。
     * 这就是之前「流畅度够了但不够丝滑」的来源：定长缓动在快速甩动时看着像被硬拽了一下。
     */
    fun settleDrawer(target: Float, velocity: Float = 0f) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                initialValue = progress,
                targetValue = target,
                initialVelocity = velocity,
                animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
            ) { value, _ ->
                // 必须夹在 0..1：带初速度的弹簧会冲过目标（左滑时冲到 -0.x），
                // 负进度会让主内容层反向位移（看着像「又开了一个主界面」），
                // 而且 RoundedCornerShape(负数) 会直接抛异常 → 闪退。
                progress = value.coerceIn(0f, 1f)
            }
        }
    }

    fun closeDrawer() = settleDrawer(0f)

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
        state = rememberDraggableState { delta ->
            settleJob?.cancel()
            progress = (progress + delta / openSpanPx).coerceIn(0f, 1f)
        },
        onDragStopped = { velocity ->
            // 速度够就按方向，速度不够就按位置 —— 之前只要 |velocity| < 250 就恒为「开」，
            // 于是左滑也关不上，只能用返回手势
            val open = if (kotlin.math.abs(velocity) > 250f) velocity > 0f else progress > 0.5f
            // 关抽屉（进度 0）时不要把手势的负速度传进去：那是「往左甩」的速度，
            // 弹簧会先冲到负进度再弹回来（见 settleDrawer 的注释）
            settleDrawer(if (open) 1f else 0f, if (open) velocity / openSpanPx else 0f)
        },
    )

    // 这两个只在「跨过台阶」时变：返回键的启用条件、模糊层的存在与否都不该跟着每一帧重组
    val drawerHalfOpen by remember { derivedStateOf { progress > 0.5f } }
    val blurVisible by remember { derivedStateOf { progress > 0f } }

    BackHandler(enabled = stack.size > 1 || drawerHalfOpen) {
        // 先退覆盖页、再关抽屉：从设置返回时会话页还在，抽屉也还开着
        if (stack.size > 1) pop() else closeDrawer()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 抽屉手势挂在根层：抽屉拉出后左滑落在抽屉那一侧也能关回去（之前手势只挂在会话内容区，
            // 抽屉占了大半屏，左滑等于滑在抽屉上，完全没反应）；顺带设置/终端/预览页也能右滑拉抽屉
            .then(drawerDrag),
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
            onNewConversation = { closeDrawer(); viewModel.newConversation() },
            onNewConversationIn = { id -> closeDrawer(); viewModel.newConversationIn(id) },
            onSelectConversation = { id -> closeDrawer(); viewModel.switchConversation(id) },
            onRenameConversation = viewModel::renameConversation,
            onForkConversation = { id -> closeDrawer(); viewModel.forkConversation(id) },
            onDeleteConversation = { id -> viewModel.deleteConversation(id) },
            onAddWorkspace = { closeDrawer(); addWorkspace() },
            onDeleteWorkspace = viewModel::deleteWorkspace,
            // 点「设置」不收起抽屉：设置页会从右侧盖上来，退出后抽屉还在原处
            onOpenSettings = { push(Dest.Settings) },
            search = searchState,
            onSearch = viewModel::searchSessions,
        )

        // 主内容层：位移 + 圆角 + 投影 + 模糊都在同一层里，值按 1/8 步进，避免每帧重建
        // 圆角 / 投影 / 模糊按 1/8 台阶走（只在台阶上重建 RenderEffect，不在每帧重建）
        val stepped by remember { derivedStateOf { (progress * 8f).roundToInt() / 8f } }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 这一层里的读取都是延迟的：拖动时只更新图层属性，不触发重组
                    translationX = progress * shiftPx
                    shape = RoundedCornerShape((20f * stepped).coerceIn(0f, 20f).dp)
                    clip = stepped > 0f
                    shadowElevation = 12.dp.toPx() * stepped
                    ambientShadowColor = Color.Black
                    spotShadowColor = Color.Black
                }
        ) {
            Box(Modifier.fillMaxSize().hazeSource(state = hazeState)) {
            ChatScreen(
                        state = state,
                        onSend = viewModel::send,
                        onCancel = viewModel::cancel,
                        onNewConversation = viewModel::newConversation,
                        onClearError = viewModel::clearError,
                        onOpenTerminal = { push(Dest.Terminal) },
                        onOpenSettings = { push(Dest.Settings) },
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
                                blurRadius((MAX_DRAWER_BLUR_DP * stepped).dp)
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
            delay(PANEL_SLIDE_MS.toLong() + 48L)
            coverReady.value = true
        }
        Box(Modifier.fillMaxSize()) {
            layers.forEachIndexed { index, dest ->
                key(dest) {
                    PanelLayer(
                        onScreen = dest !in leaving,
                        // 被盖住的层不测量也不放置：不画、不吃触摸，但**组合一直在** ——
                        // 展开态 / 滚动位置 / pager 分栏都留着，回来时不需要重新组合。
                        placed = !coverReady.value || index >= frontIndex,
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
                                    bootstrapReady = workspace.bootstrapReady,
                                    bootstrapDir = workspace.prefix,
                                    bootstrapError = workspace.bootstrapError,
                                    onOpenTerminal = { push(Dest.Terminal) },
                                    onBack = { pop() },
                                    tab = settingsTab,
                                    onTabChange = { settingsTab = it },
                                )

                                // 工作区文件：顶部常驻「工作区文件」窗口，点文件在同一标签条里新开窗口
                                is Dest.WorkspaceFiles -> FileWorkspacePanel(
                                    rootPath = workspace.path ?: "/storage/emulated/0",
                                    tabs = previewTabs,
                                    activeIndex = previewIndex,
                                    onSelect = { previewIndex = it },
                                    onClose = { closePreviewTab(it) },
                                    onOpenFile = { openPreview(it) },
                                )

                                is Dest.Terminal -> TerminalPanel(
                                    onBack = { pop() },
                                    launch = viewModel.shellLaunch,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 覆盖页滑入/滑出的时长（ms）。
 * dsh 的 Web 客户端里这类面板是直接出现的（CSS 只有 .14s 的 dock/scrim 入场），
 * 移到触屏上再快一点也不会「看不清」，所以取 220ms 而不是之前的几百毫秒。
 */
private const val PANEL_SLIDE_MS = 220
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
            animationSpec = tween(PANEL_SLIDE_MS, easing = FastOutSlowInEasing),
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            // 被盖住时把尺寸压成 0×0：子节点一次都不测量、不放置 —— 既不会被点到，
            // 也不参与绘制（叠层方案最大的风险就是「点上面那层，下面那层跟着响应」）。
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
 * dsh 的侧栏（SidebarRoot + sidebar.workspaces）：
 * 品牌行（鲸鱼标志 + 官方词标）/ 新会话 / 「工作区」分节头（+ 添加工作区）/ 工作区树 / 底部设置。
 * 尺寸逐项取自 SidebarRoot.module.css、WorkspaceBrowser.module.css、Rows.module.css。
 */
@Composable
private fun Drawer(
    modifier: Modifier,
    workspaces: List<WorkspaceEntity>,
    conversations: List<ConversationEntity>,
    /** 空白会话（一条消息都没有）的 id：只有当前那一条会露出来（dsh 的 sessionVisible） */
    blankIds: Set<Long>,
    currentId: Long?,
    currentWorkspaceId: Long?,
    onNewConversation: () -> Unit,
    onNewConversationIn: (Long?) -> Unit,
    onSelectConversation: (Long) -> Unit,
    onRenameConversation: (Long, String) -> Unit,
    onForkConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onAddWorkspace: () -> Unit,
    onDeleteWorkspace: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    /** 会话搜索（dsh 侧栏的搜索会话）：查询与结果都在 ViewModel 里 */
    search: ChatViewModel.SessionSearchState = ChatViewModel.SessionSearchState(),
    onSearch: (String) -> Unit = {},
) {
    val palette = LocalDshPalette.current
    // 工作区分组默认展开「当前会话所在的那一个」（dsh 的 groupExpansion）
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    var menuSession by remember { mutableStateOf<Long?>(null) }
    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    // 会话搜索：展开状态与查询词都留在抽屉里（结果在 ViewModel）
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // dsh 的 sessionVisible：空白会话只在它是当前会话时出现在列表里
    val ungrouped = conversations.filter { it.workspaceId == null }
        .filter { it.id !in blankIds || it.id == currentId }

    // 菜单打开时返回键先关菜单（dsh 的 Menu 也是 Esc 关闭）
    BackHandler(enabled = menuSession != null) { menuSession = null }

    Box(modifier.background(palette.sidebar)) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp),
    ) {
        // 品牌行（.logoRow：高 60、上下内边距 8、左 4、gap 8）
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DshWhale(width = 24.dp, tint = palette.labelPrimary)
            Image(
                painter = painterResource(R.drawable.ic_dsh_wordmark),
                contentDescription = "DeepSeek Harness",
                colorFilter = ColorFilter.tint(palette.labelPrimary),
                modifier = Modifier.height(24.dp).width(156.dp),
            )
        }

        // 新会话（.newSession：高 38、圆角 12、.5px border-l3、elevated 底、左右 16）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
                .padding(bottom = 8.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.buttonElevated)
                .border(0.5.dp, palette.borderL3, RoundedCornerShape(12.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onNewConversation,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = DshSidebarIcons.NewChat,
                contentDescription = null,
                tint = palette.labelPrimary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("新会话", fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = palette.labelPrimary)
        }

        // 分节头（.sectionHeader：高 36、「工作区」三级色 + 搜索 + 添加按钮）
        // dsh 的搜索就在这一行的右侧：收起时是一枚 36dp 的圆形图标按钮，展开后整行变成输入框
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(start = 4.dp).padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!searchOpen) {
                Text("工作区", fontSize = 14.sp, lineHeight = 20.sp, color = palette.labelTertiary)
                Spacer(Modifier.weight(1f))
                DrawerIconButton(DshSidebarIcons.Search, "搜索会话", palette.labelSecondary) {
                    searchOpen = true
                }
                DrawerIconButton(DshSidebarIcons.ProjectAdd, "添加工作区", palette.labelSecondary, onAddWorkspace)
            } else {
                DrawerSearchField(
                    query = query,
                    onQueryChange = { value ->
                        query = value
                        onSearch(value)
                    },
                    onClose = {
                        searchOpen = false
                        query = ""
                        onSearch("")
                    },
                )
            }
        }

        // 搜索态：会话树整块换成搜索结果（dsh 的 searchTree）
        if (search.query.isNotEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                DrawerSearchResults(
                    search = search,
                    onOpen = { id ->
                        query = ""
                        searchOpen = false
                        onSearch("")
                        onSelectConversation(id)
                    },
                )
            }
            DrawerEntry(Icons.Outlined.Settings, "设置", onOpenSettings)
            return@Column
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            workspaces.forEach { workspace ->
                val open = expanded[workspace.id] ?: (workspace.id == currentWorkspaceId)
                WorkspaceGroupRow(
                    label = workspace.name,
                    expanded = open,
                    active = open && workspace.id == currentWorkspaceId,
                    onToggle = { expanded[workspace.id] = !open },
                    onNew = { onNewConversationIn(workspace.id) },
                    onDelete = { deleteTarget = workspace },
                )
                if (open) {
                    conversations.filter { it.workspaceId == workspace.id }.forEach { session ->
                        // dsh 的 sessionVisible：空白会话只有「它就是当前会话」时才露出来
                        val blank = session.id in blankIds && session.id != currentId
                        if (blank) return@forEach
                        SessionRow(
                            title = session.title,
                            time = relativeTime(session.updatedAt),
                            selected = session.id == currentId,
                            blank = session.id in blankIds,
                            menuOpen = menuSession == session.id,
                            onOpen = { onSelectConversation(session.id) },
                            onMenuOpenChange = { open2 -> menuSession = if (open2) session.id else null },
                            onRename = { menuSession = null; renameTarget = session },
                            onFork = { menuSession = null; onForkConversation(session.id) },
                            onDelete = { menuSession = null; onDeleteConversation(session.id) },
                        )
                    }
                }
            }

            // 未分组（dsh 的 group.ungrouped）：没有工作区归属的会话。
            // dsh 里这一组没有动作（不能在这里新建/删除），所以 onNew = null。
            if (ungrouped.isNotEmpty()) {
                val open = expanded[-1L] ?: true
                WorkspaceGroupRow(
                    label = "未分组",
                    expanded = open,
                    active = false,
                    onToggle = { expanded[-1L] = !open },
                    onNew = null,
                    onDelete = null,
                )
                if (open) {
                    ungrouped.forEach { session ->
                        SessionRow(
                            title = session.title,
                            time = relativeTime(session.updatedAt),
                            selected = session.id == currentId,
                            blank = session.id in blankIds,
                            menuOpen = menuSession == session.id,
                            onOpen = { onSelectConversation(session.id) },
                            onMenuOpenChange = { open2 -> menuSession = if (open2) session.id else null },
                            onRename = { menuSession = null; renameTarget = session },
                            onFork = { menuSession = null; onForkConversation(session.id) },
                            onDelete = { menuSession = null; onDeleteConversation(session.id) },
                        )
                    }
                }
            }
        }

        DrawerEntry(Icons.Outlined.Settings, "设置", onOpenSettings)
    }

        // 菜单打开时，点抽屉任意位置先关掉它（弹层不抢焦点，所以自己接）
        if (menuSession != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { detectTapGestures { menuSession = null } },
            )
        }
    }

    // dsh 的「重命名会话」弹窗
    renameTarget?.let { target ->
        RenameDialog(
            title = "重命名会话",
            initial = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { value ->
                renameTarget = null
                onRenameConversation(target.id, value)
            },
        )
    }

    // dsh 的「删除工作区」确认（delete.desc：只解绑，文件夹与会话记录都保留）
    deleteTarget?.let { workspace ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = palette.menu,
            shape = RoundedCornerShape(12.dp),
            title = { Text("删除工作区", fontSize = 17.sp) },
            text = {
                Text(
                    "将把“" + workspace.name + "”从工作区列表中移除。文件夹与会话记录会保留，其会话将显示在“未分组”下。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    onDeleteWorkspace(workspace.id)
                }) { Text("删除工作区", color = palette.errorLabel) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消", color = palette.labelSecondary)
                }
            },
        )
    }
}

/** dsh 的工作区行（Rows 的 .projectRow：高 34、圆角 8、左右 8、gap 6；右侧 垃圾桶 + 加号） */
@Composable
private fun WorkspaceGroupRow(
    label: String,
    expanded: Boolean,
    active: Boolean,
    onToggle: () -> Unit,
    /** null = 这一组没有「新建会话」动作（dsh 的未分组桶就没有） */
    onNew: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) palette.hover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 只留文件夹图标本身表达展开态（点一下就在开/合之间切换），左侧不再放三角
        Icon(
            imageVector = if (expanded) DshIcons.FolderOpen else DshIcons.FolderClose,
            contentDescription = if (expanded) "收起" else "展开",
            tint = if (active) palette.business else palette.labelTertiary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = palette.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onDelete != null) {
                DrawerIconButton(DshSidebarIcons.Trash, "删除工作区 " + label, palette.labelTertiary, onDelete)
            }
            if (onNew != null) {
                DrawerIconButton(DshIcons.Plus, "在“" + label + "”中新建会话", palette.labelTertiary, onNew)
            }
        }
    }
}

/** dsh 的会话行（.sessionRow：高 32、圆角 8；右侧时间 + 三点菜单） */
@Composable
private fun SessionRow(
    title: String,
    time: String,
    selected: Boolean,
    /** 空白会话（dsh 的 row.blank）：不显示时间与操作菜单，标题就是「新会话」 */
    blank: Boolean = false,
    menuOpen: Boolean,
    onOpen: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed || selected || menuOpen) palette.hover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(start = 20.dp),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = palette.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!menuOpen && !blank) {
            Text(time, fontSize = 12.sp, lineHeight = 20.sp, color = palette.labelTertiary)
        }
        if (blank) return@Row
        Box {
            DrawerIconButton(DshSidebarIcons.Ellipsis, "会话“" + title + "”的操作", palette.labelTertiary) {
                onMenuOpenChange(!menuOpen)
            }
            if (menuOpen) {
                DshPopup(onDismiss = { onMenuOpenChange(false) }, alignStart = true, below = true) {
                    DshMenuCard(Modifier.width(156.dp)) {
                        DshMenuRow(label = "重命名", icon = DshSidebarIcons.Edit, onClick = onRename)
                        DshMenuRow(label = "分叉会话", icon = DshSidebarIcons.Branch, onClick = onFork)
                        DshMenuRow(label = "删除会话", icon = DshSidebarIcons.Trash, onClick = onDelete)
                    }
                }
            }
        }
    }
}

/** 抽屉里的小图标按钮（Rows 的 .iconButton：16×16、三级色、悬停转一级色） */
/**
 * dsh 的搜索输入框（WorkspaceBrowser 的 .searchExpanded）：
 * 高 30、圆角 10、.5px border-l4、左侧 16 的放大镜、右侧「清除搜索」，
 * 文字 13/18 label-primary，占位「搜索会话…」用 caption 色。
 */
@Composable
private fun DrawerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(0.5.dp, palette.borderL4, RoundedCornerShape(10.dp))
            .padding(start = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            DshSidebarIcons.Search,
            contentDescription = null,
            tint = palette.labelCaption,
            modifier = Modifier.size(16.dp),
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("搜索会话…", fontSize = 13.sp, lineHeight = 18.sp, color = palette.labelCaption)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = palette.labelPrimary,
                ),
                cursorBrush = SolidColor(palette.accent),
            )
        }
        DrawerIconButton(
            icon = DshSettingIcons.Close,
            contentDescription = if (query.isEmpty()) "退出搜索" else "清除搜索",
            tint = palette.labelTertiary,
        ) {
            if (query.isEmpty()) onClose() else onQueryChange("")
        }
    }
}

/**
 * 搜索结果列表（dsh 的 .searchResultRow）：
 * 第一行 = 会话图标 + 标题（14/20）；第二行左缩进 20 = 工作区名（三级色）+ 命中片段（12/17）。
 * 状态文案逐字取自 dsh 的中文字典：正在搜索会话历史… / 无匹配会话 / 仅显示前 {n} 条结果，请缩小搜索范围。
 */
@Composable
private fun DrawerSearchResults(
    search: ChatViewModel.SessionSearchState,
    onOpen: (Long) -> Unit,
) {
    val palette = LocalDshPalette.current
    when {
        search.pending && search.hits.isEmpty() -> DrawerSearchHint("正在搜索会话历史…")
        search.hits.isEmpty() -> DrawerSearchHint("无匹配会话")
        else -> {
            search.hits.forEach { hit ->
                DrawerSearchResultRow(hit) { onOpen(hit.conversationId) }
            }
            if (search.hasMore) {
                DrawerSearchHint("仅显示前 " + ChatViewModel.SEARCH_LIMIT + " 条结果，请缩小搜索范围。")
            }
        }
    }
}

@Composable
private fun DrawerSearchHint(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = LocalDshPalette.current.labelSecondary,
    )
}

@Composable
private fun DrawerSearchResultRow(
    hit: com.adsh.app.core.data.ConversationRepository.SessionSearchHit,
    onClick: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) palette.hover else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                DshSidebarIcons.NewChat,
                contentDescription = null,
                tint = palette.labelPrimary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = hit.title,
                modifier = Modifier.padding(start = 4.dp),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = palette.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.padding(start = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            hit.workspace?.let { workspace ->
                Text(
                    text = workspace,
                    modifier = Modifier.widthIn(max = 110.dp),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = palette.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = hit.snippet,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = palette.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DrawerIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/** dsh 的重命名弹窗（Modal + .renameInput：高 44、圆角 22、.5px border-l4） */
@Composable
private fun RenameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val palette = LocalDshPalette.current
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.menu,
        shape = RoundedCornerShape(12.dp),
        title = { Text(title, fontSize = 17.sp) },
        text = {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = palette.labelPrimary,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .border(0.5.dp, palette.borderL4, RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value) }) {
                Text("重命名", color = if (value.isNotBlank()) palette.accent else palette.labelCaption)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = palette.labelSecondary) }
        },
    )
}

/** dsh 侧栏的会话时间（time.now / minutes / hours / days / months / years） */
private fun relativeTime(at: Long): String {
    val delta = System.currentTimeMillis() - at
    return when {
        delta < 60_000 -> "刚刚"
        delta < 3_600_000 -> (delta / 60_000).toString() + "分钟"
        delta < 86_400_000 -> (delta / 3_600_000).toString() + "小时"
        delta < 2_592_000_000L -> (delta / 86_400_000).toString() + "天"
        delta < 31_104_000_000L -> (delta / 2_592_000_000L).toString() + "个月"
        else -> (delta / 31_104_000_000L).toString() + "年"
    }
}

@Composable
private fun DrawerEntry(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.labelSecondary,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(text = label, fontSize = 14.sp, color = palette.labelPrimary)
    }
}
