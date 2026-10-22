package com.adsh.app.core.agent

import com.adsh.app.core.llm.ChatEvent

/**
 * Agent 主循环的决策点（dsh 的 `agent/pre-step` 与 `agent/request-error` 两个 waterfall）。
 *
 * 与 dsh 同一条规则：**返回值权威**。实现可以在方法里改会话状态（压缩会重写历史），
 * 但必须自己保证失败不把一轮对话打断（取消除外）。
 *
 * 缺省实现 [None] 两个方法都不做事：不拦步、不接管错误 —— 主循环行为与没有决策点时逐字一致。
 */
interface AgentHooks {

    /**
     * 每个 step 在**装配请求之前**调用（dsh 的 pre-step）。
     *
     * @return false = 本步不开，本轮直接收尾（不再发请求）；true = 照常装配并请求。
     *   [emit] 把界面事件发进本轮事件流（压缩的 started / finished 走它）。
     */
    suspend fun beforeStep(conversationId: Long, emit: (ChatEvent) -> Unit): Boolean = true

    /**
     * 终止性请求失败（[com.adsh.app.core.llm.LlmClient] 已耗尽重试）之后调用，
     * 运行在同一个 open step 内、step 关闭之前（dsh 的 request-error）。
     *
     * @return true = 在同一 step 内再试一次（重新装配请求 → 重新请求）；false = 终态，
     *   失败事件原样交给界面、本轮照常收尾。返回值权威，但调用方每轮最多接受一次 true。
     */
    suspend fun onRequestError(
        conversationId: Long,
        failure: ChatEvent.Failed,
        emit: (ChatEvent) -> Unit,
    ): Boolean = false

    companion object {
        /** 不挂任何决策的缺省实现（主循环行为与没有决策点时一致）。 */
        val None: AgentHooks = object : AgentHooks {}
    }
}
