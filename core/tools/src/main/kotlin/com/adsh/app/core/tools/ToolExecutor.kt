package com.adsh.app.core.tools

import com.adsh.app.seam.ptc.SubCall
import com.adsh.app.seam.tool.PresentedFile
import com.adsh.app.seam.tool.ToolImage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 一次工具执行的规范化结果：模型直调（[ToolExecutor.runDirect]）与 PTC 程序内子调用
 * （[ToolExecutor.runBinding]）共用。
 *
 * [text] 是**已脱敏**的正文（日志 / 界面 / 落库的唯一来源）；[value] 是 PTC 程序拿到的结构化值，
 * 不脱敏 —— 那条 wire 通道不进会话记录。成功时非 null：没有声明 value 的工具退化成正文的
 * JSON 字符串（程序拿到的就是它）。
 */
data class ToolOutcome(
    val ok: Boolean,
    /** 已脱敏的正文（[SecretRedaction] 在本类里只做一次） */
    val text: String,
    /** PTC 程序拿到的结构化值；失败为 null */
    val value: JsonElement?,
    val retryable: Boolean = false,
    val code: String? = null,
    val images: List<ToolImage> = emptyList(),
    val deliverables: List<PresentedFile> = emptyList(),
    val subCalls: List<SubCall> = emptyList(),
    /** 顶层调用计 1 + [ToolResult.Ok.steps]（dsh 状态栏「步」的口径） */
    val steps: Int = 1,
)

/**
 * 工具执行的唯一流水线：unknown tool 判定 → 参数解析 → 执行 → 错误归一化 → 脱敏。
 *
 * 两条入口的差异只有 PTC collapse 与两条文案（见 [runDirect] / [runBinding]），其余同一条路：
 *  - 取消（[CancellationException]）原样抛出，**不能**变成一条工具失败；
 *  - 脱敏只在这里做一次（[ToolOutcome.text]），[ToolOutcome.value] 保持原值；
 *  - 工具抛出的异常映射成 isError；binding 另打一条 Log（模型只看得到一行 message）。
 */
class ToolExecutor(
    private val tools: List<Tool> = ToolRegistry.all,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    /**
     * 模型直调（当前只有 run_code 允许）：先做 PTC collapse 拒绝，再走统一流水线。
     *
     * 文案逐字保持 dsh / ADSH 现状：unknown tool "x"；已知工具则是「只能从 run_code 程序里调」全句。
     */
    suspend fun runDirect(name: String, rawArgs: String, ctx: ToolContext): ToolOutcome {
        if (name != RunCodeTool.name) {
            val known = tools.any { it.name == name }
            return failed(
                if (known) {
                    "unknown tool \"" + name + "\": only `" + RunCodeTool.name +
                        "` is callable directly — call `" + name + "` from inside a `" +
                        RunCodeTool.name + "` program instead"
                } else {
                    "unknown tool \"" + name + "\""
                },
            )
        }
        return invoke(name, rawArgs, ctx, direct = true)
    }

    /** PTC 程序内 await tools.x()：不含 PTC collapse，其余同一条流水线。 */
    suspend fun runBinding(name: String, rawArgs: String, ctx: ToolContext): ToolOutcome =
        invoke(name, rawArgs, ctx, direct = false)

    private suspend fun invoke(
        name: String,
        rawArgs: String,
        ctx: ToolContext,
        direct: Boolean,
    ): ToolOutcome {
        val tool = tools.firstOrNull { it.name == name }
            ?: return failed(if (direct) "unknown tool \"" + name + "\"" else "unknown tool: " + name)
        val args = runCatching { json.parseToJsonElement(rawArgs).jsonObject }.getOrElse {
            val prefix = if (direct) "tool arguments" else "arguments"
            return failed(prefix + " are not a JSON object: " + rawArgs.take(200))
        }
        return try {
            when (val result = tool.execute(args, ctx)) {
                is ToolResult.Ok -> ToolOutcome(
                    ok = true,
                    text = SecretRedaction.redact(result.text),
                    // 没声明 value 的工具：程序拿到的就是正文的 JSON 字符串（wire 保持原值语义）
                    value = result.value ?: JsonPrimitive(result.text),
                    images = result.images,
                    deliverables = result.deliverables,
                    subCalls = result.subCalls,
                    steps = 1 + result.steps,
                )
                is ToolResult.Error -> failed(
                    message = result.message,
                    retryable = result.retryable,
                    code = result.code,
                    subCalls = result.subCalls,
                    steps = 1 + result.steps,
                )
            }
        } catch (cancel: CancellationException) {
            // 停止 ≠ 工具失败：吞掉会让「停止」变成「失败后继续下一轮」
            throw cancel
        } catch (t: Throwable) {
            // 工具内部异常在这里留一份堆栈：模型只看到一行 message，真机上要定位只能靠它。
            // 日志失败不能改变工具语义（本地单测的 android.jar 桩会让 Log 直接抛）。
            if (!direct) runCatching { android.util.Log.w("ADSH", "tool " + name + " threw", t) }
            failed(
                t.message
                    ?: if (direct) t::class.java.simpleName else t::class.java.simpleName + " (internal tool error)",
            )
        }
    }

    /** 失败结果：正文同样过脱敏；value 恒为 null */
    private fun failed(
        message: String,
        retryable: Boolean = false,
        code: String? = null,
        subCalls: List<SubCall> = emptyList(),
        steps: Int = 1,
    ): ToolOutcome = ToolOutcome(
        ok = false,
        text = SecretRedaction.redact(message),
        value = null,
        retryable = retryable,
        code = code,
        subCalls = subCalls,
        steps = steps,
    )
}
