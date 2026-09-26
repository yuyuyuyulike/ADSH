package com.adsh.app.core.tools

/**
 * PTC 的工具声明表 —— 对齐 dsh 的 renderToolsSdk（dsh-tools/lib/types/ts-types.js）。
 *
 * dsh 在 ptc 模式下对模型**只暴露 run_code 一个工具**（wireSchemas 里只 filter 出
 * RUN_CODE_NAME），其余工具全部通过系统提示词的 tools:sdk 段落声明，模型在程序里用
 * await tools.<name>(args) 调用。所以「工具清单」的唯一来源是这里，而不是请求里的 tools 数组。
 *
 * 每一条的名字 / 一句话说明 / 参数名 / 参数说明 都以 dsh 各 tool 插件为底本
 * （dsh-tool-fs、dsh-tool-fs-search、dsh-tool-bash、dsh-tool-todo、dsh-tool-present、
 * dsh-tool-ask-user、dsh-tool-web），只在这几处按本客户端的实际能力调整：
 *  - 程序语言是 JavaScript（QuickJS）而不是 TypeScript；
 *  - 没有后台任务（run_in_background / job_output / job_kill），所以 bash 的说明用 dsh 关掉后台时的
 *    原文「Background execution is not available; …」；
 *  - 没有 subagent、也没有 DSH_* 托管环境变量：dsh 的 todo 说明里拿「并发 subagents 或后台
 *    commands」当并行示例，这里改成同一个程序里的独立调用（那两个能力 ADSH 都不存在）；
 *  - 沙箱升级（sandbox_permissions / justification）是有的：拒绝标记、升级提示、审批弹窗与 dsh
 *    逐字一致（见 core/tools/Approval.kt）。但**升级流程只在跨工具规则段写一次**
 *    （PromptAssembler.workingRules）：dsh 把同一段规则在 bash / edit / write 三处逐字复制
 *    （审查报告 R-1），这里只在两个参数上留一句指过去的话；
 *  - 「每工具一段散文」整段删除（审查报告 R-0，用户第六十轮采纳）：工具自身的说明只在下面的
 *    specs 里写一次，跨工具的规则在 workingRules 里写一次，不再有第二份会漂移的副本。
 *
 * 另外三处是**按实测反馈有意偏离 dsh** 的（都写在对应说明里，不藏着）：
 *  - glob 连目录一起返回、并且按字典序（dsh：只给文件、按修改时间）；
 *  - grep 的 250 条上限在结构化值上也生效（dsh：值是全量，上限只在正文）；
 *  - 这两个工具的页脚不写「完整结果已存到 <路径>」——ADSH 没有 spill 服务，写了就是假的。
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

object ToolSdk {

    /**
     * dsh 的 PTC_ONLY_INSTRUCTION，逐字两句（第二句是第六十九轮补回来的）。
     *
     * 第一句把「只有 run_code 能直接调用」这条规则明说，否则模型只能靠被拒绝来发现它。
     * 第二句是 dsh 原文里就有的 "Reach every tool the SDK declares below from inside the
     * program." —— ADSH 早先只留了第一句，于是「工具在下面声明」与「怎么够到它们」之间少了
     * 那句话：模型会**反复发多个 run_code 调用**去够不同的工具，而不是在一个程序里把这一步做完。
     * 加回之后与 SDK 段里那句「整步写在一个程序里」是同一件事的两端（规则 → 写法），不重复。
     */
    val PTC_ONLY_INSTRUCTION: String =
        "`run_code` is the only tool you can call directly — a tool call naming any other tool fails. " +
            "Reach every tool the SDK declares below from inside the program."

    /**
     * dsh 的 FILE_REFERENCE 段（order 900）逐字：@路径 的读法。
     * 来源：dsh-file-reference 注册的 systemPrompt.section。
     *
     * 两处按审查报告 N-3 调整：「列目录」明确用 tools.glob（ADSH 的 glob 连目录一起返回，
     * dsh 那句 "list it" 没说用什么列），「读文件」写成 tools.read —— 与 PTC_ONLY 的口径一致。
     */
    val FILE_REFERENCE_INSTRUCTION: String =
        "Tokens prefixed with @ are workspace paths the user explicitly referenced, relative to the " +
            "workspace root. A trailing slash marks a directory: list it with `tools.glob` when its " +
            "contents matter. Anything else is a file: read it with `tools.read` when its contents are " +
            "needed, and do not claim to have inspected it before reading. \"@...\" quotes a path " +
            "containing spaces."

    /**
     * dsh-tool-present 的 schema.description，逐字，两处按本项目调整（审查报告 T-2 / N-2）：
     *  - 「Session filesystem / Session working directory」改成 workspace（术语统一）；
     *  - 补一句「就地修既有文件不算交付物」：androidEnvText 把工作区说成「素材进 / 成品出」，
     *    与「你必须 present」叠在一起时，模型会把每次就地修文件都当成交付物。
     */
    const val PRESENT_DESCRIPTION: String =
        "Declare existing files as final deliverables for the user. When a file you create or update is " +
            "an output the user asked to receive, you must call present after writing it and before your " +
            "final response, including files created through Bash or code execution. Mentioning its path " +
            "in your reply does not replace this call. The files must already exist. The user opens the " +
            "current source files; their contents are not copied or preserved. Repairing an existing " +
            "workspace file in place is not a deliverable — present only files the user asked to receive."

    /**
     * dsh-tool-bash 的 bashDescription(backgroundEnabled = false, escalationModes = 非空)。
     * 三处**有意收紧**：
     *  1. 去掉本客户端不存在的 DSH_* 环境变量那一句；
     *  2. 拒绝标记、升级流程、以及「试着跑一下是安全的」那句全部**上移到跨工具规则段**
     *     （PromptAssembler.workingRules）：dsh 在 bash / edit / write 三处逐字复制同一段规则
     *     （审查报告 R-1），这里只留「这个工具是什么、返回什么」；
     *  3. 输出上限写实：ADSH 的收集器保留**尾部**、每路默认 64 KB（dsh 另有 spill 文件兜底，
     *     这里没有），所以正文里补一句「写到文件里再读」。
     */
    const val BASH_DESCRIPTION: String =
        "Execute a bash command (`bash -c`) and return its stdout/stderr. Each call runs in a fresh " +
            "shell: no state (cwd, variables, functions) persists between calls — pass `workdir` " +
            "instead of using `cd`. Long output is capped per stream: the tail is kept (64 KB by " +
            "default) and the stream is flagged `truncated`. Background execution is not available; " +
            "long-running commands must finish within the timeout."

    /** dsh-tool-todo 的 DESCRIPTION_HEAD / DESCRIPTION_PARALLEL / DESCRIPTION_SINGLE / DESCRIPTION_TAIL */
    private const val TODO_HEAD =
        "Record and update a structured task list for the current work. Send the ENTIRE list every call — it " +
            "REPLACES the previous list (there are no partial updates, no per-item edits). Use it to plan " +
            "multi-step work and show progress: add one todo per concrete step before you start. "
    private const val TODO_PARALLEL =
        "Mark every todo being actively worked on `in_progress` — several at once only when work " +
            "genuinely runs in parallel (independent calls inside one program), one for sequential " +
            "work; while work remains, at least one task should be `in_progress`. "
    private const val TODO_SINGLE =
        "Keep AT MOST ONE todo `in_progress` at a time; while work remains, exactly one active task " +
            "should be `in_progress`. "
    private const val TODO_TAIL =
        "Mark a todo `completed` the moment it is done (do not batch completions), and allow no " +
            "`in_progress` item only once all work is complete. Skip the list for trivial single-step " +
            "tasks."

    /**
     * dsh 的 todo 说明：并行策略那一段由 agent-loop.maxParallelToolCalls 决定
     * （默认 10 > 1，所以默认是并行版）。
     */
    fun todoDescription(allowParallel: Boolean): String =
        TODO_HEAD + (if (allowParallel) TODO_PARALLEL else TODO_SINGLE) + TODO_TAIL

    /** dsh 的 SDK_INSTRUCTIONS + renderBashExample */
    private val SDK_INSTRUCTIONS: String = """
        ## Writing code for run_code

        `run_code` takes two required arguments: `code` — the body of an async JavaScript function; top-level `await` and `return` work — and `description`, a short summary of what the program does (shown in the UI). The runtime is QuickJS, so the code is plain JavaScript: TypeScript syntax (type annotations, `enum`, `namespace`, `interface`) is a syntax error, not an annotation. The declarations below are SDK bindings for this program. A declaration does not make its name a directly callable tool; only names supplied as separate tool schemas may be called directly. Because `run_code` is the only separately supplied schema, call a declared binding from inside the program — the guidance above names them `tools.read`, `tools.bash`, and so on:

        `run_code({ code: "return await tools.bash({ command: 'pwd', description: 'Show current directory' })", description: "Show current directory" })`

        Inside the program:

        - Call tools as `await tools.name(args)` — quoted access for exotic names: `tools["my-tool"](args)`. Every call resolves to the tool's canonical JSON value. Tool arguments must be lossless JSON.
        - A FAILED tool call rejects with `ToolCallError`, whose `toolName` identifies the failed tool and whose `message` is human-readable — `try/catch` it to handle and continue.
        - Do the step's work in ONE program rather than several `run_code` calls — including after a failure: fix the program and run the corrected version once instead of re-sending the same code or a fragment of it. Every extra call is another full round trip and shows up as a separate row, so write a second program only when the first one's output decides what comes next.
        - Independent read-only calls MAY overlap under `Promise.all` (safe calls run concurrently; mutating calls run alone, in submission order). Sequence dependent work with `await`.
        - Emit results with `return` and/or `console.log(...)`. Only what you print or return is program output; everything else stays out of the conversation, so curate it inside the program — what you print becomes this run's record, and your final reply summarizes it. A successful tool result containing an image is attached after the run so you can inspect it on the next step.
        - A program has a fixed elapsed-time budget of two minutes (nested tool waits count, and the runtime is single-threaded inside the program): when it runs out, the program is stopped and you get whatever it already printed. Print partial progress as you go, and keep one program's work bounded.
    """.trimIndent()

    /** dsh 的 SDK_PROGRAM_INSTRUCTIONS */
    private const val SDK_PROGRAM_INSTRUCTIONS: String = "Program-only SDK bindings:"

    /**
     * 升级参数说明：**整段只在 SDK 段写一次**（第六十一轮去重）。
     * 上一轮把两行注释放进了 bash / edit / write 三处，扫一遍提示词就是三段一模一样的文本；
     * 现在改成段首一句总说明，三个参数的声明里只留类型（Working rules 里已有升级流程）。
     */
    private const val ESCALATION_NOTE: String =
        "`bash`, `edit` and `write` also accept `sandbox_permissions` and `justification`, declared below; " +
            "their use is the one-shot retry described under Working rules."

    /** 参数表：工具名与参数名对齐 dsh，说明按本客户端的能力收紧（头部注释列了偏离点） */
    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "exit_plan_mode",
            description = "Use only in plan mode. Present your plan for the user's review and, on approval, " +
                "leave plan mode. Send the COMPLETE plan as markdown, starting with a # heading that names it. " +
                "The user may approve (carry out the plan from your next step) or keep planning — their " +
                "feedback comes back in the tool result; revise and present again.",
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
            description = "Ask the user a concise question when you need confirmation, a choice, or missing " +
                "information before proceeding. Send one or more questions, each with a stable id that will " +
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
                    /** The bash command to execute. */
                    command: string;
                    /** Short description of this command for the UI. */
                    description: string;
                    /** Timeout in milliseconds. The executor applies its configured default and cap, and kills the command on expiry. */
                    timeoutMs?: number;
                    /** Working directory for this command. Defaults to the workspace. */
                    workdir?: string;
                    sandbox_permissions?: "workspace-write" | "danger-full-access";
                    justification?: string;
                  }
            """.trimIndent(),
            // dsh 的 bash 输出：退出码是字段，stdout/stderr 分开（截断时 dsh 还有 spillPath，这里没有落盘）
            output = """
                {
                    kind: "foreground";
                    exitCode: number | null;
                    signal: string | null;
                    timedOut: boolean;
                    aborted: boolean;
                    timeoutMs: number;
                    stdout: {
                      text: string;
                      truncated: boolean;
                    };
                    stderr: {
                      text: string;
                      truncated: boolean;
                    };
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "edit",
            description = "Edit an existing UTF-8 text file by replacing literal text.",
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
                    before: string;
                    after: string;
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "glob",
            // ADSH 与 dsh 的差异（两处，都是「实测反馈」要求的）：
            //  - 连目录一起返回（dsh: "Returns matching file paths — never directories"，想列目录只能绕回 bash）；
            //  - 顺序是**字典序**（dsh 用 rg 的 --sort=modified，两次调用之间不稳定，也没法按路径定位）。
            // 另外这里没有 spill 服务，所以不能照抄 dsh 的「完整结果已存到 <路径>」。
            description = "Find paths that match a glob pattern. Returns matching paths in lexicographic order, " +
                "plus hidden and ignored files (VCS metadata directories are excluded). Up to 100 paths come " +
                "back; a larger result returns the first 100 in that order and says so — narrow the pattern or " +
                "path to see more, because the complete list is not saved.",
            args = """
                {
                    /** Glob pattern to match paths against (e.g. "**/*.ts", "src/**/*.test.js"). A pattern with no "/" matches the basename at any depth, so "*" and "*.ts" both search the whole tree; include a separator to anchor the depth. */
                    pattern: string;
                    /** Directory to search in. Defaults to the workspace. */
                    path?: string;
                    /** Include directories whose paths match. Defaults to true; set false to list files only. */
                    directories?: boolean;
                  }
            """.trimIndent(),
            output = """
                {
                    root: string;
                    paths: string[];
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "grep",
            // 与 dsh 的差异：250 条上限在**结构化值**上也生效（dsh 的 execute 返回 { matches: all }，
            // 只在 render 里截）。理由见 Tools.kt 的 grepResult：PTC 模式下正文不进模型上下文，
            // 上限只写在正文上等于没有上限；而 ADSH 没有 spill，多出来的部分本来也取不回来。
            description = "Search file contents with a ripgrep regular expression. Returns matching lines " +
                "with line numbers, grouped by file. Ripgrep skips hidden and ignored paths, so `grep` sees " +
                "fewer files than `glob`, which lists them. Returns at most the first 250 matches and says " +
                "so when it stopped early — narrow pattern, path, or include to see more, because the " +
                "complete list is not saved. Use read on a matched file for surrounding context.",
            args = """
                {
                    /** Regular expression to search for (ripgrep syntax). */
                    pattern: string;
                    /** File or directory to search. Defaults to the workspace. */
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
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "present",
            description = PRESENT_DESCRIPTION,
            args = """
                {
                    files: {
                      /** Path of an existing regular file in the workspace. */
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
            description = "Read a UTF-8 text file and return line-numbered content.",
            args = """
                {
                    /** Path to read. */
                    file_path: string;
                    /** 1-based first line to return. Defaults to 1. */
                    offset?: number;
                    /** Maximum number of lines to return. Defaults to 2000, which is also the cap. */
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
            // dsh-tool-fs 的 read_image schema.description，逐字
            description = "Read a PNG/JPEG/WebP/GIF file and return the image itself. A path without a file " +
                "extension is accepted; the format is detected from the file content, so normalized " +
                "attachment paths can be passed directly without copying or renaming. Harness validates and " +
                "downscales large supported images before the next model request, so use this tool directly " +
                "instead of installing image libraries or creating thumbnails merely to inspect an image. " +
                "Independent files may be read concurrently in small batches. Requires the current model to " +
                "accept image input.",
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
                      originalDimensions?: {
                        width: number;
                        height: number;
                      };
                    };
                  }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "todo_write",
            description = todoDescription(allowParallel = true),
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
            description = "Fetch the content of a specific HTTP(S) URL and return it decoded to text.",
            args = """
                {
                    /** The HTTP(S) URL to fetch. */
                    url: string;
                  }
            """.trimIndent(),
            output = """
                {
                    /** The final URL after redirects. */
                    url: string;
                    /** The HTTP status code. */
                    statusCode: number;
                    /** The decoded body; kind is "html" when the markup was converted to text. */
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
            description = "Create or fully replace a UTF-8 text file.",
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
                    before: string | null;
                    after: string;
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
     * 第 62 轮的《提示词重复审计报告》把它压成了一句话（R1：「机制留在提示词里，schema 不会单独下发」）。
     * **第 81 轮按用户实测把「语言约束」加回来一处**（别的仍然不复述）：dsh 跑的是可擦除 TypeScript ——
     * 模型顺手写类型标注意无妨；本运行时是 QuickJS，同一个标注是**语法错误**，整个程序一行都不跑。
     * 这是模型最需要提前知道、也最容易踩的一条，值得出现在它最先读到的 schema 里。
     * 输出规则、调用写法、description 规范都仍然只在提示词里说一次（PromptAssemblerTest 的 R1/R5 钉着）。
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
     * 工具按名字典序输出，工具集不变则文本逐字节稳定。
     *
     * @param allowParallel Agent 循环里的并行工具调用数 > 1（决定 todo_write 说明里的那一段）
     */
    fun section(allowParallel: Boolean = true): String {
        val sorted = specs.sortedBy { it.name }
        val argsMembers = sorted.map { spec ->
            val description = if (spec.name == "todo_write") todoDescription(allowParallel) else spec.description
            docLines(description) + "\n  " + spec.name + ": " + spec.args + " & Record<string, JsonValue>;"
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
            appendLine()
            appendLine(declaration)
            append(fence)
        }
    }

    /** 一句话说明按 dsh 的 docLines 渲染成 /** ... */ 注释块 */
    private fun docLines(description: String): String =
        "  /** " + description.replace("\n", " ") + " */"
}