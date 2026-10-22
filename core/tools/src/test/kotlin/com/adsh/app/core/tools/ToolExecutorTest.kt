package com.adsh.app.core.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行流水线的状态表：PTC collapse / unknown 文案 / 参数形状 / 异常映射 / 取消透传 /
 * 脱敏只做一次 / 值原样 / steps 与 subCalls 透传。两条入口（direct / binding）只差文案。
 */
class ToolExecutorTest {

    private val ctx = testToolContext()

    private fun executor(vararg tools: Tool) = ToolExecutor(tools.toList())

    private fun echo(name: String, body: suspend (JsonObject) -> ToolResult) = StubTool(name, body = body)

    // ------------------------------------------------------------ PTC collapse / unknown

    @Test
    fun directCallRejectsEveryKnownToolExceptRunCode() = runBlocking {
        val ex = executor(echo("bash") { ToolResult.Ok("ran") }, echo("run_code") { ToolResult.Ok("program") })
        val outcome = ex.runDirect("bash", "{}", ctx)
        assertFalse(outcome.ok)
        assertNull(outcome.value)
        assertEquals(
            "unknown tool \"bash\": only `run_code` is callable directly — call `bash` " +
                "from inside a `run_code` program instead",
            outcome.text,
        )
        assertEquals("失败只计顶层 1 步", 1, outcome.steps)
    }

    @Test
    fun directUnknownNameKeepsThePlainDshWording() = runBlocking {
        val ex = executor(echo("run_code") { ToolResult.Ok("program") })
        assertEquals("unknown tool \"nope\"", ex.runDirect("nope", "{}", ctx).text)
        // run_code 本身在执行器里找不到时也走 direct 的 unknown 文案
        assertEquals("unknown tool \"run_code\"", ToolExecutor(emptyList()).runDirect("run_code", "{}", ctx).text)
    }

    @Test
    fun bindingUnknownNameUsesTheColonWording() = runBlocking {
        val ex = executor(echo("bash") { ToolResult.Ok("ran") })
        assertEquals("unknown tool: nope", ex.runBinding("nope", "{}", ctx).text)
    }

    @Test
    fun runCodeIsTheOnlyDirectlyExecutableTool() = runBlocking {
        val ex = executor(echo("run_code") { ToolResult.Ok("program output") })
        val outcome = ex.runDirect("run_code", "{}", ctx)
        assertTrue(outcome.ok)
        assertEquals("program output", outcome.text)
    }

    // ------------------------------------------------------------ 参数形状

    @Test
    fun argumentShapeErrorsKeepTheTwoWordingForms() = runBlocking {
        val ex = executor(echo("echo") { ToolResult.Ok("never") }, echo("run_code") { ToolResult.Ok("never") })
        assertEquals("arguments are not a JSON object: []", ex.runBinding("echo", "[]", ctx).text)
        assertEquals("tool arguments are not a JSON object: []", ex.runDirect("run_code", "[]", ctx).text)
    }

    @Test
    fun argumentShapeErrorsShowAtMostTwoHundredCharacters() = runBlocking {
        val ex = executor(echo("echo") { ToolResult.Ok("never") })
        val raw = "x".repeat(300)
        val expected = "arguments are not a JSON object: " + "x".repeat(200)
        assertEquals(expected, ex.runBinding("echo", raw, ctx).text)
    }

    // ------------------------------------------------------------ 异常映射

    @Test
    fun toolExceptionsBecomeErrorsWithTheDirectWording() = runBlocking {
        val boom = executor(echo("run_code") { throw IllegalStateException("boom") })
        val quiet = executor(echo("run_code") { throw IllegalStateException() })
        assertEquals("boom", boom.runDirect("run_code", "{}", ctx).text)
        assertEquals("IllegalStateException", quiet.runDirect("run_code", "{}", ctx).text)
    }

    @Test
    fun toolExceptionsBecomeErrorsWithTheBindingWording() = runBlocking {
        // binding 的兜底文案多一句 (internal tool error)，并打一条宿主日志（日志失败不影响结果）
        val quiet = executor(echo("echo") { throw IllegalStateException() })
        assertEquals("IllegalStateException (internal tool error)", quiet.runBinding("echo", "{}", ctx).text)
        val boom = executor(echo("echo") { throw IllegalStateException("kaboom") })
        assertEquals("kaboom", boom.runBinding("echo", "{}", ctx).text)
    }

    // ------------------------------------------------------------ 取消

    @Test
    fun cancellationIsRethrownInsteadOfBecomingAToolFailure() {
        val direct = executor(echo("run_code") { throw CancellationException("stop") })
        assertThrows(CancellationException::class.java) {
            runBlocking { direct.runDirect("run_code", "{}", ctx) }
        }
        val binding = executor(echo("echo") { throw CancellationException("stop") })
        assertThrows(CancellationException::class.java) {
            runBlocking { binding.runBinding("echo", "{}", ctx) }
        }
    }

    // ------------------------------------------------------------ 脱敏 / 值 / steps

    @Test
    fun redactionRunsOnceOnTextAndNeverTouchesTheValue() = runBlocking {
        val token = "ghp_" + "a".repeat(36)
        val ex = executor(
            echo("echo") {
                ToolResult.Ok("token=" + token, buildJsonObject { put("token", token) })
            },
        )
        val outcome = ex.runBinding("echo", "{}", ctx)
        assertTrue(outcome.text, outcome.text.contains("***redacted***"))
        assertFalse(outcome.text, outcome.text.contains(token))
        assertEquals(token, (outcome.value as JsonObject)["token"]!!.jsonPrimitive.content)
    }

    @Test
    fun valueFallsBackToTheTextJsonPrimitiveOnlyWhenTheToolDeclaresNone() = runBlocking {
        val withValue = executor(echo("echo") { ToolResult.Ok("t", buildJsonObject { put("x", 1) }) })
        val plain = executor(echo("echo") { ToolResult.Ok("plain text") })
        assertEquals(
            buildJsonObject { put("x", 1) },
            withValue.runBinding("echo", "{}", ctx).value,
        )
        assertEquals(JsonPrimitive("plain text"), plain.runBinding("echo", "{}", ctx).value)
        // 显式 null 值不退化（工具明确声明了 value = null 的语义）
        val explicitNull = executor(echo("echo") { ToolResult.Ok("t", JsonNull) })
        assertEquals(JsonNull, explicitNull.runBinding("echo", "{}", ctx).value)
    }

    @Test
    fun okOutcomeCarriesImagesDeliverablesSubCallsAndSteps() = runBlocking {
        val sub = com.adsh.app.seam.ptc.SubCall(
            name = "read", args = "{}", ok = true, result = "x", durationMs = 1, id = "1:ptc:1",
        )
        val deliverable = com.adsh.app.seam.tool.PresentedFile(path = "a.txt", description = "d")
        val ex = executor(
            echo("echo") {
                ToolResult.Ok(
                    "done",
                    subCalls = listOf(sub),
                    steps = 3,
                    deliverables = listOf(deliverable),
                )
            },
        )
        val outcome = ex.runBinding("echo", "{}", ctx)
        assertTrue(outcome.ok)
        assertEquals(4, outcome.steps)
        assertEquals(listOf(sub), outcome.subCalls)
        assertEquals(listOf(deliverable), outcome.deliverables)
    }

    @Test
    fun errorOutcomeCarriesRetryableCodeSubCallsAndSteps() = runBlocking {
        val sub = com.adsh.app.seam.ptc.SubCall(
            name = "read", args = "{}", ok = false, result = "boom", durationMs = 1, id = "1:ptc:1",
        )
        val ex = executor(
            echo("echo") {
                ToolResult.Error("failed", retryable = true, code = "E_TEST", subCalls = listOf(sub), steps = 2)
            },
        )
        val outcome = ex.runBinding("echo", "{}", ctx)
        assertFalse(outcome.ok)
        assertEquals("failed", outcome.text)
        assertTrue(outcome.retryable)
        assertEquals("E_TEST", outcome.code)
        assertNull(outcome.value)
        assertEquals(listOf(sub), outcome.subCalls)
        assertEquals(3, outcome.steps)
    }

    @Test
    fun toolContextIsPassedThroughToTheTool() = runBlocking {
        val seen = arrayOfNulls<Long>(1)
        val ex = executor(
            StubTool("echo") {
                seen[0] = ctx.execToken
                ToolResult.Ok("ok")
            },
        )
        assertNotNull(ex.runBinding("echo", "{}", ctx))
        assertEquals(7L, seen[0])
    }
}
