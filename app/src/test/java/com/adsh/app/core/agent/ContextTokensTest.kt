package com.adsh.app.core.agent

import com.adsh.app.core.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文占用里「对话消息」那一栏的算法（第 103 轮用户实测：新会话发一句你好就显示一万 token）。
 *
 * 重点是：**展示用的 sysprompt 行不能算进去** —— 它的正文就是请求里那条 system 消息，
 * 已经算在「系统提示词」那一栏了（AgentLoop.buildMessages 装配时把它跳过）。
 */
class ContextTokensTest {

    private fun message(
        role: String,
        content: String,
        reasoning: String? = null,
        name: String? = null,
        form: String? = null,
    ) = MessageEntity(
        conversationId = 1L,
        role = role,
        content = content,
        reasoning = reasoning,
        name = name,
        subCallsJson = form,
        createdAt = 1L,
    )

    @Test
    fun `系统提示词行不计入对话消息`() {
        val prompt = "x".repeat(4000) // 4000 字符 = 1000 token
        val sysprompt = message("sysprompt", prompt)
        assertFalse(countsTowardContext(sysprompt))
        assertEquals(0L, contextMessageTokens(listOf(sysprompt)))
    }

    @Test
    fun `closing 一句话会话的对话消息只有那两句`() {
        val sysprompt = message("sysprompt", "y".repeat(20_000))
        val user = message("user", "你好")
        val assistant = message("assistant", "你好！有什么可以帮你的？")
        // 提示词那一条不算，剩下的就是两次「你好」大小
        assertEquals(
            estimateTokens(user.content) + estimateTokens(assistant.content),
            contextMessageTokens(listOf(sysprompt, user, assistant)),
        )
    }

    @Test
    fun `命令行走不进请求`() {
        assertFalse(countsTowardContext(message("command", "/plan 写一个页面")))
    }

    @Test
    fun `运行时快照与指令文件链进请求、展示行不进`() {
        assertTrue(countsTowardContext(message("context", "当前策略：可写", form = PromptAssembler.FORM_SNAPSHOT)))
        // 第 117 轮起指令文件链也是**真的会发出去的 user 消息**（dsh 的 agent-instructions）
        assertTrue(countsTowardContext(message("context", "指令文件链", form = PromptAssembler.FORM_INSTRUCTIONS)))
        assertFalse(countsTowardContext(message("context", "自定义后缀", form = PromptAssembler.FORM_NOTICE)))
        // 空白快照不上 wire（装配时判 content.isNotBlank）
        assertFalse(countsTowardContext(message("context", "", form = PromptAssembler.FORM_SNAPSHOT)))
    }

    @Test
    fun `思考也算在对话消息里`() {
        val assistant = message("assistant", "答案", reasoning = "x".repeat(400))
        assertTrue(countsTowardContext(assistant))
        assertEquals(estimateTokens("答案") + estimateTokens("x".repeat(400)), contextMessageTokens(listOf(assistant)))
    }
}
