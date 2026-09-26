package com.adsh.app.core.tools

import com.adsh.app.core.ptc.QuickJsRuntime
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

    private val runtime = QuickJsRuntime()

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
        val result = withContext(Dispatchers.IO) {
            runtime.run(program = program, tools = ToolRegistry.bindings, context = ctx)
        }
        ToolStepCounter.bump(result.toolCalls)
        SubCallTrace.record(result.subCalls)
        val logs = result.logs.joinToString("\n")
        if (result.error != null) {
            // dsh 的 CodeRunFailedError：code run failed (<kind>): <message>，
            // 后面再附一段 Captured output（程序已经 console.log 出来的东西），模型据此自己纠正
            val kind = if (result.error.startsWith("程序在 ")) "timeout" else "exception"
            val captured = if (result.logs.isEmpty()) "" else "\nCaptured output:\n" + logs
            return ToolResult.Error("code run failed (" + kind + "): " + result.error + captured)
        }
        val rendered = result.valueJson ?: ""
        val parts = listOf(logs, rendered).filter { it.isNotEmpty() }
        return ToolResult.Ok(
            if (parts.isEmpty()) "(run_code completed with no output)" else parts.joinToString("\n")
        )
    }
}
