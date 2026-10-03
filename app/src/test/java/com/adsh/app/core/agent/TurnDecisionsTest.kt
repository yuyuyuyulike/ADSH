package com.adsh.app.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单轮循环里的判定（TurnDecisions）—— R11 从 397 行的 AgentLoop.runTurnBody 里提出来的。
 *
 * 这里每一条都对应一类真机现场：
 *  - DeepSeek 路由判错 → 带 tool_calls 的 assistant 不回 reasoning_content，整轮 400；
 *  - 思考字段映射错 → off 也发 reasoning_effort，或该发 thinking 的档位什么都没发；
 *  - 死循环判据错 → 网关把 tool_calls 截断时反复重试同一个调用，或一条消息刷出几 MB；
 *  - 落库判据错 → 库里多出空 assistant 行，之后每一轮请求都被服务端 400 拒绝。
 */
class TurnDecisionsTest {

    // ---------- DeepSeek 路由（reasoning_content 要不要回传）----------

    @Test
    fun deepseekRouteIsDetectedByAnyOfTheThreeSigns() {
        assertTrue(isDeepSeekRoute("deepseek", "https://api.deepseek.com", "deepseek-chat"))
        assertTrue(isDeepSeekRoute("custom", "https://my-proxy.example/deepseek", "gpt-4o"))
        assertTrue(isDeepSeekRoute("custom", "https://proxy.example", "deepseek-reasoner"))
    }

    @Test
    fun otherRoutesAreNotTreatedAsDeepSeek() {
        assertFalse(isDeepSeekRoute("openai", "https://api.openai.com", "gpt-4o"))
        // 大小写敏感：地址里写 DeepSeek 不算（与旧实现逐字一致）
        assertFalse(isDeepSeekRoute("custom", "https://proxy.example/DeepSeek", "gpt-4o"))
    }

    // ---------- 思考字段（dsh 的 resolveThinking）----------

    @Test
    fun offSendsOnlyDisabled() {
        val wire = thinkingWire("off")
        assertNull(wire.reasoningEffort)
        assertEquals("disabled", wire.thinking?.type)
    }

    @Test
    fun aLevelSendsEnabledPlusTheEffort() {
        listOf("low", "high", "max").forEach { level ->
            val wire = thinkingWire(level)
            assertEquals(level, wire.reasoningEffort)
            assertEquals("enabled", wire.thinking?.type)
        }
    }

    @Test
    fun noStoredLevelSendsNeitherField() {
        val wire = thinkingWire(null)
        assertNull(wire.reasoningEffort)
        assertNull(wire.thinking)
    }

    /**
     * 小调用（会话标题）的思考字段：DeepSeek 路由发 `thinking = disabled`，别的路由什么都不发。
     *
     * 这条是用户报的「DeepSeek 的会话标题没有总结」的根因所在：标题那次调用只给 64 个输出 token，
     * 而 DeepSeek V4 默认先推理 —— 实测不加字段时 `finish_reason = length`、`content` 为空、
     * `reasoning_content` 175 字；加上 disabled 之后同一请求 `finish_reason = stop`、10 字、6 个 token。
     */
    @Test
    fun smallCallDisablesThinkingOnlyOnDeepSeekRoutes() {
        listOf(
            Triple("deepseek", "https://api.deepseek.com/v1", "deepseek-flash"),
            Triple("custom", "https://my-proxy.example/deepseek", "gpt-4o"),
            Triple("custom", "https://proxy.example", "deepseek-v4-pro"),
        ).forEach { (provider, base, model) ->
            // 只发 thinking（off 不是一档，不发 reasoning_effort —— 映射来自上面那条 thinkingWire("off")）
            assertEquals("disabled", noThinkFor(provider, base, model)?.type)
        }
        assertNull(noThinkFor("qwen", "https://ws-x.maas.aliyuncs.com/compatible-mode/v1", "qwen3.8-flash"))
        assertNull(noThinkFor("openai", "https://api.openai.com/v1", "gpt-4o"))
    }

    // ---------- 死循环判据 ----------

    @Test
    fun signatureSeparatesNameFromArguments() {
        assertEquals(callSignature("run_code", "{}"), callSignature("run_code", "{}"))
        // 没有分隔符时 ("run_cod", "e{}") 会与 ("run_code", "{}") 撞成同一个签名
        assertNotEquals(callSignature("run_cod", "e{}"), callSignature("run_code", "{}"))
        // 参数不做规范化：空白不同就是不同的调用（宁可漏判，不能误判）
        assertNotEquals(callSignature("bash", "{\"a\":1}"), callSignature("bash", "{ \"a\": 1 }"))
    }

    @Test
    fun repeatedCallStopsOnlyAfterTheLimit() {
        assertNull(loopGuardReason("bash", REPEAT_CALL_LIMIT, 1))
        val why = loopGuardReason("bash", REPEAT_CALL_LIMIT + 1, 4)!!
        assertTrue(why.contains("bash"))
        assertTrue(why.contains("4 次"))
    }

    @Test
    fun tooManyCallsInOneTurnStops() {
        assertNull(loopGuardReason("bash", 1, MAX_TOOL_CALLS_PER_TURN))
        assertEquals(
            "单条消息的工具调用总数超过 300 次",
            loopGuardReason("bash", 1, MAX_TOOL_CALLS_PER_TURN + 1),
        )
    }

    @Test
    fun repeatReasonWinsWhenBothAreHit() {
        val why = loopGuardReason("bash", REPEAT_CALL_LIMIT + 1, MAX_TOOL_CALLS_PER_TURN + 1)!!
        assertTrue(why.startsWith("同一个工具调用重复了"))
    }

    // ---------- 本轮结束判据 ----------

    @Test
    fun turnEndsWhenThereIsNoExecutableCall() {
        assertTrue(turnEndsAfterStep(0, ptcEnabled = true))
        assertFalse(turnEndsAfterStep(2, ptcEnabled = true))
    }

    @Test
    fun turnEndsWhenPtcIsOffEvenWithCalls() {
        assertTrue(turnEndsAfterStep(1, ptcEnabled = false))
    }

    // ---------- 落库判据 ----------

    @Test
    fun emptyStepIsNotPersisted() {
        assertFalse(hasAssistantContent("", ""))
        assertTrue(hasAssistantContent("答案", ""))
        assertTrue(hasAssistantContent("", "思考"))
        // 口径是 isNotEmpty：纯空白也算内容（旧行为，逐字保留）
        assertTrue(hasAssistantContent(" ", ""))
    }

    @Test
    fun interruptedKeepsOnlyRealContent() {
        assertFalse(hasInterruptedContent("", "", 0))
        // 口径是 isNotBlank：纯空白不算（与 hasAssistantContent 有意不同，见 KDoc）
        assertFalse(hasInterruptedContent("  ", "\n", 0))
        assertTrue(hasInterruptedContent("", "", 1))
        assertTrue(hasInterruptedContent("半句", "", 0))
    }

    @Test
    fun stoppedTextIsTheSentenceTheUserSees() {
        assertEquals(
            "（已停下：同一个工具调用重复了 4 次（bash），疑似陷入循环。可以换个说法让我继续，或先检查工作区状态）",
            stoppedText("同一个工具调用重复了 4 次（bash），疑似陷入循环"),
        )
    }
}
