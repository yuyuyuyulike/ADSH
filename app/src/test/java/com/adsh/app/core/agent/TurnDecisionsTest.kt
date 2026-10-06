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
     * 标题那次小调用该发的思考字段：**逐路由按 dsh 的适配器口径**（用户第 195 轮口径：
     * 向 dsh 看齐，标题慢一点就慢一点 —— 不做比 dsh 更硬的事）。
     *
     * dsh 那边：标题调用带 purpose = session-title，**怎么落由各适配器决定** ——
     * dsh-llm-deepseek 落成 thinking=disabled；dsh-llm-pi-ai（其余提供方）落成「省略 reasoning 字段」，
     * 也就是走提供方默认（那里的注释明说：会思考的提供方选了 off 也照样思考）。
     *
     * 实测（2026-10-06，真机 + 同一把 key 直连复现，标题那次的提示词与 max_tokens=64）：
     *  - qwen 路由不关思考：推理 187-290 字、2.8-7.3 秒、content 为空 ⇒ 标题退回兜底值；
     *  - qwen 路由发 enable_thinking=false：推理 0 字、1.6 秒、标题正常；
     *  - 同一条 qwen 路由改发 thinking={type:disabled}：0 字推理但要 4.4-9.9 秒（两个一起发 9.9 秒）；
     *  - DeepSeek（R79 那次）：不关是 finish=length / content 0 字 / reasoning 175 字，
     *    关掉是 finish=stop / 10 字 / 6 个 token。
     */
    @Test
    fun titleCallFollowsTheAdapterRuleOfEachRoute() {
        // DeepSeek 路由（含只改地址 / 只改模型名的自建中转）：thinking = disabled
        listOf(
            Triple("deepseek", "https://api.deepseek.com/v1", "deepseek-flash"),
            Triple("custom", "https://my-proxy.example/deepseek", "glm-5.3"),
            Triple("custom", "https://proxy.example", "deepseek-v4-pro"),
        ).forEach { (provider, base, model) ->
            val wire = titleNoThink(provider, base, model)
            // off 不是一档：不发 reasoning_effort
            assertNull(wire.reasoningEffort)
            assertEquals("disabled", wire.thinking?.type)
            assertNull(wire.enableThinking)
        }

        // DashScope 兼容模式（qwen 系，ADSH 自己的路由知识）：只发 enable_thinking=false
        listOf(
            Triple("qwen", "https://ws-x.maas.aliyuncs.com/compatible-mode/v1", "qwen3.8-flash"),
            Triple("custom", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-max"),
        ).forEach { (provider, base, model) ->
            val wire = titleNoThink(provider, base, model)
            assertEquals(false, wire.enableThinking)
            assertNull("两个一起发实测要 4-10 秒", wire.thinking)
        }

        // 其余路由：一个字段都不发（= dsh 的 pi-ai：off 就是省略 reasoning，走提供方默认）
        listOf(
            Triple("openai", "https://api.openai.com/v1", "gpt-5.6-sol"),
            Triple("zai", "https://api.z.ai/api/coding/paas/v4", "glm-5.3"),
            Triple("custom", "https://my-proxy.example/v1", "gpt-4o"),
            Triple("ant-ling", "https://api.example/v1", "Ling-2.6-flash"),
        ).forEach { (provider, base, model) ->
            val wire = titleNoThink(provider, base, model)
            assertNull(wire.thinking)
            assertNull(wire.enableThinking)
            assertNull(wire.reasoningEffort)
        }
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
