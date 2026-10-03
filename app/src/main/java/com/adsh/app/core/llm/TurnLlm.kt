package com.adsh.app.core.llm

import kotlinx.coroutines.flow.Flow

/**
 * Agent 主循环面向模型层的窄端口（甲方案第 1 步）。
 *
 * 整条 [com.adsh.app.core.agent.AgentLoop] 回合只用到模型层的一个能力：发一轮请求、拿事件流。
 * 生产实现就是 [LlmClient]（见其类声明 `class LlmClient(...) : TurnLlm`），测试里换成脚本化的内存实现，
 * 于是「回合主体」可以在没有网络、没有 Android 环境的情况下跑完整条循环 —— 这是后续拆分
 * 那个 391 行函数时唯一的安全网。
 *
 * 端口有意只有这一个方法：多一个方法，替身就要多实现一块与本轮行为无关的东西。
 */
interface TurnLlm {
    /** 一次模型请求的流；重连、mock、错误语义全部由实现负责（见 [LlmClient.stream]）。 */
    fun stream(request: ChatRequest): Flow<ChatEvent>
}
