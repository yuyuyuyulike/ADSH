package com.adsh.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adsh.app.core.agent.AgentLoop
import com.adsh.app.core.agent.compaction.CompactionService
import com.adsh.app.core.agent.compaction.CompactionTrigger
import com.adsh.app.seam.model.describeAttachments
import com.adsh.app.core.data.generateSessionTitleIfNeeded
import com.adsh.app.seam.model.encodeAttachments
import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.data.SettingsStore
import com.adsh.app.core.llm.ChatEvent
import com.adsh.app.core.llm.LlmClient
import com.adsh.app.core.llm.SessionStats
import com.adsh.app.core.workspace.WorkspaceManager
import com.adsh.app.runtime.termux.TermuxRuntime
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import com.adsh.app.seam.exec.filePolicyOf


/**
 * 会话宿主：全部依赖**构造注入**（装配在 `AppContainer`，见 `docs/ARCHITECTURE.md` §五）——
 * 这里不许再 `new` 数据库 / LLM / 运行环境，否则「谁依赖谁」就散在类体里了。
 */
class ChatViewModel(
    app: Application,
    private val repository: ConversationRepository,
    private val settings: SettingsStore,
    private val runtime: TermuxRuntime,
    private val workspaces: WorkspaceManager,
    private val agent: AgentLoop,
    /** 标题与小调用用的一条 LLM 通道（不经过 AgentLoop 的工具循环） */
    private val compactLlm: LlmClient,
    /** 压缩服务：/compact 只调它（区间选择 / 摘要请求 / 历史替换全在 :core:agent） */
    private val compactor: CompactionService,
    /** 工具上下文工厂：组合根实现（:app 的 ToolingHost），本类只给每轮才变的东西 */
    private val tooling: ToolContextFactory,
    /**
     * 把这一轮的岛状态推给前台服务（:app 的 IslandController.sync）：本模块只算出那一帧，
     * 不认识服务实现。
     */
    private val onIslandFrame: (IslandWork?) -> Unit,
    /** 岛上的「停止」请求（:app 的 IslandBus.stopRequests）= 与输入框停止键同一件事 */
    private val islandStopRequests: Flow<Unit>,
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** 会话列表（dsh 侧栏按工作区分组列出会话） */
    private val _conversations = MutableStateFlow<List<com.adsh.app.core.data.ConversationEntity>>(emptyList())
    val conversations: StateFlow<List<com.adsh.app.core.data.ConversationEntity>> = _conversations.asStateFlow()

    /** 工作区列表（dsh 侧栏的「工作区」分组：工作区 → 会话） */
    private val _workspaceList = MutableStateFlow<List<com.adsh.app.core.data.WorkspaceEntity>>(emptyList())
    val workspaceList: StateFlow<List<com.adsh.app.core.data.WorkspaceEntity>> = _workspaceList.asStateFlow()

    /**
     * 抽屉里的会话搜索（dsh 侧栏的搜索会话）。
     * pending = 正在搜索会话历史…（dsh 的 search.pending）；hasMore = 仅显示前 N 条结果。
     */
    data class SessionSearchState(
        val query: String = "",
        val pending: Boolean = false,
        val hits: List<com.adsh.app.core.data.ConversationRepository.SessionSearchHit> = emptyList(),
        val hasMore: Boolean = false,
    )

    private val _search = MutableStateFlow(SessionSearchState())
    val search: StateFlow<SessionSearchState> = _search.asStateFlow()

    private var searchJob: Job? = null

    /**
     * 侧栏动作失败的通知文案（界面层负责弹一条提示）。
     *
     * 置顶 / 取消置顶失败时侧栏没有任何别的动静，静默失败会读成「按钮坏了」—— dsh 同样用
     * toast 说明（见 ui-workspace 的 toast.pinFailed / toast.unpinFailed）。
     */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /**
     * 置顶 / 取消置顶（dsh 的 pinSession / unpinSession）：只改 pinned 列，**不刷新 updatedAt**
     * —— 置顶不改变会话的「最近更新」时间。侧栏顺序由
     * [ConversationRepository.sidebarConversations] 的置顶分区给出。
     */
    fun setPinned(id: Long, pinned: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setPinned(id, pinned) }
                .onSuccess { refreshConversations() }
                .onFailure {
                    _notices.emit(if (pinned) "Pin failed. Try again later." else "Unpin failed. Try again later.")
                }
        }
    }

    /** 会话搜索：每次输入都重跑（在 IO 线程上查库），空查询直接清空结果 */
    fun searchSessions(query: String) {
        searchJob?.cancel()
        val needle = query.trim()
        if (needle.isEmpty()) {
            _search.value = SessionSearchState()
            return
        }
        _search.value = SessionSearchState(query = needle, pending = true)
        searchJob = viewModelScope.launch {
            val outcome = withContext(kotlinx.coroutines.Dispatchers.IO) {
                repository.searchSessions(needle, SEARCH_LIMIT)
            }
            // 期间又改了查询：这次结果作废（只认最后一次）
            if (_search.value.query != needle) return@launch
            _search.value = SessionSearchState(
                query = needle,
                pending = false,
                hits = outcome.hits,
                hasMore = outcome.hasMore,
            )
        }
    }

    private suspend fun refreshConversations() {
        // 抽屉的行序：置顶会话在所属分组内排最前（dsh 的 pinned 分区），组内仍按 updatedAt 倒序。
        // 别处的「最近」语义用 repository.conversations()（updatedAt 倒序），两者别混。
        _conversations.value = repository.sidebarConversations()
        // dsh 的 sessionVisible：一条消息都没有的空白会话只在「它就是当前会话」时出现在侧栏里，
        // 这样「新会话」不会在抽屉里堆成一串行，也不会凭空多出一个分组
        val blanks = repository.blankIds()
        if (blanks != _state.value.blankConversationIds) {
            _state.update { it.copy(blankConversationIds = blanks) }
        }
    }

    private suspend fun refreshWorkspaceList() {
        _workspaceList.value = repository.workspaces()
    }

    /**
     * 「退出时所在的工作区」实时落盘：状态里每次出现非空的工作区就记下来，下次启动用它开新会话
     * （[startupWorkspaceId]）。落盘只有这一处 —— 会话 / 工作区 / 新会话切换都只改状态。
     */
    private fun trackLastWorkspace() {
        viewModelScope.launch {
            _state.map { it.workspaceId }.distinctUntilChanged().collect { id ->
                if (id != null) settings.lastWorkspaceId = id
            }
        }
    }

    /**
     * 「上次在看的那条会话」实时落盘：切会话 / 首页发出第一条 / 删会话换一条都只改状态，
     * 落盘只有这一处（与 [trackLastWorkspace] 同一条规矩）。下次启动读它，见 [resumeConversationId]。
     */
    private fun trackLastConversation() {
        viewModelScope.launch {
            _state.map { it.conversationId }.distinctUntilChanged().collect { id ->
                if (id != null) settings.lastConversationId = id
            }
        }
    }

    private var sendJob: Job? = null

    /**
     * 后台预热 Termux 运行时（解压 / 升级后重链接），不阻塞首屏。
     *
     * 必须切到 IO：viewModelScope 是 Main.immediate，launch 出来的协程体会**同步**跑到
     * 第一个挂起点；ensureReady() 里没有任何挂起点，于是「全新安装」时那 32MB bootstrap 的
     * 解压（上万个文件 + 建链接 + 重写前缀）整个跑在主线程上，界面直接冻住几分钟。
     * 升级安装不会触发：有 MANIFEST.properties 时它立刻返回。
     */
    private fun warmUpRuntime() {
        // 注意：不能在 init 期间同步调用 refreshWorkspaceInfo（此时字段尚未初始化），
        // 所以放到 launch 里，等构造结束再执行。
        viewModelScope.launch {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { runtime.ensureReady { android.util.Log.i(BOOTSTRAP_TAG, it) } }
                    .onFailure { android.util.Log.w(BOOTSTRAP_TAG, "bootstrap failed", it) }
                // 环境自检：把「内嵌 Termux 走的是哪种 exec 模式」写进 logcat，只在 debug 构建里跑
                // （release 用户要看就在终端里敲 adsh-env-check）。
                if (com.adsh.app.ui.chat.ChatDebug.enabled) {
                    runCatching {
                        com.adsh.app.runtime.termux.EnvSelfCheck.runAndLog(
                            context = getApplication(),
                            runtime = runtime,
                            workspaceRoot = conversationWorkspacePath()?.let { java.io.File(it) },
                        )
                    }
                }
            }
            refreshWorkspaceInfo()
        }
    }

    private suspend fun bootstrap() {
        refreshWorkspaceInfo()
        // 设置里绑定过的那个文件夹 = 兜底 cwd（会话没有工作区归属时用它）
        val fallbackPath = _workspaceInfo.value.path
        // 老数据平滑迁移：它成为第一个工作区，已有会话收进去
        fallbackPath?.let { runCatching { repository.ensureDefaultWorkspace(it) } }
        _state.update { it.copy(workspacePath = fallbackPath) }
        refreshWorkspaceList()
        // **启动回到上次那条会话**：上次在看的那条 → 最近一条非空会话 → 都没有就**不建**，停在首页
        // （发第一条消息时才建，见 [send] 那一支）。回落规则是 [resumeConversationId]，纯函数、有单测。
        //
        // 工作区沿用上次退出时那一个（[SettingsStore.lastWorkspaceId]）：回到会话时由会话自己的工作区
        // 接管；一条会话都没有时才用它 —— 那正是「下一条新会话该落在哪个工作区」。
        val targetWorkspace = startupWorkspaceId()
        val resumedId = com.adsh.app.core.data.resumeConversationId(
            conversations = repository.conversations(),
            blankIds = repository.blankIds(),
            lastId = settings.lastConversationId,
        )
        refreshConversations()
        if (resumedId != null) {
            openConversation(resumedId)
        } else {
            // 一条会话都没有：只把工作区挂上（首页那颗 chip 与第一条消息的 cwd 都看它），
            // 会话本身留到用户真的发消息时再建。
            targetWorkspace?.let { id ->
                val path = _workspaceList.value.firstOrNull { it.id == id }?.path
                _state.update { it.copy(workspaceId = id, workspacePath = path ?: it.workspacePath) }
                if (path != null) bindFolder(path)
            }
        }
        refreshContext()
    }

    /**
     * 启动时新会话挂在哪个工作区：上次退出时那一个（[SettingsStore.lastWorkspaceId]）还在 ⇒ 用它；
     * 被删了 ⇒ 用列表里的第一条（[repository.workspaces]）；一个都没有 ⇒ null（待绑定）。
     * 纯函数，三种情形都被单测钉住。
     */
    private suspend fun startupWorkspaceId(): Long? {
        val workspaces = repository.workspaces()
        val remembered = settings.lastWorkspaceId
        return workspaces.firstOrNull { it.id == remembered }?.id ?: workspaces.firstOrNull()?.id
    }

    /**
     * 打开一条会话：消息 / 统计 / 计划 / 目标，以及它所属的工作区
     * （dsh 里 cwd 跟着会话走，所以这里顺手把工作区绑定切过去）。
     *
     * [knownMessages] 非空 = 调用方已经把这批正文摆上去了（缓存命中）：这一趟不再读库，
     * 只补统计 / 计划模式 / 工作区 —— 切会话时正文的换入换出越少，收起动画越稳。
     */
    private suspend fun openConversation(id: Long, knownMessages: List<com.adsh.app.core.data.MessageEntity>? = null) {
        val conversation = repository.byId(id)
        val workspace = conversation?.workspaceId?.let { repository.workspace(it) }
        if (workspace != null) bindFolder(workspace.path)
        val messages = knownMessages ?: repository.messages(id)
        // 统计随会话持久化：切回来能看到上次的用时/用量。
        // 旧版本的分叉 / 分支没有聚合列（输入框下方的胶囊整排消失）：有逐轮用量、聚合却全零时
        // 按正文重算一次 —— 与 dsh 一样，统计是日志的投影，不是另一份状态。
        var stats = repository.statsOf(id)
        if (stats.totalTokens == 0L && messages.any { it.usageJson != null }) {
            stats = runCatching { repository.recomputeStats(id, messages) }.getOrDefault(stats)
        }
        showConversationFrame(
            id = id,
            messages = messages,
            meta = ConversationFrame(
                workspaceId = conversation?.workspaceId,
                stats = stats,
                planMode = conversation?.planMode == true,
            ),
        )
        // 这条会话排着的消息（在别的会话跑着的时候排下的）：回到它、而且现在没人在跑，就接着发
        flushQueue(id)
    }

    /** 切会话时要一起换掉的**会话级**字段（正文之外的那几个） */
    private data class ConversationFrame(
        val workspaceId: Long?,
        val stats: SessionStats,
        val planMode: Boolean,
    )

    /**
     * 把「现在看这条会话」这一帧摆上去 —— 切会话的两条路（缓存命中 / 读库）**只有这一处**写状态。
     * 顺序固定三步（见 LiveRunState.kt）：① 会话级字段；② 轮内字段整块复位；③ 把「正在跑的那一轮」
     * 盖回来 —— 复位不能抹掉在跑的那一轮，这是「切走再切回来不许停」。
     *
     * [meta] 必须是**完整**的一帧（统计也在内）：胶囊的可见性 / 入场动画由统计决定，晚一帧补
     * 会让 footer 在抽屉收起动画里单跳一次（见 [switchConversation]）。
     */
    private fun showConversationFrame(
        id: Long,
        messages: List<com.adsh.app.core.data.MessageEntity>,
        meta: ConversationFrame,
    ) {
        _state.update { previous ->
            previous.copy(
                conversationId = id,
                messages = messages,
                workspaceId = meta.workspaceId,
                stats = meta.stats,
                planMode = meta.planMode,
                // 载入时统计为空 = 这条会话还没跑出过胶囊，允许胶囊第一次出现时播一次入场；
                // 载入时已有统计（启动恢复 / 切到旧会话）必须瞬间出现，否则 footer 高度动画
                // 会经底部内边距把会话流顶一下（判据与 ChatStatsDock 共用 statsDockVisible）。
                dockEntranceArmed = !statsDockVisible(meta.stats),
            ).copy(pendingAttachments = emptyList(), queuedCount = queueOf(id).size)
                .resetTurnFields(queueOf(id).size)
                .withLiveRun(liveRuns[id])
        }
    }

    /** 把工作区绑定切到某个文件夹（工具沙箱 / bash 的 cwd 都跟着走） */
    private suspend fun bindFolder(path: String) {
        val current = workspaces.current()
        if (current.form == com.adsh.app.core.workspace.WorkspaceForm.REAL_PATH &&
            current.root.absolutePath == path
        ) {
            return
        }
        runCatching { workspaces.bindRealPath(File(path)) }
        refreshWorkspaceInfo()
    }

    /**
     * 新会话（dsh 侧栏的 startSession）：
     * 目标工作区 = 当前会话所在的工作区 ?? 最近更新过的那条会话的工作区 ?? 列表里第一个工作区。
     *
     * 一个都落不到时**不建**未分组的空白会话，而是 clearMain 停在首页（dsh 的 startSession
     * 无目标分支就是 clearMain）—— 首页的输入框这时是「选择工作区」的惰性态。
     * connectWorkspace 会复用那个工作区里已有的空白会话，所以停在空白会话上再点一次是「没有反应」的
     * —— 这正是 dsh 的行为，不会堆出一串空会话。
     */
    fun newConversation() {
        val target = _state.value.workspaceId
            ?: conversations.value.filter { it.workspaceId != null }.maxByOrNull { it.updatedAt }?.workspaceId
            ?: workspaceList.value.firstOrNull()?.id
        if (target == null) {
            viewModelScope.launch { clearMain() }
            return
        }
        newConversationIn(target)
    }

    /**
     * 回到「没有会话」的首页（dsh 的 clearMain）：状态清空（见 [clearedMain]），抽屉列表刷新。
     *
     * 首页没有会话、也没有工作区归属 —— 输入框因此是「选择工作区」的惰性态，
     * 必须先把工作区选好才能开始一条会话。
     */
    private suspend fun clearMain() {
        _state.update { it.clearedMain() }
        refreshConversations()
    }

    /**
     * 在指定工作区里新建会话（dsh 工作区行右侧的 ＋ / 侧栏新会话按钮）。
     *
     * 对齐 dsh 的 connectWorkspace：同一个工作区里已经有**一条消息都没有**的空白会话就复用它，
     * 不再反复建空会话；另外在真实工作区里开了会话之后，未分组的空白占位会话自动消失。
     */
    fun newConversationIn(workspaceId: Long?) {
        viewModelScope.launch {
            openBlankConversation(workspaceId)
            refreshContext()
        }
    }

    /**
     * 开一条会话并把它摆上屏幕，返回它的 id。两个调用点：「新会话」按钮（[newConversationIn]）与
     * **首页发出的第一条消息**（[send] 里 conversationId 还是 null 的那一支）。
     * 「建会话」有四个必做步骤（落库 / 刷列表 / 摆上屏幕 / 统计清零），所以只留这一处。
     */
    private suspend fun openBlankConversation(workspaceId: Long?): Long {
        val id = repository.openOrCreateBlank(workspaceId)
        refreshConversations()
        openConversation(id)
        _state.update { it.copy(stats = SessionStats()) }
        return id
    }

    /** dsh 的「添加工作区」/ 设置里的绑定：把手机文件夹绑定成工作区并切过去 */
    fun bindWorkspaceFolder(path: String) {
        viewModelScope.launch {
            val id = repository.addWorkspace(path, File(path).name.ifBlank { path })
            refreshWorkspaceList()
            newConversationIn(id)
        }
    }

    /**
     * dsh 的「删除工作区」：只解除绑定 —— 文件夹与会话记录都保留，
     * 它的会话回到「未分组」（与 dsh 的 delete.desc 文案一致）
     */
    fun deleteWorkspace(id: Long) {
        viewModelScope.launch {
            repository.deleteWorkspace(id)
            refreshWorkspaceList()
            refreshConversations()
            if (_state.value.workspaceId == id) _state.update { it.copy(workspaceId = null) }
        }
    }

    /** dsh 的会话「分叉」：整份历史复制成新会话并切过去 */
    fun forkConversation(id: Long) {
        viewModelScope.launch {
            val copy = repository.forkConversation(id) ?: return@launch
            refreshConversations()
            openConversation(copy)
            refreshContext()
        }
    }

    /**
     * dsh 轮尾的「在新对话中分支」：把截止到该轮收尾回答的历史复制成一条新会话并切过去。
     *
     * 每个**已结束**轮次的轮尾都挂这个入口（dsh 的 turn-tail 按轮独立判断，不要求是最新一轮、
     * 也不要求会话空闲）；传进来的是该轮 closing 回答的行 id（[TurnView.branchMessageId]）。
     * dsh 传的是 turn/end 事件的 seq——ADSH 没有事件日志，按消息行截断是等价物：enabled 时
     * closing 行就是该轮最后一行，复制到它为止 = 切到「回答结尾」。
     */
    fun branchAt(messageId: Long) {
        val conversationId = _state.value.conversationId ?: return
        viewModelScope.launch {
            val copy = repository.forkAt(conversationId, messageId) ?: return@launch
            refreshConversations()
            openConversation(copy)
            refreshContext()
        }
    }

    /**
     * dsh 的 `export` 指令（命令栏「下载日志」）：把当前会话导出成 ZIP。
     *
     * 落盘根取**这条会话自己的工作区** —— 与工具 / 附件落点是同一个函数
     * （[conversationWorkspacePath]）；不能读 [ChatUiState.workspacePath]（那是启动时的兜底
     * 工作区，绑定 / 切换工作区后不更新，会让导出落到应用私有目录或上一个工作区）。
     * 没有会话时返回 null（界面 toast「export failed」）。
     */
    fun exportSession(): String? {
        if (_state.value.conversationId == null) return null
        return exportSessionZip(getApplication(), _state.value, conversationWorkspacePath())
    }

    /** dsh 的「重命名会话」 */
    fun renameConversation(id: Long, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.rename(id, trimmed)
            refreshConversations()
        }
    }

    /**
     * 切换会话（抽屉里点一条）。**是 suspend 的**：调用方（抽屉）要等它返回再收起 ——
     * 收起动画期间不再有正文换入换出，这是「切会话掉帧」的第一号来源。
     *
     * 两条路：
     *  - 缓存命中：正文**这一帧**就位，返回后抽屉立刻可以收；统计**也在这一帧**（两条小查询）——
     *    胶囊的出现 / 消失必须跟正文一起落到抽屉还盖着的那一帧，拖到收起动画里再改 footer 高度
     *    就会看到输入卡单独跳一下。只有上下文占用（要扫全部正文，按长度线性增长）延后补。
     *  - 未命中：**先等正文读出来**（正常 1~5ms）再返回 ——「先收起、读完再换」会让抽屉
     *    滑进来的那几帧里正文整块换掉，用户看到的就是闪一下 / 卡一下。
     */
    suspend fun switchConversation(id: Long) {
        // **不再看 sending**（用户口径，参照 dsh）：正在跑的那一轮在后台接着跑，切会话只是换一下
        // 「看哪一条」—— 它的实时内容一直住在 liveRuns 里（跟视图无关），切回来原样接上。
        if (_state.value.conversationId == id) return
        val cached = repository.cachedMessages(id)
        if (cached != null) {
            // 命中缓存：正文、统计、计划、工作区在**同一帧**换到位（showConversationFrame 的唯一入口）。
            openConversation(id, knownMessages = cached)
            viewModelScope.launch { refreshContext() }
            return
        }
        val ready = withTimeoutOrNull(SWITCH_CONTENT_WAIT_MS) { openConversation(id) } != null
        // 库慢到超过上限：先让抽屉收起，正文晚一帧到（不至于把抽屉吊在那里）
        if (!ready) viewModelScope.launch { openConversation(id) }
        viewModelScope.launch { refreshContext() }
    }

    /**
     * 删除会话（侧栏的「删除会话」）。
     *
     * 对齐 dsh 的 archiveSession + clearArchivedCurrent：
     *  - 删的不是当前会话：只刷新列表，当前会话不动；
     *  - 删的是当前会话：clearMain 回到没有会话的首页（工作区归属也清掉）—— 首页的输入框
     *    因此是「选择工作区」的惰性态，选好工作区才能开始下一条。**不自动落到别的会话**：
     *    dsh 删掉当前会话后同样停在 clearMain 的首页。
     */
    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            val wasCurrent = _state.value.conversationId == id
            val workspaceId = repository.byId(id)?.workspaceId
            // 会话私有附件随会话一起删除。必须用**这条会话所属工作区**的路径：
            // 旧写法用的是「当前工作区」，删别的分组里的会话时会去找错目录，附件就留在磁盘上了。
            val attachmentRoot = workspaceId
                ?.let { wid -> repository.workspace(wid)?.path }
                ?: _state.value.workspacePath
            attachmentRoot?.let { root ->
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    java.io.File(root, ".adsh/attachments/" + id).deleteRecursively()
                }
            }
            // 会话没了：它的后台任务也不该继续跑（dsh 的 archive admission：reason = session archived）。
            // 通知整类跳过（cause = teardown），不会去喂一条已经删掉的会话。
            runCatching { com.adsh.app.core.jobs.Jobs.cancelOwner(id, "session archived") }
            repository.delete(id)
            // 会话连同它的任务清单一并丢掉（清单是会话级的，见 TodoStore）
            com.adsh.app.core.tools.TodoStore.forget(id)
            if (wasCurrent) {
                // 这条会话的实时登记表 / 排队消息随会话一起丢掉，免得后台残留的写入再把它们喂回来。
                liveRuns.remove(id)
                pendingSends.remove(id)
                clearMain()
            } else {
                refreshConversations()
            }
        }
    }

    /**
     * 排队待发的消息（dsh 的 queue：繁忙时按「排队发送」进来的消息，轮结束后按序发出）。
     * 附件必须跟着这一条一起排队：只排队正文的话，轮到它发的时候附件早被清掉了。
     */
    private data class PendingSend(val text: String, val attachments: List<String>)

    /** 排队待发的消息：**按会话各排各的**（切会话不打断正在跑的那一轮，队列也不能串会话） */
    private val pendingSends = HashMap<Long, ArrayDeque<PendingSend>>()

    private fun queueOf(conversationId: Long): ArrayDeque<PendingSend> =
        pendingSends.getOrPut(conversationId) { ArrayDeque() }

    /** 正在跑的那条会话（同一时刻只有一轮在跑；切会话不打断它，见 [switchConversation]） */
    private var runningConversationId: Long? = null

    /**
     * 每条会话「正在跑的那一轮」的状态登记表 —— 「切会话不打断正在跑的那一轮」的支点：实时状态
     * 跟着**会话**走，不跟着「现在看哪一条」走。**一轮在跑期间，它的状态永远有一份住在这里**，
     * [updateTurn] 负责写（切回来由 [ChatUiState.withLiveRun] 盖到库状态上）。
     */
    private val liveRuns = HashMap<Long, ChatUiState>()

    /** 某条会话此刻的界面状态：正在看它就读 _state，否则读 [liveRuns]（它还在后台跑） */
    private fun stateOf(conversationId: Long): ChatUiState =
        if (_state.value.conversationId == conversationId) _state.value else liveRuns[conversationId] ?: _state.value

    /**
     * 只改「这条会话」的状态，顺带把这一帧推给灵动岛。
     * 正在跑的那一轮**两边都写**（上屏的与登记表里的必须一致，否则切回来会看到「没在跑」的旧快照）；
     * 其它写入只落在它自己身上，不会画到屏幕上正在看的那条上。
     */
    private fun updateTurn(conversationId: Long, block: (ChatUiState) -> ChatUiState) {
        val current = _state.value
        val viewed = current.conversationId == conversationId
        val next = block(if (viewed) current else liveRuns[conversationId] ?: current)
        // 登记表只装「有轮在跑」的会话：一轮收尾时 finishTurn 会把它撤掉，
        // 于是这里不必给空闲会话留快照（留了反而会在切回来时把旧字段盖上去）
        val hasLive = runningConversationId == conversationId || liveRuns.containsKey(conversationId)
        if (hasLive) liveRuns[conversationId] = next
        if (viewed) _state.value = next
        publishIsland(conversationId)
    }

    /** 把这条会话此刻的岛状态推给前台服务（岛只在有轮在跑时才出现，见 islandWorkOf） */
    private fun publishIsland(conversationId: Long) {
        onIslandFrame(islandWorkOf(stateOf(conversationId), islandWaitingOf(question.value, approval.value)))
    }

    /**
     * 一轮收尾 / 切回某条会话时：现在没人在跑，就把**这条会话**排着的消息发出去。
     *
     * 队列跟着会话（用户口径：各会话各排各的），所以这里带 conversationId —— 旧写法读的是
     * `_state.value.conversationId`：正在跑的 A 收尾时用户已经切到 B，A 队列里那条会被
     * `send()` 发进 **B**（串会话）。没在被看的那条会话，它的队列**先留着** ——
     * 切回它时 [openConversation] 会接着发，语义是「这条会话空出来了就继续」。
     */
    private fun flushQueue(conversationId: Long) {
        if (runningConversationId != null) return
        if (_state.value.conversationId != conversationId) return
        val next = queueOf(conversationId).removeFirstOrNull() ?: return
        updateTurn(conversationId) { it.copy(queuedCount = queueOf(conversationId).size) }
        send(next.text, next.attachments)
    }

    /** 本轮里发生过一次「插话发送」：收尾时要看这条插话有没有人接（没人接就补一轮） */
    /**
     * 正在跑（含「按了停止、AgentLoop 还在收尾」那段窗口）的轮数。
     *
     * 为什么不看 `_state.sending`：`cancel()` 会**先**把 sending 置 false、再去 cancelAndJoin，
     * 这段窗口里 `sending` 已经是 false 而那一轮还在收尾 —— 通知此刻若「唤醒」一轮，
     * 旧一轮收尾时那几次 `_state.update`（清 liveTurnId / turnEvents / 回写 messages）会把
     * 新的一轮的实时状态整块盖掉（工具行、思考全丢，干完才从库里长回来）。dsh 在这里
     * 走的是**注入**（「turn 已 cancelled 但尚未收敛的 owner 也走注入」），这里照做。
     * 计数在 [startTurn] 里同步 +1、在这一轮的 Job 完成时 -1 —— 所以它覆盖整个收尾窗口。
     */
    private val turnsInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 结算通知的车道（dsh-tool-jobs 的完成通知车道）：**只入队**，由一个消费者投递（落库在 IO 上、
     * 界面那一半回主线程）—— 同一批结算只落一次库、只刷一次界面、**只决定一次「要不要为它开一轮」**
     * （dsh：第一条唤醒空闲 owner，后面几条走注入）。消费者只有一个，所以不需要串行锁。
     */
    private val noticeChannel =
        kotlinx.coroutines.channels.Channel<com.adsh.app.core.jobs.Jobs.Notice>(
            kotlinx.coroutines.channels.Channel.UNLIMITED,
        )

    /** 注册表在结算线程上回调；退订器在 [onCleared] 里收掉 */
    private val jobNoticeSubscription = com.adsh.app.core.jobs.Jobs.onNotice { notice ->
        noticeChannel.trySend(notice)
    }

    init {
        // 消费者跑在 **IO** 上：唤醒支要落库、开轮，注入支要动收件箱的锁，认领那条路本来也在 IO 上。
        // 通知**到认领那一刻才落库**（见 ConversationRepository.enqueueInjected），所以它落在会话里的
        // 位置与「结算线程还是主线程」无关。
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for (first in noticeChannel) {
                val batch = ArrayList<com.adsh.app.core.jobs.Jobs.Notice>()
                batch += first
                while (true) batch += noticeChannel.tryReceive().getOrNull() ?: break
                deliverJobNotices(batch)
            }
        }
    }

    /**
     * 一批结算通知的投递（对应 dsh-tool-jobs 的 `owner.followup` / `owner.inject` 两支）：
     *  - 会话空闲、而且正是当前会话 → **开一轮**（dsh 的 wakeup / followup）：这条通知落库成
     *    [ConversationRepository.JOB_NOTICE]（它自己就是那一轮的开头），同一批里后面的几条看到
     *    owner 已经在跑，走注入；
     *  - 正在跑（含「按了停止、还在收尾」的窗口）→ 进**收件箱**（dsh 的 inject → next-step）：
     *    到达时不落库，正在跑的那一轮**下一步开始时**认领它（见 ConversationRepository.claimInjected）
     *    —— 位置一次定死，界面不会再看着它换地方；
     *  - 不是当前会话 → 直接落库：ADSH 的循环绑在「当前会话」上，替后台会话跑一轮会把界面状态
     *    搬到另一个会话去 —— 这条是**有意的平台差异**，记在 NOTES 里；模型下次读这个会话时自然看到它。
     *
     * 唤醒那一支**不在这里先刷新 `messages`**：交给 [startTurn] 的第一次状态更新把「通知行 +
     * liveTurnId」一起换上去。分两次的话，中间会先画出一个「已结束的空轮」（轮尾那一条行动一下），
     * 真机上就是一闪。
     */
    private suspend fun deliverJobNotices(batch: List<com.adsh.app.core.jobs.Jobs.Notice>) {
        for ((owner, notices) in batch.groupBy { it.owner }) {
            val current = _state.value.conversationId == owner
            var opensTurn = current && turnsInFlight.get() == 0
            var wake: Pair<Long, String>? = null
            for (notice in notices) {
                if (opensTurn) {
                    // 空闲：这条通知**就是新的一轮的开头**（dsh 的 followup），直接落库
                    val row = repository.addMessage(
                        conversationId = owner,
                        role = "user",
                        content = notice.text,
                        name = ConversationRepository.JOB_NOTICE,
                    )
                    opensTurn = false
                    wake = row to notice.text
                } else if (current) {
                    // 忙（或正在收尾）：进收件箱，正在跑的那一轮**下一步开始时**认领（dsh 的
                    // inject → next-step inbox）。不在这里落库、也不在这里刷新界面 ——
                    // 认领那一刻（ChatEvent.InboxClaimed）才写、才画，位置一次定死。
                    repository.enqueueInjected(
                        conversationId = owner,
                        role = "user",
                        content = notice.text,
                        name = ConversationRepository.JOB_NOTICE_INJECTED,
                    )
                } else {
                    // 不是当前会话：这一轮不会有人认领它（ADSH 的循环绑在当前会话上），
                    // 直接落库 —— 下次进这个会话时模型自然读到（有意的平台差异，见类注释）
                    repository.addMessage(
                        conversationId = owner,
                        role = "user",
                        content = notice.text,
                        name = ConversationRepository.JOB_NOTICE_INJECTED,
                    )
                }
            }
            if (!current) continue
            // 只有「开轮」这一半要回主线程；注入那一支等认领时由 InboxClaimed 带上界面。
            val opening = wake
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (opening != null) {
                    startTurn(owner, opening.second, persistUser = false, turnKey = opening.first)
                }
            }
        }
    }

    /**
     * View 销毁（Activity 结束）：把还活着的后台任务一起停掉。
     *
     * 为什么必须有：这些子进程是 App 的子进程，App 进程被杀之后会被 init 收养继续跑
     * （安卓不会把它整组带走）—— 不主动杀就是「界面没了、构建还在后台耗电」。dsh 的服务销毁同理
     * （cancelForTeardown），只是它没有「系统杀进程」这一层。
     */
    override fun onCleared() {
        runCatching { jobNoticeSubscription() }
        runCatching { com.adsh.app.core.jobs.Jobs.cancelAll("app is shutting down") }
        super.onCleared()
    }

    /**
     * 发送。繁忙时的行为对齐 dsh 的 busyEnter：
     *  - 排队发送（默认）：入队，等这一轮结束再发；
     *  - 插话发送：立刻把这条用户消息插进正在跑的这一轮（AgentLoop 每一轮都重读历史，
     *    所以下一步就会看到它），如果这一轮刚好收尾了就补一轮接着跑。
     */
    fun send(text: String, attachmentPaths: List<String>? = null) {
        // 附件不拼进正文：**不要**把待发附件写成 @路径 追加在正文后面 —— 气泡里会出现一串路径，
        // 图片更是永远到不了能收图的模型。附件随消息落库（MessageEntity.attachmentsJson），
        // 装配请求时才翻译成 dsh 的文件句柄文本 / 图片句柄 + 图片块（见 core/agent/Attachments.kt）。
        val attachments = attachmentPaths ?: _state.value.pendingAttachments
        val body = text.trim()
        if (body.isEmpty() && attachments.isEmpty()) return
        // 首页（还没有会话）发出的第一条：**这时才建会话**，启动不预先建空会话。建完按正常路径跑
        // 这一轮；工作区用首页挂着的那一个。
        val current = _state.value.conversationId
        if (current == null) {
            val workspaceId = _state.value.workspaceId
            viewModelScope.launch {
                val id = openBlankConversation(workspaceId)
                startTurn(id, body, persistUser = true, attachmentPaths = attachments)
            }
            return
        }
        val conversationId = current
        val running = runningConversationId
        if (running != null) {
            if (running != conversationId) {
                // 别的会话正在跑（用户切过来发的）：排进**它自己那条会话**的队列，等那边空出来再发
                queueOf(conversationId).addLast(PendingSend(body, attachments))
                updateTurn(conversationId) {
                    it.copy(queuedCount = queueOf(conversationId).size, pendingAttachments = emptyList())
                }
                return
            }
            if (settings.busyEnter == SettingsStore.BUSY_STEER) {
                steer(body, conversationId, attachments)
            } else {
                queueOf(conversationId).addLast(PendingSend(body, attachments))
                updateTurn(conversationId) {
                    it.copy(queuedCount = queueOf(conversationId).size, pendingAttachments = emptyList())
                }
            }
            return
        }
        startTurn(conversationId, body, persistUser = true, attachmentPaths = attachments)
    }

    /**
     * 插话发送：把消息落进历史，正在跑的那一轮会在下一步带上它。按 dsh 的 **steering 节点**落库
     * （`name = STEERING`）：**不动 `liveTurnId`**（不开新一轮，否则在跑那一轮的流式状态会被判成已结束）；
     * 不直接落库 —— 先进收件箱、到这一步开始才认领，但**界面当帧就要看到回显**（`session.beginSubmission`）。
     */
    private fun steer(body: String, conversationId: Long, attachmentPaths: List<String> = emptyList()) {
        val echo = PendingSteer(id = System.nanoTime(), text = body, time = System.currentTimeMillis())
        updateTurn(conversationId) { it.copy(pendingSteering = it.pendingSteering + echo) }
        repository.enqueueInjected(
            conversationId = conversationId,
            role = "user",
            content = body,
            name = ConversationRepository.STEERING,
            attachmentPaths = attachmentPaths,
        )
        _state.update { it.copy(pendingAttachments = emptyList()) }
        if (attachmentPaths.isNotEmpty()) {
            // 附件的描述（sha256 / 尺寸 / MIME）要读文件，只能异步补到回显上；
            // 用的还是认领那一行用的同一个 describeAttachments，两边的展示因此一致。
            viewModelScope.launch {
                val json = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { encodeAttachments(describeAttachments(attachmentPaths)) }.getOrNull()
                }
                updateTurn(conversationId) { state ->
                    state.copy(
                        pendingSteering = state.pendingSteering.map {
                            if (it.id == echo.id) it.copy(attachmentsJson = json) else it
                        },
                    )
                }
            }
        }
    }

    /** 清空排队发送的消息（dsh 的 queue chip 上的清空） */
    fun clearQueued() {
        val id = _state.value.conversationId ?: return
        queueOf(id).clear()
        _state.update { it.copy(queuedCount = 0) }
    }

    /**
     * 这一轮收尾时：插话没人接就补一轮（消息已经在历史里，不再重复落库）。
     *
     * 「没人接」= 模型还没看到它就收尾了（最后一条消息仍是那条插话）。这时它不再是插进别人
     * 一轮里的 steering，而是**下一轮的开头** —— 先把 name 标记去掉，再按新的一轮跑，
     * 与 dsh 的 inbox 认领语义一致（没被认领 → 普通 user 节点、算一轮）。
     */
    private suspend fun continueIfDangling(conversationId: Long) {
        // openTurn = true：这批行没人接（这一轮收尾了）—— 插话去掉 steering 标记、第一条通知换成
        // 唤醒形态，它们就是下一轮的内容（dsh 的 `if (!this.inbox.hasPending) return false;`）
        val claimed = repository.claimInjected(conversationId, openTurn = true)
        if (claimed.isEmpty()) return
        val opener = claimed.first()
        startTurn(conversationId, opener.content, persistUser = false, turnKey = opener.id)
    }

    private fun startTurn(
        conversationId: Long,
        body: String,
        persistUser: Boolean,
        attachmentPaths: List<String> = emptyList(),
        /**
         * 这一轮的身份（dsh 的 turn 起点）。默认 = 刚落库的那条人类消息；**通知唤醒的一轮**
         * 由投递方给通知行的 id —— 否则 liveTurnId 会对上一条旧消息，流式正文挂不上这一轮。
         */
        turnKey: Long? = null,
    ) {
        // cwd 跟着会话的工作区走；未分组时回落到设置里绑定的工作区
        // （附件落盘用的是同一个函数，见 conversationWorkspacePath）
        val workspacePath = conversationWorkspacePath()
        var stopped = false
        // dsh 的 turn/start 会把 todos 这份 projection 清成 null：新一轮一开始，
        // 输入框上方那条任务横窗就收起来，等这一轮的 todo_write 再把它写出来
        com.adsh.app.core.tools.TodoStore.clear(conversationId)
        // 新一轮 = 新的重试链：新失败要新开一行（上一轮的链在它自己的 Step / 收尾时已结束）
        retryMessageIds.remove(conversationId)

        turnsInFlight.incrementAndGet()
        runningConversationId = conversationId
        // 这一轮的状态从这一刻起由登记表持有：正在看它就以屏幕上这一份为底；没在看（补一轮那种）
        // 就保留已有的那一份，没有才拿当前状态当底
        if (_state.value.conversationId == conversationId) {
            liveRuns[conversationId] = _state.value
        } else {
            liveRuns.putIfAbsent(conversationId, _state.value)
        }
        val job = viewModelScope.launch {
            updateTurn(conversationId) { turnOpened(it, System.currentTimeMillis()) }
            try {
                openTurnInputs(conversationId, body, persistUser, attachmentPaths, turnKey)
                val toolContext = buildToolContext(conversationId, workspacePath)
                collectTurn(conversationId, agent.send(conversationId, body, toolContext, persistUser = false))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    stopped = true
                    throw t
                }
                updateTurn(conversationId) {
                    it.copy(
                        error = t::class.java.simpleName + "：" + (t.message ?: "") + imageRouteHint(it, settings.modelLabel()),
                    )
                }
            } finally {
                finishTurn(conversationId, stopped)
            }
        }
        sendJob = job
        // 这一轮彻底结束（正常收尾 / 被取消 / 出错）才减计数：收尾窗口也算「在飞」
        job.invokeOnCompletion { turnsInFlight.decrementAndGet() }
    }

    /**
     * 开轮的输入侧：系统提示词 / 上下文注入落库 → 用户消息落库并上屏 → 这一轮的身份 → 侧栏刷新
     * → 异步生成标题。顺序是 dsh 的会话节点顺序（sysprompt / context 先于 user）。
     * **用户消息必须在这里落库**：[AgentLoop.send] 恒以 persistUser = false 调用，用户行只落这一次。
     */
    private suspend fun openTurnInputs(
        conversationId: Long,
        body: String,
        persistUser: Boolean,
        attachmentPaths: List<String>,
        turnKey: Long?,
    ) {
        // dsh 的会话节点顺序：系统提示词行 / 上下文注入行，然后才是用户消息。
        // **切到 IO**：装配系统提示词要读指令文件链（`instructionChain` 会把 AGENTS.md 这类
        // 文件读进来）再拼出整段正文 —— 这是发送路径上唯一一处「文件 IO + 线性字符串拼接」，
        // 留在主线程就是用户报的「发消息时卡一下」。
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                agent.recordContext(
                    conversationId = conversationId,
                    // 读**内存状态**而不是库：`/plan 消息` 是「开关 + 发送」两步，
                    // setPlan 已经先把状态改成新值（落库稍后完成），这里必须跟着它走。
                    // 工作区传对象而不是路径：装配器读的就是这个对象（见 AgentLoop.assemblePrompt）；
                    // 路径参数不会被使用，第一条消息会装配成「没有绑定工作区」
                    workspace = workspaces.current(),
                    planMode = _state.value.planMode,
                )
            }
        }
        // 用户消息落库并立刻上屏：不再等 AI 的第一个 token 才出现。
        // 附件在这里变成引用（sha256 / 图片尺寸 / MIME），随消息一起存
        if (persistUser) {
            val attachments = describeAttachments(attachmentPaths)
            // 附件副本在发送前就消失（被外部清理 / 工作区被移动）：dsh 会以 FILE_NOT_STAGED 拒绝这条
            // prompt；ADSH 不拦发送，但要可见地说出来 —— 静默丢掉会让用户以为模型看到了文件。
            val missing = attachmentPaths.size - attachments.size
            if (missing > 0) {
                updateTurn(conversationId) {
                    it.copy(error = missing.toString() + " attachment copy(ies) are no longer readable and were not sent")
                }
            }
            repository.addMessage(
                conversationId = conversationId,
                role = "user",
                content = body,
                attachmentsJson = encodeAttachments(attachments),
            )
        }
        // 这一轮的身份 = 它起始的用户消息 id（dsh 的 turn/start）
        val openedMessages = repository.messages(conversationId)
        // 按**这条会话**路由：正在跑的 A 收尾时又补一轮（continueIfDangling）而用户已经切到 B 时，
        // 旧写法会把 A 的消息换到 B 的屏幕上
        updateTurn(conversationId) { turnIdentityResolved(it, openedMessages, turnKey) }
        // **兜底标题**（dsh 的 `SessionTitleService.onUserMessage` → `ensureFallback`）：首条人类
        // 消息一落库就按它取前 5 个空格分隔 token（≤40 UTF-8 字节）当标题 —— 左上角与抽屉立刻
        // 就有名字，不再是「新会话」；标题模型回来后覆盖它（时机见 [generateTitle]）。
        writeFallbackTitle(conversationId, openedMessages)
        // 第一条消息落库后这条会话就不再是「空白」了：顺手刷新侧栏（上面写下的兜底标题
        // 也在这一次刷新里上屏）
        refreshConversations()
    }

    /**
     * dsh 的 `ensureFallback`：**首条人类消息之后立刻**写下确定性兜底标题。
     *
     * 取的是**第一条**真人消息（dsh 的 `titleInputOf().first`，不是刚发的这条：首轮失败重发时
     * 也要跟 dsh 同一个来源），而且只在标题仍是「新会话」时写得进去（[ConversationRepository.renameAutoTitle]
     * 是 CAS）—— 用户手改过名、或标题模型已经写过一次，这里就什么都不做。
     */
    private suspend fun writeFallbackTitle(conversationId: Long, messages: List<MessageEntity>) {
        val fallback = com.adsh.app.core.data.fallbackTitleOf(messages) ?: return
        runCatching {
            repository.renameAutoTitle(conversationId, com.adsh.app.core.data.NEW_SESSION_TITLE, fallback)
        }
    }

    /**
     * 生成会话标题（时机与机制都对齐 dsh 的 session-title）：**兜底**是首条人类消息一落库就写
     * （[writeFallbackTitle]）；**模型标题**订在**主请求写到连接上那一刻**（`ChatEvent.RequestHeader`，
     * 即 dsh 的 `SessionTitleService.onRequestHeader`），与第一轮并发、回来后覆盖兜底值。
     */
    private fun generateTitle(conversationId: Long) {
        viewModelScope.launch {
            // 标题这次小调用**默认关思考**（dsh 的 purpose = session-title 口径）：只有 64 个输出
            // token，会推理的模型会把预算全花在推理上 → content 为空 → 标题永远退回兜底值。
            // 字段名逐路由不同，由 titleNoThink 按路由族给（见它的 KDoc）。
            val config = settings.providerConfig()
            val noThink = com.adsh.app.core.agent.titleNoThink(config.providerId, config.baseUrl, config.model)
            runCatching {
                generateSessionTitleIfNeeded(
                    compactLlm, repository, settings.model, conversationId,
                    noThink.thinking, noThink.enableThinking,
                )
            }.onFailure {
                // 这一路必须报出来：模型只回推理 / 被截断 / 提供方报错时，界面上不能只是「标题没变」。
                android.util.Log.w("ADSH", "session title generation failed; keeping the fallback title", it)
            }
            // 写回库的那个标题（模型的或兜底的）要立刻可见：左上角与抽屉都读那份会话列表
            refreshConversations()
        }
    }

    /**
     * 这一轮的工具上下文（PTC）：全局项由组合根 [ToolingHost] 装配，
     * 这里只给每轮才变的东西 —— 会话 id / 工作区 / 轮次 / 计划模式。
     */
    private suspend fun buildToolContext(
        conversationId: Long,
        workspacePath: String?,
    ): com.adsh.app.core.tools.ToolContext = tooling.contextFor(
        conversationId = conversationId,
        workspacePath = workspacePath,
        // dsh 的 present 输出带 turn：这一轮是第几轮（用户消息条数；带 name 的不是轮次）
        turn = repository.messages(conversationId).count { isOwnUserMessage(it) },
        // exit_plan_mode：计划被批准后离开计划模式
        planMode = _state.value.planMode,
        onPlanModeChanged = { active -> setPlan(active) },
    )
    /**
     * 收这一轮的事件流（原 startTurn 里 46 行的 collect 体）。
     *
     * 三条分工别打乱：**模型请求重试的两支归 [persistRetry] / [markRetryStarted]**（重试行要
     * 落库，才能像 dsh 的 model-retry 节点一样留在会话流里）；**trace 留在 ViewModel**
     * （[traceAppended] 走 android.util.Log，纯投影文件里放它，单测会撞 "not mocked"）；
     * 其余分支全是 [turnProjected] 的纯投影。
     *
     * Appended 那一支里两处 IO（读库、[refreshMessageTokens]）保持**顺序**调用 —— 改成 launch 会让
     * 状态乱序；而且读库必须发生在 trace 之后、与状态更新同一帧（见那一支的注释）。
     */
    private suspend fun collectTurn(conversationId: Long, events: kotlinx.coroutines.flow.Flow<ChatEvent>) {
        events.collect { event ->
            when (event) {
                // 主请求已经写到连接上（dsh 的 `request/header`）：**标题那次小调用订在这里**
                // （dsh 的 SessionTitleService.onRequestHeader）—— 第一轮进行当中并发跑，不挡主请求。
                // 每步都会发一次这个事件，真正会发出去的只有「首条真人消息 + 标题还是新会话」的
                // 会话一次（判据是 SessionTitleRunner 里的进程内集合），其余是一次集合查找。
                is ChatEvent.RequestHeader -> generateTitle(conversationId)
                // 模型请求重试（dsh 的 llm/retry）：在会话流里落一行「模型请求重试行」，
                // 与 dsh 的 model-retry 节点同位置（失败的那一步之后）、同文案。
                is ChatEvent.RetryScheduled -> {
                    persistRetry(
                        conversationId,
                        RetryInfo(
                            retry = event.retry,
                            maximum = event.maximum,
                            delayMs = event.delayMs,
                            failure = event.failure,
                            started = false,
                        ),
                    )
                    val messages = repository.messages(conversationId)
                    updateTurn(conversationId) { it.copy(messages = messages) }
                }
                // 退避结束、请求已重发（dsh 的 llm/retry-started）：同一行原地切成「已重试」
                is ChatEvent.RetryStarted -> {
                    markRetryStarted(conversationId)
                    val messages = repository.messages(conversationId)
                    updateTurn(conversationId) { it.copy(messages = messages) }
                }
                // 轮内事件只有这一种（会话日志追加了一条）：工具的开始 / 结算、子调用的开始 /
                // 结算、一步 assistant 输出定稿，全都从这条通道来，界面形态由 fold 得出 ——
                // 归属按 harnessId / 子调用 id 前缀，顺序就是 append 顺序（dsh 的 cell 模型）。
                is ChatEvent.Appended -> {
                    val appended = event.event
                    traceAppended(appended)
                    // 一步定稿：**与日志追加在同一次状态更新里**从库里重读 + 清空流式缓冲。两件事
                    // 一起发生，所以「同一段文字既在库行里、又当尾巴挂着」那一帧不存在；去重判据只有这一条。
                    val messages =
                        if (appended.body is com.adsh.app.core.session.SessionBody.Step) {
                            // 这一步定稿 = 这一条重试链结束：下一步的失败要新开一行
                            retryMessageIds.remove(conversationId)
                            repository.messages(conversationId)
                        } else {
                            null
                        }
                    updateTurn(conversationId) { current -> turnProjected(current, event, messages) { settings.modelLabel() } }
                    if (messages != null) {
                        // 上下文占用跟着这一步立刻重算：dsh 的 contextPressure 是会话状态每次追加后
                        // 都变的投影。只重算 messages 那一栏 —— 系统提示词与 tools 两栏不会因为聊天
                        // 变长而变（它们各自的变化点会调 refreshContext）。
                        refreshMessageTokens()
                    }
                }
                // 收件箱认领（dsh 的 preStep claim）：行已经写进库，重读一次把它们画出来。
                is ChatEvent.InboxClaimed -> {
                    val claimed = repository.messages(conversationId)
                    updateTurn(conversationId) { current -> turnProjected(current, event, claimed) { settings.modelLabel() } }
                }
                // 自动压缩开始（dsh 的 compaction/start）：把「压缩中」条亮起来。
                // 只有 pressure / overflow 走这条通道；手动 /compact 由 [compact] 自己置状态。
                is ChatEvent.CompactionStarted -> updateTurn(conversationId) { it.copy(compacting = true) }
                // 自动压缩结束（dsh 的 compaction/end）：重读消息（历史可能被替换）+ 重算上下文占用。
                // applied = false 表示这次什么都没改，重读是空操作但刷新了上下文占用。
                is ChatEvent.CompactionFinished -> {
                    val messages = repository.messages(conversationId)
                    updateTurn(conversationId) { it.copy(compacting = false, messages = messages) }
                    refreshContext()
                }
                // 其余分支（Delta / Reasoning / ToolCallDelta / Stats / StreamReset / Failed / Usage）
                // 都是纯投影，见 [turnProjected]；Usage 由 AgentLoop 的 token 账本消费，这里不动状态
                else -> updateTurn(conversationId) { current -> turnProjected(current, event) { settings.modelLabel() } }
            }
        }
    }

    /**
     * 一轮的收尾（startTurn 的 finally 体）。
     *
     * **必须 NonCancellable**：被打断时协程已取消，任何普通挂起点都会立刻再抛取消，
     * 结果就是「按了停止，界面卡在对话中、消息也没保存」。
     *
     * [stopped] 走参数、**不提成字段**：它是这一轮的局部信号（catch 里被 CancellationException 置真），
     * 提成字段会被下一轮覆盖。被停与正常收尾的两条路不能合并 —— 前者清收件箱、后者排下一轮。
     */
    private suspend fun finishTurn(conversationId: Long, stopped: Boolean) {
        withContext(NonCancellable) {
            val messages = repository.messages(conversationId)
            // 轮/步由消息推导（每轮上报的 turns 是绝对值，累加会重复计数）；其余统计随会话落库。
            // 轮数与 AgentLoop 的口径一致：带 name 的 user 行（插话 / 权限切换通知）不算一轮。
            val stats = turnStatsOf(stateOf(conversationId).stats, messages)
            // 收尾同时清掉「压缩中」：自动压缩跑在本轮里，本轮被取消时不会再收到 CompactionFinished，
            // 不在这里复位的话压缩中条会一直亮着
            updateTurn(conversationId) { turnFinished(it, messages, stats).copy(compacting = false) }
            runCatching { repository.setStats(conversationId, stats) }
            refreshConversations()
            refreshContext()
            // 这一轮结束 = 不再有进行中的重试链（等待中的那一行会由 TurnView 推导成「已取消」）
            retryMessageIds.remove(conversationId)
            if (stopped) {
                // dsh 的 cancel：收件箱一起清掉（`inbox.clear()`）—— 没被认领的插话 /
                // 通知随这一轮作废，不会在下一轮冒出来补跑一段没人要的对话
                repository.clearInjected(conversationId)
                // 回显跟着一起作废（dsh 的 abandon → retireFailedSubmission）：
                // 库里不会再出现那一行，回显留着就成了一条永远不落地的气泡
                updateTurn(conversationId) { it.copy(pendingSteering = emptyList()) }
            } else {
                // 被停掉的那一轮不接着发（队列留着，下次发送前还在）
                val next = queueOf(conversationId).removeFirstOrNull()
                if (next != null) {
                    updateTurn(conversationId) { it.copy(queuedCount = queueOf(conversationId).size) }
                    send(next.text, next.attachments)
                } else if (repository.hasInjected(conversationId)) {
                    // dsh 的「inbox 非空就不能关轮」：还有没被认领的注入行（多半正好压在
                    // 这一轮收尾那一下到达）就补一轮认领掉
                    continueIfDangling(conversationId)
                }
            }

            // 这一轮跑完了：运行时格子撤掉（内容已落库）、岛收尾（updateTurn 里 sending 已复位 →
            // islandWorkOf 返回 null → 服务进入「已结束」）、别的会话排下的消息轮到它了
            if (runningConversationId == conversationId) runningConversationId = null
            liveRuns.remove(conversationId)
            publishIsland(conversationId)
            flushQueue(conversationId)
        }
    }
    /**
     * 会话日志的 trace（android.util.Log）：**只能在 ViewModel 这一侧** ——
     * 纯投影文件里出现它，单测就会撞上 "Method d in android.util.Log not mocked"。
     * 顺序保持在读库之前：日志先落地，界面再重读。
     */
    private fun traceAppended(appended: com.adsh.app.core.session.SessionEvent) {
        when (val body = appended.body) {
            is com.adsh.app.core.session.SessionBody.ToolCall ->
                trace("tool+", body.name + " " + body.callId)
            is com.adsh.app.core.session.SessionBody.ToolResult ->
                trace(
                    "tool-",
                    body.name + " err=" + body.isError + " out=" + body.output.length,
                )
            is com.adsh.app.core.session.SessionBody.Step ->
                // 一步 assistant 输出定稿（dsh 的 assistant 步）
                trace("step+", "text=" + body.text.length + " think=" + body.reasoning.length)
            is com.adsh.app.core.session.SessionBody.PtcDispatchStart ->
                trace("sub+", body.sub.name + " " + body.sub.id)
            is com.adsh.app.core.session.SessionBody.PtcDispatch ->
                trace("sub-", body.sub.name + " " + body.sub.id + " ok=" + body.sub.ok)
        }
    }


    /**
     * 打断（dsh 的停止）：立刻复位发送态并落一条「已停止」的助手消息。
     *
     * **只 cancel 协程是不够的**：界面要等 finally 才复位，而被取消的协程在 finally 里的
     * 第一个挂起点就抛取消，sending 永远停在 true，按钮卡死。所以这里先把发送态复位，
     * 再等 AgentLoop 用 NonCancellable 把已生成的内容落库。
     *
     * **只复位 `sending`，不动流式正文 / 思考 / 工具行**：那些内容还要等 AgentLoop 落成
     * 一条 interrupted 的助手消息（几百毫秒）。这里若顺手清掉，界面会出现「思考行/正文先消失、
     * 过一会儿又从库里长回来」——展开的思考内容尤其明显（用户报的「终止时画面闪烁」）。
     * `liveTurnId` 保持不动，于是 `buildChatItems` 仍然把这段流式内容挂在这一轮上；
     * 落库完成时 [startTurn] 的 finally 用**一次** state 更新同时换上库里的行并清掉流式内容，
     * 两者内容一致，画面不会闪。
     */
    fun cancel() {
        val job = sendJob
        sendJob = null
        // 要停的可能是**另一条会话**在跑的那一轮（用户切走了再按停止）——按跑着的那条路由
        val owner = runningConversationId ?: _state.value.conversationId
        // 被停掉的那一轮里插进来的消息不会被接：收件箱在 startTurn 的收尾里一起清掉
        // （dsh 的 cancel → inbox.clear()）。
        if (job == null) {
            if (owner != null) updateTurn(owner) { it.copy(sending = false) }
            return
        }
        // **立刻**把运行中的指示全部停下（工具行 / 子调用 / 思考行的扫光）：
        // AgentLoop 那边还在收手（阻塞中的流读与 bash 已经改成可中断，见 LlmClient 与
        // TermuxRuntime 的取消路径，正常是几十毫秒），但界面不能跟着继续转 ——
        // 用户按下停止时看到的第一件事必须是「它停了」。库里的 interrupted 行随后替换掉这些
        // 流式行（内容一致，不闪）。
        val stoppedAt = System.currentTimeMillis()
        if (owner != null) updateTurn(owner) { current ->
            current.copy(
                sending = false,
                reasoningRunning = false,
                toolArgsFlowing = false,
                // 停止：把还在跑的调用与子行就地标成「已结束」（不再有新的 append，fold 不会被覆盖）
                liveTurn = current.liveTurn.copy(
                    calls = current.liveTurn.calls.map {
                        if (it.running) it.copy(finishedAt = stoppedAt) else it
                    },
                    subCalls = current.liveTurn.subCalls.map {
                        if (it.running) it.copy(running = false) else it
                    },
                ),
            )
        }
        viewModelScope.launch {
            job.cancelAndJoin()
            // 到这里 AgentLoop 的收尾（NonCancellable）已经跑完：库里那条 interrupted 消息就位、
            // startTurn 的 finally 也把流式内容清干净了。这里只补一次「库为准」的消息快照，
            // 万一 finally 与这次读有先后差，也不会把内容清空。
            val conversationId = owner ?: return@launch
            // 先读库（挂起），再写状态：updateTurn 是普通函数，里面不能调挂起函数
            val fresh = repository.messages(conversationId)
            updateTurn(conversationId) { it.copy(messages = fresh) }
        }
    }

    // ------------------------------------------------------------------ 模型请求重试（会话流里的一行）

    /** 当前重试链落在库里的消息行 id（会话 → 行 id）。新 attempt 原地更新同一行，不堆行。 */
    private val retryMessageIds = HashMap<Long, Long>()

    /**
     * dsh 的 `llm/retry`：先在库里落一行（role = "retry"，只给界面看、不进模型请求），
     * 同一阶段内的后续 attempt 更新同一行 —— 界面上永远只有一行（dsh 的 model-retry 节点）。
     */
    private suspend fun persistRetry(conversationId: Long, info: RetryInfo) {
        val encoded = RetryInfoCodec.encode(info)
        val existing = retryMessageIds[conversationId]
        if (existing == null) {
            retryMessageIds[conversationId] = repository.addMessage(
                conversationId = conversationId,
                role = "retry",
                content = encoded,
            )
        } else {
            repository.setMessageContent(existing, encoded)
        }
    }

    /** dsh 的 `llm/retry-started`：同一行原地切成「已重试模型请求」（行保留到这一轮结束）。 */
    private suspend fun markRetryStarted(conversationId: Long) {
        val id = retryMessageIds[conversationId] ?: return
        val row = repository.messages(conversationId).firstOrNull { it.id == id } ?: return
        val info = RetryInfoCodec.decode(row.content) ?: return
        repository.setMessageContent(id, RetryInfoCodec.encode(info.copy(started = true)))
    }

    data class WorkspaceInfo(
        val form: com.adsh.app.core.workspace.WorkspaceForm,
        val path: String?,
        val prefix: String,
        /** bootstrap 最近一次失败原因（成功则为 null）。没有它，界面只能显示「未安装」。 */
        val bootstrapError: String? = null,
    )

    private val _workspaceInfo = MutableStateFlow(
        WorkspaceInfo(
            form = com.adsh.app.core.workspace.WorkspaceForm.PRIVATE,
            path = null,
            prefix = runtime.prefix.absolutePath,
        )
    )
    val workspaceInfo: StateFlow<WorkspaceInfo> = _workspaceInfo.asStateFlow()

    val settingsStore: SettingsStore get() = settings

    val bashPath: String get() = runtime.bashPath

    /**
     * 终端页的启动描述（前缀 + bash -i + 环境）。PS1 由终端页自己补：
     * bash 的 \s 取 argv[0] 的基名，直接起 libbash.so 会显示成 libbash.so-5.3$。
     *
     * 工作区跟着**当前会话**走（与 AgentLoop 的 cwd 同一个来源）：终端里 `$ADSH_WORKSPACE`
     * 必须与模型看到的那一个一致，否则「把命令贴进终端里再跑一遍」会跑到别的地方去。
     */
    val shellLaunch: com.adsh.app.runtime.termux.ShellLaunch
        get() = runtime.shellLaunch(
            workspaceRoot = conversationWorkspacePath()?.let { java.io.File(it) },
        )

    private fun refreshWorkspaceInfo() {
        val workspace = workspaces.current()
        _workspaceInfo.value = WorkspaceInfo(
            form = workspace.form,
            path = if (workspace.form == com.adsh.app.core.workspace.WorkspaceForm.PRIVATE) null else workspace.root.absolutePath,
            prefix = runtime.prefix.absolutePath,
            bootstrapError = com.adsh.app.runtime.termux.BootstrapStatus.error,
        )
    }

    /** 待用户回答的提问（来自 ask_user_question） */
    val question = com.adsh.app.seam.tool.UserQuestionChannel.pending

    /** dsh 的 answers 载荷：每题一条 { id, selected[], custom? }（被跳过的题 selected 为空） */
    fun answerQuestion(answers: List<com.adsh.app.seam.tool.Answer>) {
        if (question.value == null) return
        com.adsh.app.seam.tool.UserQuestionChannel.answer(answers)
    }

    fun skipQuestion() {
        com.adsh.app.seam.tool.UserQuestionChannel.skip()
    }

    /**
     * 待审批的越权请求（来自工具层的 sandbox 升级，dsh 的 ctx.approval.request）。
     * 失败关闭：没人应答就是 unavailable，那次调用什么都不执行。
     */
    val approval = com.adsh.app.seam.tool.ApprovalChannel.pending

    /** 模型菜单里每个提供方的余额（providerId → 状态）；只有 DeepSeek 系会填 */
    data class BalanceState(
        val loading: Boolean = false,
        /** 展示文案（例如 ¥19.06）；空 = 还没拿到 */
        val text: String = "",
        /** 失败原因（界面不显示错误正文，只表示这次没拿到） */
        val error: String = "",
    )

    private val _balances = kotlinx.coroutines.flow.MutableStateFlow<Map<String, BalanceState>>(emptyMap())
    val balances: kotlinx.coroutines.flow.StateFlow<Map<String, BalanceState>> = _balances

    /**
     * 刷新余额：模型菜单每次打开时调一次（用户要求「每次点开时自动刷新」）。
     * 拉取在 IO 线程上做，逐个提供方更新 —— 先到的先显示，失败只留空不弹错。
     */
    fun refreshBalances() {
        val targets = settings.providers.filter {
            com.adsh.app.core.data.BalanceApi.supports(it) && it.apiKey.isNotBlank()
        }
        if (targets.isEmpty()) return
        _balances.update { current ->
            current + targets.associate { provider ->
                // 沿用上一次的数字，刷新期间不闪
                provider.id to BalanceState(loading = true, text = current[provider.id]?.text.orEmpty())
            }
        }
        viewModelScope.launch {
            targets.forEach { provider ->
                val state = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { com.adsh.app.core.data.BalanceApi.fetch(provider) }.fold(
                        onSuccess = { BalanceState(text = it.label) },
                        onFailure = { BalanceState(error = it.message ?: "balance unavailable") },
                    )
                }
                _balances.update { it + (provider.id to state) }
            }
        }
    }

    /** dsh 的 allowed-once：只放行这一次调用 */
    fun allowApprovalOnce() {
        com.adsh.app.seam.tool.ApprovalChannel.allowOnce()
    }


    /** dsh 的 rejected：拒绝这次升级（模型会收到「user rejected」并停手） */
    fun rejectApproval() {
        com.adsh.app.seam.tool.ApprovalChannel.reject()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * 重算上下文占用（dsh 的 ContextMeter），工作区 / 提示词 / 消息变化后调用。**必须在后台线程算**：
     * 逐条消息估 token 与装配整段系统提示词（`PromptAssembler.buildParts` 还要读指令文件链）都按会话
     * 长度线性增长，放在调用方线程上会让「发消息 / 收尾 / 切会话」各带一次主线程扫描。
     */
    private suspend fun refreshContext() {
        val messages = _state.value.messages
        val planMode = _state.value.planMode
        val usage = withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 只算真的会上 wire 的行（见 core/agent/ContextTokens.kt）：sysprompt 那一条就是下面
            // system 那一栏的内容，不能再算一遍。
            val messageTokens = com.adsh.app.core.agent.contextMessageTokens(messages)
            val systemTokens = runCatching {
                estimateTokens(
                    com.adsh.app.core.agent.PromptAssembler.buildParts(
                        workspace = workspaces.current(),
                        extraSuffix = settings.systemPromptSuffix,
                        filePolicy = filePolicyOf(settings.permission),
                        planMode = planMode,
                    ).system
                )
            }.getOrDefault(0L)
            // dsh 的 estimateToolsTokens：wire 上真正发出去的 tools 数组的 JSON 体积 / 4 + 4。
            // PTC 下 wire 上只有 run_code，所以这一栏很小；其余工具的声明在系统提示词的 tools:sdk 段里，
            // 算在「系统提示词」那一栏（dsh 也是这么分的）。
            // dsh 的 CHARS_PER_TOKEN = 4、BLOCK_OVERHEAD = 4
            val wireToolsJson = "[" + com.adsh.app.core.tools.RunCodeTool.wireSchema.toString() + "]"
            val toolTokens = (wireToolsJson.length / 4 + 4).toLong()
            ContextUsage(
                system = systemTokens,
                // wire 上的 tool schema（PTC 只有 run_code）；SDK 声明在 system 那一栏里
                tools = toolTokens,
                messages = messageTokens,
                window = settings.contextWindow,
            )
        }
        _state.update { it.copy(context = usage) }
    }

    /**
     * 只重算上下文占用里的**消息**那一栏：每落一步调用一次（dsh 的 contextPressure 投影跟着会话
     * 状态走），所以必须便宜 —— 不装配系统提示词、不读指令文件链（那些只在工作区 / 策略 / 附录
     * 变化时有意义，走 [refreshContext]）。
     */
    private suspend fun refreshMessageTokens() {
        val messages = _state.value.messages
        val messageTokens = withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.adsh.app.core.agent.contextMessageTokens(messages)
        }
        _state.update { it.copy(context = it.context.copy(messages = messageTokens)) }
    }

    /** 模型选择（dsh 的 ModelSelect：按提供方分组，选中同时切换提供方） */
    fun setModel(model: String, providerId: String? = null) {
        if (providerId != null && providerId != settings.providerId) settings.providerId = providerId
        settings.model = model
        // 推理等级是逐模型的：换模型之后已选的等级可能根本不在新模型的菜单里
        // （dsh 选中模型时会顺手把等级写成该模型的 defaultEffort）。ADSH 的存法是全局一个值，
        // 这里只在不被支持时退掉它 —— 菜单上勾不中的等级留在设置里，界面就会出现「没有值」。
        // 退掉之后落到**该模型的默认档**：DeepSeek 是 high，其他模型是空串（菜单里的 Default）。
        val modelLevels = com.adsh.app.core.data.Reasoning.levelsFor(settings.providerRoute, model)
        if (modelLevels.none { it.id == settings.reasoningEffort }) {
            settings.reasoningEffort =
                com.adsh.app.core.data.Reasoning.defaultEffortFor(settings.providerRoute, model).orEmpty()
        }
        _state.update {
            it.copy(
                modelLabel = model,
                providerName = settings.currentProvider().displayName,
                providerId = settings.providerId,
                reasoningEffort = settings.reasoningEffort,
            )
        }
    }

    /**
     * 模型菜单的数据：每个提供方一组（dsh 的 ModelSelect 按提供方分组，
     * 组标题就是提供方 displayName）。
     */
    data class ModelGroup(val providerId: String, val providerName: String, val models: List<String>)

    val availableModelGroups: List<ModelGroup>
        get() = settings.providers.map { provider ->
            ModelGroup(
                providerId = provider.id,
                providerName = provider.displayName,
                models = provider.models.map { it.id },
            )
        }

    /**
     * 推理等级菜单的行（dsh 的模型菜单子页）：
     * **逐模型**取该模型支持的等级名（dsh 里 DeepSeek 是 Off/Low/High/Max、pi-ai 那边是
     * 这个模型自己那一套），第一行是 dsh 的「提供方默认」（Default）。
     * 空串 = 不指定，off 会走 thinking=disabled；模型没有等级可选时整表为空。
     */
    val availableEfforts: List<Pair<String, String>>
        get() = com.adsh.app.core.data.Reasoning.menuOptions(settings.providerRoute, settings.model)

    val permission: String get() = settings.permission
    val reasoningEffort: String get() = settings.reasoningEffort

    fun setPermission(id: String) {
        settings.permission = id
        _state.update { it.copy(permission = id) }
    }

    /** 外观：浅色 / 深色 / 跟随系统（dsh 的 AppearanceRow 三个立方） */
    fun setThemePreference(id: String) {
        settings.themePreference = id
        _state.update { it.copy(themePreference = id) }
    }

    /** 会话内容字号（dsh 的 FontSizeRow 步进器） */
    fun setContentFontSize(px: Int) {
        settings.contentFontSize = px
        _state.update { it.copy(contentFontSize = settings.contentFontSize) }
    }

    /** 对话显示：标准 / 紧凑（dsh 的 TranscriptViewRow） */
    fun setTranscriptView(id: String) {
        settings.transcriptView = id
        _state.update { it.copy(transcriptView = id) }
    }

    /** 繁忙时的发送行为：排队发送 / 插话发送（dsh 的 EnterBehaviorRow） */
    fun setBusyEnter(id: String) {
        settings.busyEnter = id
        // 已排队的那几条照旧发出去（模式只管「之后」按下的发送）
        _state.update { it.copy(busyEnter = id) }
    }

    fun setEffort(id: String) {
        settings.reasoningEffort = id
        // 读回来而不是直接用 id：DeepSeek 模型没有「提供方默认」档，写空串会被归一成 high
        _state.update { it.copy(reasoningEffort = settings.reasoningEffort) }
    }

    /** dsh 的 /plan：进入或退出计划模式（只影响提示词与占位文案，不改动文件） */
    fun togglePlan() = setPlan(!_state.value.planMode)

    /** dsh 的 `/plan` / `/plan off`：显式开关计划模式 */
    fun setPlan(enable: Boolean) {
        val id = _state.value.conversationId ?: return
        if (_state.value.planMode == enable) {
            return
        }
        // **先改内存状态、再落库**：`/plan 写一个页面` 是「开关 + 立即发送」两件事，
        // 发送协程下一次装配上下文时就要读到新状态；等 DB 写回来才改状态的话，那一条消息会
        // 在「还没进计划模式」的旧状态下装配（plan:policy 快照整轮不注入）。
        _state.update {
            it.copy(
                planMode = enable,
                            )
        }
        viewModelScope.launch {
            repository.setPlanMode(id, enable)
            refreshContext()
        }
    }

    /**
     * dsh 的 /compact：把较早的对话压缩成一份检查点摘要，只保留最近几条原文。
     *
     * 业务全在 [CompactionService]（区间选择 / 摘要请求 / 历史替换）；这里只维护界面状态并重读会话。
     * 失败文案保持原有两条（摘要为空 / 摘要请求失败），前缀一律 "compaction failed: "。
     */
    fun compact() {
        val conversationId = _state.value.conversationId ?: return
        if (_state.value.sending || _state.value.compacting) return
        viewModelScope.launch {
            _state.update { it.copy(compacting = true, error = null) }
            try {
                compactor.compact(
                    conversationId = conversationId,
                    window = settings.contextWindow,
                    planMode = _state.value.planMode,
                    trigger = CompactionTrigger.Manual,
                )
                val messages = repository.messages(conversationId)
                _state.update { it.copy(messages = messages) }
                refreshContext()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                _state.update { it.copy(error = "compaction failed: " + (t.message ?: t::class.java.simpleName)) }
            } finally {
                _state.update { it.copy(compacting = false) }
            }
        }
    }

    /** 移除一个待发附件 */
    fun removeAttachment(path: String) {
        _state.update { it.copy(pendingAttachments = it.pendingAttachments - path) }
    }

    /**
     * 这个会话的 cwd（dsh 的 session workspace）：会话自己的工作区优先，
     * 没分组时回落到设置里绑定的那个。
     *
     * 它也决定 `read_image` 工具图片的落点（`.adsh/attachments/<会话 id>`）：必须和**工具**用同一个根，
     * 不能只看 _state.workspacePath（那是「兜底工作区」，不含会话自己的归属）。
     * 用户导入的附件**不在这里** —— 它们在应用私有的附件仓库里（内容寻址、只读），句柄带的是绝对路径。
     */
    private fun conversationWorkspacePath(): String? =
        _state.value.workspaceId
            ?.let { id -> _workspaceList.value.firstOrNull { it.id == id }?.path }
            ?: _state.value.workspacePath

    /**
     * 导入附件：收进应用私有的附件仓库（[com.adsh.app.core.data.AttachmentStore]，内容寻址、只读），
     * 成功后挂到待发附件。**首页（还没有会话）也能导入**：dsh 的 composer 在第一条消息前就有附件条，
     * ADSH 的仓库不依赖会话，发送时由 [send] 把这一批显式带进新会话。
     */
    fun importAttachments(uris: List<android.net.Uri>): ImportResult {
        val result = importAttachments(context = getApplication(), uris = uris)
        if (result.paths.isNotEmpty()) {
            _state.update { it.copy(pendingAttachments = (it.pendingAttachments + result.paths).distinct()) }
        }
        return result
    }

    init {
        // 必须放在类末尾：Kotlin 按声明顺序初始化，Main.immediate 的 launch 会同步执行到首个挂起点。
        // 工具并发闸门是**每轮一份**（见 ToolConcurrency）：上限跟着每一次子调用的 ToolContext 走，
        // 不在这里做全局初始化。
        // 持久化设置**同步**回填：首帧就要用对主题 / 字号 / 权限（bootstrap 是异步的，晚一帧就会
        // 先画默认值再变，切主题时尤其明显）。
        _state.update {
            it.copy(
                modelLabel = settings.model,
                providerName = settings.currentProvider().displayName,
                providerId = settings.providerId,
                mockMode = settings.providerConfig().mock,
                themePreference = settings.themePreference,
                contentFontSize = settings.contentFontSize,
                transcriptView = settings.transcriptView,
                busyEnter = settings.busyEnter,
                permission = settings.permission,
                reasoningEffort = settings.reasoningEffort,
            )
        }
        warmUpRuntime()
        trackLastWorkspace()
        trackLastConversation()
        viewModelScope.launch {
            try {
                bootstrap()
            } finally {
                // 失败也要放行界面（否则一直停在空底上）；真正的错误由 bootstrap 自己报。
                _state.update { it.copy(booted = true) }
            }
        }
        viewModelScope.launch { refreshContext() }
    }

    companion object {
        /**
         * 切会话时等正文读出来的上限（见 [switchConversation]）：正常 1~5ms，
         * 库慢到超过它就先把抽屉收起来，正文晚一帧到。
         */
        private const val SWITCH_CONTENT_WAIT_MS = 150L

        /** bootstrap（Termux 前缀）安装进度的 logcat 标签：adb logcat -s ADSH-bootstrap */
        const val BOOTSTRAP_TAG = "ADSH-bootstrap"

        /** 会话搜索最多列出多少条结果（超出时提示「仅显示前 N 条结果」） */
        const val SEARCH_LIMIT = 20


    }

    /**
     * 灵动岛：界面状态 → 岛的那一帧（见 IslandStatus.kt 的 [islandWorkOf]）。
     *
     * 放在**类的最末尾**：Kotlin 的 init 块与属性初始化按声明顺序执行，而这里要读的
     * [question] / [approval] 是类中部才声明的 —— 放到前面会读到还没初始化的 null。
     *
     * 实现侧（组合根的 IslandController.sync）顺带负责「从没有在跑变成有在跑」那一下把前台服务拉起来：
     * 保活只在 agent 干活期间，用户口径是「干完一起停」（收尾在 IslandService 里）。
     */
    init {
        // 岛：跟某一轮有关的状态写入全部走 updateTurn（里面调 publishIsland）；这里只管
        // 「等你回答」那两格的变化（提问 / 审批来了或走了）
        viewModelScope.launch {
            combine(question, approval) { asking, approving -> islandWaitingOf(asking, approving) }
                .distinctUntilChanged()
                .collect { runningConversationId?.let { id -> publishIsland(id) } }
        }
        // 岛上的「停止」（通知那颗按钮 / 媒体卡的暂停）= 与输入框右下角那个停止键同一件事
        viewModelScope.launch {
            islandStopRequests.collect { cancel() }
        }
    }
}
