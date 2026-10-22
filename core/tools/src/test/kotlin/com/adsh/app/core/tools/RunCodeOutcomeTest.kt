package com.adsh.app.core.tools

import com.adsh.app.seam.ptc.CodeRunResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * run_code 的结果映射：成功与失败**都**要带已结算的子调用与程序步数 ——
 * 工具行轨迹与状态栏步数读这里。
 */
class RunCodeOutcomeTest {

    private val ctx = testToolContext()

    private suspend fun settledRunner(): PtcToolRunner {
        val runner = PtcToolRunner(listOf(StubTool("echo") { ToolResult.Ok("hello") }), ctx) { false }
        runner.call("echo", "{}")
        return runner
    }

    @Test(timeout = 30_000)
    fun successCarriesSettledSubCallsAndProgramSteps() = runBlocking {
        val runner = settledRunner()
        val result = RunCodeTool.programOutcome(
            CodeRunResult(valueJson = null, logs = emptyList(), error = null, durationMs = 10, toolCalls = 1),
            runner,
        )
        val ok = result as ToolResult.Ok
        assertEquals("(run_code completed with no output)", ok.text)
        assertEquals(1, ok.steps)
        assertEquals(listOf("echo"), ok.subCalls.map { it.name })
    }

    @Test(timeout = 30_000)
    fun failureCarriesSettledSubCallsStepsAndTheCompletedReceipt() = runBlocking {
        val runner = settledRunner()
        val result = RunCodeTool.programOutcome(
            CodeRunResult(
                valueJson = null,
                logs = emptyList(),
                error = "boom",
                durationMs = 10,
                toolCalls = 1,
                failureKind = "exception",
            ),
            runner,
        )
        val error = result as ToolResult.Error
        assertTrue(error.message, error.message.startsWith("code run failed (exception): boom"))
        assertTrue(error.message, error.message.contains("Sub-calls that already completed (1)"))
        assertEquals("步数在失败路径也不能丢", 1, error.steps)
        assertEquals(listOf("echo"), error.subCalls.map { it.name })
    }

    @Test(timeout = 30_000)
    fun failureIncludesCapturedOutputAndTheFailureKind() = runBlocking {
        val runner = settledRunner()
        val error = RunCodeTool.programOutcome(
            CodeRunResult(
                valueJson = null,
                logs = listOf("line one", "line two"),
                error = "boom",
                durationMs = 10,
                toolCalls = 0,
                failureKind = "timeout",
            ),
            runner,
        ) as ToolResult.Error
        assertTrue(error.message, error.message.contains("code run failed (timeout): boom"))
        assertTrue(error.message, error.message.contains("Captured output:\nline one\nline two"))
    }

    @Test(timeout = 30_000)
    fun successRendersLogsThenTheReturnedValue() = runBlocking {
        val runner = settledRunner()
        val ok = RunCodeTool.programOutcome(
            CodeRunResult(
                valueJson = "{\"ok\":true}",
                logs = listOf("logged"),
                error = null,
                durationMs = 5,
                toolCalls = 0,
            ),
            runner,
        ) as ToolResult.Ok
        assertEquals("logged\n{\"ok\":true}", ok.text)
    }

    @Test
    fun abortedRunThrowsCancellationInsteadOfBecomingAToolFailure() {
        val runner = PtcToolRunner(emptyList(), ctx) { false }
        assertThrows(CancellationException::class.java) {
            RunCodeTool.programOutcome(
                CodeRunResult(
                    valueJson = null,
                    logs = emptyList(),
                    error = null,
                    durationMs = 0,
                    toolCalls = 0,
                    aborted = true,
                ),
                runner,
            )
        }
    }
}
