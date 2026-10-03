package com.adsh.app.core.agent

import com.adsh.app.core.data.BuiltInProviders
import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.data.injectedName
import com.adsh.app.core.data.MessageEntity
import com.adsh.app.core.data.TurnSettings
import com.adsh.app.core.data.TurnStore
import com.adsh.app.core.llm.ChatEvent
import com.adsh.app.core.llm.ChatRequest
import com.adsh.app.core.llm.ProviderConfig
import com.adsh.app.core.llm.TurnLlm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * **这是给 [AgentLoop] 用的测试替身，不是生产代码。**
 *
 * 三个窄端口（[TurnLlm] / [TurnStore] / [TurnSettings]）的内存实现，让整条回合主体
 * 在没有网络、没有 SQLite、没有 Android 运行时的纯 JVM 单测里跑完整 —— 这是后续拆分
 * 那个 391 行的 `runTurnBody` 时唯一的安全网。
 *
 * 只有 app/src/test/ 下的测试引用它们；生产代码一行都不碰。
 */

/**
 * 脚本化的模型层替身：第 N 次 [stream] 吐出脚本里的第 N 轮事件，并**记下每次收到的请求**。
 *
 * 脚本用完之后再被调用时：**抛异常**（`IllegalStateException`）。选抛不选空流，是因为
 * 「脚本写漏了一轮」是测试自己的 bug —— 空流会被 AgentLoop 当成「模型这轮什么都没说」，
 * 安静地跑完一整轮，测试于是断言在一个假现场上；抛出来才是看得见的失败。
 * 异常在 [stream] 调用点同步抛出（不是流里），会直接冒出 `send(...).toList()`。
 *
 * @param script 每一轮的完整事件脚本，按调用顺序消费
 */
class FakeTurnLlm(private val script: List<List<ChatEvent>>) : TurnLlm {

    /** 最常见写法：`FakeTurnLlm(listOf(Delta("a"), Delta("b")))` 一轮、多个脚本顺序给出 */
    constructor(vararg rounds: List<ChatEvent>) : this(rounds.toList())

    private val lock = Any()
    private val requestLog = ArrayList<ChatRequest>()
    private var cursor = 0

    /** 每次 [stream] 收到的请求（调用顺序）；断言用快照，避免与 IO 线程共享可变列表 */
    val requests: List<ChatRequest> get() = synchronized(lock) { requestLog.toList() }

    /** 最后一次请求（最常见的断言对象） */
    val lastRequest: ChatRequest get() = requests.last()

    /** [stream] 被调用的次数（= AgentLoop 走了几步） */
    val callCount: Int get() = synchronized(lock) { requestLog.size }

    override fun stream(request: ChatRequest): Flow<ChatEvent> {
        val index = synchronized(lock) {
            requestLog += request
            cursor++
            cursor - 1
        }
        val round = script.getOrNull(index) ?: error(
            "FakeTurnLlm：脚本只有 " + script.size + " 轮，第 " + (index + 1) +
                " 次 stream() 无事件可发（测试脚本写漏了一轮）",
        )
        return flow { round.forEach { emit(it) } }
    }
}

/**
 * 会话存储的内存替身，语义照抄 [ConversationRepository]（+ Room 的 MessagesDao）：
 *
 *  - 行有自增 id（从 1 起，与 Room 的 autoGenerate 同形），[rows] 按 id 升序；
 *  - [lastNamed] `name == null` 时按角色取最后一条，否则按 role + name；
 *  - [lastContextOfForm] 按 `role = "context" && subCallsJson = form` 取最后一条；
 *  - [lastTurnOpener] 的口径 = MessagesDao.lastTurnOpener：`name IS NULL OR name = 'tool-jobs'`；
 *  - [setUsage] / [setMessageContent] / [setTurnChanges] 就地改那一行（Room 的 UPDATE 语义）；
 *  - [hasInjected] / [claimInjected] 用每个会话一个内存队列模拟（dsh 的 ReactLoopInbox）。
 *
 * 不引用任何 Android 类：不碰 SQLite、不碰 Room、不碰 Log（纯 JVM 单测里 android.util.Log
 * 会抛 "not mocked"）。
 */
class FakeTurnStore : TurnStore {

    /** 收件箱里的一条待注入行（生产里是 ConversationRepository.PendingInjection） */
    private class Pending(val role: String, val content: String, val name: String?)

    private val lock = Any()
    private val all = ArrayList<MessageEntity>()
    private var nextId = 1L
    private val inboxes = HashMap<Long, ArrayDeque<Pending>>()

    /** 就地更新没打中任何一行的 id —— Room 静默影响 0 行，这里留个痕，测试可以断言它为空 */
    private val missedUpdates = ArrayList<Long>()
    val missedUpdateIds: List<Long> get() = synchronized(lock) { missedUpdates.toList() }

    /** 某个会话的全部行（id 升序）—— 测试的主要断言对象 */
    fun rows(conversationId: Long): List<MessageEntity> =
        synchronized(lock) { all.filter { it.conversationId == conversationId } }

    /**
     * [rows] 的紧凑视图，失败时 diff 可读：`"role|name|content 前 N 个字符"`。
     * 长正文（系统提示词、快照）用 [rows] 拿原文再单独断言。
     */
    fun shape(conversationId: Long, contentLimit: Int = 60): List<String> =
        rows(conversationId).map { row ->
            row.role + "|" + (row.name ?: "-") + "|" + row.content.take(contentLimit)
        }

    /**
     * 把一条注入行放进收件箱（生产里由 ConversationRepository.enqueueInjected 完成）。
     * 到达顺序 = 认领顺序。
     */
    fun enqueueInjected(
        conversationId: Long,
        content: String,
        role: String = "user",
        name: String? = null,
    ) {
        synchronized(lock) {
            inboxes.getOrPut(conversationId) { ArrayDeque() }.addLast(Pending(role, content, name))
        }
    }

    /** 追加一行并返回 id（Room 的 @Insert + autoGenerate） */
    private fun insert(row: MessageEntity): MessageEntity {
        val stored = row.copy(id = nextId++, createdAt = System.currentTimeMillis())
        synchronized(lock) { all += stored }
        return stored
    }

    private fun update(id: Long, change: (MessageEntity) -> MessageEntity) {
        synchronized(lock) {
            val index = all.indexOfFirst { it.id == id }
            if (index < 0) {
                missedUpdates += id
            } else {
                all[index] = change(all[index])
            }
        }
    }

    // ---------------------------------------------------------------- TurnStore

    override suspend fun messages(conversationId: Long): List<MessageEntity> = rows(conversationId)

    override suspend fun lastNamed(conversationId: Long, role: String, name: String?): MessageEntity? =
        rows(conversationId).lastOrNull { it.role == role && (name == null || it.name == name) }

    override suspend fun lastContextOfForm(conversationId: Long, form: String): MessageEntity? =
        rows(conversationId).lastOrNull { it.role == "context" && it.subCallsJson == form }

    override suspend fun lastTurnOpener(conversationId: Long, role: String): MessageEntity? =
        rows(conversationId).lastOrNull {
            it.role == role && (it.name == null || it.name == ConversationRepository.JOB_NOTICE)
        }

    override suspend fun addMessage(
        conversationId: Long,
        role: String,
        content: String,
        reasoning: String?,
        toolCallsJson: String?,
        toolCallId: String?,
        name: String?,
        subCallsJson: String?,
        isError: Boolean,
        durationMs: Long,
        attachmentsJson: String?,
        imagesJson: String?,
        deliverablesJson: String?,
    ): Long = insert(
        MessageEntity(
            conversationId = conversationId,
            role = role,
            content = content,
            reasoning = reasoning,
            toolCallsJson = toolCallsJson,
            toolCallId = toolCallId,
            name = name,
            subCallsJson = subCallsJson,
            isError = isError,
            durationMs = durationMs,
            attachmentsJson = attachmentsJson,
            imagesJson = imagesJson,
            deliverablesJson = deliverablesJson,
            createdAt = 0,
        ),
    ).id

    override suspend fun setUsage(messageId: Long, usageJson: String?) =
        update(messageId) { it.copy(usageJson = usageJson) }

    override suspend fun setMessageContent(messageId: Long, content: String) =
        update(messageId) { it.copy(content = content) }

    override suspend fun setTurnChanges(messageId: Long, changesJson: String?) =
        update(messageId) { it.copy(changesJson = changesJson) }

    override fun hasInjected(conversationId: Long): Boolean =
        synchronized(lock) { inboxes[conversationId]?.isNotEmpty() == true }

    override suspend fun claimInjected(conversationId: Long, openTurn: Boolean): List<MessageEntity> {
        val pending = synchronized(lock) {
            val queue = inboxes.remove(conversationId) ?: return emptyList()
            queue.toList()
        }
        val out = ArrayList<MessageEntity>(pending.size)
        pending.forEachIndexed { index, item ->
            // 与生产实现共用同一条规则（原先这里是手抄的一份，注释写着「逐字一致」）
            val name = injectedName(item.name, index, openTurn)
            out += insert(
                MessageEntity(
                    conversationId = conversationId,
                    role = item.role,
                    content = item.content,
                    name = name,
                    createdAt = 0,
                ),
            )
        }
        return out
    }
}

/**
 * 设置项的内存替身。默认值刻意是「最素的一档」：
 *  - deepseek 路由（providerId = 内置 DeepSeek `+` 模型名 deepseek-flash）→ 走 DeepSeek 分支；
 *  - reasoningEffort = 空串 → 请求里 `reasoning_effort` 与 `thinking` 两个字段都不发；
 *  - systemPromptSuffix = 空串 → 系统提示词就是 [PromptAssembler.STATIC_SYSTEM_PROMPT] 原文；
 *  - lastWorkspacePath = null（没有绑定工作区）。
 *
 * 每一项都是 `var`/`val`，测试按需要改；不要在这里加生产里没有的行为。
 */
class FakeTurnSettings(
    /** 阶段四之后可以整体换一份（换提供方 / 换模型的用例） */
    var config: ProviderConfig = ProviderConfig(
        baseUrl = "http://localhost",
        apiKey = "test",
        model = "deepseek-flash",
        providerId = BuiltInProviders.DEEPSEEK_ID,
    ),
    /** 展示用路由名（写进本轮用量）：默认就是内置 DeepSeek 的显示名 */
    var route: String = BuiltInProviders.DEEPSEEK_NAME,
    var effort: String = "",
    var suffix: String = "",
    /** 权限档位 → 提示词里的 filePolicy 段（名字避开接口成员 permission，见下面的 override） */
    var permissionMode: String = "danger-full-access",
    /** 这个模型收不收图（决定工具结果里的图片是发图还是发文字占位） */
    var acceptsImages: Boolean = false,
    var bashTimeout: Long = 120_000,
    var bashMaxTimeout: Long = 600_000,
) : TurnSettings {

    override var lastWorkspacePath: String? = null

    override fun providerConfig(): ProviderConfig = config

    override val providerRoute: String get() = route

    override fun modelAcceptsImages(modelId: String): Boolean = acceptsImages

    override val reasoningEffort: String get() = effort

    override val systemPromptSuffix: String get() = suffix

    override val permission: String get() = permissionMode

    override val bashTimeoutMs: Long get() = bashTimeout

    override val bashMaxTimeoutMs: Long get() = bashMaxTimeout
}
