package com.adsh.app.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 终态失败与压缩事件的契约：`Failed.code` 是自动恢复的分流依据（默认 null = 不可恢复），
 * 压缩事件只携带原因与「有没有替换落地」。
 */
class ChatEventContractTest {

    @Test
    fun `Failed 默认不带 code、可携带上下文超限 code`() {
        assertNull(ChatEvent.Failed("boom").code)
        assertEquals(ChatEvent.Failed("boom"), ChatEvent.Failed("boom", null))
        assertEquals(CONTEXT_WINDOW_EXCEEDED, ChatEvent.Failed("boom", CONTEXT_WINDOW_EXCEEDED).code)
    }

    @Test
    fun `压缩原因三种、事件携带 applied`() {
        assertEquals(
            listOf("MANUAL", "PRESSURE", "OVERFLOW"),
            CompactionCause.values().map { it.name },
        )
        assertEquals(
            ChatEvent.CompactionFinished(CompactionCause.OVERFLOW, applied = false),
            ChatEvent.CompactionFinished(CompactionCause.OVERFLOW, false),
        )
        assertEquals(
            ChatEvent.CompactionStarted(CompactionCause.PRESSURE),
            ChatEvent.CompactionStarted(CompactionCause.PRESSURE),
        )
    }
}
