package com.adsh.app.core.tools

import com.adsh.app.core.workspace.Workspace
import com.adsh.app.core.workspace.WorkspaceForm
import com.adsh.app.seam.exec.ExecResult
import com.adsh.app.seam.exec.Permissions
import com.adsh.app.seam.exec.Shell
import com.adsh.app.seam.exec.ShellProcess
import com.adsh.app.seam.tool.Question
import com.adsh.app.seam.tool.QuestionOption
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ask_user_question 的参数形状校验：dsh 的 defineTool 在 execute 前按 parameters schema 整条拒绝
 * （violation 文案与粒度照 dsh 的 json-schema），空串 id/question、空 options、重复 id 按 dsh legacy 放行。
 */
class AskUserArgsTest {

    /** 只为构造 [ToolContext]：本组用例都在起提问往返之前返回 */
    private class FakeShell(private val tmp: File) : Shell {
        override val bashPath = "/nonexistent/bash"
        override val ripgrepPath = "/nonexistent/rg"

        override fun tmpDir(): File = tmp
        override fun scratchDir(): File = tmp
        override fun cacheRoots(): List<File> = emptyList()
        override fun fenceEnv(mode: String, roots: List<File?>, mark: File?): Array<String> = emptyArray()

        override fun spawn(
            command: String,
            workspaceRoot: File?,
            extraEnv: Array<String>?,
            separateStreams: Boolean,
        ): ShellProcess = throw UnsupportedOperationException("unit test must not spawn")

        override fun run(
            command: String,
            workspaceRoot: File?,
            timeoutMs: Long,
            maxOutputBytes: Int,
            extraEnv: Array<String>?,
            separateStreams: Boolean,
            cancel: (() -> Boolean)?,
        ): ExecResult = throw UnsupportedOperationException("unit test must not run commands")
    }

    private fun ctx(): ToolContext = ToolContext(
        workspace = Workspace(WorkspaceForm.PRIVATE, File("unused"), "test"),
        runtime = FakeShell(File("unused")),
        permission = Permissions.FULL_ACCESS,
    )

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun accepted(raw: String): List<Question> {
        val parsed = parseAskUserArgs(json(raw))
        assertTrue("unexpected rejection: " + parsed, parsed is AskArgs.Accepted)
        return (parsed as AskArgs.Accepted).questions
    }

    private fun violations(raw: String): List<String> {
        val parsed = parseAskUserArgs(json(raw))
        assertTrue("unexpected acceptance: " + parsed, parsed is AskArgs.Rejected)
        return (parsed as AskArgs.Rejected).violations
    }

    // ---------------------------------------------------------------- 合法

    @Test
    fun validMinimalQuestionIsAccepted() {
        val questions = accepted("""{"questions":[{"id":"q1","question":"继续？"}]}""")
        assertEquals(1, questions.size)
        assertEquals("q1", questions[0].id)
        assertEquals("继续？", questions[0].question)
        assertEquals(emptyList<QuestionOption>(), questions[0].options)
        assertFalse(questions[0].multiSelect)
    }

    @Test
    fun validFullQuestionIsAccepted() {
        val question = accepted(
            """{"questions":[{"id":"scope","question":"选范围","header":"确认",""" +
                """"options":[{"label":"全部","description":"含隐藏文件"},{"label":"仅源码"}],""" +
                """"multi_select":true}]}""",
        ).single()
        assertEquals("scope", question.id)
        assertEquals("选范围", question.question)
        assertEquals("确认", question.header)
        assertEquals(
            listOf(QuestionOption("全部", "含隐藏文件"), QuestionOption("仅源码", null)),
            question.options,
        )
        assertTrue(question.multiSelect)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val question = accepted(
            """{"questions":[{"id":"q","question":"x","intent":"plan-review",""" +
                """"options":[{"label":"a","color":"red"}]}],"extra":1}""",
        ).single()
        assertEquals(listOf(QuestionOption("a", null)), question.options)
    }

    @Test
    fun emptyIdAndEmptyQuestionAreAccepted() {
        val question = accepted("""{"questions":[{"id":"","question":""}]}""").single()
        assertEquals("", question.id)
        assertEquals("", question.question)
    }

    @Test
    fun emptyOptionsIsAnOpenQuestion() {
        val question = accepted("""{"questions":[{"id":"q","question":"x","options":[]}]}""").single()
        assertEquals(emptyList<QuestionOption>(), question.options)
    }

    @Test
    fun duplicateIdsAreAccepted() {
        val questions = accepted(
            """{"questions":[{"id":"a","question":"x"},{"id":"a","question":"y"}]}""",
        )
        assertEquals(listOf("a", "a"), questions.map { it.id })
    }

    // ---------------------------------------------------------------- 非法形状

    @Test
    fun missingQuestionsIsRejected() {
        assertEquals(listOf("missing required property \"questions\""), violations("{}"))
    }

    @Test
    fun questionsNotArrayIsRejected() {
        for (raw in listOf("""{"questions":"q"}""", """{"questions":null}""", """{"questions":{}}""")) {
            assertEquals(listOf("\"questions\" must be an array"), violations(raw))
        }
    }

    @Test
    fun questionElementNotObjectIsRejected() {
        for (raw in listOf(
            """{"questions":["q"]}""",
            """{"questions":[null]}""",
            """{"questions":[1]}""",
            """{"questions":[[]]}""",
        )) {
            assertEquals(listOf("\"questions[0]\" must be an object"), violations(raw))
        }
    }

    @Test
    fun missingQuestionIdIsRejected() {
        assertEquals(
            listOf("missing required property \"questions[0].id\""),
            violations("""{"questions":[{"question":"x"}]}"""),
        )
    }

    @Test
    fun missingQuestionTextIsRejected() {
        assertEquals(
            listOf("missing required property \"questions[0].question\""),
            violations("""{"questions":[{"id":"q"}]}"""),
        )
    }

    @Test
    fun nonStringIdIsRejected() {
        for (literal in listOf("{}", "[]", "null", "1", "true")) {
            assertEquals(
                listOf("\"questions[0].id\" must be a string"),
                violations("""{"questions":[{"id":""" + literal + ""","question":"x"}]}"""),
            )
        }
    }

    @Test
    fun nonStringQuestionIsRejected() {
        for (literal in listOf("{}", "[]", "null", "1", "true")) {
            assertEquals(
                listOf("\"questions[0].question\" must be a string"),
                violations("""{"questions":[{"id":"q","question":""" + literal + """}]}"""),
            )
        }
    }

    @Test
    fun nonStringHeaderIsRejected() {
        for (literal in listOf("{}", "[]", "null", "1", "true")) {
            assertEquals(
                listOf("\"questions[0].header\" must be a string"),
                violations("""{"questions":[{"id":"q","question":"x","header":""" + literal + """}]}"""),
            )
        }
    }

    @Test
    fun nonArrayOptionsIsRejected() {
        for (literal in listOf("{}", "null", "1", "true", "\"yes\"")) {
            val rejected = violations("""{"questions":[{"id":"q","question":"x","options":""" + literal + """}]}""")
            assertEquals(listOf("\"questions[0].options\" must be an array"), rejected)
            assertNoImplementationLeak(rejected)
        }
    }

    @Test
    fun optionElementNotObjectIsRejected() {
        for (literal in listOf("[]", "null", "1", "true", "\"yes\"")) {
            assertEquals(
                listOf("\"questions[0].options[0]\" must be an object"),
                violations("""{"questions":[{"id":"q","question":"x","options":[""" + literal + """]}]}"""),
            )
        }
    }

    @Test
    fun missingOptionLabelIsRejected() {
        assertEquals(
            listOf("missing required property \"questions[0].options[0].label\""),
            violations("""{"questions":[{"id":"q","question":"x","options":[{"description":"d"}]}]}"""),
        )
    }

    @Test
    fun nonStringOptionLabelIsRejected() {
        for (literal in listOf("{}", "[]", "null", "1", "true")) {
            assertEquals(
                listOf("\"questions[0].options[0].label\" must be a string"),
                violations("""{"questions":[{"id":"q","question":"x","options":[{"label":""" + literal + """}]}]}"""),
            )
        }
    }

    @Test
    fun nonStringOptionDescriptionIsRejected() {
        for (literal in listOf("{}", "[]", "null", "1", "true")) {
            assertEquals(
                listOf("\"questions[0].options[0].description\" must be a string"),
                violations(
                    """{"questions":[{"id":"q","question":"x","options":[{"label":"a","description":""" +
                        literal + """}]}]}""",
                ),
            )
        }
    }

    @Test
    fun nonBooleanMultiSelectIsRejected() {
        for (literal in listOf("{}", "[]", "null", "1", "\"true\"")) {
            assertEquals(
                listOf("\"questions[0].multi_select\" must be a boolean"),
                violations("""{"questions":[{"id":"q","question":"x","multi_select":""" + literal + """}]}"""),
            )
        }
    }

    @Test
    fun violationsAreCollectedInDshOrder() {
        assertEquals(
            listOf(
                "missing required property \"questions[0].id\"",
                "missing required property \"questions[0].question\"",
                "\"questions[1].id\" must be a string",
                "\"questions[1].options\" must be an array",
            ),
            violations("""{"questions":[{},{"id":1,"question":"x","options":"y"}]}"""),
        )
    }

    @Test
    fun hostileValuesNeverThrowOrLeak() {
        val cases: List<Pair<String, List<String>>> = listOf(
            """{"questions":[{"id":%s,"question":"x"}]}""" to listOf("{}", "[]", "null", "1", "true"),
            """{"questions":[{"id":"q","question":%s}]}""" to listOf("{}", "[]", "null", "1", "true"),
            """{"questions":[{"id":"q","question":"x","header":%s}]}""" to listOf("{}", "[]", "null", "1", "true"),
            """{"questions":[{"id":"q","question":"x","options":%s}]}""" to listOf("{}", "null", "1", "true"),
            """{"questions":[{"id":"q","question":"x","options":[%s]}]}""" to listOf("{}", "[]", "null", "1", "true"),
            """{"questions":[{"id":"q","question":"x","options":[{"label":%s}]}]}""" to
                listOf("{}", "[]", "null", "1", "true"),
            """{"questions":[{"id":"q","question":"x","options":[{"label":"a","description":%s}]}]}""" to
                listOf("{}", "[]", "null", "1", "true"),
            """{"questions":[{"id":"q","question":"x","multi_select":%s}]}""" to listOf("{}", "[]", "null", "1"),
        )
        for ((template, literals) in cases) {
            for (literal in literals) {
                val raw = template.replace("%s", literal)
                val outcome = runCatching { parseAskUserArgs(json(raw)) }
                val parsed = outcome.getOrElse { throw AssertionError("parser threw for " + raw, it) }
                assertTrue("expected rejection for " + raw + ": " + parsed, parsed is AskArgs.Rejected)
                val rejected = (parsed as AskArgs.Rejected).violations
                assertTrue("violations must not be empty for " + raw, rejected.isNotEmpty())
                assertNoImplementationLeak(rejected)
            }
        }
    }

    private fun assertNoImplementationLeak(violations: List<String>) {
        val joined = violations.joinToString("; ")
        for (forbidden in listOf("kotlinx", "JsonLiteral", "Element class", "Kotlin reflection", "JsonArray", "JsonObject")) {
            assertFalse("violation leaked implementation detail: " + joined, joined.contains(forbidden))
        }
    }

    // ---------------------------------------------------------------- 空数组与工具层

    @Test
    fun emptyQuestionsArrayIsAcceptedByParser() {
        val parsed = parseAskUserArgs(json("""{"questions":[]}"""))
        assertTrue("dsh schema allows an empty array", parsed is AskArgs.Accepted)
        assertTrue((parsed as AskArgs.Accepted).questions.isEmpty())
    }

    @Test
    fun toolReportsEmptyQuestionsWithDshMessage() = runBlocking {
        val result = AskUserTool.execute(json("""{"questions":[]}"""), ctx())
        val error = result as ToolResult.Error
        assertEquals("ask_user_question requires at least one question", error.message)
        assertEquals("EMPTY_QUESTIONS", error.code)
    }

    @Test
    fun toolReportsNonArrayOptionsAsInvalidArgs() = runBlocking {
        val result = AskUserTool.execute(
            json("""{"questions":[{"id":"q","question":"x","options":"yes"}]}"""),
            ctx(),
        )
        val error = result as ToolResult.Error
        assertEquals("invalid arguments: \"questions[0].options\" must be an array", error.message)
        assertEquals("INVALID_ARGS", error.code)
    }

    @Test
    fun toolReportsMissingQuestionsAsInvalidArgs() = runBlocking {
        val result = AskUserTool.execute(json("{}"), ctx())
        val error = result as ToolResult.Error
        assertEquals("invalid arguments: missing required property \"questions\"", error.message)
        assertEquals("INVALID_ARGS", error.code)
    }
}
