package com.adsh.app.core.tools

import com.adsh.app.seam.ptc.SubCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * PTC 的唯一入口：模型写一段程序，程序内用 `await tools.<name>(args)` 组合调用其它工具。
 * 对齐 dsh：ptc 模式下只有 run_code 可以被模型直接调用（其余工具只在系统提示词的
 * `tools:sdk` 段里声明）。
 *
 * 参数与输出都按 dsh 的 dsh-tools/lib/types/ptc.js：
 *  - 参数是 `code`（程序体）+ `description`（UI 里显示的短说明），两个都必填；
 *  - 输出 = logs 逐行拼接 + 结果值，两者都空时是 `(run_code completed with no output)`。
 */
object RunCodeTool : Tool {
    override val name = "run_code"
    override val description = ToolSdk.RUN_CODE_DESCRIPTION

    /**
     * wire 上唯一暴露的工具 schema（dsh-tools 的 wireSchemas）：code + description，两个都必填。
     * 上下文统计的「工具定义」一栏就是它的 JSON 体积（dsh 的 estimateToolsTokens）。
     */
    val wireSchema: JsonObject = buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", "run_code")
                put("description", ToolSdk.RUN_CODE_DESCRIPTION)
                put(
                    "parameters",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "code",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", ToolSdk.RUN_CODE_CODE_DESCRIPTION)
                                    },
                                )
                                put(
                                    "description",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", ToolSdk.RUN_CODE_DESCRIPTION_PARAM_DESCRIPTION)
                                    },
                                )
                                // dsh 的 RUN_CODE_CONTROLS.timeoutMs（dsh-tools/lib/types/ptc.js）：
                                // 「Positive elapsed-time budget in milliseconds, **including nested
                                // tool and approval waits**. Default 120000; capped at 600000.」
                                put(
                                    "timeoutMs",
                                    buildJsonObject {
                                        put("type", "number")
                                        put("description", ToolSdk.RUN_CODE_TIMEOUT_PARAM_DESCRIPTION)
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            buildJsonArray {
                                add(JsonPrimitive("code"))
                                add(JsonPrimitive("description"))
                            },
                        )
                    },
                )
            },
        )
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        when (val parsed = parseRunCodeArgs(args)) {
            is ParsedRunCodeArgs.Rejected ->
                ToolResult.Error("invalid arguments: " + parsed.violations.joinToString("; "))
            is ParsedRunCodeArgs.Accepted -> executeProgram(parsed, args, ctx)
        }

    /** 参数通过 schema 语义之后的执行体 */
    private suspend fun executeProgram(args: ParsedRunCodeArgs.Accepted, rawArgs: JsonObject, ctx: ToolContext): ToolResult {
        // dsh 的 execute 自己拒绝全空白 description（schema 层只查类型），文案逐字
        if (args.description.trim().isEmpty()) {
            return ToolResult.Error("invalid description: expected a non-empty string")
        }
        // 权限不在程序这一层拦：程序里的每一次 tools.x() 调用都会各自过闸
        // （bash / write / edit 各自带 sandbox_permissions 升级，dsh 也是按工具调用围栏的）。
        // **不要整条拒绝 run_code**：那会让 read_only 下连 glob / grep 这种只读子调用都跑不了。
        if (ctx.workspace.shellRoot == null) {
            return ToolResult.Error("当前工作区为 SAF 引用形态，run_code 不可用（工具链需要真实路径）")
        }
        // 中断探针：用户按「停止」时，正在跑的子调用要立刻收手，宿主也会**杀掉 :ptc 进程**
        // （没有它的话「停止」要等程序自己跑完 —— 程序里一个 120s 的 bash 就是两分钟）
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val cancelProbe = { job?.isActive == false }
        val appContext = ctx.appContext
            ?: return ToolResult.Error("run_code 需要应用上下文才能把程序放进独立进程（ToolContext.appContext 为空）")
        // PTC 运行时由组合根注入（[com.adsh.app.seam.ptc.PtcRunner] 端口）：工具层不认识 QuickJS 那一侧
        val ptc = ctx.ptcRunner
            ?: return ToolResult.Error("run_code 需要 PTC 运行时（ToolContext.ptcRunner 未注入）")
        // 工具在**主进程**执行（ToolContext 只在这里有效），程序在 :ptc 进程跑 —— 两边靠 LocalSocket
        // 上的「4 字节长度 + JSON」帧做同步 RPC，与 dsh 的「子进程 ↔ 父进程」同形（见 platform/ptc/PtcChannel）
        val runner = PtcToolRunner(ToolRegistry.bindings, ctx, cancelProbe)
        // dsh 的 clampTimeout：模型没给就用默认值 12e4，给了就夹在 (0, maxTimeoutMs]。
        // 预算**包含**程序里等工具与等审批的时间（dsh 的 schema 原文就这么写）—— 到点由宿主
        // 杀掉 :ptc 进程，所以这条预算是硬上限。
        val timeoutMs = (Args.int(rawArgs, "timeoutMs")?.toLong() ?: ptc.defaultTimeoutMs)
            .coerceIn(1L, ptc.maxTimeoutMs)
        val result = try {
            withContext(Dispatchers.IO) {
                ptc.run(
                    appContext = appContext,
                    program = args.program,
                    toolNames = ToolRegistry.bindings.map { it.name },
                    timeoutMs = timeoutMs,
                    cancel = cancelProbe,
                    call = { name, argsJson -> runner.call(name, argsJson) },
                )
            }
        } finally {
            // dsh：run 结束（超时 / worker 崩溃 / 用户中断）时先把「还在跑」的子调用按 abort 结算，
            // 父工具行的 tool/result 之后才写进日志 —— 界面不会留下永远在转的子行，晚到的完成也会被丢弃。
            runner.abortOpenCalls()
        }
        // 被中断：把取消原样抛出去（不要变成一条「程序失败」的工具结果 —— 那会接着跑下一轮）
        if (result.aborted) throw kotlinx.coroutines.CancellationException("run_code interrupted by the user")
        ToolStepCounter.bump(result.toolCalls)
        val logs = result.logs.joinToString("\n")
        if (result.error != null) {
            // dsh 的 CodeRunFailedError：code run failed (<kind>): <message>，
            // 后面再附一段 Captured output（程序已经 console.log 出来的东西），模型据此自己纠正。
            // kind 直接取运行时的失败分类（timeout / worker-exit / abort / exception），不再猜文案。
            val kind = result.failureKind ?: "exception"
            val captured = if (result.logs.isEmpty()) "" else "\nCaptured output:\n" + logs
            // 再附一张「已经跑成的子调用」回执（见 completedDigest）
            val digest = completedDigest(runner.subCalls())?.let { "\n" + it } ?: ""
            return ToolResult.Error(
                "code run failed (" + kind + "): " + result.error + captured + digest,
            )
        }
        return ToolResult.Ok(renderRunCodeOutput(logs, result.valueJson))
    }

    /**
     * 成功结果的正文：日志 + 结果值，两者都空时是 dsh 的 no-output 文案。
     *
     * 显式 `return null` 是结果（dsh 的 renderValue(null) 渲染成 `null`）；没有 return 时运行时
     * 不带 valueJson（dsh：`value.result === undefined ? '' : renderValue(value.result)`）。
     */
    internal fun renderRunCodeOutput(logs: String, valueJson: String?): String {
        val parts = listOf(logs, valueJson ?: "").filter { it.isNotEmpty() }
        return if (parts.isEmpty()) "(run_code completed with no output)" else parts.joinToString("\n")
    }

    /**
     * run_code 的参数读写结果。
     *
     * 参数契约照 dsh 的 json-schema 层（defineTool 在 execute **之前**整条拒绝）：`code` 与
     * `description` 必填且必须是 string；violation 文案逐字，多条用 `; ` 连接。
     * `program` 是 `code` 的旧别名（dsh 没有这个名字）：老会话发来的调用继续认，
     * 类型错时报它自己的名字。
     */
    internal sealed interface ParsedRunCodeArgs {
        data class Accepted(val program: String, val description: String) : ParsedRunCodeArgs
        data class Rejected(val violations: List<String>) : ParsedRunCodeArgs
    }

    /** dsh 的 required 段与类型段分开收集（先 required、后类型），所以一路读到底再一起报 */
    internal fun parseRunCodeArgs(args: JsonObject): ParsedRunCodeArgs {
        val violations = ArrayList<String>(2)
        if (args["code"] == null && args["program"] == null) {
            violations += "missing required property \"code\""
        }
        if (args["description"] == null) {
            violations += "missing required property \"description\""
        }
        val codeKey = if (args["code"] == null) "program" else "code"
        val program = args[codeKey]?.let { readStringArg(it, codeKey, violations) }
        val description = args["description"]?.let { readStringArg(it, "description", violations) }
        if (violations.isNotEmpty() || program == null || description == null) {
            return ParsedRunCodeArgs.Rejected(violations)
        }
        return ParsedRunCodeArgs.Accepted(program, description)
    }

    /** dsh 的 `"<key>" must be a string`：JSON null / 数字 / 对象都算类型错 */
    private fun readStringArg(value: JsonElement, key: String, violations: MutableList<String>): String? {
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            violations += "\"" + key + "\" must be a string"
            return null
        }
        return primitive.content
    }

    /**
     * 失败消息里的**子调用回执**：唯一的生产者是 PtcToolRunner，模型据此只补发没跑到的步骤。
     *
     * 抛错语义照 dsh 不动（子调用失败同样中止程序、不重放）；回执把**已经跑成的子调用 + 结果
     * 摘要**写进失败消息，每条标 `read-only` / `mutating`（判据取 [ToolConcurrency.isSafe]，与
     * 并发调度器、tools:sdk 的 safe 清单同一来源）并带**退出状态**（[bashStatusMarker]）。
     * 条数 [MAX_DIGEST_CALLS]、单条 [MAX_DIGEST_CHARS] 封顶：一条错误消息不能把上下文吃掉。
     */
    internal fun completedDigest(subCalls: List<SubCall>): String? {
        val done = subCalls.filter { it.ok }
        if (done.isEmpty()) return null
        // 只报最近几条：一次程序里几十个子调用时，前面那些的结果对「接下来补什么」没有帮助
        val shown = done.takeLast(MAX_DIGEST_CALLS)
        return buildString {
            append("Sub-calls that already completed (").append(done.size)
            if (shown.size < done.size) append(", last ").append(shown.size)
            append(") — mutations stayed in effect, so do not redo those; a read-only call's result " +
                "died with the program, so re-run the ones whose values you still need:")
            shown.forEach { call ->
                append("\n  - ").append(call.name)
                append(if (ToolConcurrency.isSafe(call.name)) " (read-only)" else " (mutating)")
                val digest = digestOf(call)
                if (digest.isNotEmpty()) append(" — ").append(digest)
            }
            append(
                "\nThe program stopped at the failed call, so the steps after it never ran: send a " +
                    "follow-up program with only the remaining steps instead of re-sending this one.",
            )
        }
    }

    /**
     * 一条子调用的结果摘要：**退出状态 + 第一行非空文本**，单行、封顶 [MAX_DIGEST_CHARS] 个字符。
     *
     * 退出状态取自结果正文末尾那行标记（[bashStatusMarker] 从 `renderBash` 写的正文里读回来：
     * `[exit code: N]` / `[timed out after Nms]`，两端格式在同一个文件里）—— 没有它，一条
     * 「跑成功了但命令本身失败」的探测（curl 打状态码、`apt` 打 `E:`）在回执里看不出成败。
     */
    private fun digestOf(call: SubCall): String {
        val status = bashStatusMarker(call.result)
        val first = call.result.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val parts = ArrayList<String>(2)
        if (status != null) parts += status
        if (first.isNotEmpty() && first != status) parts += first
        val digest = parts.joinToString(" · ")
        return if (digest.length > MAX_DIGEST_CHARS) digest.take(MAX_DIGEST_CHARS) + "…" else digest
    }

    /** 失败回执里最多列几条子调用 */
    internal const val MAX_DIGEST_CALLS = 12

    /** 每条子调用摘要的字符上限（单测按它断言，所以是 internal） */
    internal const val MAX_DIGEST_CHARS = 120
}
