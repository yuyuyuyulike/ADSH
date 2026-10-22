package com.adsh.app.ui.chat

import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.data.SettingsStore
import com.adsh.app.core.llm.SessionStats
import com.adsh.app.seam.exec.Permissions
import com.adsh.app.seam.llm.DEEPSEEK_ID
import com.adsh.app.seam.model.ThemeMode

/*
 * 会话宿主对界面暴露的状态：一屏所需的全部快照（会话、轮次、连接、上下文占用…）。
 * ChatUiState 每次更新都整份替换（见 ChatViewModel.updateTurn），所以这里只放不可变数据。
 */

data class ChatUiState(
    val conversationId: Long? = null,
    val messages: List<MessageEntity> = emptyList(),
    /**
     * 启动恢复是否已经完成（读设置 / 上次会话 / 工作区）。
     *
     * 为 false 时界面只画底色：bootstrap 是异步的，先画一帧“新会话”首页再跳到上次那条会话
     * 会明显闪一下（用户口径）。true 之后才允许画会话页。
     */
    val booted: Boolean = false,
    override val streaming: String = "",
    override val reasoning: String = "",
    /**
     * 本步最后流出来的东西**是不是思考**（dsh 的 ReasoningRow：`running = streaming && i === last`）。
     *
     * 它决定思考行的形态：运行中 → 摘要跟着最后一行滚动 + 扫光；一旦结束 → 立刻变回**第一行**
     * 摘要。结束的时刻是「模型开始写正文或工具调用」（`ChatEvent.Delta` / `ChatEvent.ToolCallDelta`），
     * **不是**「工具行出现」—— 后者要等这一步落库，用户看到的就是「思考完不动，等工具蹦出来才一起变」。
     */
    override val reasoningRunning: Boolean = false,
    /**
     * 模型是不是已经在写**工具调用的参数**了：参数一开始流，这一段的正文就不会再长。
     * 灵动岛用它把相切成「工具」；置位 `ToolCallDelta`；复位于正文增量 `Delta`、新的工具调用
     * `ToolCall`、定稿 `Step`、轮首与轮尾。
     */
    override val toolArgsFlowing: Boolean = false,
    /**
     * 模型**正在写参数**的那几次工具调用（dsh 的 streaming tool row）：名字在 delta 里一到就出行，
     * 只有标题 + 运行中的扫光；右侧摘要要等参数写完、正式调用落日志（[LiveTurn.calls]）之后才有。
     */
    val pendingCalls: List<PendingCall> = emptyList(),
    override val sending: Boolean = false,
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
    val providerId: String = DEEPSEEK_ID,
    val mockMode: Boolean = false,
    /**
     * 本轮工具族的**会话日志副本**（dsh 的 `session/event` 累积）：界面形态由它折叠出来
     * （[foldLiveTurn]）—— **一份日志，一个 fold**，不做「实时状态 + 落库状态」两套对账。
     */
    val turnEvents: List<com.adsh.app.core.session.SessionEvent> = emptyList(),
    /** [turnEvents] 折叠出来的界面形态（工具行 / 子调用 / 谁的轨迹） */
    val liveTurn: LiveTurn = LiveTurn(),
    /** 会话统计（对齐 dsh 状态栏：轮/步、tps、token、缓存命中） */
    val stats: SessionStats = SessionStats(),
    /**
     * 输入框下方胶囊行的入场动画是否还武装（判据见 [statsDockVisible] / [ChatStatsDock]）。
     *
     * **只有会话载入时统计为空**（新会话、还没跑过一轮）才为 true —— 胶囊第一次长出来是这条
     * 会话的起点，允许一次淡入 + 高度展开。启动恢复、切到已有统计的旧会话时为 false：那一帧
     * 胶囊本来就该在，播动画等于给 footer 做一次高度动画（底部内边距跟着变，会话流会被顶一下）。
     */
    val dockEntranceArmed: Boolean = false,
    val permission: String = Permissions.FULL_ACCESS,
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
    val themePreference: String = ThemeMode.SYSTEM,
    /** 会话内容字号（dsh 的 ui-theme.fontSize，12..17） */
    val contentFontSize: Int = SettingsStore.DEFAULT_CONTENT_FONT_SIZE,
    /** 对话显示（dsh 的 ui-chat.transcriptView：normal / compact） */
    val transcriptView: String = SettingsStore.TRANSCRIPT_COMPACT,
    /** 繁忙时的发送行为（dsh 的 ui-conversation.busyEnter：queue / steer） */
    val busyEnter: String = SettingsStore.BUSY_QUEUE,
    /** 排队待发的消息条数（排队发送模式下，输入栏右下角显示） */
    val queuedCount: Int = 0,
    /**
     * 已提交、还没被认领的插话回显（dsh 的 `pendingSubmissions`）。
     *
     * 它是「点完立刻看到」的唯一来源：会话节点仍要等收件箱在下一步开头认领才落库
     * （[com.adsh.app.core.data.ConversationRepository.claimInjected]），
     * 而这一条在 [steer] 里同步写进来。认领之后由 [pendingSteeringToShow] 去掉。
     */
    val pendingSteering: List<PendingSteer> = emptyList(),
) : IslandSnapshot {

    /** 正在跑的那个工具名；null = 没有在跑的工具（岛据此画「#代码」那一格） */
    override val runningToolName: String? get() = liveTurn.calls.lastOrNull { it.running }?.name

    /** 正在跑的那个工具行的摘要 —— 与对话里那一行**同一个函数**算，岛只接收算好的字符串 */
    override val runningToolSummary: String
        get() {
            val call = liveTurn.calls.lastOrNull { it.running } ?: return ""
            return runCatching { toolRowSummary(call.name, call.arguments) }.getOrDefault("")
        }
}

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
internal fun estimateTokens(text: String): Long = com.adsh.app.core.agent.estimateTokens(text)

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
