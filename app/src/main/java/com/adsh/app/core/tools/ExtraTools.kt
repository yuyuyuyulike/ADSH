package com.adsh.app.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
    override val name = "todo_write"
    override val description: String get() = ToolSdk.TODO_DESCRIPTION

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
 * present：声明最终交付物。
 *
 * 参数与输出逐字对齐 dsh-tool-present：files: [{ path, description? }]，1..maxFiles 个
 * （dsh 的 Config.maxFiles 默认 8），每个 path 必须是已存在的普通文件；
 * 输出是 { turn, files }，正文每行 "Presented <path>"。
 *
 * 交付物的展示分两处（都对齐 dsh）：
 *  - 对话里这一行工具（dsh 的 PresentRow：状态 + 文件名 + 可展开的输出）；
 *  - **轮尾的文件卡片**（dsh 的 ui-deliverables 的 turnTail 槽）：由成功的这次调用声明的
 *    [com.adsh.app.core.agent.PresentedFile] 落库（dsh 的 deliverables/presented 事件），
 *    点一张卡片进文件预览 —— dsh 的 PresentedFileCard 也是点开进 Sidebar 预览。
 */
object PresentTool : Tool {
    override val name = "present"
    override val description: String get() = ToolSdk.PRESENT_DESCRIPTION

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
        // given（模型给的原样路径） / description / shown（卡片用的展示路径，裁过工作区前缀）
        val accepted = ArrayList<Triple<String, String?, String>>()
        // 逐条回一句「在不在工作区内」（第 105 轮，实测报告问题 6）：present 是专门用来保证
        // 「用户拿得到」的工具，却只报一句 Presented —— 文件在工作区外（例如 scratch）时照样
        // 静默成功，核对只能靠模型自己 `ls`。现在把判定写进返回值（dsh 的契约仍然是
        // 「present 不检查根、也不拒绝」，所以这里只是**说清楚**，不改成失败）。
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
            accepted += Triple(path, description, shown)
            // 回执回显**模型给出的那个路径**（第 108 轮，实测报告问题 5）：以前回的是工作区相对
            // 路径（输入绝对路径、回来相对路径），模型据此记下的路径就是错的。展示路径仍按
            // 第 85 轮的口径裁前缀，只用在轮尾那张卡片上，不参与回执。
            lines += if (Args.inside(ctx, file)) {
                "Presented " + path + " (inside the workspace)"
            } else {
                "Presented " + path + " — WARNING: this path is outside the workspace, so the user may " +
                    "not be able to open it. Copy the file into the workspace and present it again " +
                    "before you finish."
            }
        }
        val value = buildJsonObject {
            put("turn", ctx.turn)
            put("files", buildJsonArray {
                accepted.forEach { (given, description, _) ->
                    add(
                        buildJsonObject {
                            // 结构化返回值里也是**原样路径**：与输入一致（回执与 value 同一口径）
                            put("path", given)
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
                com.adsh.app.core.agent.PresentedFile(path = shown, description = description)
            },
        )
    }
}

/**
 * ask_user_question：向用户提问并等待应答（UI 往返，超时/跳过返回结构化错误）。
 * 输出值与正文对齐 dsh-tool-ask-user：值是 { answers: [{ id, selected, custom? }] }，
 * 正文是这个值的 JSON。
 */
object AskUserTool : Tool {
    override val name = "ask_user_question"
    override val description: String get() = ToolSdk.description("ask_user_question")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val raw = args["questions"] as? JsonArray ?: return ToolResult.Error("questions must be an array")
        val questions = raw.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val text = obj["question"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val options = obj["options"]?.jsonArray?.mapNotNull { opt ->
                val o = opt as? JsonObject ?: return@mapNotNull null
                val label = o["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                QuestionOption(label, o["description"]?.jsonPrimitive?.contentOrNull)
            } ?: emptyList()
            Question(
                id = id,
                question = text,
                header = obj["header"]?.jsonPrimitive?.contentOrNull,
                options = options,
                multiSelect = obj["multi_select"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
        if (questions.isEmpty()) return ToolResult.Error("questions must contain at least one question with an id and question text")

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
