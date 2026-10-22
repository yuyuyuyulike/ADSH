package com.adsh.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具定义单一事实源的 parity：registry、tools:sdk 投影、并发分类、各工具自己的 spec
 * 必须来自同一份定义 —— 任意两处对不上，模型看到的就是谎话（提示词说 A、执行按 B）。
 */
class ToolDefinitionParityTest {

    private val allToolNames = listOf(
        "ask_user_question", "bash", "edit", "exit_plan_mode", "glob", "grep", "job_kill",
        "job_list", "job_output", "present", "read", "read_image", "run_code", "todo_write",
        "web_fetch", "web_search", "write",
    )

    /** 可与其它调用重叠执行的工具（`Tool.parallelSafe = true`） */
    private val safeTools = setOf(
        "ask_user_question", "glob", "grep", "job_list", "job_output", "present",
        "read", "read_image", "todo_write", "web_fetch", "web_search",
    )

    @Test
    fun registryHasEveryToolExactlyOnce() {
        assertEquals(17, ToolRegistry.all.size)
        assertEquals(allToolNames.sorted(), ToolRegistry.all.map { it.name }.sorted())
        assertEquals(ToolRegistry.all.size, ToolRegistry.all.map { it.name }.toSet().size)
        assertEquals(16, ToolRegistry.bindings.size)
        assertFalse("run_code 不能出现在 PTC 绑定里（避免递归）", ToolRegistry.bindings.any { it.name == "run_code" })
    }

    @Test
    fun registrySpecsAreTheProjectionOfTheToolObjects() {
        assertEquals(ToolRegistry.all.map { it.spec }, ToolRegistry.specs)
        ToolRegistry.all.forEach { tool ->
            assertEquals(tool.spec.name, tool.name)
            assertEquals(tool.spec.description, tool.description)
        }
    }

    @Test
    fun sdkSpecsAreExactlyTheBindingSpecs() {
        assertEquals(ToolRegistry.bindings.map { it.spec }, ToolSdk.specs)
        assertFalse("run_code 的 schema 单独下发，不在 SDK 段里", ToolSdk.specs.any { it.name == "run_code" })
    }

    @Test
    fun eachToolOwnsTheTextTheSdkRenders() {
        ToolRegistry.bindings.forEach { tool ->
            assertEquals(tool.spec, ToolSdk.specs.first { it.name == tool.name })
        }
        assertEquals(RunCodeTool.spec.description, ToolSdk.RUN_CODE_DESCRIPTION)
        assertEquals(BashTool.spec.description, ToolSdk.BASH_DESCRIPTION)
        assertEquals(PresentTool.spec.description, ToolSdk.PRESENT_DESCRIPTION)
        assertEquals(TodoTool.spec.description, ToolSdk.TODO_DESCRIPTION)
    }

    @Test
    fun parallelSafeNamesAreTheSortedProjection() {
        assertEquals(safeTools.sorted(), ToolRegistry.parallelSafeNames)
        assertEquals(
            "parallelSafe 必须精确等于工具定义的投影",
            safeTools,
            ToolRegistry.all.filter { it.parallelSafe }.map { it.name }.toSet(),
        )
        assertFalse("run_code 是写类（独占）", RunCodeTool.parallelSafe)
        // 集合内每个名字只能出现一次、且有序
        assertEquals(ToolRegistry.parallelSafeNames.sorted(), ToolRegistry.parallelSafeNames)
        assertEquals(ToolRegistry.parallelSafeNames.size, ToolRegistry.parallelSafeNames.toSet().size)
    }

    @Test
    fun sdkSectionListsEveryBindingAndNoRunCode() {
        val section = ToolSdk.section()
        ToolSdk.specs.sortedBy { it.name }.forEach { spec ->
            assertTrue(spec.name, section.contains("\n  " + spec.name + ": " + spec.args + " & Record<string, JsonValue>;"))
            assertTrue(spec.name, section.contains("\n  " + spec.name + ": " + spec.output + ";"))
            assertTrue(spec.name, section.contains("  /** " + spec.description.replace("\n", " ") + " */"))
        }
        assertFalse("run_code 不进 SDK 段", section.contains("\n  run_code: "))
        // safe 清单从定义生成：排序 + 每个名字出现（提示词与调度器同一份）
        val rendered = ToolRegistry.parallelSafeNames.joinToString(", ") { "`" + it + "`" }
        assertTrue(section.contains("Safe: " + rendered + "."))
        assertFalse(section.contains("Safe: \n"))
    }

    @Test
    fun everyBindingDeclaresArgsAndOutputInTheSdkContract() {
        ToolSdk.specs.forEach { spec ->
            assertTrue(spec.name + " 缺 args 声明", spec.args.isNotBlank())
            assertTrue(spec.name + " 缺 output 声明", spec.output.isNotBlank())
            assertTrue(spec.name + " 缺 description", spec.description.isNotBlank())
        }
        // run_code 的 schema 走 wireSchema，spec 的 SDK 文本为空是有意的
        assertEquals("", RunCodeTool.spec.args)
        assertEquals("", RunCodeTool.spec.output)
        assertTrue(RunCodeTool.wireSchema.toString().contains("\"run_code\""))
    }

    @Test
    fun toolSpecsAreStableValueObjects() {
        val bash = BashTool.spec
        assertEquals("bash", bash.name)
        assertEquals(bash, ToolSpec(bash.name, bash.description, bash.args, bash.output))
        assertTrue(bash.args.contains("command: string"))
        assertTrue(bash.output.contains("kind: \"foreground\""))
    }

    @Test
    fun jsonValueFallbackIsIdempotent() {
        // 占位防误删：spec 正文里不允许出现 Kotlin 侧的中文旁注/轮次（AGENTS.md 提示词约束）
        ToolSdk.specs.forEach { spec ->
            val text = spec.description + spec.args + spec.output
            assertFalse(spec.name, text.contains("第"))
            assertFalse(spec.name, text.contains("轮"))
        }
    }
}
