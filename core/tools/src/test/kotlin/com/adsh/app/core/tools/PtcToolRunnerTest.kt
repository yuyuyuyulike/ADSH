package com.adsh.app.core.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PTC 子调用回归：wire 信封、轨迹结算、脱敏、4 MiB 值上限、中断看门狗与 abort 时序
 * 必须保持 dsh 的 wire 语义与结算规则。
 */
class PtcToolRunnerTest {

    private fun runner(
        tools: List<Tool>,
        ctx: ToolContext = testToolContext(),
        cancel: () -> Boolean = { false },
    ): PtcToolRunner = PtcToolRunner(tools, ctx, cancel)

    @Test(timeout = 30_000)
    fun unknownCallReturnsTheBindingErrorEnvelope() = runBlocking {
        val ptc = runner(listOf(StubTool("echo") { ToolResult.Ok("hi") }))
        val wire = ptc.call("nope", "{}")
        assertTrue(wire, wire.contains("\"__error\":true"))
        assertTrue(wire, wire.contains("\"toolName\":\"nope\""))
        assertTrue(wire, wire.contains("\"message\":\"unknown tool: nope\""))
        val call = ptc.subCalls().single()
        assertFalse(call.ok)
        assertEquals("unknown tool: nope", call.result)
        assertEquals("7:ptc:1", call.id)
    }

    @Test(timeout = 30_000)
    fun successfulCallReturnsTheStructuredValueAndRecordsBothCallbacks() = runBlocking {
        val starts = ArrayList<String>()
        val ends = ArrayList<String>()
        val ctx = testToolContext().copy(
            onSubCallStart = { starts += it.id },
            onSubCall = { ends += it.id },
        )
        val ptc = runner(
            listOf(StubTool("echo") { ToolResult.Ok("hello", buildJsonObject { put("x", 1) }) }),
            ctx,
        )
        val wire = ptc.call("echo", "{\"a\":1}")
        assertEquals("{\"x\":1}", wire)
        assertEquals(listOf("7:ptc:1"), starts)
        assertEquals(listOf("7:ptc:1"), ends)
        val call = ptc.subCalls().single()
        assertTrue(call.ok)
        assertEquals("hello", call.result)
        assertEquals("{\"a\":1}", call.args)
        assertFalse(call.running)
    }

    @Test(timeout = 30_000)
    fun redactionAppliesToTheTraceTextButNotToTheProgramValue() = runBlocking {
        val token = "sk-" + "b".repeat(24)
        val ptc = runner(listOf(StubTool("echo") { ToolResult.Ok("key=" + token, JsonPrimitive(token)) }))
        val wire = ptc.call("echo", "{}")
        assertEquals("\"" + token + "\"", wire)
        assertTrue(ptc.subCalls().single().result.contains("***redacted***"))
    }

    @Test(timeout = 60_000)
    fun wireValueOverTheDeviceCapBecomesAnErrorEnvelope() = runBlocking {
        val huge = JsonPrimitive("x".repeat(4 * 1024 * 1024 + 8))
        val ptc = runner(listOf(StubTool("echo") { ToolResult.Ok("big", huge) }))
        val wire = ptc.call("echo", "{}")
        assertTrue(wire, wire.contains("\"__error\":true"))
        assertTrue(wire, wire.contains("-byte cap this device can hand to a PTC program"))
        val call = ptc.subCalls().single()
        assertFalse(call.ok)
        assertTrue(call.result, call.result.contains("-byte cap this device"))
    }

    @Test(timeout = 30_000)
    fun userInterruptSettlesTheCallAsInterrupted() = runBlocking {
        val ptc = runner(
            listOf(StubTool("slow") { delay(10_000); ToolResult.Ok("late") }),
            cancel = { true },
        )
        val wire = withTimeout(10_000) { ptc.call("slow", "{}") }
        assertTrue(wire, wire.contains("\"code\":\"interrupted\""))
        assertTrue(wire, wire.contains(ABORTED_SUB_CALL_MESSAGE))
        val call = ptc.subCalls().single()
        assertFalse(call.ok)
        assertEquals(ABORTED_SUB_CALL_MESSAGE, call.result)
    }

    @Test(timeout = 30_000)
    fun abortOpenCallsSettlesRunningOnesAndDiscardsLateCompletion() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val ctx = testToolContext().copy(onSubCallStart = { started.complete(Unit) })
        val ptc = PtcToolRunner(
            listOf(StubTool("slow") { release.await(); ToolResult.Ok("late") }),
            ctx,
        ) { false }
        val call = async(Dispatchers.Default) { ptc.call("slow", "{}") }
        withTimeout(10_000) { started.await() }
        ptc.abortOpenCalls()
        release.complete(Unit)
        call.await()
        val calls = ptc.subCalls()
        assertEquals("abort 之后晚到的完成不能覆盖失败行", 1, calls.size)
        assertFalse(calls[0].ok)
        assertEquals(ABORTED_SUB_CALL_MESSAGE, calls[0].result)
        assertFalse(calls[0].running)
    }

    @Test(timeout = 30_000)
    fun subCallIdsAreSequentialAndCarryTheExecToken() = runBlocking {
        val ptc = runner(
            listOf(
                StubTool("echo") { ToolResult.Ok("hi") },
                StubTool("second") { ToolResult.Ok("hi") },
            ),
        )
        ptc.call("echo", "{}")
        ptc.call("second", "{}")
        assertEquals(listOf("7:ptc:1", "7:ptc:2"), ptc.subCalls().map { it.id })
    }

    @Test(timeout = 30_000)
    fun subCallIdsAreSequentialAndCarryTheExecTokenValue() {
        // 占位：id 前缀来自 execToken（上面一条已钉住 7:ptc:N，这里只防 execToken 默认值漂移）
        assertEquals(7L, testToolContext().execToken)
    }
}
