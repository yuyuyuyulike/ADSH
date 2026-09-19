package com.adsh.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adsh.app.core.agent.AgentLoop
import com.adsh.app.core.agent.describeAttachments
import com.adsh.app.core.agent.encodeAttachments
import com.adsh.app.core.data.AdshDatabase
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ChatUiState(
    val conversationId: Long? = null,
    val messages: List<MessageEntity> = emptyList(),
    val streaming: String = "",
    val reasoning: String = "",
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
    val toolLog: List<String> = emptyList(),
    /** 本轮正在跑 / 刚跑完的工具调用（流式展示用） */
    val liveCalls: List<LiveCall> = emptyList(),
    /**
     * 正在跑的那次 run_code 里已经完成的子调用（dsh 的 tool/ptc-dispatch）：
     * 程序跑一步就长一行，而不是等整个程序结束一次性蹦出来。
     */
    val liveSubCalls: List<com.adsh.app.core.ptc.SubCall> = emptyList(),
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
)

/**
 * 正在跑的一轮里的工具调用（dsh 的 live tool row）：
 * 开始即出行、运行中带扫光、结束后钉上用时与状态。
 */
data class LiveCall(
    val id: Long,
    /** 模型的 tool_call id：用它把流式行贴到「已落库的那一行」上，而不是再追加一行 */
    val callId: String? = null,
    val name: String,
    val arguments: String,
    val startedAt: Long,
    val output: String? = null,
    val isError: Boolean = false,
    val finishedAt: Long? = null,
) {
    val running: Boolean get() = finishedAt == null
    val durationMs: Long? get() = finishedAt?.minus(startedAt)
}

/** token 估算：CJK 按 1 token/字，其余按 4 字符/token（够用且可解释） */
fun estimateTokens(text: String): Long {
    var cjk = 0
    var other = 0
    text.forEach { ch -> if (ch.code in 0x2E80..0x9FFF || ch.code in 0xF900..0xFAFF) cjk++ else other++ }
    return cjk.toLong() + (other / 4).toLong()
}

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
        val existing = repository.conversations().firstOrNull()
        val id = existing?.id ?: repository.createConversation(workspaceId = repository.workspaces().firstOrNull()?.id)
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
     * 打开一条会话：消息 / 统计 / 计划 / 目标，以及它所属的工作区
     * （dsh 里 cwd 跟着会话走，所以这里顺手把工作区绑定切过去）
     */
    private suspend fun openConversation(id: Long) {
        val conversation = repository.byId(id)
        val workspace = conversation?.workspaceId?.let { repository.workspace(it) }
        if (workspace != null) bindFolder(workspace.path)
        _state.update {
            it.copy(
                conversationId = id,
                messages = repository.messages(id),
                workspaceId = conversation?.workspaceId,
                streaming = "",
                reasoning = "",
                error = null,
                toolLog = emptyList(),
                // 统计随会话持久化：切回来能看到上次的用时/用量
                stats = repository.statsOf(id),
                planMode = conversation?.planMode == true,
                pendingAttachments = emptyList(),
            )
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

    /** 切换会话（侧栏点某条会话） */
    fun switchConversation(id: Long) {
        if (_state.value.conversationId == id || _state.value.sending) return
        viewModelScope.launch {
            openConversation(id)
            refreshContext()
        }
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
            repository.delete(id)
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

    private val pendingSends = ArrayDeque<PendingSend>()

    /** 本轮里发生过一次「插话发送」：收尾时要看这条插话有没有人接（没人接就补一轮） */
    private var steerInjected = false

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
        if (_state.value.sending) {
            if (settings.busyEnter == SettingsStore.BUSY_STEER) {
                steer(body, conversationId, attachments)
            } else {
                pendingSends.addLast(PendingSend(body, attachments))
                _state.update { it.copy(queuedCount = pendingSends.size, pendingAttachments = emptyList()) }
            }
            return
        }
        startTurn(conversationId, body, persistUser = true, attachmentPaths = attachments)
    }

    /** 插话发送：把消息直接落进历史，正在跑的那一轮会在下一步带上它 */
    private fun steer(body: String, conversationId: Long, attachmentPaths: List<String> = emptyList()) {
        steerInjected = true
        viewModelScope.launch {
            repository.addMessage(
                conversationId = conversationId,
                role = "user",
                content = body,
                attachmentsJson = encodeAttachments(describeAttachments(attachmentPaths)),
            )
            val steered = repository.messages(conversationId)
            _state.update {
                it.copy(
                    messages = steered,
                    // 插话消息开启了一轮新的：正在跑的这一轮之后的输出都属于它
                    liveTurnId = steered.lastOrNull { message -> message.role == "user" }?.id,
                    pendingAttachments = emptyList(),
                )
            }
        }
    }

    /** 清空排队发送的消息（dsh 的 queue chip 上的清空） */
    fun clearQueued() {
        pendingSends.clear()
        _state.update { it.copy(queuedCount = 0) }
    }

    /** 这一轮收尾时：插话没人接就补一轮（消息已经在历史里，不再重复落库） */
    private suspend fun continueIfDangling(conversationId: Long) {
        val messages = repository.messages(conversationId)
        val last = messages.lastOrNull() ?: return
        if (last.role != "user") return
        startTurn(conversationId, last.content, persistUser = false)
    }

    private fun startTurn(
        conversationId: Long,
        body: String,
        persistUser: Boolean,
        attachmentPaths: List<String> = emptyList(),
    ) {
        // cwd 跟着会话的工作区走；未分组时回落到设置里绑定的工作区
        // （附件落盘用的是同一个函数，见 conversationWorkspacePath）
        val workspacePath = conversationWorkspacePath()
        var stopped = false

        sendJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    sending = true,
                    // 新一轮还没落库：这一瞬间界面必须把上一轮维持成「已结束」，
                    // 否则上一轮的最终回答会先缩回过程里、等用户消息落库再弹出来
                    liveTurnId = null,
                    error = null,
                    streaming = "",
                    reasoning = "",
                    toolLog = emptyList(),
                    liveCalls = emptyList(),
                    liveSubCalls = emptyList(),
                    pendingAttachments = emptyList(),
                    runStartedAt = System.currentTimeMillis(),
                )
            }
            try {
                // dsh 的会话节点顺序：系统提示词行 / 上下文注入行，然后才是用户消息
                runCatching {
                    agent.recordContext(
                        conversationId = conversationId,
                        workspaceRoot = workspacePath,
                        planMode = repository.byId(conversationId)?.planMode == true,
                    )
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
                _state.update {
                    it.copy(
                        messages = openedMessages,
                        // 这一轮的身份：人类消息或目标轮注入行（dsh 里两者都是 turn 的起点）
                        liveTurnId = openedMessages.lastOrNull { message -> message.role == "user" }?.id,
                    )
                }
                // 第一条消息落库后这条会话就不再是「空白」了：顺手刷新侧栏
                // （标题也从这条消息来，抽屉里的「新会话」当场变成真实标题）
                refreshConversations()
                val toolContext = com.adsh.app.core.tools.ToolContext(
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
                    // PTC 子调用实时上报（工具线程回调，MutableStateFlow 本身线程安全）。
                    // 「开始」先长出一行运行中的子调用（带扫光），「结束」按同一个 id 贴回结果。
                    onSubCallStart = { sub ->
                        _state.update { it.copy(liveSubCalls = it.liveSubCalls + sub) }
                    },
                    onSubCall = { sub ->
                        _state.update { current ->
                            val index = current.liveSubCalls.indexOfLast { it.id.isNotEmpty() && it.id == sub.id }
                            val next = current.liveSubCalls.toMutableList()
                            if (index >= 0) next[index] = sub else next += sub
                            current.copy(liveSubCalls = next)
                        }
                    },
                    // dsh 的 present 输出带 turn：这一轮是第几轮（用户消息条数）
                    turn = repository.messages(conversationId).count { it.role == "user" },
                    // exit_plan_mode：计划被批准后离开计划模式
                    planMode = _state.value.planMode,
                    onPlanModeChanged = { active -> setPlan(active) },
                )
                agent.send(conversationId, body, workspacePath, toolContext, persistUser = false).collect { event ->
                    when (event) {
                        is ChatEvent.Delta -> _state.update { it.copy(streaming = it.streaming + event.text) }
                        is ChatEvent.Reasoning -> _state.update { it.copy(reasoning = it.reasoning + event.text) }
                        // 一步 assistant 输出（含它的工具调用）已经定稿落库：立刻改从库里读。
                        // 流式的正文/思考交给库里那一行，于是下一步的正文永远排在它后面，
                        // 不会把已经跑过的工具行越挤越远（旧实现的「很乱」就是这么来的）。
                        is ChatEvent.StepCommitted -> {
                            val messages = repository.messages(conversationId)
                            _state.update { it.copy(messages = messages, streaming = "", reasoning = "") }
                        }
                        is ChatEvent.ToolStarted -> _state.update {
                            it.copy(
                                toolLog = it.toolLog + ("▶ " + event.name + " " + event.arguments.take(120)),
                                liveCalls = it.liveCalls + LiveCall(
                                    id = it.liveCalls.size + 1L,
                                    callId = event.callId,
                                    name = event.name,
                                    arguments = event.arguments,
                                    startedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                        is ChatEvent.ToolFinished -> {
                            // 工具结果也已经落库：重读一次，那一行的输出/用时/红点直接来自消息
                            val messages = repository.messages(conversationId)
                            _state.update { current ->
                                val exact = event.callId
                                    ?.let { id -> current.liveCalls.indexOfFirst { it.callId == id } }
                                    ?: -1
                                // 没有 id 的老数据退回「最后一个还在跑的」
                                val index = if (exact >= 0) exact else current.liveCalls.indexOfLast { it.running }
                                val calls = current.liveCalls.toMutableList()
                                if (index >= 0) {
                                    val call = calls[index]
                                    if (call.callId != null) {
                                        // 结果已经在消息里了：把这条流式行摘掉，避免同一行出现两次
                                        calls.removeAt(index)
                                    } else {
                                        calls[index] = call.copy(
                                            output = event.output,
                                            isError = event.isError,
                                            finishedAt = System.currentTimeMillis(),
                                        )
                                    }
                                }
                                current.copy(
                                    toolLog = current.toolLog + (
                                        (if (event.isError) "✗ " else "✓ ") + event.name + "：" +
                                            event.output.take(200).replace('\n', ' ')
                                        ),
                                    liveCalls = calls,
                                    liveSubCalls = emptyList(),
                                    messages = messages,
                                )
                            }
                        }
                        is ChatEvent.Stats -> _state.update { it.copy(stats = it.stats + event.stats) }
                        is ChatEvent.Finished -> Unit
                        is ChatEvent.Failed -> _state.update { it.copy(error = event.message) }
                        else -> Unit
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    stopped = true
                    throw t
                }
                _state.update { it.copy(error = t::class.java.simpleName + "：" + (t.message ?: "")) }
            } finally {
                // 收尾必须 NonCancellable：被打断时协程已取消，任何普通挂起点都会立刻再抛取消，
                // 结果就是「按了停止，界面卡在对话中、消息也没保存」（旧实现的 bug）
                withContext(NonCancellable) {
                    val messages = repository.messages(conversationId)
                    // 轮/步由消息推导（每轮上报的 turns 是绝对值，累加会重复计数）；其余统计随会话落库
                    val stats = _state.value.stats.copy(
                        turns = messages.count { it.role == "user" },
                        steps = messages.count { it.role == "tool" },
                    )
                    _state.update {
                        it.copy(
                            sending = false,
                            liveTurnId = null,
                            streaming = "",
                            reasoning = "",
                            liveCalls = emptyList(),
                            liveSubCalls = emptyList(),
                            messages = messages,
                            stats = stats,
                        )
                    }
                    runCatching { repository.setStats(conversationId, stats) }
                    refreshConversations()
                    refreshContext()
                    // 被停掉的那一轮不接着发（队列留着，下次发送前还在）
                    if (!stopped) {
                        val next = pendingSends.removeFirstOrNull()
                        if (next != null) {
                            _state.update { it.copy(queuedCount = pendingSends.size) }
                            send(next.text, next.attachments)
                        } else if (steerInjected) {
                            steerInjected = false
                            continueIfDangling(conversationId)
                        }
                    }
                }
            }
        }
    }

    /**
     * 打断（dsh 的停止）：立刻复位发送态并落一条「已停止」的助手消息。
     *
     * 旧实现只 cancel 协程，界面要等协程的 finally 才复位 —— 而被取消的协程在
     * finally 里的第一个挂起点就抛取消，sending 永远停在 true，按钮卡死。
     * 现在先把状态复位，再等 AgentLoop 用 NonCancellable 把已生成的内容落库。
     */
    fun cancel() {
        val job = sendJob
        sendJob = null
        if (job == null) {
            _state.update { it.copy(sending = false) }
            return
        }
        _state.update { it.copy(sending = false) }
        viewModelScope.launch {
            job.cancelAndJoin()
            val conversationId = _state.value.conversationId ?: return@launch
            _state.update {
                it.copy(
                    messages = repository.messages(conversationId),
                    streaming = "",
                    reasoning = "",
                    liveCalls = emptyList(),
                    liveSubCalls = emptyList(),
                )
            }
        }
    }

    data class WorkspaceInfo(
        val form: com.adsh.app.core.workspace.WorkspaceForm,
        val path: String?,
        val bootstrapReady: Boolean,
        val prefix: String,
        /** bootstrap 最近一次失败原因（成功则为 null）。没有它，界面只能显示「未安装」。 */
        val bootstrapError: String? = null,
    )

    private val _workspaceInfo = MutableStateFlow(
        WorkspaceInfo(
            form = com.adsh.app.core.workspace.WorkspaceForm.PRIVATE,
            path = null,
            bootstrapReady = false,
            prefix = runtime.prefix.absolutePath,
        )
    )
    val workspaceInfo: StateFlow<WorkspaceInfo> = _workspaceInfo.asStateFlow()

    val settingsStore: SettingsStore get() = settings

    val bashPath: String get() = runtime.bashPath

    /**
     * 终端页的启动描述（别名前缀的 argv + 环境）。PS1 由终端页自己补：
     * bash 的 \s 取 argv[0] 的基名，直接起 libbash.so 会显示成 libbash.so-5.3$。
     */
    val shellLaunch: com.adsh.app.runtime.termux.ShellLaunch get() = runtime.shellLaunch()

    fun refreshWorkspaceInfo() {
        val workspace = workspaces.current()
        _workspaceInfo.value = WorkspaceInfo(
            form = workspace.form,
            path = if (workspace.form == com.adsh.app.core.workspace.WorkspaceForm.PRIVATE) null else workspace.root.absolutePath,
            bootstrapReady = File(runtime.prefix, "MANIFEST.properties").isFile,
            prefix = runtime.prefix.absolutePath,
            bootstrapError = com.adsh.app.runtime.termux.BootstrapStatus.error,
        )
    }

    fun bindWorkspace(path: String): String? {
        val error = workspaces.bindRealPath(File(path))
        refreshWorkspaceInfo()
        // 设置里绑定的工作区同样进抽屉的工作区列表（两处入口保持同步）
        if (error == null) {
            viewModelScope.launch {
                runCatching { repository.addWorkspace(path, File(path).name.ifBlank { path }) }
                refreshWorkspaceList()
            }
        }
        return error
    }

    fun unbindWorkspace() {
        workspaces.unbind()
        refreshWorkspaceInfo()
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
    private fun refreshContext() {
        val messages = _state.value.messages
        val messageTokens = messages.sumOf { estimateTokens(it.content) + estimateTokens(it.reasoning.orEmpty()) }
        val systemTokens = runCatching {
            estimateTokens(
                com.adsh.app.core.agent.PromptAssembler.buildParts(
                    workspace = workspaces.current(),
                    extraSuffix = settings.systemPromptSuffix,
                    previousWorkspacePath = settings.lastWorkspacePath,
                    filePolicy = com.adsh.app.core.agent.PromptAssembler.filePolicyOf(settings.permission),
                    planMode = _state.value.planMode,
                    model = settings.model,
                    allowParallel = settings.agentMaxParallel > 1,
                ).system
            )
        }.getOrDefault(0L)
        // dsh 的 estimateToolsTokens：wire 上真正发出去的 tools 数组的 JSON 体积 / 4 + 4。
        // PTC 下 wire 上只有 run_code，所以这一栏很小；其余工具的声明在系统提示词的 tools:sdk 段里，
        // 算在「系统提示词」那一栏（dsh 也是这么分的）。
        // dsh 的 CHARS_PER_TOKEN = 4、BLOCK_OVERHEAD = 4
        val wireToolsJson = "[" + com.adsh.app.core.tools.RunCodeTool.wireSchema.toString() + "]"
        val toolTokens = (wireToolsJson.length / 4 + 4).toLong()
        _state.update {
            it.copy(
                context = ContextUsage(
                    system = systemTokens,
                    // wire 上的 tool schema（PTC 只有 run_code）；SDK 声明在 system 那一栏里
                    tools = toolTokens,
                    messages = messageTokens,
                    window = settings.contextWindow,
                )
            )
        }
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
            // dsh 的 /plan off：本来就没开也要给一句结果
            if (!enable) _state.update { it.copy(toolLog = it.toolLog + "✓ Plan mode is already inactive.") }
            return
        }
        viewModelScope.launch {
            repository.setPlanMode(id, enable)
            _state.update {
                it.copy(
                    planMode = enable,
                    toolLog = it.toolLog + if (enable) {
                        "✓ Plan mode on. Use /plan off to leave."
                    } else {
                        "✓ Plan mode off."
                    },
                )
            }
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
                    _state.update { it.copy(toolLog = it.toolLog + "✓ No compactable history yet.") }
                    return@launch
                }
                val (prefix, older, keep) = range
                if (older.isEmpty()) {
                    _state.update { it.copy(toolLog = it.toolLog + "✓ No compactable history yet.") }
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
                        toolLog = it.toolLog + (
                            "✓ Compacted " + older.size + " history items (~" +
                                older.sumOf { estimateTokens(it.content) } + " tokens)."
                            ),
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
        val system = runCatching {
            com.adsh.app.core.agent.PromptAssembler.buildParts(
                workspace = workspaces.current(),
                extraSuffix = settings.systemPromptSuffix,
                previousWorkspacePath = settings.lastWorkspacePath,
                filePolicy = com.adsh.app.core.agent.PromptAssembler.filePolicyOf(settings.permission),
                planMode = _state.value.planMode,
                model = settings.model,
                allowParallel = settings.agentMaxParallel > 1,
            ).system
        }.getOrDefault("")
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
        val out = StringBuilder()
        compactLlm.stream(request).collect { event ->
            when (event) {
                is ChatEvent.Delta -> out.append(event.text)
                is ChatEvent.Failed -> throw IllegalStateException(event.message)
                else -> Unit
            }
        }
        return out.toString()
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
        viewModelScope.launch { bootstrap() }
        viewModelScope.launch { refreshContext() }
    }

    companion object {
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
}
