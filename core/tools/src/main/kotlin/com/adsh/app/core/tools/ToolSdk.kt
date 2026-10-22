package com.adsh.app.core.tools

/**
 * 系统提示词里的工具说明段（`tools:sdk`）—— 对齐 dsh 的 renderToolsSdk
 * （dsh-tools/lib/types/ts-types.js）。
 *
 * dsh 在 ptc 模式下对模型**只暴露 run_code 一个工具**（wireSchemas 里只 filter 出
 * RUN_CODE_NAME），其余工具全部通过系统提示词的 tools:sdk 段落声明，模型在程序里用
 * await tools.<name>(args) 调用。所以「工具清单」的唯一来源是 [ToolRegistry]，这里只做投影。
 *
 * 工具自身的说明（description / args / output）只在各 `Tool.spec` 里写一次；跨工具的规则只在
 * PromptAssembler.workingRules 写一次 —— 不许出现第二份会漂移的副本。
 *
 * 说明文字里的数字上限在实现那头也有定义，两边靠人肉同步，改一处必须改另一处：
 *
 *  - `glob` 的 100 ↔ `Tools.kt` 的 `GLOB_MAX_RESULTS`
 *  - `grep` 的 250 ↔ `Tools.kt` 的 `GREP_MAX_MATCHES`
 *  - `read` 的 2000 行 ↔ `Tools.kt` 的 `READ_MAX_LINES`
 *  - bash 的 64 KB ↔ `Shell.DEFAULT_MAX_OUTPUT_BYTES`（= 64_000，不是 65536）
 *
 * 不把常量插进说明文字：这些描述以 dsh 原文为底本，插值后 diff 里看不出哪一句偏离了 dsh。
 */
object ToolSdk {

    /**
     * dsh 的 PTC_ONLY_INSTRUCTION，两句都要在（PromptAssemblerTest 钉着）。
     * 缺第二句时，模型会**反复发多个 run_code 调用**去够不同的工具，而不是在一个程序里做完一步。
     */
    val PTC_ONLY_INSTRUCTION: String =
        "`run_code` is the only tool you can call directly — a tool call naming any other tool fails. " +
            "Reach every tool the SDK declares below from inside the program."

    /**
     * dsh 的 FILE_REFERENCE 段（order 900）：@路径 的读法，来源是 dsh-file-reference 注册的
     * systemPrompt.section。两处按本客户端改写：「列目录」明确用 tools.glob（ADSH 的 glob 连
     * 目录一起返回，dsh 那句 "list it" 没说用什么列），「读文件」写成 tools.read —— 与
     * PTC_ONLY 的口径一致。
     */
    val FILE_REFERENCE_INSTRUCTION: String =
        "Tokens prefixed with @ are workspace paths the user explicitly referenced, relative to the " +
            "workspace root. A trailing slash marks a directory: list it with `tools.glob` when its " +
            "contents matter. Anything else is a file: read it with `tools.read` when its contents are " +
            "needed, and do not claim to have inspected it before reading. \"@...\" quotes a path " +
            "containing spaces."

    /**
     * dsh-tool-jobs 注册的 systemPrompt.section（tool:jobs，逐字）。
     *
     * 它是**跨工具的规则**（排在文件系统各段之后、SDK 段之前），与 bash 描述里那句「超时会转后台」
     * 分工：这里说的是「怎么对待后台任务」（记 id、别轮询、收尾前收干），描述里说的是参数怎么用。
     */
    const val JOBS_INSTRUCTION: String =
        "Track every background job id you start. You are notified in-session when a job finishes — do " +
            "not busy-poll or sleep on one; keep working on independent steps and do not duplicate a " +
            "running job's work. Before giving a final answer, collect every still-relevant job with " +
            "`tools.job_output` (set wait: true only when you are genuinely blocked on it), and " +
            "`tools.job_kill` jobs that stopped mattering."

    /**
     * 各工具说明文本的别名：正文只写在对应 `Tool.spec` 里，这里只做投影 —— 外部按名字取用时
     * 不复制文本，口径与调度器、SDK 段同一份。
     */
    val PRESENT_DESCRIPTION: String get() = PresentTool.spec.description
    val BASH_DESCRIPTION: String get() = BashTool.spec.description
    val TODO_DESCRIPTION: String get() = TodoTool.spec.description
    val RUN_CODE_DESCRIPTION: String get() = RunCodeTool.spec.description

    /**
     * 「可并行」工具清单的正文，**从工具定义生成**（`Tool.parallelSafe` 经
     * [ToolRegistry.parallelSafeNames] 投影）：不能让提示词只写「safe calls run concurrently」——
     * 哪些算 safe 全靠猜，而 `bash` 与只读工具在调用形状上完全同形，猜错就是并发写坏文件。
     * 清单从实现里生成、再用 parity 单测钉住，就不会出现「提示词说 A、调度器按 B」的漂移。
     */
    private val SAFE_TOOL_LIST: String get() =
        ToolRegistry.parallelSafeNames.joinToString(", ") { "`" + it + "`" }

    /** dsh 的 SDK_INSTRUCTIONS + renderBashExample */
    private val SDK_INSTRUCTIONS: String get() = """
        ## Writing code for run_code

        `run_code` takes two required arguments: `code` — the body of an async JavaScript function; top-level `await` and `return` work — and `description`, a short summary of what the program does (shown in the UI). The runtime is QuickJS, so the code is plain JavaScript: TypeScript syntax (type annotations, `enum`, `namespace`, `interface`) is a syntax error, not an annotation. There is **no host API behind it**: every Node.js built-in (`require`, `fs`, `process`, `Buffer`) is missing, and so is every platform global — no timers (`setTimeout`, `queueMicrotask`), no network or crypto (`fetch`, `crypto`, `URL`), no encoders (`TextEncoder`, `TextDecoder`, `atob`, `btoa`), and no `structuredClone`, `performance`, `WeakRef` or `Intl`: touching any of those names is a `ReferenceError`. What remains is plain ECMAScript plus the `tools.*` bindings below; anything else you find in the sandbox is an implementation detail, not an API. A step that needs a timer, a socket, an encoder or another process therefore belongs in `tools.bash`, where `sleep`, `curl`, `base64` and `xxd` are available. The declarations below are SDK bindings. A declaration does not make its name a directly callable tool; only names supplied as separate tool schemas may be called directly. Because `run_code` is the only separately supplied schema, call a declared binding from inside the program — the guidance above names them `tools.read`, `tools.bash`, and so on:

        `run_code({ code: "return await tools.bash({ command: 'pwd', description: 'Show current directory' })", description: "Show current directory" })`

        Inside the program:

        - Call tools as `await tools.name(args)` — quoted access for exotic names: `tools["my-tool"](args)`. Every call resolves to the tool's canonical JSON value. Tool arguments must be lossless JSON — they are always an object, so pass `{}` when the tool takes no arguments.
        - A FAILED tool call rejects with `ToolCallError`, whose `toolName` identifies the failed tool and whose `message` is human-readable — `try/catch` it to handle and continue.
        - Do the step's work in ONE program rather than several `run_code` calls. An uncaught tool failure ends the program at that call — the calls after it never run — and the failure result lists the completed sub-calls, each marked read-only or mutating: a mutation stayed in effect, a read-only result died with the program. After a failure send a **follow-up program with only the remaining steps** (re-run a read only when you still need its value; never redo a mutation), or wrap the call in `try/catch` when the program should carry on. Write a second program only when the first one's output decides what comes next.
        - Independent safe calls MAY overlap under `Promise.all` (safe calls run concurrently; mutating calls run alone, in submission order). Safe: ${SAFE_TOOL_LIST}. Mutating, and therefore exclusive: `bash`, `write`, `edit`, and any tool not in the safe list. Sequence dependent work with `await`.
        - Emit results with `return` and/or `console.log(...)`. Only what you print or return is program output; everything else stays out of the conversation, so curate it inside the program — what you print becomes this run's record, and your final reply summarizes it. A successful tool result containing an image is attached after the run so you can inspect it on the next step.
        - A program has an elapsed-time budget (120s by default; pass `timeoutMs` to raise it, capped at 600s) that **includes nested tool and approval waits**: a slow command, an escalation approval, or an answer to `ask_user_question` all consume it. The runtime is single-threaded; when the budget runs out the program is stopped and you get whatever it already printed. Keep one program's work bounded, print partial progress as you go, and never hold a whole large file, page or dataset in memory — about 60 MB of heap is all you get; read in slices with `offset`/`limit`, or let a shell command print only what matters.
    """.trimIndent()

    /** dsh 的 RUN_CODE_CONTROLS.timeoutMs 的模型可见说明（dsh-tools/lib/types/ptc.js） */
    internal const val RUN_CODE_TIMEOUT_PARAM_DESCRIPTION: String =
        "Positive elapsed-time budget in milliseconds, including nested tool and approval waits. " +
            "Default 120000; capped at 600000. Zero does not disable the deadline."

    /** dsh 的 SDK_PROGRAM_INSTRUCTIONS */
    private const val SDK_PROGRAM_INSTRUCTIONS: String = "Program-only SDK bindings:"

    /**
     * 升级参数说明：**整段只在 SDK 段写一次**，bash / edit / write 的声明里只留类型
     * （重试流程是策略事实，写在 runtime-context 的 file-policy 段里）。
     */
    private const val ESCALATION_NOTE: String =
        "`bash`, `edit` and `write` also accept `sandbox_permissions` and `justification`, declared below; " +
            "the runtime context's file-policy section describes their use."

    /** tools:sdk 段的工具定义：registry 的投影（run_code 是唯一直调工具，不在 SDK 段里） */
    val specs: List<ToolSpec> get() = ToolRegistry.bindings.map { it.spec }

    const val RUN_CODE_CODE_DESCRIPTION: String = "The program: the body of an async JavaScript function."

    val RUN_CODE_DESCRIPTION_PARAM_DESCRIPTION: String =
        "A short summary of what this program does (shown in the UI)."

    /**
     * 渲染完整 tools:sdk 段落（dsh 的 renderToolsSdk：固定说明 + 一段 declare const tools 声明）。
     * 工具按名字典序输出 —— **没有任何参数**，整段逐字节稳定（按并行上限分叉会让改一个设置就整段失效）。
     */
    fun section(): String {
        val sorted = specs.sortedBy { it.name }
        val argsMembers = sorted.map { spec ->
            docLines(spec.description) + "\n  " + spec.name + ": " + spec.args + " & Record<string, JsonValue>;"
        }
        val outputMembers = sorted.map { "  " + it.name + ": " + it.output + ";" }
        val declaration = buildString {
            appendLine("interface ToolArgsMap {")
            appendLine(argsMembers.joinToString("\n"))
            appendLine("}")
            appendLine()
            appendLine("interface ToolOutputMap {")
            appendLine(outputMembers.joinToString("\n"))
            appendLine("}")
            appendLine()
            appendLine("type ToolName = keyof ToolOutputMap")
            appendLine()
            appendLine("declare class ToolCallError extends Error {")
            appendLine("  readonly name: \"ToolCallError\";")
            appendLine("  readonly toolName: ToolName;")
            appendLine("}")
            appendLine()
            appendLine("declare const tools: {")
            appendLine("  [K in ToolName]: (args: ToolArgsMap[K]) => Promise<ToolOutputMap[K]>;")
            append("}")
        }
        val fence = "```"
        return buildString {
            // 注意：dsh 里 PTC_ONLY 是**独立一段**（tools:ptc-only，order 800），
            // 由装配器按 SECTION_ORDERS 排在 SDK 段（order 5000）之前，这里不要重复带上。
            appendLine(SDK_INSTRUCTIONS)
            appendLine()
            appendLine(SDK_PROGRAM_INSTRUCTIONS)
            appendLine()
            // 升级参数的总说明：只在这里出现一次（三个工具的声明里不再各写一遍）
            appendLine(ESCALATION_NOTE)
            appendLine()
            appendLine(fence + "ts")
            appendLine("type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue }")
            // 状态联合类型与状态含义都只在别名这一处：args / output / 工具描述里都不再复述
            appendLine("/** pending (not started) | in_progress (now) | completed (done) */")
            appendLine("type TodoStatus = \"pending\" | \"in_progress\" | \"completed\"")
            // 后台任务的两个别名：状态联合与公共投影（dsh 的 JobStatus / PublicJobSnapshot）
            appendLine("/** running | stopping | completed | killed | failed */")
            appendLine("type JobStatus = \"running\" | \"stopping\" | \"completed\" | \"killed\" | \"failed\"")
            appendLine("/** A background job; `detail` is the progress line while running, the terminal reason once settled. */")
            appendLine(
                "type JobView = { id: string; kind: string; label: string; status: JobStatus; " +
                    "detail?: string; startedAt: number; finishedAt?: number }",
            )
            appendLine()
            appendLine(declaration)
            append(fence)
        }
    }

    /** 一句话说明按 dsh 的 docLines 渲染成 /** ... */ 注释块 */
    private fun docLines(description: String): String =
        "  /** " + description.replace("\n", " ") + " */"
}
