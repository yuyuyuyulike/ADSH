package com.adsh.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.R
import com.adsh.app.core.data.ConversationEntity
import com.adsh.app.core.data.WorkspaceEntity
import com.adsh.app.ui.theme.LocalDshPalette

/**
 * 会话抽屉（`AppRoot` 的左侧栏）：工作区分组、会话行、搜索、重命名 / 删除、面板入口。
 *
 * R16 从 `AppRoot.kt`（1310 行）整块搬出来 —— 抽屉有自己的 20 多个参数、自己的展开态 /
 * 搜索态 / 菜单态，与 AppRoot 的其它部分（画布、浮层栈、返回处理、引导）没有任何共享状态。
 * 这一块**原文逐行搬运**，只把入口 `Drawer` 的可见性从 private 改成 internal（AppRoot 要调它）。
 * 尺寸与视觉口径见下面 [Drawer] 自己的注释（取自 dsh 的 SidebarRoot / WorkspaceBrowser / Rows）。
 */

/**
 * dsh 的侧栏（SidebarRoot + sidebar.workspaces）：
 * 品牌行（鲸鱼标志 + 官方词标）/ 新会话 / 「工作区」分节头（+ 添加工作区）/ 工作区树 / 底部设置。
 * 尺寸逐项取自 SidebarRoot.module.css、WorkspaceBrowser.module.css、Rows.module.css。
 */
@Composable
internal fun Drawer(
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
    /** 抽屉是不是拉开着（收起时搜索整块复位，见下面的 LaunchedEffect） */
    open: Boolean = false,
    /** 打开终端会话（新会话下面那一行；dsh 的侧栏面板入口） */
    onOpenTerminal: () -> Unit = {},
    /** bootstrap 安装失败的原因：非空时这一行的「终端会话」直接变成这条报错 */
    bootstrapError: String? = null,
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
    /**
     * 收起搜索 —— dsh 的 WorkspaceBrowser 就这一条规矩：搜索根之外的**一次按下**、或者选了一条
     * 结果，都收起（setSearchExpanded(false) + 清查询）。所有收起路径（点别处 / 点清除 / 选中结果 /
     * 抽屉收起）都走它，免得四个地方各写一遍。
     */
    fun closeSearch() {
        searchOpen = false
        query = ""
        onSearch("")
    }
    // 抽屉收起 = 搜索整块复位（dsh 那边搜索框随侧栏卸载而消失）。必须连 ViewModel 的查询一起清：
    // 会话树那一段是按 search.query 换成搜索结果的，只清本地状态会留下「搜索框没了、结果还在」。
    LaunchedEffect(open) { if (!open && (searchOpen || query.isNotEmpty())) closeSearch() }
    // dsh 的 sessionVisible：空白会话只在它是当前会话时出现在列表里
    val ungrouped = conversations.filter { it.workspaceId == null }
        .filter { it.id !in blankIds || it.id == currentId }
        // 用户口径（与 dsh 一致）：「新会话」永远排在这一组最上面
        .sortedByDescending { it.id == currentId && it.id in blankIds }

    /**
     * 抽屉里的一行会话。**两个分支（工作区 / 未分组）共用** ——
     * 第 2 阶段熵减前它们各抄了 10 行完全一样的参数，任何一处漏改就是「某个分组里的会话行行为不一样」，
     * 而这种差异在真机上极难认出来。菜单状态（[menuSession]）与重命名/删除目标都由外层持有，
     * 所以这里只收一个 [ConversationEntity]。
     */
    @Composable
    fun sessionRow(session: ConversationEntity) {
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

    // 菜单打开时返回键先关菜单（dsh 的 Menu 也是 Esc 关闭）
    BackHandler(enabled = menuSession != null) { menuSession = null }

    Box(modifier.background(palette.sidebar)) {
    Column(
        Modifier
            .fillMaxSize()
            // 抽屉里点「别处」→ 收起搜索（dsh 的 WorkspaceBrowser 在 document 上挂的就是这个意思的
            // 监听）。**挂在内容这一列上**而不是铺一层背景：铺的那层会被上面这个可滚动列整个盖住、
            // 一次都接不到。挂在祖先上正好等价于 dsh 的判据 —— 行 / 搜索框 / 按钮自己先消费掉落在
            // 它们身上的那一下，只有没人接的空白才落到这里。
            .dshClickable(interactionSource = dshInteraction(), enabled = searchOpen, onClick = { closeSearch() })
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = DshSpacing.Xxxl),
    ) {
        // 品牌行（.logoRow：高 60、上下内边距 8、左 4、gap 8）
        // 抽屉顶部：鲸鱼图标 + 字标
        DrawerWordmark()

        // 新会话（.newSession：高 38、圆角 12、.5px border-l3、elevated 底、左右 16）
        // 「新会话」按钮
        NewConversationButton(onClick = onNewConversation)

        // dsh 的 panelList：**新会话那一行下面、工作区那一块上面**的面板入口
        // （dsh 的 SidebarRoot 里 panelList 正好夹在 newSession 与 sidebar.workspaces 之间）。
        // ADSH 里只有「终端会话」一个面板，样式按 .panelRow。
        // 装不上 bootstrap 时这一行**就是报错**（用户第 117 轮点名：报错从设置页搬到这里，
        // 装好了照旧显示「终端会话」）—— 装坏的人一定看得见，不用去翻设置页或 logcat。
        DrawerPanelRow(
            icon = DshIcons.Terminal,
            label = bootstrapError ?: "终端会话",
            error = bootstrapError != null,
            modifier = Modifier.padding(bottom = DshSpacing.Xl),
            onClick = onOpenTerminal,
        )

        // 分节头（.sectionHeader：高 36、「工作区」三级色 + 搜索 + 添加按钮）。
        // dsh 的搜索是**同一个控件**从图标那一格长到整行：.searchSlot 28px → .searchExpanded 100%、
        // .sectionLabel 与 .headerActions 各自把 max-width 收到 0、输入框 120ms 淡入（全部 180ms
        // ease-in-out）。这里用两个**权重桶**表达同一件事：左桶（标题 + 空格 + 两个动作按钮）
        // 随展开进度掉到 0，右桶长到占满；两桶各自的裁剪就是 dsh 的 overflow:hidden。
        // 「工作区」小节头（标题 / 两个图标按钮 / 展开的搜索框）见下面 WorkspaceSectionHeader
        WorkspaceSectionHeader(
            searchOpen = searchOpen,
            query = query,
            onQueryChange = { value ->
                query = value
                onSearch(value)
            },
            onSearchOpen = { searchOpen = true },
            onCloseSearch = { closeSearch() },
            onAddWorkspace = onAddWorkspace,
        )

        // 搜索态：会话树整块换成搜索结果（dsh 的 searchTree）
        if (search.query.isNotEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                DrawerSearchResults(
                    search = search,
                    onOpen = { id ->
                        closeSearch()
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
                    conversations.filter { it.workspaceId == workspace.id }
                        // 用户口径（与 dsh 一致）：「新会话」永远排在工作区标题下面第一行
                        .sortedByDescending { it.id == currentId && it.id in blankIds }
                        .forEach { session ->
                            // dsh 的 sessionVisible：空白会话只有「它就是当前会话」时才露出来
                            val blank = session.id in blankIds && session.id != currentId
                            if (blank) return@forEach
                            sessionRow(session)
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
                    ungrouped.forEach { session -> sessionRow(session) }
                }
            }
        }

        DrawerEntry(Icons.Outlined.Settings, "设置", onOpenSettings)
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
        DeleteWorkspaceDialog(
            workspace = workspace,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                onDeleteWorkspace(workspace.id)
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
            .dshClickable(interactionSource = interaction, onClick = onToggle)
            .padding(horizontal = DshSpacing.Xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
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
        Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xxxl), verticalAlignment = Alignment.CenterVertically) {
            if (onDelete != null) {
                DrawerIconButton(DshSidebarIcons.Trash, "删除工作区 " + label, palette.labelTertiary, onDelete)
            }
            if (onNew != null) {
                DrawerIconButton(DshSidebarIcons.NewChat, "在“" + label + "”中新建会话", palette.labelTertiary, onNew)
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
            .dshClickable(interactionSource = interaction, onClick = onOpen)
            .padding(horizontal = DshSpacing.Xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(start = DshSpacing.Page),
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
            .border(DshSpacing.Hairline, palette.borderL4, RoundedCornerShape(10.dp))
            // 落在搜索框这一块里的那一下不许冒到抽屉背景去（dsh 的「搜索根之内不收起」）：
            // 放大镜周边的留白也算搜索框自己的一部分。
            .dshClickable(interactionSource = dshInteraction()) {}
            .padding(start = DshSpacing.Lg, end = DshSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.Xxxl, vertical = DshSpacing.Xxl),
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
            .dshClickable(interactionSource = interaction, onClick = onClick)
            .padding(horizontal = DshSpacing.Xl, vertical = DshSpacing.Md),
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
                modifier = Modifier.padding(start = DshSpacing.Md),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = palette.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.padding(start = DshSpacing.Page),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
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
            .dshClickable(interactionSource = dshInteraction(), onClick = onClick),
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
                    .border(DshSpacing.Hairline, palette.borderL4, RoundedCornerShape(22.dp))
                    .padding(horizontal = DshSpacing.Section, vertical = 11.dp),
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

/**
 * dsh 的 .panelRow（SidebarRoot.module.css）：侧栏里**一个插件面板**的入口 ——
 * 与新会话那条同一个 12px 圆角、同一份 2px 内缩，用会话行的悬停面与一级色字：
 * gap 8、最小高 36、padding 7 8、图标 16、正文 14/22。
 *
 * 位置也照 dsh：SidebarRoot 的 `<nav class="panelList">` 正好夹在 newSession 与
 * sidebar.workspaces 之间（终端的 TerminalIcon 就是这样一个面板）。
 */
@Composable
private fun DrawerPanelRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    /** 报错态：正文用 error 色（bootstrap 安装失败时这一行显示的就是报错原文） */
    error: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.Xs)
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) palette.hover else Color.Transparent)
            .dshClickable(interactionSource = interaction, onClick = onClick)
            .padding(horizontal = DshSpacing.Xl, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.labelPrimary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = if (error) palette.errorLabel else palette.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DrawerEntry(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .dshClickable(interactionSource = dshInteraction(), onClick = onClick)
            .padding(horizontal = DshSpacing.Xxxl, vertical = DshSpacing.Xxxl),
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

/** 抽屉顶部：鲸鱼图标 + 「DeepSeek Harness」字标（尺寸取自 dsh 的 SidebarRoot） */
@Composable
private fun DrawerWordmark() {
    val palette = LocalDshPalette.current
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(start = DshSpacing.Md, top = DshSpacing.Xl, bottom = DshSpacing.Xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
    ) {
        DshWhale(width = 24.dp, tint = palette.labelPrimary)
        Image(
            painter = painterResource(R.drawable.ic_dsh_wordmark),
            contentDescription = "DeepSeek Harness",
            colorFilter = ColorFilter.tint(palette.labelPrimary),
            modifier = Modifier.height(24.dp).width(156.dp),
        )
    }
}

/** 「新会话」按钮（dsh 侧栏的 NewChatButton：38dp 高、12dp 圆角、居中图标 + 文案） */
@Composable
private fun NewConversationButton(onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.Xs)
            .padding(bottom = DshSpacing.Xl)
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.buttonElevated)
            .border(DshSpacing.Hairline, palette.borderL3, RoundedCornerShape(12.dp))
            .dshClickable(interactionSource = dshInteraction(), onClick = onClick),
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
}

/**
 * 「工作区」小节头：左边是标题 + 两个图标按钮（搜索 / 添加工作区），右边是展开出来的搜索框。
 *
 * 两半用**互补权重**（`1 - expand` 与 `expand`）做 180ms 的横向让位，所以动画值在这里算：
 * 它同时喂给标题的 alpha/translationX、图标按钮组与搜索框容器。
 */
@Composable
private fun WorkspaceSectionHeader(
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchOpen: () -> Unit,
    onCloseSearch: () -> Unit,
    onAddWorkspace: () -> Unit,
) {
    val palette = LocalDshPalette.current
    val expand by animateFloatAsState(
        targetValue = if (searchOpen) 1f else 0f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "searchExpand",
    )
    Row(
        Modifier.fillMaxWidth().height(36.dp).padding(start = DshSpacing.Md).padding(bottom = DshSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight((1f - expand).coerceAtLeast(0.0001f)).clipToBounds(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "工作区",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = palette.labelTertiary,
                maxLines = 1,
                softWrap = false,
                // dsh 的 .sectionLabelHidden：左移 4px 同时淡出
                modifier = Modifier.graphicsLayer {
                    alpha = 1f - expand
                    translationX = -4.dp.toPx() * expand
                },
            )
            Spacer(Modifier.weight(1f))
            // 两个动作按钮：展开时整块收掉（dsh 的 .headerActionsHidden，右移 4px）
            if (expand < 1f) {
                Row(
                    Modifier.graphicsLayer {
                        alpha = 1f - expand
                        translationX = 4.dp.toPx() * expand
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DrawerIconButton(DshSidebarIcons.Search, "搜索会话", palette.labelSecondary) {
                        onSearchOpen()
                    }
                    DrawerIconButton(DshSidebarIcons.ProjectAdd, "添加工作区", palette.labelSecondary, onAddWorkspace)
                }
            }
        }
        Box(Modifier.weight(expand.coerceAtLeast(0.0001f)).clipToBounds()) {
            // 收起动画走完之前不拆：dsh 里这一格也是「缩回去」而不是瞬间消失
            if (expand > 0f) {
                DrawerSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onClose = onCloseSearch,
                )
            }
        }
    }
}

/** 删除工作区的确认框（文案：文件夹与会话保留，其会话落到「未分组」） */
@Composable
private fun DeleteWorkspaceDialog(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val palette = LocalDshPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
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
            TextButton(onClick = onConfirm) { Text("删除工作区", color = palette.errorLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = palette.labelSecondary)
            }
        },
    )
}
