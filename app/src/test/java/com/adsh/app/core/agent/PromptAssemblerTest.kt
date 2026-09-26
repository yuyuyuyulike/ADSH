package com.adsh.app.core.agent

import com.adsh.app.core.tools.ToolSdk
import com.adsh.app.core.workspace.Workspace
import com.adsh.app.core.workspace.WorkspaceForm
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统提示词的**结构不变量**。
 *
 * 第五十九轮（用户点名的三处重复）：工作区路径只出现一次；「拒绝是策略、不是 bug」只说一次；
 * 临时目录口径只有一处（android-termux 段）。
 *
 * 第六十轮（按审查报告重写）：身份只有一句；散文段只剩跨工具规则，工具自身的说明只在 SDK 里
 * 写一次；升级流程只有一份；不再出现 ADSH 没有的能力（subagent / 后台任务）；术语统一成 workspace。
 */
class PromptAssemblerTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "adsh-prompt-test").apply { mkdirs() }
    private val cwd = root.absolutePath
    private val modes = listOf("read-only", "workspace-write", "danger-full-access")

    private fun assemble(mode: String, planMode: Boolean = false): String = PromptAssembler.buildParts(
        workspace = Workspace(WorkspaceForm.REAL_PATH, root, "ws"),
        extraSuffix = "",
        previousWorkspacePath = null,
        filePolicy = mode,
        planMode = planMode,
        model = "deepseek-flash",
        allowParallel = true,
    ).system

    private fun count(text: String, needle: String): Int {
        var index = 0
        var found = 0
        while (true) {
            val next = text.indexOf(needle, index)
            if (next < 0) return found
            found++
            index = next + needle.length
        }
    }

    /** 目视检查用：把几种形态的系统提示词写到临时目录（顺带当装配冒烟测试） */
    @Test
    fun dumpAssembledPromptForReview() {
        val directory = File(System.getProperty("java.io.tmpdir") ?: ".")
        (modes + listOf("plan-mode")).forEach { mode ->
            val file = File(directory, "adsh-prompt-" + mode + ".txt")
            file.writeText(assemble(if (mode == "plan-mode") "workspace-write" else mode, mode == "plan-mode"))
            assertTrue(file.length() > 0)
        }
        val noWorkspace = File(directory, "adsh-prompt-no-workspace.txt")
        noWorkspace.writeText(
            PromptAssembler.buildWithoutWorkspace(model = "deepseek-flash", extraSuffix = "").system,
        )
        assertTrue(noWorkspace.length() > 0)
        println("ADSH_PROMPT_DUMP=" + File(directory, "adsh-prompt-workspace-write.txt").absolutePath)
    }

    @Test
    fun identityIsOneSentence() {
        modes.forEach { mode ->
            val system = assemble(mode)
            assertEquals("harness identity once (" + mode + ")", 1, count(system, "You are an AI agent powered by"))
            assertFalse("no second \"You are …\" identity (" + mode + ")", system.contains("You are a coding agent"))
            assertTrue(system.contains("currently running the deepseek-flash model"))
        }
    }

    @Test
    fun workspacePathAppearsExactlyOnce() {
        modes.forEach { mode ->
            val system = assemble(mode)
            assertEquals("workspace path must appear once only (" + mode + ")", 1, count(system, cwd))
            assertTrue(
                "the trailing dynamic cwd line must stay (" + mode + ")",
                system.contains("Your working directory is " + cwd + "."),
            )
        }
    }

    @Test
    fun denialIsPolicyStatementAppearsOnceAtMost() {
        modes.forEach { mode ->
            val hits = count(assemble(mode), "not a bug")
            assertTrue("policy-denial statement at most once (" + mode + ", got " + hits + ")", hits <= 1)
        }
        assertTrue(assemble("read-only").contains("Do not refuse a required modification from this policy alone"))
        assertTrue(assemble("workspace-write").contains("A blocked operation is a policy denial, not a bug"))
    }

    @Test
    fun vagueTemporaryAreaSentenceIsGone() {
        modes.forEach { mode ->
            assertFalse(
                "the vague temporary-area sentence must be gone (" + mode + ")",
                assemble(mode).contains("temporary areas may also be writable"),
            )
        }
        assertTrue(assemble("workspace-write").contains("There is no writable /tmp on Android"))
    }

    /** 升级流程只写一遍（第六十轮从 bash / edit / write 三处上移到跨工具规则段） */
    @Test
    fun escalationProcedureAppearsExactlyOnce() {
        modes.forEach { mode ->
            val system = assemble(mode)
            assertEquals(
                "escalation rule must appear exactly once (" + mode + ")",
                1,
                count(system, "the approval prompt raised by that retry is how the user consents"),
            )
            assertEquals("denial marker once (" + mode + ")", 1, count(system, "[sandbox: file access denied under"))
            assertTrue("the marker shows a real mode (" + mode + ")", system.contains("for example `workspace-write`"))
        }
        assertFalse("bash description no longer carries the procedure", ToolSdk.BASH_DESCRIPTION.contains("sandbox_permissions"))
        assertFalse("bash description no longer carries the marker", ToolSdk.BASH_DESCRIPTION.contains("[sandbox:"))
        assertFalse("edit description no longer carries the procedure", ToolSdk.description("edit").contains("sandbox_permissions"))
        assertFalse("write description no longer carries the procedure", ToolSdk.description("write").contains("sandbox_permissions"))
        assertFalse(
            "the seven-line escalation paragraph must not come back",
            assemble("workspace-write").contains("Do not detour through chat to ask permission first"),
        )
    }

    /** 审查报告 R-0：散文段不再复述 SDK 里已经写过的工具说明 */
    @Test
    fun toolProseNoLongerDuplicatesTheSdk() {
        modes.forEach { mode ->
            val system = assemble(mode)
            assertEquals("one cross-tool rules section (" + mode + ")", 1, count(system, "## Working rules"))
            listOf(
                "Use the read tool",
                "Use the write tool",
                "Use the glob tool",
                "Use the grep tool",
                "Use the web_search tool",
                "Results include line numbers",
            ).forEach { gone ->
                assertFalse("per-tool prose must be gone: " + gone + " (" + mode + ")", system.contains(gone))
            }
            assertTrue("the cross-tool rules stay (" + mode + ")", system.contains("Read text files with `tools.read`"))
        }
    }

    /** 审查报告 C-2：提示词不许引用 ADSH 没有的能力 */
    @Test
    fun noGhostCapabilities() {
        modes.forEach { mode ->
            val lower = assemble(mode).lowercase()
            listOf("subagent", "sub-agent", "run_in_background", "job_output", "job_kill", "background commands")
                .forEach { ghost -> assertFalse("ghost capability: " + ghost, lower.contains(ghost)) }
        }
    }

    /** 审查报告 N-1 / N-2：术语统一成 workspace，不再出现 Session ×× */
    @Test
    fun terminologyIsUnified() {
        modes.forEach { mode ->
            val lower = assemble(mode).lowercase()
            listOf("session workspace", "session working directory", "session filesystem").forEach { term ->
                assertFalse("stale term: " + term, lower.contains(term))
            }
        }
        assertTrue(ToolSdk.PRESENT_DESCRIPTION.contains("not a deliverable"))
    }

    /** 审查报告 C-5：grep 与 glob 的可见集不同，必须在 grep 的说明里点出来 */
    @Test
    fun grepDisclosesItsSmallerVisibleSet() {
        assertTrue(ToolSdk.description("grep").contains("skips hidden and ignored paths"))
        assertTrue(ToolSdk.description("glob").contains("hidden and ignored files"))
    }

    /**
     * 第六十一轮（用户点名「提示词里还有重复」）：**逐字重复的段落各只留一处**。
     *  1. 「工作区在哪」这句只在 Working rules 的 Paths 那条里说一次（沙箱策略里不再跟着复述）；
     *  2. bash 输出截断的机制只在 bash 描述里讲一次（规则段只留「怎么办」）；
     *  3. 升级参数的两行说明从 bash / edit / write 三处收进 SDK 段首一句总说明；
     *  4. todo 的状态联合类型收进 `type TodoStatus` 别名（args 与 output 各写一遍就是两份）。
     */
    @Test
    fun verbatimParagraphsAppearOnce() {
        modes.forEach { mode ->
            val system = assemble(mode)
            assertEquals(
                "the path pointer must appear once only (" + mode + ")",
                1,
                count(system, "the directory named at the end of this prompt"),
            )
            assertEquals(
                "the truncation mechanism belongs to the bash description only (" + mode + ")",
                1,
                count(system.lowercase(), "capped per stream"),
            )
            assertEquals(
                "the escalation note points at the rules instead of restating them (" + mode + ")",
                1,
                count(system, "their use is the one-shot retry described under Working rules"),
            )
            assertFalse(
                "the per-tool escalation doc comments must be gone (" + mode + ")",
                system.contains("The wider sandbox mode this call needs"),
            )
            // union 只允许出现在别名那一行；args 与 output 都写 `status: TodoStatus;`
            assertEquals(
                "the todo status union is declared once (" + mode + ")",
                1,
                count(system, "\"pending\" | \"in_progress\" | \"completed\""),
            )
            assertEquals("args and output share the alias (" + mode + ")", 2, count(system, "status: TodoStatus;"))
            assertTrue(system.contains("type TodoStatus = \"pending\" | \"in_progress\" | \"completed\""))
        }
        assertTrue(assemble("workspace-write").contains("may modify files under the workspace."))
    }

    /**
     * 第六十二轮：按测试 agent 的《提示词重复审计报告》逐条核对（报告 §5 的校验清单直接钉在这里）。
     * 计数范围 = 系统提示词 + run_code 的 schema（两者每轮都一起注入）。
     */
    @Test
    fun auditChecklistHolds() {
        modes.forEach { mode ->
            val system = assemble(mode)
            assertEquals("R2: description 规范只留 Working rules", 1, count(system, "5-10 words"))
            assertEquals("R2: schema 只写参数含义", 1, count(system, "a short summary of what the program does"))
            assertEquals("R3: 不再复述升级规则", 0, count(system, "for the single retry described under Working rules"))
            assertEquals("R4: 状态含义只在别名注释里", 1, count(system, "(not started)"))
            assertEquals("R4: todo 描述只渲染一次", 1, count(system, "Send the ENTIRE list every call"))
            assertEquals("R5: program output 只说一次", 1, count(system, "program output"))
            assertEquals("R7: directories 规则只留参数文档", 0, count(system, "unless `directories` is false"))
            assertEquals("R7: 参数默认值仍在", 1, count(system, "Defaults to true"))
            // 段标题「Program-only SDK bindings:」不算复述，只数那句声明
            assertEquals("R8: SDK binding 只说一次", 1, count(system, "are SDK bindings"))
        }
        // R1/R5：run_code 的 schema 不再复述系统提示词里的机制与输出规则
        assertFalse(ToolSdk.RUN_CODE_DESCRIPTION.contains("QuickJS"))
        assertFalse(ToolSdk.RUN_CODE_DESCRIPTION.contains("program output"))
        assertFalse(ToolSdk.RUN_CODE_DESCRIPTION.contains("tools.name(args)"))
        assertFalse(ToolSdk.RUN_CODE_DESCRIPTION_PARAM_DESCRIPTION.contains("5-10 words"))
        // R1（第 81 轮修订）：**语言约束**这一条留在 schema 里。dsh 跑可擦除 TypeScript，
        // 写类型标注没事；本运行时是 QuickJS，同一个标注是语法错误 —— 用户实测 run_code 报错
        // 比 dsh 多，这条是模型最需要提前知道、又最容易踩的。别的仍然只说不一遍。
        assertTrue(ToolSdk.RUN_CODE_DESCRIPTION.contains("TypeScript syntax"))
    }

    /**
     * 第六十九轮：**「一步一个程序」这句必须在**。
     *
     * 模型以前会连着发好几个 run_code 去够不同的工具（用户看到的就是「一次蹦出来多个 run_code
     * 工具调用」）。dsh 的 PTC_ONLY 原文有第二句 "Reach every tool the SDK declares below from
     * inside the program."，ADSH 早先漏了它，只剩「点名别的工具会失败」那半句 —— 声明与够法之间
     * 断了。两句一起钉住：规则段说「从程序里够到工具」，SDK 段说「整步写在一个程序里」。
     */
    @Test
    fun ptcAsksForOneProgramPerStep() {
        modes.forEach { mode ->
            val system = assemble(mode)
            assertTrue(
                "the PTC rule must say how to reach the declared tools (" + mode + ")",
                system.contains("Reach every tool the SDK declares below from inside the program."),
            )
            assertEquals(
                "the one-program rule is stated once (" + mode + ")",
                1,
                count(system, "Do the step's work in ONE program"),
            )
        }
        // 别退化成「同一件事写两遍」：SDK 那句只说写法，规则段那句只说够法
        assertFalse(ToolSdk.PTC_ONLY_INSTRUCTION.contains("ONE program"))
    }
}
