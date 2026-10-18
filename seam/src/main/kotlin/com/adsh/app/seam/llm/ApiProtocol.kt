package com.adsh.app.seam.llm

/** dsh 的 api 字段（pi-ai 支持三种协议） */
object ApiProtocol {
    const val OPENAI_COMPLETIONS = "openai-completions"
    const val ANTHROPIC_MESSAGES = "anthropic-messages"
    val ALL = listOf(OPENAI_COMPLETIONS, ANTHROPIC_MESSAGES)

    fun label(api: String): String = when (api) {
        OPENAI_COMPLETIONS -> "OpenAI 兼容（chat completions）"
        ANTHROPIC_MESSAGES -> "Anthropic（messages）"
        else -> api
    }
}
