package com.adsh.app.core.data

/**
 * Agent 主循环面向会话存储的窄端口（甲方案第 1 步）。
 *
 * 这是 [com.adsh.app.core.agent.AgentLoop] 在整条回合里用到的**全部**读写能力（10 个方法，逐一从
 * 既有调用点量出来的，不多不少）。生产实现就是 [ConversationRepository]（类声明上
 * `class ConversationRepository(...) : TurnStore`），测试里换成内存实现。
 *
 * 参数默认值只在这里声明：Kotlin 不允许 override 重复默认值，而默认值对调用方是按**静态类型**
 * 继承的 —— 所以 ConversationRepository 的现有调用点写法一个字都不用改。
 */
interface TurnStore {
    /** 会话全部消息（按 id 升序）。 */
    suspend fun messages(conversationId: Long): List<MessageEntity>

    /** 最后一条某种角色的消息；name 为 null 时按角色取。 */
    suspend fun lastNamed(conversationId: Long, role: String, name: String? = null): MessageEntity?

    /** 最后一条某种形态的上下文行。 */
    suspend fun lastContextOfForm(conversationId: Long, form: String): MessageEntity?

    /** 最后一条开启轮次的用户消息。 */
    suspend fun lastTurnOpener(conversationId: Long, role: String = "user"): MessageEntity?

    /** 追加一条消息，返回行 id。 */
    suspend fun addMessage(
        conversationId: Long,
        role: String,
        content: String,
        reasoning: String? = null,
        toolCallsJson: String? = null,
        toolCallId: String? = null,
        name: String? = null,
        subCallsJson: String? = null,
        isError: Boolean = false,
        durationMs: Long = 0,
        attachmentsJson: String? = null,
        imagesJson: String? = null,
        deliverablesJson: String? = null,
    ): Long

    /** 把本轮的用量写回用户消息行。 */
    suspend fun setUsage(messageId: Long, usageJson: String?)

    /** 系统提示词就地替换。 */
    suspend fun setMessageContent(messageId: Long, content: String)

    /** 本轮的文件改动写回轮首那一行。 */
    suspend fun setTurnChanges(messageId: Long, changesJson: String?)

    /** 收件箱里是否有待注入的行。 */
    fun hasInjected(conversationId: Long): Boolean

    /** 取走收件箱里的注入行（取走即消费）。 */
    suspend fun claimInjected(conversationId: Long, openTurn: Boolean = false): List<MessageEntity>
}
