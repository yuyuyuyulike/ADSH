package com.adsh.app.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.adsh.app.seam.tool.Question
import com.adsh.app.seam.tool.QuestionOption
import com.adsh.app.seam.tool.UserQuestionChannel

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
private val TODO_DESCRIPTION: String = TODO_HEAD + TODO_PARALLEL + TODO_TAIL

/**
 * todo_write：结构化任务列表。
 *
 * 逐项对齐 dsh-tool-todo：
 *  - 名字是 todo_write（不是 todo）；
 *  - 校验：content 去空白后非空、不能重复；allowParallel = false 时最多一条 in_progress；
 *  - 输出值 { todos, counts }，正文是 "Updated todo list: N pending, M in progress, K completed."；
 *  - 清单写进**发起这次调用的会话**（dsh 的 `exec.agent.session.append("todo/write")`，
 *    没有会话可写的调用方直接拒绝）。
 */
object TodoTool : Tool {
    override val spec = ToolSpec(
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
    )
    override val parallelSafe = true

    private val statuses = listOf("pending", "in_progress", "completed")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val conversationId = ctx.conversationId
            ?: return ToolResult.Error("todo_write requires an owning agent session")
        val raw = args["todos"] as? JsonArray
            ?: return ToolResult.Error("todos must be an array of { content, status }")
        val allowParallel = ctx.maxParallelSubCalls > 1
        val items = ArrayList<TodoItem>()
        val seen = HashSet<String>()
        var active = 0
        raw.forEach { element ->
            val obj = element as? JsonObject
                ?: return ToolResult.Error("invalid todo: each item must be an object with content and status")
            val content = (obj["content"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (content.isEmpty()) return ToolResult.Error("invalid todo: `content` must be a non-empty string")
            if (!seen.add(content)) return ToolResult.Error("invalid todos: duplicate content " + quote(content))
            val status = (obj["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (status !in statuses) {
                return ToolResult.Error("invalid todo: `status` must be one of " + statuses.joinToString(", "))
            }
            if (status == "in_progress") active++
            items += TodoItem(content, status)
        }
        if (!allowParallel && active > 1) {
            return ToolResult.Error("invalid todos: at most one task may be in_progress (got " + active + ")")
        }
        TodoStore.replace(conversationId, items)
        val pending = items.count { it.status == "pending" }
        val inProgress = items.count { it.status == "in_progress" }
        val completed = items.count { it.status == "completed" }
        val value = buildJsonObject {
            put("todos", buildJsonArray {
                items.forEach { item ->
                    add(
                        buildJsonObject {
                            put("content", item.content)
                            put("status", item.status)
                        },
                    )
                }
            })
            put("counts", buildJsonObject {
                put("pending", pending)
                put("inProgress", inProgress)
                put("completed", completed)
            })
        }
        return ToolResult.Ok(
            "Updated todo list: " + pending + " pending, " + inProgress + " in progress, " + completed + " completed.",
            value,
        )
    }

    /** dsh 报错里的 JSON.stringify(content) */
    private fun quote(text: String): String = JsonPrimitive(text).toString()
}

/**
 * present 的 schema.description：dsh 原句更短，这里按本项目调整 —— 术语统一为 workspace；
 * 明说「就地修既有文件不算交付物」；「新建还是改写」的灰区写成可判定规则。
 * 这三处是有意偏离 dsh，别按原句还原。
 *
 * 说明必须与返回值一致：返回值固定是 {turn, files:[{path, description?}]}，不承诺别的字段。
 */
private const val PRESENT_DESCRIPTION: String =
    "Declare existing files as final deliverables for the user. Present every file the user asked to " +
        "receive — whether you created it or rewrote it — after writing it and before your final " +
        "response, including files created through Bash or code execution; present it again in a later " +
        "step if you change it afterwards. Mentioning its path in your reply does not replace this " +
        "call. The files must already exist. Usually 1-2 most important; at most 8 per call. The user " +
        "opens the current files; their contents are not copied or preserved."

/**
 * present：声明最终交付物。
 *
 * 参数与输出逐字对齐 dsh-tool-present：files: [{ path, description? }]，1..maxFiles 个
 * （dsh 的 Config.maxFiles 默认 8），每个 path 必须是已存在的普通文件；
 * 输出是 { turn, files }，正文每行 "Presented <path>"。
 *
 * 交付物的展示分两处（都对齐 dsh）：
 *  - 对话里这一行工具（dsh 的 PresentRow：状态 + 文件名 + 可展开的输出）；
 *  - **轮尾的文件卡片**（dsh 的 ui-deliverables 的 turnTail 槽）：由成功的这次调用声明的
 *    [com.adsh.app.seam.tool.PresentedFile] 落库（dsh 的 deliverables/presented 事件），
 *    点一张卡片进文件预览 —— dsh 的 PresentedFileCard 也是点开进 Sidebar 预览。
 */
object PresentTool : Tool {
    override val spec = ToolSpec(
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
    )
    override val parallelSafe = true

    /** dsh 的 config.maxFiles 默认值 */
    private const val MAX_FILES = 8

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val raw = args["files"] as? JsonArray
        if (raw == null || raw.isEmpty()) {
            return ToolResult.Error("present accepts 1 to " + MAX_FILES + " files")
        }
        if (raw.size > MAX_FILES) {
            return ToolResult.Error("present accepts 1 to " + MAX_FILES + " files")
        }
        // target（解析后的绝对路径，dsh 的 processPath）/ description / shown（卡片用的展示路径，裁过工作区前缀）
        val accepted = ArrayList<Triple<String, String?, String>>()
        // 正文与值都回显 target（dsh 的 render 用 processPath 之后的 file.path）；轮尾交付卡片用 shown。
        val lines = ArrayList<String>()
        raw.forEach { element ->
            val obj = element as? JsonObject ?: return ToolResult.Error("present requires { path, description? } objects")
            val path = (obj["path"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val description = (obj["description"] as? JsonPrimitive)?.contentOrNull
            if (path.trim().isEmpty()) return ToolResult.Error("present requires a non-empty file path")
            val file = try {
                Args.resolve(ctx, path)
            } catch (e: Exception) {
                return ToolResult.Error("Cannot present " + path + ": " + (e.message ?: "invalid path"))
            }
            if (!file.exists()) {
                return ToolResult.Error(
                    "Cannot present " + path + ": file not found. Check the path, create the file if needed, and retry.",
                )
            }
            if (!file.isFile) return ToolResult.Error("Cannot present " + path + ": not a regular file")
            val shown = Args.display(ctx, file)
            accepted += Triple(file.path, description, shown)
            lines += "Presented " + file.path
        }
        val value = buildJsonObject {
            put("turn", ctx.turn)
            put("files", buildJsonArray {
                accepted.forEach { (target, description, _) ->
                    add(
                        buildJsonObject {
                            put("path", target)
                            if (description != null) put("description", description)
                        },
                    )
                }
            })
        }
        return ToolResult.Ok(
            text = lines.joinToString("\n"),
            value = value,
            // dsh 的 deliverables/presented：成功的最终结果才算交付（这里没有失败路径 —— 上面
            // 任何一个文件不合法就整条调用报错，dsh 也是「先全部校验完再声明」）。
            deliverables = accepted.map { (_, description, shown) ->
                com.adsh.app.seam.tool.PresentedFile(path = shown, description = description)
            },
        )
    }
}

/** dsh 的 ToolArgsError / UserQuestionError 的 code（随 wire 交给程序，可机读） */
private const val INVALID_ARGS = "INVALID_ARGS"
private const val EMPTY_QUESTIONS = "EMPTY_QUESTIONS"

/** dsh 的 defineTool 在 execute 前按 parameters schema 整条校验；这里同层，violation 文案与粒度照 dsh 的 json-schema。 */
internal sealed interface AskArgs {
    data class Accepted(val questions: List<Question>) : AskArgs
    data class Rejected(val violations: List<String>) : AskArgs
}

/**
 * 校验 ask_user_question 的入参。
 *
 * 空串 id/question、空 options、重复 id 都合法：dsh 的 schema 只要求 string、options 无 minItems，
 * 空选项就是开放题；重复 id 只有 dsh 的 timed 变体拒绝，legacy（ADSH 对齐的那个）不拒。
 * 形状非法时整条拒绝（dsh 同层行为），不静默丢弃元素。
 */
internal fun parseAskUserArgs(args: JsonObject): AskArgs {
    val violations = ArrayList<String>()
    val raw = args["questions"]
    if (raw == null) {
        violations += missingRequired("questions")
        return AskArgs.Rejected(violations)
    }
    if (raw !is JsonArray) {
        violations += mustBe("questions", "an array")
        return AskArgs.Rejected(violations)
    }
    val questions = ArrayList<Question>()
    raw.forEachIndexed { index, element ->
        val item = element as? JsonObject
        val path = "questions[" + index + "]"
        if (item == null) {
            violations += mustBe(path, "an object")
            return@forEachIndexed
        }
        val before = violations.size
        if (!item.containsKey("id")) violations += missingRequired(path + ".id")
        if (!item.containsKey("question")) violations += missingRequired(path + ".question")
        val id = readString(item, "id", path, violations)
        val text = readString(item, "question", path, violations)
        val header = readString(item, "header", path, violations)
        val options = readOptions(item, path, violations)
        val multiSelect = readBoolean(item, "multi_select", path, violations) ?: false
        if (violations.size > before) return@forEachIndexed
        questions += Question(
            id = checkNotNull(id),
            question = checkNotNull(text),
            header = header,
            options = options ?: emptyList(),
            multiSelect = multiSelect,
        )
    }
    return if (violations.isEmpty()) AskArgs.Accepted(questions) else AskArgs.Rejected(violations)
}

private fun missingRequired(path: String): String = "missing required property \"" + path + "\""

private fun mustBe(path: String, expected: String): String = "\"" + path + "\" must be " + expected

/** 字段缺失或非法都返回 null；非法记一条 violation（缺失由调用方先判 required）。 */
private fun readString(obj: JsonObject, key: String, path: String, violations: MutableList<String>): String? {
    if (!obj.containsKey(key)) return null
    val primitive = obj[key] as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        violations += mustBe(path + "." + key, "a string")
        return null
    }
    return primitive.content
}

private fun readBoolean(obj: JsonObject, key: String, path: String, violations: MutableList<String>): Boolean? {
    if (!obj.containsKey(key)) return null
    val primitive = obj[key] as? JsonPrimitive
    val value = if (primitive == null || primitive.isString) null else primitive.booleanOrNull
    if (value == null) {
        violations += mustBe(path + "." + key, "a boolean")
        return null
    }
    return value
}

private fun readOptions(obj: JsonObject, path: String, violations: MutableList<String>): List<QuestionOption>? {
    if (!obj.containsKey("options")) return null
    val raw = obj["options"]
    if (raw !is JsonArray) {
        violations += mustBe(path + ".options", "an array")
        return null
    }
    val options = ArrayList<QuestionOption>()
    raw.forEachIndexed { index, element ->
        val item = element as? JsonObject
        val optionPath = path + ".options[" + index + "]"
        if (item == null) {
            violations += mustBe(optionPath, "an object")
            return@forEachIndexed
        }
        val before = violations.size
        if (!item.containsKey("label")) violations += missingRequired(optionPath + ".label")
        val label = readString(item, "label", optionPath, violations)
        val description = readString(item, "description", optionPath, violations)
        if (violations.size > before) return@forEachIndexed
        options += QuestionOption(checkNotNull(label), description)
    }
    return options
}

/**
 * ask_user_question：向用户提问并等待应答（UI 往返，超时/跳过返回结构化错误）。
 * 输出值与正文对齐 dsh-tool-ask-user：值是 { answers: [{ id, selected, custom? }] }，
 * 正文是这个值的 JSON。
 */
object AskUserTool : Tool {
    override val spec = ToolSpec(
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
    )
    override val parallelSafe = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val questions = when (val parsed = parseAskUserArgs(args)) {
            is AskArgs.Rejected -> return ToolResult.Error(
                "invalid arguments: " + parsed.violations.joinToString("; "),
                code = INVALID_ARGS,
            )
            is AskArgs.Accepted -> parsed.questions
        }
        if (questions.isEmpty()) {
            return ToolResult.Error("ask_user_question requires at least one question", code = EMPTY_QUESTIONS)
        }

        val answers = UserQuestionChannel.ask(questions, ctx.askTimeoutMs)
            ?: return ToolResult.Error("the user did not answer (timed out or skipped)")
        val value = buildJsonObject {
            put("answers", buildJsonArray {
                answers.forEach { answer ->
                    add(
                        buildJsonObject {
                            put("id", answer.id)
                            put("selected", buildJsonArray { answer.selected.forEach { add(JsonPrimitive(it)) } })
                            if (!answer.custom.isNullOrBlank()) put("custom", answer.custom)
                        },
                    )
                }
            })
        }
        return ToolResult.Ok(value.toString(), value)
    }
}
