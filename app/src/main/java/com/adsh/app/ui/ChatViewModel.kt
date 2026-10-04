package com.adsh.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adsh.app.core.agent.AgentLoop
import com.adsh.app.core.agent.describeAttachments
import com.adsh.app.core.llm.collectText
import com.adsh.app.core.data.generateSessionTitleIfNeeded
import com.adsh.app.core.agent.encodeAttachments
import com.adsh.app.core.data.AdshDatabase
import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.data.SettingsStore
import com.adsh.app.core.llm.ChatEvent
import com.adsh.app.core.llm.LlmClient
import com.adsh.app.core.llm.SessionStats
import com.adsh.app.core.workspace.WorkspaceManager
import com.adsh.app.island.IslandBus
import com.adsh.app.island.IslandController
import com.adsh.app.island.islandWaitingOf
import com.adsh.app.island.islandWorkOf
import com.adsh.app.runtime.termux.TermuxRuntime
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class ChatUiState(
    val conversationId: Long? = null,
    val messages: List<MessageEntity> = emptyList(),
    val streaming: String = "",
    val reasoning: String = "",
    /**
     * 本步最后流出来的东西**是不是思考**（dsh 的 ReasoningRow：`running = streaming && i === last`）。
     *
     * 它决定思考行的形态：运行中 → 摘要跟着最后一行滚动 + 扫光；一旦结束 → 立刻变回**第一行**
     * 摘要。结束的时刻是「模型开始写正文或工具调用」（`ChatEvent.Delta` / `ChatEvent.ToolCallDelta`），
     * **不是**「工具行出现」—— 后者要等这一步落库，用户看到的就是「思考完不动，等工具蹦出来才一起变」。
     */
    val reasoningRunning: Boolean = false,
    /**
     * 模型是不是已经在写**工具调用的参数**了（第 105 轮）。
     *
     * 一步的输出顺序是「思考 → 正文 → tool_calls 的参数」（参数可能很长：一段 run_code 程序
     * 就是几千个字符）。参数一开始流，这一段的正文就不会再长 —— 于是界面可以**立刻把正文的
     * 平滑显现补齐**，不必按采样节拍把最后那一截滴完。少了它，用户看到的是
     * 「agent 都开始调用工具了，输出才结束」（第 105 轮第 1 条）。
     *
     * 置位：`ToolCallDelta`；复位：正文增量（`Delta`，说明这一步又说回正文了）、
     * 新的工具调用开始（日志里的 `ToolCall`）、一步定稿（日志里的 `Step`）、轮首与轮尾。
     */
    val toolArgsFlowing: Boolean = false,
    val sending: Boolean = false,
    /**
     * 正在生成的那一轮（它的用户消息 id）；null = 没有正在生成的一轮。
     * 界面据此判断「哪一轮是活的」——不能只看 [sending]：排队消息发出、用户消息还没落库的
     * 那一两帧里，只看 sending 会把上一轮误判成还在跑（见 buildChatItems 的注释）。
     */
    val liveTurnId: Long? = null,
    val error: String? = null,
    val workspacePath: String? = null,
    /** 当前会话所属工作区（dsh 侧栏的树；null = 未分组） */
    val workspaceId: Long? = null,
    val modelLabel: String = "",
    /** 提供方展示名（dsh 的 provider displayName）：模型菜单的分组标题 */
    val providerName: String = "DeepSeek",
    /** 当前提供方 id（dsh 的 agent-default-model.provider） */
    val providerId: String = com.adsh.app.core.data.BuiltInProviders.DEEPSEEK_ID,
    val mockMode: Boolean = false,
    /**
     * 本轮工具族的**会话日志副本**（dsh 的 `session/event` 累积）。
     *
     * 界面形态由它折叠出来（[foldLiveTurn]），不再是「实时状态 + 落库状态两套对账」——
     * 这是第 109 轮重写的核心：**一份日志，一个 fold**。
     */
    val turnEvents: List<com.adsh.app.core.session.SessionEvent> = emptyList(),
    /** [turnEvents] 折叠出来的界面形态（工具行 / 子调用 / 谁的轨迹） */
    val liveTurn: LiveTurn = LiveTurn(),
    /** 会话统计（对齐 dsh 状态栏：轮/步、tps、token、缓存命中） */
    val stats: SessionStats = SessionStats(),
    val permission: String = com.adsh.app.core.data.SettingsStore.PERMISSION_FULL_ACCESS,
    val reasoningEffort: String = "",
    /** 上下文占用（dsh 的 ContextMeter）：系统提示词 / 工具定义 / 对话消息 + 窗口上限 */
    val context: ContextUsage = ContextUsage(),
    /** dsh 的 /plan：计划模式（注入 plan 提示词段 + 输入框占位文案变化） */
    val planMode: Boolean = false,
    /** 已导入、待随下一条消息发出的附件（绝对路径；会话删除时一起删除） */
    val pendingAttachments: List<String> = emptyList(),
    /** dsh 的 /compact：压缩进行中 */
    val compacting: Boolean = false,
    /** 本轮开始的时间（dsh 的 TurnStatus：15s 后开始显示用时） */
    val runStartedAt: Long = 0,
    /** 空白会话（一条消息都没有）的 id：侧栏只显示其中「正好是当前会话」的那一条 */
    val blankConversationIds: Set<Long> = emptySet(),
    /** 外观（dsh 的 ui-theme.preference：light / dark / system） */
    val themePreference: String = SettingsStore.THEME_SYSTEM,
    /** 会话内容字号（dsh 的 ui-theme.fontSize，12..17） */
    val contentFontSize: Int = SettingsStore.DEFAULT_CONTENT_FONT_SIZE,
    /** 对话显示（dsh 的 ui-chat.transcriptView：normal / compact） */
    val transcriptView: String = SettingsStore.TRANSCRIPT_COMPACT,
    /** 繁忙时的发送行为（dsh 的 ui-conversation.busyEnter：queue / steer） */
    val busyEnter: String = SettingsStore.BUSY_QUEUE,
    /** 排队待发的消息条数（排队发送模式下，输入栏右下角显示） */
    val queuedCount: Int = 0,
    /** 掉线重连的可见状态（dsh 的 ConnectionIndicator）：连接条就画在输入框上面 */
    val connection: ConnectionState = ConnectionState.Idle,
)

/**
 * 掉线重连的三态（dsh 的 `ConnectionIndicator`：断线 / 连接中 / 已恢复）。
 *
 * 时序规则也照搬 dsh 的 shell（见 [ConnectionRecovery.CONNECTING_MIN_VISIBLE_MS] /
 * [ConnectionRecovery.RECOVERY_CONFIRMATION_MS]）：「正在重连」至少可见 800ms（更短的一次
 * 看起来就是闪一下），「已恢复」显示 2 秒后自己消失。
 */
sealed interface ConnectionState {
    /** 连接正常 / 没有正在重连的事（界面什么都不画） */
    data object Idle : ConnectionState

    /** 正在按退避重连（第 [attempt] 次）——dsh 的 `connecting`，指示器带点动画 */
    data class Reconnecting(val attempt: Int, val message: String = "") : ConnectionState

    /**
     * **断网挂起**（dsh 的 `disconnected`）：自动重试已暂停，等网络回来。
     *
     * 与 [Reconnecting] 的区别就是 dsh 那两个状态的区别：这个在**等**（指示器静态、写着「点此重试」），
     * 那个在**试**（带点动画）。看错这两个状态，用户就分不清「手机没网」和「服务端挂了」。
     */
    data class Disconnected(val message: String = "") : ConnectionState

    /** 刚刚重连上（HTTP 200 已经回来），停留 [ConnectionRecovery.RECOVERY_CONFIRMATION_MS] */
    data class Recovered(val at: Long) : ConnectionState
}

/**
 * 这条连接条是不是**临时态**（重连中 / 断网挂起）—— 轮结束、按停止时都要把它收掉。
 *
 * 「已恢复」不算：它有自己的 2 秒计时收尾（dsh 的 `RECOVERY_CONFIRMATION_MS`），
 * 提前收掉就等于用户永远看不到「已恢复」。
 */
internal fun ConnectionState.isTransient(): Boolean =
    this is ConnectionState.Reconnecting || this is ConnectionState.Disconnected

/**
 * 正在跑的一轮里的工具调用（dsh 的 live tool row）：
 * 开始即出行、运行中带扫光、结束后钉上用时与状态。
 */
data class LiveCall(
    /**
     * 这次顶层调用的**宿主身份**（AgentLoop 的 `EXEC_SEQ`，进程内单调、永不复用）。
     *
     * PTC 子调用的 id 是 `<harnessId>:ptc:<n>`，界面按这个前缀取「自己那几行」——
     * 于是不再需要「这一份轨迹属于谁」的状态，也不需要在下一次工具开始时清空它。
     */
    val harnessId: Long = 0L,
    /** 模型的 tool_call id：只用于给「已落库的那一行」补上输出与状态 */
    val callId: String? = null,
    val name: String,
    val arguments: String,
    val startedAt: Long,
    val output: String? = null,
    val isError: Boolean = false,
    val finishedAt: Long? = null,
) {
    /**
     * 这次调用的 PTC 子行**id 前缀**（`<harnessId>:ptc:`）。
     *
     * 没有身份时返回一个不可能出现的前缀（NUL）—— 返回空串的话 `startsWith("")` 会把**整轮**
     * 所有子行都算成它的。见 [harnessId]。
     */
    fun prefix(): String = if (harnessId == 0L) "\u0000" else harnessId.toString() + ":ptc:"

    val running: Boolean get() = finishedAt == null
    val durationMs: Long? get() = finishedAt?.minus(startedAt)
}

/**
 * token 估算（实现与「哪几行真的会上 wire」都在 core/agent/ContextTokens.kt 里，
 * 那些是纯函数、桌面上单测直接打）。
 */
private fun estimateTokens(text: String): Long = com.adsh.app.core.agent.estimateTokens(text)

// 子调用轨迹的两条状态转移在 ui/LiveSubCalls.kt（纯函数，桌面上直接测）

data class ContextUsage(
    val system: Long = 0,
    val tools: Long = 0,
    val messages: Long = 0,
    val window: Long = 0,
) {
    val used: Long get() = system + tools + messages
    val percent: Int get() = if (window <= 0) 0 else ((used * 100) / window).toInt().coerceIn(0, 100)
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ConversationRepository(AdshDatabase.get(app))
    private val settings = SettingsStore(app)
    private val runtime = TermuxRuntime(app)
    private val workspaces = WorkspaceManager(app, runtime)
    private val agent = AgentLoop(
        LlmClient(configProvider = { settings.providerConfig() }),
        repository,
        settings,
    )
    /** /compact 单独用一条 LLM 通道（不经过 AgentLoop 的工具循环） */
    private val compactLlm = LlmClient(configProvider = { settings.providerConfig() })

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
        _conversations.value = repository.conversations()
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
     * 「退出时所在的工作区」实时落盘（第 103 轮）：状态里每次出现非空的工作区就记下来，
     * 下次启动用它开新会话（[startupWorkspaceId]）。写成独立协程而不是散在各处调用 ——
     * 会话切换 / 工作区切换 / 新会话都只改状态，落盘只有这一处。
     */
    private fun trackLastWorkspace() {
        viewModelScope.launch {
            _state.map { it.workspaceId }.distinctUntilChanged().collect { id ->
                if (id != null) settings.lastWorkspaceId = id
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
                // 环境自检（第 72 轮）：把「内嵌 Termux 走的是哪种 exec 模式」写进 logcat，
                // 只在 debug 构建里跑（release 用户要看就在终端里敲 adsh-env-check）。
                if (com.adsh.app.BuildConfig.DEBUG) {
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
        // **重新进入应用一律开一个新的会话页**（用户第 103 轮口径）：
        // 工作区沿用上次退出时那一个（[SettingsStore.lastWorkspaceId]，随会话切换实时落盘）；
        // 那个工作区在这期间被删掉了就换一个（列表里还有哪个用哪个）；一个都没有就**待绑定**
        // （workspaceId = null，输入框左上角照旧显示「选择工作区」）。
        val targetWorkspace = startupWorkspaceId()
        val id = repository.openOrCreateBlank(targetWorkspace)
        refreshConversations()
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
                // 权限预设与推理等级也必须回填：它们是持久化设置，但界面读的是 state。
                // 之前漏了这两项，于是「通用设置里改成工作区可写 → 重进软件又显示完全权限」
                // （实际生效的一直是 prefs 里的值，只有界面在撒谎）。
                permission = settings.permission,
                reasoningEffort = settings.reasoningEffort,
            )
        }
        openConversation(id)
        refreshContext()
    }

    /**
     * 启动时新会话挂在哪个工作区（用户第 103 轮口径）：
     *  - 上次退出时那一个（[SettingsStore.lastWorkspaceId]）还在 ⇒ 用它；
     *  - 它被删了 ⇒ 列表里还有哪个就用哪个（[repository.workspaces] 的第一条）；
     *  - 一个工作区都没有 ⇒ null（待绑定）。
     *
     * 纯函数放在这里而不是内联：三种情形都能被单测直接钉住。
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
        // 统计随会话持久化：切回来能看到上次的用时/用量
        showConversationFrame(
            id = id,
            messages = messages,
            meta = ConversationFrame(
                workspaceId = conversation?.workspaceId,
                stats = repository.statsOf(id),
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
     *
     * 顺序是固定的三步（第 181 轮定下的口径，见 LiveRunState.kt）：
     *  1. 会话级字段：正文，以及 [meta] 里的工作区 / 统计 / 计划模式（null = 只换正文 ——
     *     缓存命中那条快路径先抢一帧，统计与工作区由随后的 openConversation 带着真值补齐）；
     *  2. 轮内字段整块复位（sending / 流式尾巴 / 工具行 / 重连条 / 队列条数）；
     *  3. 把「这条会话正在跑的那一轮」盖回来（住在 liveRuns 里，跟视图无关 —— 所以复位不会
     *     把在跑的那一轮抹掉，这正是「切走再切回来不许停」的实现）。
     */
    private fun showConversationFrame(id: Long, messages: List<com.adsh.app.core.data.MessageEntity>, meta: ConversationFrame? = null) {
        _state.update { previous ->
            val base = if (meta == null) {
                previous.copy(conversationId = id, messages = messages)
            } else {
                previous.copy(
                    conversationId = id,
                    messages = messages,
                    workspaceId = meta.workspaceId,
                    stats = meta.stats,
                    planMode = meta.planMode,
                )
            }
            base.copy(pendingAttachments = emptyList(), queuedCount = queueOf(id).size)
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
     * 目标工作区 = 当前会话所在的工作区 ?? 最近用过的工作区。
     * connectWorkspace 会复用那个工作区里已有的空白会话，所以停在空白会话上再点一次是「没有反应」的
     * —— 这正是 dsh 的行为，不会堆出一串空会话。
     */
    fun newConversation() {
        val target = _state.value.workspaceId
            ?: conversations.value.firstOrNull { it.workspaceId != null }?.workspaceId
            ?: workspaceList.value.firstOrNull()?.id
        newConversationIn(target)
    }

    /**
     * 在指定工作区里新建会话（dsh 工作区行右侧的 ＋ / 侧栏新会话按钮）。
     *
     * 对齐 dsh 的 connectWorkspace：同一个工作区里已经有**一条消息都没有**的空白会话就复用它，
     * 不再反复建空会话；另外在真实工作区里开了会话之后，未分组的空白占位会话自动消失。
     */
    fun newConversationIn(workspaceId: Long?) {
        viewModelScope.launch {
            val id = repository.openOrCreateBlank(workspaceId)
            refreshConversations()
            openConversation(id)
            _state.update { it.copy(stats = SessionStats()) }
            refreshContext()
        }
    }

    /** dsh 的「添加工作区」/ 设置里的绑定：把手机文件夹绑定成工作区并切过去 */
    fun bindWorkspaceFolder(path: String) {
        viewModelScope.launch {
            val id = repository.addWorkspace(path, File(path).name.ifBlank { path })
            refreshWorkspaceList()
            newConversationIn(id)
        }
    }

    /** 点工作区行：绑定它，并打开它最近的一条会话 */
    fun openWorkspace(workspaceId: Long) {
        viewModelScope.launch {
            val workspace = repository.workspace(workspaceId) ?: return@launch
            bindFolder(workspace.path)
            val target = repository.conversations().firstOrNull { it.workspaceId == workspaceId }
            if (target != null) openConversation(target.id) else newConversationIn(workspaceId)
        }
    }

    /** dsh 的「重命名工作区」 */
    fun renameWorkspace(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.renameWorkspace(id, trimmed)
            refreshWorkspaceList()
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
     * dsh 轮尾的「在新对话中分支」：把这轮之前的历史复制成一条新会话并切过去。
     * dsh 是 forkAt(seq)（从某条消息分叉），这里等价地按消息 id 截断。
     */
    fun branchAt(messageId: Long) {
        val conversationId = _state.value.conversationId ?: return
        if (_state.value.sending) return
        viewModelScope.launch {
            val copy = repository.forkAt(conversationId, messageId) ?: return@launch
            refreshConversations()
            openConversation(copy)
            refreshContext()
        }
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
     *  - 缓存命中：正文**这一帧**就位，返回后抽屉立刻可以收；统计 / 上下文占用随后由协程补
     *    （上下文占用要扫全部正文，按会话长度线性增长 —— 不能挡着收起）。
     *  - 未命中：**先等正文读出来**（正常 1~5ms）再返回；旧实现是「先收起、读完再换」，
     *    于是抽屉滑进来的那几帧里正文整块换掉，用户看到的就是闪一下 / 卡一下。
     */
    suspend fun switchConversation(id: Long) {
        // **不再看 sending**（用户口径，参照 dsh）：正在跑的那一轮在后台接着跑，切会话只是换一下
        // 「看哪一条」—— 它的实时内容一直住在 liveRuns 里（跟视图无关），切回来原样接上。
        if (_state.value.conversationId == id) return
        val cached = repository.cachedMessages(id)
        if (cached != null) {
            // 命中缓存：正文与「那一条自己的实时状态」**在同一帧**换到位 ——
            // 正在跑的那条切回来时，sending / 流式尾巴 / 工具行 / 岛一起亮，中间没有一帧是「空的」。
            // 以前这里只换正文、随后靠 restoreLiveRun 补 —— 而补之前 openConversation 会先复位一次，
            // 复位与补之间那一帧正是用户眼里的「切一下它停了」（第 181 轮修）。
            showConversationFrame(id, cached)
            viewModelScope.launch {
                openConversation(id, knownMessages = cached)
                refreshContext()
            }
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
     * 对齐 dsh 的 archiveSession + clearArchivedCurrent + connectWorkspace：
     *  - 删的不是当前会话：只刷新列表，当前会话不动；
     *  - 删的是当前会话：先落到**同一个工作区**里最近的一条会话；
     *    那里没有别的会话了，就复用/建一条该工作区的空白会话（connectWorkspace），
     *    这样输入框始终有会话可发 —— 不会出现「删完就打不了字」，
     *    也不会凭空多出一个「未分组」分组（旧实现会去未分组建一条空白占位）。
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
            refreshConversations()
            if (wasCurrent) {
                val blanks = repository.blankIds()
                val next = repository.conversations()
                    .firstOrNull { it.workspaceId == workspaceId && it.id != id && it.id !in blanks }
                val target = next?.id ?: repository.openOrCreateBlank(workspaceId)
                refreshConversations()
                openConversation(target)
                refreshContext()
            }
        }
    }

    /**
     * 排队待发的消息（dsh 的 queue：繁忙时按「排队发送」进来的消息，轮结束后按序发出）。
     * 附件跟着这一条一起排队：旧实现只排队正文，轮到它发的时候附件早就被清掉了。
     */
    private data class PendingSend(val text: String, val attachments: List<String>)

    /** 排队待发的消息：**按会话各排各的**（切会话不打断正在跑的那一轮，队列也不能串会话） */
    private val pendingSends = HashMap<Long, ArrayDeque<PendingSend>>()

    private fun queueOf(conversationId: Long): ArrayDeque<PendingSend> =
        pendingSends.getOrPut(conversationId) { ArrayDeque() }

    /** 正在跑的那条会话（同一时刻只有一轮在跑；切会话不打断它，见 [switchConversation]） */
    private var runningConversationId: Long? = null

    /**
     * 每条会话「正在跑的那一轮」的状态登记表 —— 这是「切会话不打断正在跑的那一轮」的支点
     * （用户口径，参照 dsh：实时状态跟着**会话**走，不跟着「现在看哪一条」走）。
     *
     * 规则只有一条：**一轮在跑期间，它的状态永远有一份住在这里**，看没看它都在。
     * [updateTurn] 负责写：看这一条时同一份同时上屏；没看时只落在这里（切回来由
     * [ChatUiState.withLiveRun] 盖到刚读出来的库状态上）。所以这里不需要「切走时抓一帧」——
     * 那个模型在正文缓存命中的快路径上会把正在流的一份覆盖成库快照，就是用户反复报的
     * 「切一下正在跑的会话就停了」（第 181 轮换掉，见 LiveRunState.kt 的文件头）。
     */
    private val liveRuns = HashMap<Long, ChatUiState>()

    /** 某条会话此刻的界面状态：正在看它就读 _state，否则读 [liveRuns]（它还在后台跑） */
    private fun stateOf(conversationId: Long): ChatUiState =
        if (_state.value.conversationId == conversationId) _state.value else liveRuns[conversationId] ?: _state.value

    /**
     * 只改「这条会话」的状态，顺带把这一帧推给灵动岛。
     *
     * 正在跑的那一轮：**两边都写** —— 上屏的那一份与登记表里的那一份必须一致，否则切回来会看到
     * 一个「没在跑」的旧快照（这正是第 181 轮报的那个「切一下它停了」）。其它写入（收尾窗口里
     * 那几次、以及没在看的那条会话）只落在**它自己**身上，不会画到屏幕上正在看的那条上。
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
        IslandController.sync(
            getApplication(),
            islandWorkOf(stateOf(conversationId), islandWaitingOf(question.value, approval.value)),
        )
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
     * 结算通知的车道（dsh-tool-jobs 的完成通知车道）：**只入队**。
     *
     * 注册表是在结算线程上回调的，投递改由一个消费者来做（落库在 IO 上、界面那一半回主线程）——
     * 于是「同一批结算」
     * 一次落库、一次刷新界面、**只决定一次「要不要为它开一轮」**（dsh 的做法：第一条把空闲的
     * owner 唤醒，后面的几条看到 owner 已经在跑，走注入）。以前每条通知各起一个协程、各自
     * `_state.update(messages = 重新读库)`：一次重读就是一次整条对话流的重建，10 个任务同时结算
     * 就是 10 次重建挤在几十毫秒里 —— 用户看到的「明显闪了一下」（第 119 轮）。顺带那个显式串行锁
     * [kotlinx.coroutines.sync.Mutex] 也不需要了：消费者只有一个。
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
        // 消费者跑在 **IO** 上：唤醒支要落库、开轮，注入支要动收件箱的锁；认领那条路
        // （AgentLoop 的 claimInjected）本来也在 IO 上。位置上不再依赖「谁先跑」——
        // 通知现在**到认领那一刻才落库**（见 ConversationRepository.enqueueInjected 的长注释），
        // 所以「结算线程还是主线程」不再影响它在会话里的位置（第 120 轮之前是影响的）。
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
        // 附件不再拼进正文。旧实现把待发附件写成 @路径 追加在正文后面：
        // 气泡里出现一串路径，图片更是永远到不了能收图的模型 —— 现在附件随消息落库
        // （MessageEntity.attachmentsJson），装配请求时才翻译成 dsh 的
        // 文件句柄文本 / 图片句柄 + 图片块（见 core/agent/Attachments.kt）。
        val attachments = attachmentPaths ?: _state.value.pendingAttachments
        val body = text.trim()
        if (body.isEmpty() && attachments.isEmpty()) return
        val conversationId = _state.value.conversationId ?: return
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
     * 插话发送：把消息直接落进历史，正在跑的那一轮会在下一步带上它。
     *
     * 这条消息按 dsh 的 **steering 节点**落库（`name = STEERING`），因此：
     *  - **不动 `liveTurnId`**：它不开一轮新的。旧实现把它当成新一轮的开头，于是正在跑的那一轮
     *    当场被判成「已结束」、流式状态（正文 / 思考 / 运行中的工具行 / PTC 子调用）全部改挂到
     *    这条插话下面 —— 用户看到的就是「工具调用的展示被吞掉，过一会儿又蹦出来」；
     *  - 界面上它排在这一轮的过程条目里（TurnList 的 steering 条目），与 dsh 一样夹在过程之间。
     *
     * 这里**不落库**：消息先进收件箱（[ConversationRepository.enqueueInjected]），到这一步开始时
     * 才被认领 —— 位置一次定死，界面不会再看着它换地方。
     */
    private fun steer(body: String, conversationId: Long, attachmentPaths: List<String> = emptyList()) {
        repository.enqueueInjected(
            conversationId = conversationId,
            role = "user",
            content = body,
            name = ConversationRepository.STEERING,
            attachmentPaths = attachmentPaths,
        )
        _state.update { it.copy(pendingAttachments = emptyList()) }
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
     * 开轮的输入侧（原 startTurn 的前半段）：系统提示词 / 上下文注入落库 → 用户消息落库并上屏 →
     * 这一轮的身份 → 侧栏刷新 → 异步生成标题。
     *
     * 顺序是 dsh 的会话节点顺序（sysprompt / context 先于 user），而且**用户消息必须在这里落库**：
     * [AgentLoop.send] 恒以 persistUser = false 调用，用户行只落这一次（第 120 轮的硬规矩）。
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
                    // 工作区传对象而不是路径：装配器读的就是这个对象（见 AgentLoop.assemblePrompt），
                    // 以前传的路径参数从来没被用过，第一条消息因此装配成「没有绑定工作区」
                    workspace = workspaces.current(),
                    planMode = _state.value.planMode,
                )
            }
        }
        // 用户消息落库并立刻上屏：不再等 AI 的第一个 token 才出现。
        // 附件在这里变成引用（sha256 / 图片尺寸 / MIME），随消息一起存
        if (persistUser) {
            val attachments = describeAttachments(attachmentPaths)
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
        // 第一条消息落库后这条会话就不再是「空白」了：顺手刷新侧栏
        // （标题也从这条消息来，抽屉里的「新会话」当场变成真实标题）
        refreshConversations()
        // dsh 的 session/title：首条用户消息之后异步跑一次小模型生成标题（不阻塞回答）。
        // 触发条件、提示词与兜底都在 core/data/SessionTitle*.kt，这里只把它发出去。
        viewModelScope.launch {
            // 标题这次小调用**要显式关掉思考**：只有 64 个输出 token，会推理的模型（DeepSeek V4）
            // 默认把预算全花在推理上 → content 为空 → 标题永远出不来（用户报的 bug，实测见
            // noThinkFor 的 KDoc）。判据与别的请求同一条（不认识这个字段的路由就不发）。
            val config = settings.providerConfig()
            val noThink = com.adsh.app.core.agent.noThinkFor(config.providerId, config.baseUrl, config.model)
            runCatching {
                generateSessionTitleIfNeeded(compactLlm, repository, settings.model, conversationId, noThink)
            }.onFailure {
                // 这一路以前是静默的：模型只回推理 / 被截断 / 提供方报错，界面上都只是「标题没变」。
                android.util.Log.w("ADSH", "会话标题生成失败（保留兜底标题）", it)
            }
        }
    }

    /**
     * 这一轮的工具上下文（PTC）：工作区 / 终端限额 / 权限 / 模型能力 / 附件落点 / 轮次 / 计划模式。
     *
     * 14 处 settings 与 30 个实参逐字照搬 —— 它是「这一轮能干什么」的唯一事实来源，改它等于改权限语义。
     * `livePermission` 是**实时读取器**：本轮中途改权限，下一次受限调用就按新的判（dsh 的 sandboxPolicy.resolve）。
     */
    private suspend fun buildToolContext(
        conversationId: Long,
        workspacePath: String?,
    ): com.adsh.app.core.tools.ToolContext = com.adsh.app.core.tools.ToolContext(
            workspace = workspaces.current(),
            runtime = runtime,
            webSearchProvider = settings.webSearchProvider,
            webSearchBaseUrl = settings.webSearchBaseUrl,
            webSearchApiKey = settings.webSearchApiKey,
            webSearchMaxUses = settings.webSearchMaxUses,
            maxParallelSubCalls = settings.agentMaxParallel,
            bashTimeoutMs = settings.bashTimeoutMs,
            bashMaxOutputBytes = settings.bashMaxOutputBytes,
            bashMaxTimeoutMs = settings.bashMaxTimeoutMs,
            permission = settings.permission,
            // 受限调用现读一次预设：本轮中途改了权限，下一次 bash / write / edit 就按新的判
            livePermission = { settings.permission },
            // PTC 子调用的实时上报由 **AgentLoop** 接到会话日志上（工具线程 append，
            // 见 ToolContext.onSubCallStart / onSubCall）；界面只收 ChatEvent.Appended。
            // read_image：模型能力闸门 + 图片副本的落点 + 报错里回显的模型 id
            // （dsh 的 assertImageCapableRoute / attachments.saveImage）
            modelId = settings.model,
            imageCapable = settings.modelAcceptsImages(settings.model),
            attachmentDir = workspacePath
                ?.let { java.io.File(it, ".adsh/attachments/" + conversationId) },
            // dsh 的 present 输出带 turn：这一轮是第几轮（用户消息条数；带 name 的不是轮次）
            turn = repository.messages(conversationId).count { isOwnUserMessage(it) },
            // todo_write 的清单是会话级的（dsh 的 session projection「todos」）
            conversationId = conversationId,
            // exit_plan_mode：计划被批准后离开计划模式
            planMode = _state.value.planMode,
            onPlanModeChanged = { active -> setPlan(active) },
    )
    /**
     * 收这一轮的事件流（原 startTurn 里 46 行的 collect 体）。
     *
     * 三条分工别打乱：**掉线重连那两支归 [setConnection]**（它带「最短可见 800ms / 收尾 2s」两个
     * 计时器）；**trace 留在 ViewModel**（[traceAppended] 走 android.util.Log，纯投影文件里放它，
     * 单测会撞 "not mocked"）；其余分支全是 [turnProjected] 的纯投影。
     *
     * Appended 那一支里两处 IO（读库、[refreshMessageTokens]）保持**顺序**调用 —— 改成 launch 会让
     * 状态乱序；而且读库必须发生在 trace 之后、与状态更新同一帧（见那一支的注释）。
     */
    private suspend fun collectTurn(conversationId: Long, events: kotlinx.coroutines.flow.Flow<ChatEvent>) {
        events.collect { event ->
            when (event) {
                // 掉线重连（dsh 的 ConnectionController + ConnectionIndicator）：状态与「最短可见 /
                // 收尾」两个计时器都归 setConnection 管，纯投影里不碰
                is ChatEvent.Reconnecting -> setConnection(
                    conversationId,
                    ConnectionState.Reconnecting(event.attempt, event.message),
                )
                // 断网挂起（dsh 的 disconnected）：不是「正在重连」，是在等网络
                is ChatEvent.Disconnected -> setConnection(
                    conversationId,
                    ConnectionState.Disconnected(event.message),
                )
                is ChatEvent.Reconnected -> setConnection(
                    conversationId,
                    ConnectionState.Recovered(System.currentTimeMillis()),
                )
                // 轮内事件只有这一种（会话日志追加了一条）：工具的开始 / 结算、子调用的开始 /
                // 结算、一步 assistant 输出定稿，全都从这条通道来，界面形态由 fold 得出 ——
                // 归属按 harnessId / 子调用 id 前缀，顺序就是 append 顺序（dsh 的 cell 模型）。
                // 以前是四种事件各带一套对账（三层启发式匹配、owner / parent 归属、33ms 采样），
                // 第 109～111 轮整块删掉了。
                is ChatEvent.Appended -> {
                    val appended = event.event
                    traceAppended(appended)
                    // 一步定稿：**与日志追加在同一次状态更新里**从库里重读 + 清空流式缓冲。
                    // 两件事一起发生，所以「同一段文字既在库行里、又当尾巴挂着」那一帧不存在 ——
                    // 第 111 轮把「拿流式正文与落库正文逐字比对」的去重删掉了，判据只剩这一条。
                    val messages =
                        if (appended.body is com.adsh.app.core.session.SessionBody.Step) {
                            repository.messages(conversationId)
                        } else {
                            null
                        }
                    updateTurn(conversationId) { current -> turnProjected(current, event, messages) { settings.modelLabel() } }
                    if (messages != null) {
                        // 上下文占用跟着这一步立刻重算（第 99 轮，用户点名「只有在一轮对话结束时
                        // 才更新」）：dsh 的 contextPressure 是会话状态每次追加后都变的投影，
                        // 而这里以前只在「发消息 / 一轮收尾 / 切会话」重算，于是整轮里那一圈
                        // 百分比一直不动。只重算 messages 那一栏 —— 系统提示词与 tools 两栏
                        // 不会因为聊天变长而变（它们各自的变化点本来就会调 refreshContext）。
                        refreshMessageTokens()
                    }
                }
                // 收件箱认领（dsh 的 preStep claim）：行已经写进库，重读一次把它们画出来。
                is ChatEvent.InboxClaimed -> {
                    val claimed = repository.messages(conversationId)
                    updateTurn(conversationId) { current -> turnProjected(current, event, claimed) { settings.modelLabel() } }
                }
                // 其余分支（Delta / Reasoning / ToolCallDelta / Stats / StreamReset / Failed / Usage）
                // 都是纯投影，见 [turnProjected]；Usage 由 AgentLoop 的 token 账本消费，这里不动状态
                else -> updateTurn(conversationId) { current -> turnProjected(current, event) { settings.modelLabel() } }
            }
        }
    }

    /**
     * 一轮的收尾（原 startTurn 的 finally 体）。
     *
     * **必须 NonCancellable**：被打断时协程已取消，任何普通挂起点都会立刻再抛取消，
     * 结果就是「按了停止，界面卡在对话中、消息也没保存」（旧实现的 bug）。
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
            updateTurn(conversationId) { turnFinished(it, messages, stats) }
            runCatching { repository.setStats(conversationId, stats) }
            refreshConversations()
            refreshContext()
            // 这一轮结束就不该再挂着「正在重连 / 断网」了（「已恢复」那条由它自己的
            // 2 秒计时收尾，这里不动）
            if (stateOf(conversationId).connection.isTransient()) {
                applyConnection(conversationId, ConnectionState.Idle)
            }
            if (stopped) {
                // dsh 的 cancel：收件箱一起清掉（`inbox.clear()`）—— 没被认领的插话 /
                // 通知随这一轮作废，不会在下一轮冒出来补跑一段没人要的对话
                repository.clearInjected(conversationId)
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
     * 旧实现只 cancel 协程，界面要等协程的 finally 才复位 —— 而被取消的协程在
     * finally 里的第一个挂起点就抛取消，sending 永远停在 true，按钮卡死。
     * 现在先把发送态复位，再等 AgentLoop 用 NonCancellable 把已生成的内容落库。
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
                // 正在重连 / 断网挂起时按停止：连接条也要收掉（否则它会一直挂在输入框上面）
                connection = if (current.connection.isTransient()) {
                    ConnectionState.Idle
                } else {
                    current.connection
                },
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

    // ------------------------------------------------------------------ 掉线重连的可见状态

    /** 「正在重连」是什么时候开始露的（dsh 的 `connectingShownAt`） */
    private var connectingShownAt = 0L
    /** 「已恢复」的收尾计时器 / 「正在重连」的最短可见期的收尾计时器 */
    private var connectionJob: Job? = null

    /**
     * 用户点了重连条：按 dsh 的 `connection.reconnect()` —— 退避序列归零、立刻重发
     * （LlmClient 的退避等待会看到这个信号并马上返回）。
     */
    fun retryConnection() {
        com.adsh.app.core.llm.ManualReconnect.request()
    }

    /**
     * 状态迁移（dsh 的 SettingsRoot）：**「正在重连」至少可见 800ms**（
     * [com.adsh.app.core.llm.ConnectionRecovery.CONNECTING_MIN_VISIBLE_MS]）——比这更短的
     * 一次重连在屏幕上就是「闪一下」，用户会以为界面坏了。所以离开 reconnecting 时若还没露够，
     * 就把目标状态压后到露够为止。
     */
    private fun setConnection(conversationId: Long, next: ConnectionState) {
        val current = stateOf(conversationId).connection
        if (current == next) return
        if (current is ConnectionState.Reconnecting && next !is ConnectionState.Reconnecting) {
            val remaining = com.adsh.app.core.llm.ConnectionRecovery.CONNECTING_MIN_VISIBLE_MS -
                (System.currentTimeMillis() - connectingShownAt)
            if (remaining > 0) {
                connectionJob?.cancel()
                connectionJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(remaining)
                    applyConnection(conversationId, next)
                }
                return
            }
        }
        applyConnection(conversationId, next)
    }

    private fun applyConnection(conversationId: Long, next: ConnectionState) {
        val current = stateOf(conversationId).connection
        connectionJob?.cancel()
        connectionJob = null
        // 一直是 reconnecting 时保留最初的时间戳（dsh 的 connectingShownAt 只在**进入**
        // connecting 时记一次）：否则每次重试都把 800ms 重新计时，指示器永远不消失。
        connectingShownAt = if (next is ConnectionState.Reconnecting) {
            if (current is ConnectionState.Reconnecting && connectingShownAt > 0L) {
                connectingShownAt
            } else {
                System.currentTimeMillis()
            }
        } else {
            0L
        }
        // 按会话路由：正在跑的 A 掉线重连时用户切到 B，重连条不许画到 B 上
        updateTurn(conversationId) { it.copy(connection = next) }
        if (next is ConnectionState.Recovered) {
            // 「已恢复」停留 2 秒后自己消失（dsh 的 RECOVERY_CONFIRMATION_MS）
            connectionJob = viewModelScope.launch {
                kotlinx.coroutines.delay(com.adsh.app.core.llm.ConnectionRecovery.RECOVERY_CONFIRMATION_MS)
                if (stateOf(conversationId).connection === next) {
                    updateTurn(conversationId) { it.copy(connection = ConnectionState.Idle) }
                }
            }
        }
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

    fun refreshWorkspaceInfo() {
        val workspace = workspaces.current()
        _workspaceInfo.value = WorkspaceInfo(
            form = workspace.form,
            path = if (workspace.form == com.adsh.app.core.workspace.WorkspaceForm.PRIVATE) null else workspace.root.absolutePath,
            prefix = runtime.prefix.absolutePath,
            bootstrapError = com.adsh.app.runtime.termux.BootstrapStatus.error,
        )
    }

    /** 待用户回答的提问（来自 ask_user_question） */
    val question = com.adsh.app.core.tools.UserQuestionChannel.pending

    /** dsh 的 answers 载荷：每题一条 { id, selected[], custom? }（被跳过的题 selected 为空） */
    fun answerQuestion(answers: List<com.adsh.app.core.tools.Answer>) {
        if (question.value == null) return
        com.adsh.app.core.tools.UserQuestionChannel.answer(answers)
    }

    fun skipQuestion() {
        com.adsh.app.core.tools.UserQuestionChannel.skip()
    }

    /**
     * 待审批的越权请求（来自工具层的 sandbox 升级，dsh 的 ctx.approval.request）。
     * 失败关闭：没人应答就是 unavailable，那次调用什么都不执行。
     */
    val approval = com.adsh.app.core.tools.ApprovalChannel.pending

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
        com.adsh.app.core.tools.ApprovalChannel.allowOnce()
    }


    /** dsh 的 rejected：拒绝这次升级（模型会收到「user rejected」并停手） */
    fun rejectApproval() {
        com.adsh.app.core.tools.ApprovalChannel.reject()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** 重新估算上下文占用（工作区/提示词/消息变化后调用） */
    /**
     * 重算上下文占用（dsh 的 ContextMeter）。
     *
     * **必须在后台线程算**（第 95 轮）：这里要做两件按会话长度线性增长的事 ——
     * 逐条消息估 token（把全部正文与思考扫一遍），以及装配整段系统提示词
     * （`PromptAssembler.buildParts` 还要读指令文件链）。以前它是普通函数、直接在调用方的
     * 线程上跑，而调用方几乎都是 Main.immediate 的 `viewModelScope.launch` ——
     * 「发消息」「这一轮收尾」「切会话」这几处因此各带一次主线程扫描，
     * 会话越长越明显（用户报的「发送消息时可能卡顿」有它一份）。
     */
    private suspend fun refreshContext() {
        val messages = _state.value.messages
        val planMode = _state.value.planMode
        val usage = withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 只算真的会上 wire 的行（见 core/agent/ContextTokens.kt：sysprompt 那一条就是
            // 下面 system 那一栏的内容，不能再算一遍 —— 用户第 103 轮问的就是这个）
            val messageTokens = com.adsh.app.core.agent.contextMessageTokens(messages)
            val systemTokens = runCatching {
                estimateTokens(
                    com.adsh.app.core.agent.PromptAssembler.buildParts(
                        workspace = workspaces.current(),
                        extraSuffix = settings.systemPromptSuffix,
                        previousWorkspacePath = settings.lastWorkspacePath,
                        filePolicy = com.adsh.app.core.agent.PromptAssembler.filePolicyOf(settings.permission),
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
     * 只重算上下文占用里的**消息**那一栏（第 99 轮）。
     *
     * 每落一步就调用一次（dsh 的 contextPressure 投影跟着会话状态走），所以它必须是便宜的：
     * 不装配系统提示词、不读指令文件链 —— 那两件事只在工作区 / 策略 / 附录变化时才有意义
     * （那些地方调 [refreshContext]）。
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
        // 推理等级是逐模型的：换模型之后原来选的等级可能根本不在新模型的菜单里
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
     * 与 dsh 的 compaction-basic 一致：摘要作为一条 user 消息落在历史最前面，
     * 用 <compacted-summary> 包起来，前面的原文被替换掉（不是删掉就算，而是换成摘要）。
     */
    fun compact() {
        val conversationId = _state.value.conversationId ?: return
        if (_state.value.sending || _state.value.compacting) return
        viewModelScope.launch {
            _state.update { it.copy(compacting = true, error = null) }
            try {
                val history = repository.messages(conversationId)
                // dsh 的 selectCompactableRange：从后往前累计 token，保留约 16% 上下文窗口的尾巴，
                // 并且不把工具结果和它前面的 assistant 调用切开
                val range = compactableRange(history)
                if (range == null) {
                    return@launch
                }
                val (prefix, older, keep) = range
                if (older.isEmpty()) {
                    return@launch
                }
                val summary = summarize(renderTranscript(older))
                if (summary.isBlank()) {
                    _state.update { it.copy(error = "压缩失败：模型没有返回摘要，会话未改动") }
                    return@launch
                }
                val checkpoint = CHECKPOINT_PREAMBLE + "\n\n<compacted-summary>\n" + summary.trim() + "\n</compacted-summary>"
                repository.replaceHistory(conversationId, prefix, keep, checkpoint)
                val messages = repository.messages(conversationId)
                _state.update {
                    it.copy(
                        messages = messages,
                        // dsh 的 /compact 结果文案：Compacted N history items (~T tokens).
                                            )
                }
                refreshContext()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                _state.update { it.copy(error = "压缩失败：" + (t.message ?: t::class.java.simpleName)) }
            } finally {
                _state.update { it.copy(compacting = false) }
            }
        }
    }

    /**
     * dsh 的 selectCompactableRange（dsh-compaction-basic/lib/index.js）：
     *
     *  - 从末尾往前累计 token，累计到「上下文窗口 × DEFAULT_RETAIN_RATIO（0.16）」为止，
     *    这一段原样保留（verbatim tail），前面的整段进摘要；
     *  - 系统提示词 / 上下文注入节点永不在压缩范围里（dsh 的 systemHead 规则）；
     *  - 不切开「assistant 的工具调用 + 它的工具结果」：保留段不能以 tool 行开头。
     *
     * @return (保留在前面的系统节点, 要压缩的历史, 要原样保留的尾巴)，没有可压缩的就返回 null
     */
    private fun compactableRange(
        history: List<MessageEntity>,
    ): Triple<List<MessageEntity>, List<MessageEntity>, List<MessageEntity>>? {
        if (history.isEmpty()) return null
        val window = _state.value.context.window.takeIf { it > 0 } ?: return null
        val retainTokens = (window * 16 / 100).coerceAtLeast(1)
        val floor = history.indexOfFirst { it.role != "sysprompt" && it.role != "context" }
        if (floor < 0) return null

        var accumulated = 0L
        var keepFrom = history.size
        for (index in history.indices.reversed()) {
            if (index < floor) break
            accumulated += estimateTokens(history[index].content)
            keepFrom = index
            if (accumulated >= retainTokens) break
        }
        // 不切开工具对：保留段不能以 tool 行开头
        while (keepFrom < history.size && history[keepFrom].role == "tool") keepFrom++
        if (keepFrom <= floor) return null

        val prefix = history.subList(0, floor)
        val older = history.subList(floor, keepFrom)
        val keep = history.subList(keepFrom, history.size)
        if (older.none { it.role == "user" || it.role == "assistant" }) return null
        return Triple(prefix.toList(), older.toList(), keep.toList())
    }

    /** 把历史渲染成纯文本，交给模型做摘要（工具调用/结果只保留结论，避免噪音） */
    private fun renderTranscript(messages: List<MessageEntity>): String = buildString {
        messages.forEach { message ->
            val role = when (message.role) {
                "user" -> "USER"
                "assistant" -> "ASSISTANT"
                "tool" -> "TOOL RESULT (" + (message.name ?: "tool") + ")"
                else -> message.role.uppercase()
            }
            if (message.content.isBlank()) return@forEach
            appendLine("### " + role)
            appendLine(message.content.take(MAX_TRANSCRIPT_CHARS_PER_MESSAGE))
            appendLine()
        }
    }

    /**
     * 一次性摘要调用（不经过工具循环）。
     *
     * dsh 的 summarizer 复用会话自己的系统提示词，只把摘要指令作为**最后一条 user 消息**
     * 跟在对话后面（这样这次调用是上一个请求的真前缀，能吃到 KV 缓存），
     * 而不是另起一个「摘要引擎」人格。
     */
    private suspend fun summarize(transcript: String): String {
        val config = settings.providerConfig()
        // 同 refreshContext：装配系统提示词要读指令文件链，不能在主线程上做
        val system = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.adsh.app.core.agent.PromptAssembler.buildParts(
                    workspace = workspaces.current(),
                    extraSuffix = settings.systemPromptSuffix,
                    previousWorkspacePath = settings.lastWorkspacePath,
                    filePolicy = com.adsh.app.core.agent.PromptAssembler.filePolicyOf(settings.permission),
                    planMode = _state.value.planMode,
                ).system
            }.getOrDefault("")
        }
        val request = com.adsh.app.core.llm.ChatRequest(
            model = config.model,
            messages = buildList {
                if (system.isNotBlank()) {
                    add(
                        com.adsh.app.core.llm.ChatMessage(
                            role = "system",
                            content = com.adsh.app.core.llm.textContent(system),
                        ),
                    )
                }
                add(
                    com.adsh.app.core.llm.ChatMessage(
                        role = "user",
                        content = com.adsh.app.core.llm.textContent(transcript + "\n---\n\n" + COMPACTION_INSTRUCTION),
                    ),
                )
            },
            stream = true,
        )
        // 收文本的那三条口径（Delta / StreamReset 清空重来 / Failed 抛出）在 llm/collectText.kt，
        // 与标题生成共用一份实现 —— 这里不再各抄一遍
        return compactLlm.collectText(request)
    }

    /** 移除一个待发附件 */
    fun removeAttachment(path: String) {
        _state.update { it.copy(pendingAttachments = it.pendingAttachments - path) }
    }

    /**
     * 这个会话的 cwd（dsh 的 session workspace）：会话自己的工作区优先，
     * 没分组时回落到设置里绑定的那个。
     *
     * 附件落盘必须和**工具**用同一个根：以前 importAttachments 只看 _state.workspacePath
     * （那个是「兜底工作区」，不含会话自己的归属），于是会话自己的 workspace 与它不同时，
     * 附件被写进了另一个目录 —— 模型拿到的路径在工具的工作区之外，read/workdir 一律
     * 「路径越界（不在工作区内）」，等于附件白传。
     */
    private fun conversationWorkspacePath(): String? =
        _state.value.workspaceId
            ?.let { id -> _workspaceList.value.firstOrNull { it.id == id }?.path }
            ?: _state.value.workspacePath

    /** 导入附件：复制到「工作区/.adsh/attachments/<会话 id>/」，随会话删除；成功后挂到待发附件 */
    fun importAttachments(uris: List<android.net.Uri>): ImportResult {
        val conversationId = _state.value.conversationId ?: return ImportResult()
        val result = com.adsh.app.ui.importAttachments(
            context = getApplication(),
            workspacePath = conversationWorkspacePath(),
            conversationId = conversationId,
            uris = uris,
        )
        if (result.paths.isNotEmpty()) {
            _state.update { it.copy(pendingAttachments = (it.pendingAttachments + result.paths).distinct()) }
        }
        return result
    }

    init {
        // 必须放在类末尾：Kotlin 按声明顺序初始化，Main.immediate 的 launch 会同步执行到首个挂起点
        // 工具并发闸门（dsh 的 agent-loop.maxParallelToolCalls）先按设置就位
        com.adsh.app.core.tools.ToolConcurrency.configure(settings.agentMaxParallel)
        warmUpRuntime()
        trackLastWorkspace()
        viewModelScope.launch { bootstrap() }
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

        /** 单条消息进入摘要时最多取多少字符（避免超长工具输出把摘要请求撑爆） */
        private const val MAX_TRANSCRIPT_CHARS_PER_MESSAGE = 6000

        /** dsh compaction-basic 的检查点前言（逐字） */
        private const val CHECKPOINT_PREAMBLE =
            "This is an automatically generated checkpoint condensing an earlier span of the conversation " +
                "to free up context. Treat the captured context as established background and build on it " +
                "without restating it. Continue the task directly from the messages that follow, without " +
                "acknowledging this checkpoint."

        /** dsh compaction-basic 的摘要指令（逐字，含分节结构） */
        private val COMPACTION_INSTRUCTION = listOf(
            "You are now acting as a compaction engine for this AI coding assistant. Condense the conversation " +
                "ABOVE into a structured checkpoint that lets another model resume the work with no loss of " +
                "essential context.",
            "",
            "Output EXACTLY the Markdown structure below: keep every section, in order. Use terse bullets, not " +
                "prose paragraphs. Write \"(none)\" for an empty section — never drop a section.",
            "",
            "## Primary Request and Intent",
            "- [the user's original and evolving goals; quote verbatim where the exact wording matters]",
            "",
            "## Key Technical Concepts",
            "- [technologies, frameworks, patterns, and conventions in play]",
            "",
            "## Files and Code",
            "- [exact path: why it matters, key changes or snippets]",
            "",
            "## Errors and Fixes",
            "- [error: how it was resolved, plus any related user feedback]",
            "",
            "## Pending Jobs",
            "- [explicitly requested work not yet completed]",
            "",
            "## Current Work",
            "- [precisely what was in progress at this checkpoint]",
            "",
            "## Next Step",
            "- [the single next action, directly in line with the most recent request, or \"(none)\"]",
            "",
            "## Critical Context",
            "- [decisions and their rationale, constraints, user preferences, open questions, data needed to continue]",
            "",
            "Rules:",
            "- Write concise engineering prose. Preserve exact file paths, commands, error strings, identifiers, " +
                "numeric values, function signatures, and syntax fragments.",
            "- Capture user feedback and explicit instructions faithfully, especially corrections.",
            "- Do NOT mention this summarization request or that the context was compacted.",
            "- Output only the checkpoint text: do not call any tool or take any other action.",
            "- If the conversation already contains a <compacted-summary> block, it is a PRIOR checkpoint. " +
                "Do not copy it forward verbatim: preserve still-true facts, drop stale ones, and merge newer " +
                "information into a single consolidated summary under the same structure.",
        ).joinToString("\n")

    }

    /**
     * 灵动岛：界面状态 → 岛的那一帧（见 island/IslandStatus.kt 的 [islandWorkOf]）。
     *
     * 放在**类的最末尾**：Kotlin 的 init 块与属性初始化按声明顺序执行，而这里要读的
     * [question] / [approval] 是类中部才声明的 —— 放到前面会读到还没初始化的 null。
     *
     * [IslandController.sync] 顺带负责「从没有在跑变成有在跑」那一下把前台服务拉起来：
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
            IslandBus.stopRequests.collect { cancel() }
        }
    }
}
