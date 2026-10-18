package com.adsh.app.core.tools

/**
 * PTC 的工具声明表 —— 对齐 dsh 的 renderToolsSdk（dsh-tools/lib/types/ts-types.js）。
 *
 * dsh 在 ptc 模式下对模型**只暴露 run_code 一个工具**（wireSchemas 里只 filter 出
 * RUN_CODE_NAME），其余工具全部通过系统提示词的 tools:sdk 段落声明，模型在程序里用
 * await tools.<name>(args) 调用。所以「工具清单」的唯一来源是这里，而不是请求里的 tools 数组。
 *
 * 名字 / 参数名 / 一句话说明以 dsh 各 tool 插件为底本（dsh-tool-fs、dsh-tool-fs-search、
 * dsh-tool-bash、dsh-tool-todo、dsh-tool-present、dsh-tool-ask-user、dsh-tool-web）；
 * 与本项目能力不符的地方按本客户端改写，并在该段旁注明（程序语言是 JavaScript 而非
 * TypeScript；没有 subagent 与 DSH_* 托管变量；glob / grep 的差异见各自注释）。
 *
 * 工具自身的说明只在 [specs] 写一次，跨工具的规则只在 PromptAssembler.workingRules 写一次
 * —— 不许出现第二份会漂移的副本。
 */
data class ToolSpec(
    /** 工具名（同时是程序里的 tools.<name>） */
    val name: String,
    /** 模型可见的一句话说明（dsh 的 schema.description） */
    val description: String,
    /** ToolArgsMap 里这个名字的类型字面量 */
    val args: String,
    /** ToolOutputMap 里这个名字的类型 */
    val output: String,
)

/**
 * 系统提示词里的工具说明段（`tools:sdk`），**只在 [specs] 里写一次**。
 *
 * 说明文字里的数字上限在另一头也有定义，两边靠人肉同步，改一处必须改另一处：
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
     * present 的 schema.description：dsh 原句更短，这里按本项目调整 —— 术语统一为 workspace；
     * 明说「就地修既有文件不算交付物」；「新建还是改写」的灰区写成可判定规则。
     * 这三处是有意偏离 dsh，别按原句还原。
     *
     * 说明必须与返回值一致：返回值固定是 {turn, files:[{path, description?}]}，不承诺别的字段。
     */
    const val PRESENT_DESCRIPTION: String =
        "Declare existing files as final deliverables for the user. Present every file the user asked to " +
            "receive — whether you created it or rewrote it — after writing it and before your final " +
            "response, including files created through Bash or code execution; present it again in a later " +
            "step if you change it afterwards. Mentioning its path in your reply does not replace this " +
            "call. The files must already exist. Usually 1-2 most important; at most 8 per call. The user " +
            "opens the current files; their contents are not copied or preserved."

    /**
     * dsh-tool-bash 的 bashDescription(backgroundEnabled = true, escalationModes = 非空)。
     * 两处**有意收紧**：去掉本客户端不存在的 DSH_* 环境变量那句；拒绝标记 / 升级流程 /
     * 「试着跑一下是安全的」那句上移到跨工具规则段（PromptAssembler.workingRules）—— dsh 在
     * bash / edit / write 三处复制同一段规则，这里只留「这个工具是什么、返回什么」。
     *
     * 输出那一句与 dsh **逐字一致**：只留尾部、全文落盘、路径回给模型（OutputCollector + Spill）。
     */
    const val BASH_DESCRIPTION: String =
        "Execute a bash command (`bash -c`) and return its stdout/stderr. Each call runs in a fresh " +
            "shell: no state (cwd, variables, functions) persists between calls — pass `workdir` " +
            "instead of using `cd`. Provide `description` before `command` in the arguments. Before any " +
            "delete or move, verify that the resolved absolute target path is the one intended; never " +
            "run it against a computed path you have not checked. An unset variable expands to an empty " +
            "string, so guard variables in such paths with `\${VAR:?}`. Long output is truncated to its " +
            "tail; the full output is saved to a file whose path is reported when available."

    /** dsh-tool-todo 的 DESCRIPTION_HEAD / DESCRIPTION_PARALLEL / DESCRIPTION_SINGLE / DESCRIPTION_TAIL */
    private const val TODO_HEAD =
        "Record and update a structured task list for the current work. Send the ENTIRE list every call — it " +
            "REPLACES the previous list (there are no partial updates, no per-item edits). Use it to plan " +
            "multi-step work and show progress: add one todo per concrete step before you start. "
    /**
     * 并行策略那一段（合并）：**不能按 agent-loop.maxParallelToolCalls 分叉成并行版 / 串行版** ——
     * 改一下设置，系统提示词的字节就整个变了。只说一句对两种配置都成立的话：能不能并行由运行时的
     * 闸门管（`ToolConcurrency`），提示词不需要知道档位。
     */
    private const val TODO_PARALLEL =
        "Mark every todo being actively worked on `in_progress` — several at once only when work " +
            "genuinely runs in parallel (independent calls inside one program), one for sequential " +
            "work; while work remains, at least one task should be `in_progress`. "
    private const val TODO_TAIL =
        "Mark a todo `completed` the moment it is done (do not batch completions), and allow no " +
            "`in_progress` item only once all work is complete. Skip the list for trivial single-step " +
            "tasks."

    /** dsh 的 todo 说明（并行策略那一段只有一份，见 [TODO_PARALLEL]） */
    val TODO_DESCRIPTION: String = TODO_HEAD + TODO_PARALLEL + TODO_TAIL

    /**
     * 「可并行」工具清单的正文，**从调度器那份常量生成**（[ToolConcurrency.safeToolList]）：
     * 不能让提示词只写「safe calls run concurrently」—— 哪些算 safe 全靠猜，而 `bash` 与工具
     * 调用在 schema 上完全同形，猜错就是并发写坏文件。
     */
    private val SAFE_TOOL_LIST: String = ToolConcurrency.safeToolList.joinToString(", ") { "`" + it + "`" }

    /** dsh 的 SDK_INSTRUCTIONS + renderBashExample */
    private val SDK_INSTRUCTIONS: String = """
        ## Writing code for run_code

        `run_code` takes two required arguments: `code` — the body of an async JavaScript function; top-level `await` and `return` work — and `description`, a short summary of what the program does (shown in the UI). The runtime is QuickJS, so the code is plain JavaScript: TypeScript syntax (type annotations, `enum`, `namespace`, `interface`) is a syntax error, not an annotation. There is **no host API behind it**: every Node.js built-in (`require`, `fs`, `path`, `process`, `Buffer`, `module`, `global`, `__dirname`) is missing, and so is every platform global — no timers at all (`setTimeout`, `clearTimeout`, `queueMicrotask`), no network or crypto (`fetch`, `crypto`, `URL`, `AbortController`), no encoders (`TextEncoder`, `TextDecoder`, `atob`, `btoa`), and no `structuredClone`, `performance`, `WeakRef` or `Intl`: touching any of those names is a `ReferenceError`. What remains is the ECMAScript language itself (`console.log`, `Promise`, `JSON`, `Math`, `Date`, `RegExp`, `Proxy`, `BigInt`, generators, `Object.fromEntries`, `Array.prototype.at`) plus the `tools.*` bindings below. A step that needs a timer, a socket, an encoder or another process therefore belongs in `tools.bash`, where `sleep`, `curl`, `base64` and `xxd` are available. The declarations below are SDK bindings. A declaration does not make its name a directly callable tool; only names supplied as separate tool schemas may be called directly. Because `run_code` is the only separately supplied schema, call a declared binding from inside the program — the guidance above names them `tools.read`, `tools.bash`, and so on:

        `run_code({ code: "return await tools.bash({ command: 'pwd', description: 'Show current directory' })", description: "Show current directory" })`

        Inside the program:

        - Call tools as `await tools.name(args)` — quoted access for exotic names: `tools["my-tool"](args)`. Every call resolves to the tool's canonical JSON value. Tool arguments must be lossless JSON — they are always an object, so pass `{}` when the tool takes no arguments.
        - A FAILED tool call rejects with `ToolCallError`, whose `toolName` identifies the failed tool and whose `message` is human-readable — `try/catch` it to handle and continue.
        - Do the step's work in ONE program rather than several `run_code` calls. An uncaught tool failure ends the program at that call — the calls after it never run — and the failure result lists the sub-calls that already completed, each marked read-only or mutating: a mutation stayed in effect, while a read-only call's result died with the program (nothing was left to read it back from). So after a tool failure send a **follow-up program with only the remaining steps** — re-run a read only when you still need its value, and never redo a mutation — instead of re-sending the whole program; wrap a call you expect to fail in `try/catch` when the program should carry on by itself. (A program rejected before it ran — a syntax error — did nothing, so fix it and run the corrected version once.) Write a second program only when the first one's output decides what comes next.
        - Independent safe calls MAY overlap under `Promise.all` (safe calls run concurrently; mutating calls run alone, in submission order). Safe: ${SAFE_TOOL_LIST}. Mutating, and therefore exclusive: `bash`, `write`, `edit`, and any tool not in the safe list. Sequence dependent work with `await`.
        - Emit results with `return` and/or `console.log(...)`. Only what you print or return is program output; everything else stays out of the conversation, so curate it inside the program — what you print becomes this run's record, and your final reply summarizes it. A successful tool result containing an image is attached after the run so you can inspect it on the next step.
        - A program has an elapsed-time budget (120s by default; pass `timeoutMs` to raise it, capped at 600s) that **includes nested tool and approval waits**: a slow command, an escalation approval, or an answer to `ask_user_question` all consume it. The runtime is single-threaded inside the program; when the budget runs out the program is stopped (its execution process is terminated) and you get whatever it already printed. Print partial progress as you go and keep one program's work bounded. Its heap is shared and small — about 60 MB, and although `InternalError: out of memory` is catchable there is little room left after it — so never hold a whole large file, page or dataset in memory: read it in slices with `offset`/`limit`, or let a shell command do the work and print only what matters.
    """.trimIndent()

    /** dsh 的 RUN_CODE_CONTROLS.timeoutMs 的模型可见说明（dsh-tools/lib/types/ptc.js） */
    internal const val RUN_CODE_TIMEOUT_PARAM_DESCRIPTION: String =
        "Positive elapsed-time budget in milliseconds, including nested tool and approval waits. " +
            "Default 120000; capped at 600000. Zero does not disable the deadline."

    /** dsh 的 SDK_PROGRAM_INSTRUCTIONS */
    private const val SDK_PROGRAM_INSTRUCTIONS: String = "Program-only SDK bindings:"

    /**
     * 升级参数说明：**整段只在 SDK 段写一次**，bash / edit / write 的声明里只留类型
     * （Working rules 里已有升级流程）。
     */
    private const val ESCALATION_NOTE: String =
        "`bash`, `edit` and `write` also accept `sandbox_permissions` and `justification`, declared below; " +
            "their use is the one-shot retry described under Working rules."

    /** 参数表：工具名与参数名对齐 dsh；与本项目能力不符的说明在各自段落旁注明 */
    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "exit_plan_mode",
            // 计划模式在**运行时上下文**里是有信号的（开着时快照多一段 `plan:policy`，关掉时
            // 那一段消失、抬头下补一句退出叙述）。把「怎么判断自己在不在计划模式」写进这句，
            // 免得模型靠猜。**不能引用那段的开头原文**：系统提示词里出现 "You are in plan mode"
            // 就违反了「动态事实不进稳定段」（PromptAssemblerTest 钉着）。
            description = "Use only in plan mode: the runtime context carries a plan-mode section only while " +
                "plan mode is on, and that section's own text states the rules. Present your plan for the " +
                "user's review and, on approval, leave plan mode. Send the COMPLETE plan as markdown, " +
                "starting with a # heading that names it. The user may approve (carry out the plan from " +
                "your next step) or keep planning — their feedback comes back in the tool result; revise " +
                "and present again.",
            args = """
                {
                    /** The complete plan, as markdown, starting with a # heading that names it. */
                    plan: string;
                  }
            """.trimIndent(),
            output = """
                {
                    approved: true;
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "ask_user_question",
            // dsh 原句更短（只说 confirmation / choice / missing information）；这里扩写了确认条件
            // 与「可一次问多个问题、每个问题一个稳定 id」—— 有意偏离，别按 dsh 原句还原。
            description = "Ask the user a concise question when you need a choice, missing information, or " +
                "a decision only the user can make. Do not ask for permission to use a tool: the sandbox " +
                "approval prompt handles that. Send one or more questions, each with a stable id that will " +
                "be echoed in the answer.",
            args = """
                {
                    /** Questions to ask the user before continuing. */
                    questions: ({
                      /** Stable id for this question; echoed in the answer. */
                      id: string;
                      /** The specific question to ask the user. */
                      question: string;
                      /** Optional short heading for the question, such as "Confirm" or "Choose Mode". */
                      header?: string;
                      /** Optional choices to show the user. If you recommend one, put it first and append "(Recommended)" to that label. */
                      options?: ({ label: string; description?: string } & Record<string, JsonValue>)[];
                      /** Whether the user may select more than one option. Defaults to false. */
                      multi_select?: boolean;
                    } & Record<string, JsonValue>)[];
                  }
            """.trimIndent(),
            output = """
                {
                    answers: {
                      id: string;
                      selected: string[];
                      custom?: string;
                    }[];
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "bash",
            description = BASH_DESCRIPTION,
            args = """
                {
                    /** Short description of this command for the UI. */
                    description: string;
                    /** The bash command to execute. */
                    command: string;
                    /** Timeout in milliseconds. The executor applies its configured default and cap; on expiry the command is promoted to a background job instead of being killed. Nested tool and approval waits still consume the program's own elapsed-time budget, so raise timeoutMs only when the command is expected to be slow. */
                    timeoutMs?: number;
                    /** Run in the background and return a job id immediately (collect with `tools.job_output`, stop with `tools.job_kill`). No timeout applies. */
                    run_in_background?: boolean;
                    /** Working directory for this command. Defaults to the workspace. */
                    workdir?: string;
                    sandbox_permissions?: "workspace-write" | "danger-full-access";
                    justification?: string;
                  }
            """.trimIndent(),
            // dsh 的 bash 输出是一个三选一的联合（后台句柄 / 超时转后台 / 普通前台结果）：
            // cwd 三臂都 required，前台另有可选 stopped（外部停止原因）；截断时给 spillPath。
            // ADSH 没有结构化 sandbox —— 沙箱事实走 stderr 的围栏标记（平台机制不同）。
            output = """
                {
                    kind: "background";
                    jobId: string;
                    cwd: string;
                  } | {
                    kind: "promoted";
                    cwd: string;
                    jobId: string;
                    timeoutMs: number;
                    output: string;
                  } | {
                    kind: "foreground";
                    cwd: string;
                    exitCode: number | null;
                    /** Always null on Android: the Process API exposes no wait status, so a signal-terminated child reports exit code 128+n instead. */
                    signal: string | null;
                    timedOut: boolean;
                    aborted: boolean;
                    /** Present only when something outside the call stopped the command. */
                    stopped?: string;
                    timeoutMs: number;
                    stdout: {
                      text: string;
                      truncated: boolean;
                      spillPath?: string;
                    };
                    stderr: {
                      text: string;
                      truncated: boolean;
                      spillPath?: string;
                    };
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "edit",
            description = "Edit an existing UTF-8 text file by replacing literal text. CRLF line endings " +
                "are normalized for matching and restored on write, so write \\n in old_string.",
            args = """
                {
                    /** Path to edit. */
                    file_path: string;
                    /** Literal text to replace. Must match exactly. */
                    old_string: string;
                    /** Literal replacement text. Use an empty string to delete the match. */
                    new_string: string;
                    /** Replace all matches. Defaults to false; when false, old_string must appear exactly once. */
                    replace_all?: boolean;
                    sandbox_permissions?: "workspace-write" | "danger-full-access";
                    justification?: string;
                  }
            """.trimIndent(),
            output = """
                {
                    path: string;
                    /** File text before the edit; capped at 256 KiB in the value only — the file on disk is updated in full. */
                    before: string;
                    /** File text after the edit; capped at 256 KiB in the value only — the file on disk is updated in full. */
                    after: string;
                    /** UTF-8 byte length of the pre-edit text. */
                    beforeBytes: number;
                    /** UTF-8 byte length of the post-edit text. */
                    afterBytes: number;
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "glob",
            // 与 dsh 的两处有意差异：
            //  - 连目录一起返回（dsh: "Returns matching file paths — never directories"）；
            //  - 顺序是**字典序**（dsh 用 rg 的 --sort=modified，两次调用之间不稳定）。
            // glob 不落 spill，所以页脚不写 dsh 的「完整结果已存到 <路径>」。
            description = "Find paths that match a glob pattern. Returns matching paths in lexicographic order, " +
                "plus hidden and ignored files (VCS metadata directories are excluded). Up to 100 paths come " +
                "back; a larger result returns the first 100 in that order and sets `truncated` with the true " +
                "`totalPaths` — narrow the pattern or path to see more, because the complete list is not saved. " +
                "Paths come back relative to the workspace; a path outside it is returned absolute.",
            args = """
                {
                    /** Glob pattern to match paths against (e.g. "**/*.ts", "src/**/*.test.js"). A pattern with no "/" matches the basename at any depth, so "*" and "*.ts" both search the whole tree; include a separator to anchor the depth. A pattern containing "/" is anchored to the workspace root — not to the `path` argument — so match files outside the workspace with a basename pattern or a leading "**/". */
                    pattern: string;
                    /** Directory to search in. Defaults to the workspace; the scratch directory is outside that default root, so searching it needs its absolute path here. */
                    path?: string;
                    /** Include directories whose paths match. Defaults to true; set false to list files only. */
                    directories?: boolean;
                  }
            """.trimIndent(),
            output = """
                {
                    root: string;
                    paths: string[];
                    /** True when the cap dropped matches: paths holds only the first 100. */
                    truncated: boolean;
                    /** How many paths matched in total, before the cap. */
                    totalPaths: number;
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "grep",
            // 与 dsh 的差异：250 条上限在**结构化值**上也生效（dsh 的 execute 返回 { matches: all }，
            // 只在 render 里截）。PTC 模式下正文不进模型上下文，上限只写在正文上等于没有上限；
            // grep 不落 spill，超出的部分本来也取不回来（见 Tools.kt 的 grepResult）。
            description = "Search file contents with a ripgrep regular expression. Returns matching lines " +
                "with line numbers — the text view groups them by file, the value is a flat `matches` array. " +
                "Returns at most the first 250 matches and sets `truncated` with the true " +
                "`totalMatches` when it stopped early; the complete list is not saved, so narrow the pattern, " +
                "path, or include to see more. Use `tools.read` on a matched file for surrounding context.",
            args = """
                {
                    /** Regular expression to search for (ripgrep syntax). */
                    pattern: string;
                    /** File or directory to search. Defaults to the workspace; the scratch directory is outside that default root, so searching it needs its absolute path here. */
                    path?: string;
                    /** One glob filter for which files to search (e.g. "*.ts", "*.{js,jsx}"). Not a list; negation is not supported. */
                    include?: string;
                  }
            """.trimIndent(),
            output = """
                {
                    matches: {
                      path: string;
                      lineNumber: number;
                      line: string;
                    }[];
                    /** True when the 250 cap dropped matches. */
                    truncated: boolean;
                    /** How many lines matched in total, before the cap. */
                    totalMatches: number;
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "job_output",
            // dsh-tool-jobs 的三条 schema.description，逐字
            description = "Read a background job. A stream job returns only the output produced since your " +
                "previous read; a finished final-output job returns its result on the next read only.",
            args = """
                {
                    /** Job id returned by the tool that started the background work. */
                    job_id: string;
                    /** Block until the job finishes or the timeout expires; a timed-out wait leaves the job running. Defaults to false. */
                    wait?: boolean;
                    /** Max wait in milliseconds with wait: true. Defaults to and is capped by configuration. */
                    timeout_ms?: number;
                  }
            """.trimIndent(),
            output = """
                {
                    text: string;
                    job: JobView;
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "job_list",
            description = "List your background jobs (running and finished) with their ids, kinds, and statuses.",
            args = "{}",
            output = "JobView[]",
        ),
        ToolSpec(
            name = "job_kill",
            description = "Request cancellation of a running background job.",
            args = """
                {
                    /** Job id returned by the tool that started the background work. */
                    job_id: string;
                    /** Optional short reason, recorded in the log and forwarded to the job. */
                    reason?: string;
                  }
            """.trimIndent(),
            output = """
                {
                    outcome: "cancellation-requested" | "already-finished";
                    job: JobView;
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "present",
            description = PRESENT_DESCRIPTION,
            args = """
                {
                    files: {
                      /** Path of an existing regular file. Inside the workspace is expected; a path outside it is accepted and the absolute path is echoed, so moving the file into the workspace is your job. */
                      path: string;
                      /** Brief description for the user. */
                      description?: string;
                    }[];
                  }
            """.trimIndent(),
            output = """
                {
                    turn: number;
                    files: {
                      path: string;
                      description?: string;
                    }[];
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "read",
            description = "Read a UTF-8 text file and return line-numbered content. Lines are normalized " +
                "for display (a CRLF's `\\r` is dropped, a final newline adds no empty line); use " +
                "`tools.bash` for byte-exact checks.",
            args = """
                {
                    /** Path to read. */
                    file_path: string;
                    /** 1-based first line to return. Defaults to 1. */
                    offset?: number;
                    /** Maximum number of lines to return. Defaults to 2000, which is also the cap; the returned text is additionally capped at 50 KiB, so the footer may stop before the line limit. */
                    limit?: number;
                  }
            """.trimIndent(),
            output = """
                {
                    path: string;
                    offset: number;
                    lines: {
                      number: number;
                      text: string;
                    }[];
                    totalLines: number;
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "read_image",
            // description 按 ADSH 的附件机制（不做格式归一化、请求期降采样）改写；dsh 0.2.1 原句更短
            description = "Read a PNG/JPEG/WebP/GIF file and return the image itself. A path without a file " +
                "extension is accepted; the format is detected from the file content, so normalized " +
                "attachment paths can be passed directly without copying or renaming. Harness validates and " +
                "downscales large supported images before the next model request, so use this tool directly " +
                "instead of installing image libraries or creating thumbnails merely to inspect an image. " +
                "Requires the current model to accept image input.",
            args = """
                {
                    /** Path to the image file. */
                    file_path: string;
                  }
            """.trimIndent(),
            output = """
                {
                    path: string;
                    image: {
                      attachmentId: string;
                      mediaType: "image/png" | "image/jpeg" | "image/webp" | "image/gif";
                      bytes: number;
                      width: number;
                      height: number;
                      name?: string;
                    };
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "todo_write",
            description = TODO_DESCRIPTION,
            args = """
                {
                    /** The COMPLETE task list, replacing any previous list. */
                    todos: {
                      /** What the task is — a short imperative line. */
                      content: string;
                      status: TodoStatus;
                    }[];
                  }
            """.trimIndent(),
            output = """
                {
                    todos: {
                      content: string;
                      status: TodoStatus;
                    }[];
                    counts: {
                      pending: number;
                      inProgress: number;
                      completed: number;
                    };
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "web_fetch",
            // dsh 的 schema 里没有 `timeoutMs` / `retries`（超时是宿主配置的 30s、失败不重试）。
            // 没有它们时「一次超时即失败」，网络一抖这个 safe 工具比手写 curl 更脆；默认值写进
            // 说明，让模型知道不提也有一次重试。
            description = "Fetch the content of a specific HTTP(S) URL and return it decoded to text. " +
                "A timeout or a transport failure is retried once by default (`retries`); a non-2xx " +
                "response is a result, not a failure, so check `statusCode`.",
            args = """
                {
                    /** The HTTP(S) URL to fetch. */
                    url: string;
                    /** Read timeout in milliseconds. Defaults to 30000, capped at 120000; the connect timeout is the smaller of 15000 and this value. A host with several addresses may spend this long per address, so one attempt can exceed the deadline. */
                    timeoutMs?: number;
                    /** Extra attempts after a timeout or transport error. Defaults to 1, capped at 3; responses that returned a body — including 4xx/5xx — are never retried. */
                    retries?: number;
                  }
            """.trimIndent(),
            output = """
                {
                    /** The final URL after redirects. */
                    url: string;
                    /** The HTTP status code. */
                    statusCode: number;
                    /** The decoded body; "html" means the source was HTML. This value keeps the body as decoded — the call's text form carries the Markdown rendering. */
                    body: {
                      kind: "html" | "text";
                      content: string;
                    };
                    /** Whether the body was cut at the size cap. */
                    truncated: boolean;
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "web_search",
            description = "Search the web for current information. Provide 1–4 queries in the required queries " +
                "array. Returns an optional summary answer and a list of source URLs.",
            args = """
                {
                    /** Required search queries; accepts 1–4 items and merges their results. Use a one-item array for a single search. */
                    queries: string[];
                  }
            """.trimIndent(),
            output = """
                {
                    /** The provider's summary answer, when it returns one. */
                    content?: string;
                    /** The deduplicated source list, merged in rank order across queries. */
                    sources: {
                      url: string;
                      title?: string;
                      /** The excerpt the provider cited for this URL. */
                      snippet?: string;
                      /** The page's age or publication date, when the provider reports one. */
                      publishedAt?: string;
                    }[];
                    /** Whether the source cap dropped results. */
                    truncated: boolean;
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "write",
            description = "Create or fully replace a UTF-8 text file. Missing parent directories are created.",
            args = """
                {
                    /** Path to write. */
                    file_path: string;
                    /** Full UTF-8 text content to write. */
                    content: string;
                    sandbox_permissions?: "workspace-write" | "danger-full-access";
                    justification?: string;
                  }
            """.trimIndent(),
            output = """
                {
                    path: string;
                    operation: "create" | "update";
                    /** Original text; null when the file did not exist. Capped at 256 KiB in the value only — the write itself is complete. */
                    before: string | null;
                    /** Written text; capped at 256 KiB in the value only — the file on disk holds all of it. */
                    after: string;
                    /** UTF-8 byte length of the original text (0 when the file did not exist). */
                    beforeBytes: number;
                    /** UTF-8 byte length of the written text. */
                    afterBytes: number;
                  }
            """.trimIndent(),
        ),
    )

    private val byName: Map<String, ToolSpec> = specs.associateBy { it.name }

    /** 工具实现里的 description 用同一份文本，避免两处各写一遍 */
    fun description(name: String): String = byName[name]?.description.orEmpty()

    /**
     * run_code 自己的 schema。
     *
     * 机制留在提示词里、schema 不单独下发，唯一例外是**语言约束**：dsh 跑可擦除 TypeScript，
     * 本运行时是 QuickJS，同一个类型标注是**语法错误**，整个程序一行都不跑 —— 这是模型最容易
     * 踩的一条，必须出现在它最先读到的 schema 里。
     * 输出规则、调用写法、description 规范仍然只在提示词里说一次（PromptAssemblerTest 钉着）。
     */
    val RUN_CODE_DESCRIPTION: String =
        "Execute a JavaScript program against the tools declared in the system prompt. Takes two required " +
            "arguments: `code`, the BODY of an async function (plain JavaScript — TypeScript syntax such as " +
            "type annotations, `interface`, `enum` or `namespace` is a syntax error in this runtime), and " +
            "`description`. The runtime, the two arguments and the output rules are described there under " +
            "Writing code for run_code."

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
