package com.adsh.app.core.tools

import com.adsh.app.core.ptc.PtcProcess
import com.adsh.app.core.ptc.PtcToolRunner
import com.adsh.app.core.ptc.QuickJsRuntime
import com.adsh.app.core.ptc.SubCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        // code 是 dsh 的参数名；program 是旧历史里的别名，继续认，避免老会话执行不了
        val program = Args.str(args, "code") ?: Args.str(args, "program")
            ?: return ToolResult.Error("缺少参数 code（程序体）")
        if (Args.str(args, "description").isNullOrBlank() && args["description"] != null) {
            return ToolResult.Error("invalid description: expected a non-empty string")
        }
        // 权限不在程序这一层拦：程序里的每一次 tools.x() 调用都会各自过闸
        // （bash / write / edit 各自带 sandbox_permissions 升级，dsh 也是按工具调用围栏的）。
        // 以前这里整条 run_code 在 read_only 下被拒，于是连 glob / grep 这种只读子调用都跑不了。
        if (ctx.workspace.shellRoot == null) {
            return ToolResult.Error("当前工作区为 SAF 引用形态，run_code 不可用（工具链需要真实路径）")
        }
        // 中断探针：用户按「停止」时，正在跑的子调用要立刻收手，宿主也会**杀掉 :ptc 进程**
        // （没有它的话「停止」要等程序自己跑完 —— 程序里一个 120s 的 bash 就是两分钟）
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val cancelProbe = { job?.isActive == false }
        val appContext = ctx.appContext
            ?: return ToolResult.Error("run_code 需要应用上下文才能把程序放进独立进程（ToolContext.appContext 为空）")
        // 工具在**主进程**执行（ToolContext 只在这里有效），程序在 :ptc 进程跑 —— 两边靠 Messenger
        // 做同步 RPC，与 dsh 的「子进程 ↔ 父进程」同形（见 core/ptc/PtcProcess 的 KDoc）
        val runner = PtcToolRunner(ToolRegistry.bindings, ctx, cancelProbe)
        // dsh 的 clampTimeout：模型没给就用默认值 12e4，给了就夹在 (0, maxTimeoutMs]。
        // 预算**包含**程序里等工具与等审批的时间（dsh 的 schema 原文就这么写）—— 到点由宿主
        // 杀掉 :ptc 进程，所以这条预算是硬上限。
        val timeoutMs = (Args.int(args, "timeoutMs")?.toLong() ?: QuickJsRuntime.DEFAULT_TIMEOUT_MS)
            .coerceIn(1L, QuickJsRuntime.MAX_TIMEOUT_MS)
        val result = withContext(Dispatchers.IO) {
            PtcProcess.run(
                context = appContext,
                program = program,
                toolNames = ToolRegistry.bindings.map { it.name },
                timeoutMs = timeoutMs,
                runner = runner,
                cancel = cancelProbe,
            )
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
            // 第 101 轮：再附一张「已经跑成的子调用」回执（见 completedDigest）
            val digest = completedDigest(runner.subCalls())?.let { "\n" + it } ?: ""
            return ToolResult.Error(
                "code run failed (" + kind + "): " + result.error + captured + digest,
            )
        }
        // 程序没有显式 return 时 valueJson 就是字面量 "null"（QuickJS 那边把 undefined 也归一到
        // null）—— 那不是输出，别把它当一行结果打出去（测试 agent 反馈的「多一行 null」）。
        val rendered = result.valueJson?.takeIf { it != "null" } ?: ""
        val parts = listOf(logs, rendered).filter { it.isNotEmpty() }
        return ToolResult.Ok(
            if (parts.isEmpty()) "(run_code completed with no output)" else parts.joinToString("\n")
        )
    }

    /**
     * 失败消息里的**子调用回执**（第 101 轮，用户点名「run_code 下一个工具失败会带崩它后面的，
     * 只能重发，看看这得怎么优化」；第 105 轮按测试 agent 的反馈重做）。
     *
     * 结论先说清楚：**抛错语义照 dsh 不动**。dsh 的子调用失败同样 reject（`ToolCallError`）、
     * 同样中止程序，dsh 的 run_code 说明里那句 "Programs are never replayed automatically"
     * 就是这条口径；能优化的不是「失败不中止」（那要改掉 `try/catch` 的语义），
     * 而是**让「补发」不必整段重来**：
     *
     *  - 子调用轨迹在 dsh 里是 log-only（`Inner dispatch events stay log-only`），模型看不见；
     *    ADSH 同样只把 run_code 的返回值与打印行回灌模型。于是程序一挂，模型手上只剩
     *    「哪个工具失败了」——它压根不知道自己前面那几步已经生效了，只能凭记忆把整个程序重写。
     *  - 这里把**已经跑成的子调用 + 结果摘要**写进失败消息（失败的那一个已经在正文里），
     *    模型据此只补发没跑到的步骤。
     *
     * **第 105 轮的两处修正**（测试 agent 实测报告的问题 1）：
     *  - 旧文案是一句笼统的 "their effects are kept, so do not redo them" —— 对**只读**调用
     *    完全是误导：读操作没有「副作用被保留」这回事，需要的是**结果**，而结果随着程序一起没了。
     *    现在逐条标 `read-only` / `mutating`：写类的效果留在外面（别重做），只读的结果丢了
     *    （还需要那个值就重跑那一条）。判据取 [ToolConcurrency.isSafe] —— 与并发调度器、
     *    tools:sdk 里那份 safe 清单是**同一个来源**，不会各说各话。
     *  - 摘要里补上**退出状态**（`bash` 的 `[exit code: N]` / `[timed out after …]`）：旧文案只给
     *    结果的第一行，而「探测型」命令的结果第一行常常是 curl 自己打出来的数字，看不出成败。
     *    条数上限也从 6 提到 [MAX_DIGEST_CALLS]。
     *
     * 条数与单条长度都封顶：一条错误消息不能把上下文吃掉（dsh 的失败文本同样只带 kind +
     * captured output）。
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
     * 「跑成功了但命令本身失败」的探测（curl 打状态码、`apt` 打 `E:`）在回执里看不出成败（第 105 轮）。
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
