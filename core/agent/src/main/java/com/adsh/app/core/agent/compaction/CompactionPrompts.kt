package com.adsh.app.core.agent.compaction

import com.adsh.app.core.data.MessageEntity

/**
 * /compact 的两段提示词与转录渲染。
 *
 * 文案逐字取自 dsh compaction-basic（summarizer.ts 的 COMPACTION_INSTRUCTION 与 CHECKPOINT_PREAMBLE）；
 * 从 ChatViewModel 迁进来时一个字都没改 —— /compact 的历史行为是字节级契约。
 */
internal object CompactionPrompts {

    /** 单条消息进入摘要时最多取多少字符（避免超长工具输出把摘要请求撑爆）。 */
    const val MAX_TRANSCRIPT_CHARS_PER_MESSAGE = 6000

    /** dsh compaction-basic 的检查点前言（逐字）。 */
    const val CHECKPOINT_PREAMBLE =
        "This is an automatically generated checkpoint condensing an earlier span of the conversation " +
            "to free up context. Treat the captured context as established background and build on it " +
            "without restating it. Continue the task directly from the messages that follow, without " +
            "acknowledging this checkpoint."

    /** dsh compaction-basic 的摘要指令（逐字，含分节结构）。 */
    val COMPACTION_INSTRUCTION = listOf(
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
        "- Write concise English engineering prose. Preserve exact file paths, commands, error strings, identifiers, " +
            "numeric values, function signatures, and syntax fragments.",
        "- Capture user feedback and explicit instructions faithfully, especially corrections.",
        "- Do NOT mention this summarization request or that the context was compacted.",
        "- Output only the checkpoint text: do not call any tool or take any other action.",
        "- If the conversation already contains a <compacted-summary> block, it is a PRIOR checkpoint. " +
            "Do not copy it forward verbatim: preserve still-true facts, drop stale ones, and merge newer " +
            "information into a single consolidated summary under the same structure.",
    ).joinToString("\n")

    /** 检查点正文：前言 + 包在 <compacted-summary> 里的摘要（/compact 的落库格式，逐字保持）。 */
    fun checkpoint(summary: String): String =
        CHECKPOINT_PREAMBLE + "\n\n<compacted-summary>\n" + summary.trim() + "\n</compacted-summary>"

    /** 把历史渲染成纯文本，交给模型做摘要（工具调用/结果只保留结论，避免噪音）。 */
    fun renderTranscript(messages: List<MessageEntity>): String = buildString {
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
}
