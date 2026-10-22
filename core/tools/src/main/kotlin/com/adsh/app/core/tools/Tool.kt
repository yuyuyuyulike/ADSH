package com.adsh.app.core.tools

import com.adsh.app.seam.ptc.SubCall
import com.adsh.app.seam.tool.PresentedFile
import com.adsh.app.seam.tool.ToolImage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 工具的全部静态事实（dsh 的 ToolDefinition 的静态部分）：名字、模型可见说明、
 * tools:sdk 段里的参数与输出类型字面量。一个工具只在自己身上声明一次。
 */
data class ToolSpec(
    /** 工具名（同时是程序里的 tools.<name>） */
    val name: String,
    /** 模型可见的一句话说明（dsh 的 schema.description） */
    val description: String,
    /** ToolArgsMap 里这个名字的类型字面量（逐字进 tools:sdk 段） */
    val args: String,
    /** ToolOutputMap 里这个名字的类型（逐字进 tools:sdk 段） */
    val output: String,
)

interface Tool {
    /** 全部静态事实：registry 的 specs / parallelSafeNames 都从这里投影 */
    val spec: ToolSpec
    val name: String get() = spec.name
    val description: String get() = spec.description

    /**
     * dsh 的 isConcurrencySafe：**只有精确 true** 才能与其他 safe 调用重叠执行。
     * 调度器与 tools:sdk 的 safe 清单都只认这一处；未声明（默认）就是 exclusive。
     */
    val parallelSafe: Boolean get() = false

    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

sealed interface ToolResult {
    /**
     * 成功结果：
     *  - [text] 是「展示/日志」用的正文（对话里那一行工具的输出）；
     *  - [value] 是程序里 await tools.x() **真正拿到**的结构化值（dsh 的 output.schema）。
     *    为 null 时由执行器退化成正文的 JSON 字符串（老工具/老会话兼容）。
     */
    data class Ok(
        val text: String,
        val value: JsonElement? = null,
        /**
         * 这次调用读到/产出的图片（dsh 的输出内容块里的 image 块）。
         *
         * PTC 里它是**子调用**的产物：run_code 的程序拿到结构化值，图片本身由工具行收走
         * （装配时作为一条 deferred user 消息回灌模型，dsh 的 tools-ptc deferContext 就是这么做的），
         * 界面则在那一行下面渲染画廊。
         */
        val images: List<ToolImage> = emptyList(),
        /**
         * 这次调用声明的交付物（只有 present 会产出）—— dsh 的 `deliverables/presented`：
         * 成功的最终结果追加一条会话事件，界面按轮把文件卡片画在轮尾、点开进文件预览。
         */
        val deliverables: List<PresentedFile> = emptyList(),
        /** run_code：程序内子调用轨迹（顶层工具行落库/界面轨迹读它） */
        val subCalls: List<SubCall> = emptyList(),
        /** run_code：程序内工具步数（顶层调用另计 1，见 [ToolOutcome.steps]） */
        val steps: Int = 0,
    ) : ToolResult

    /**
     * 失败结果。
     * @param message 人可读的原文（dsh 的 WebError.message / 工具自身报错），**不带** Error: 前缀
     * @param retryable ADSH 自己的提示：同样的调用重试一次可能成功
     * @param code 可机读的原因码（dsh 的 WebError.code，如 WEB_BLOCKED_URL / WEB_PROVIDER_ERROR），
     *   由执行层以结构化错误字段暴露（dsh 的 "Tool execution exposes the code in structured error metadata"）
     * @param subCalls / steps 只为 run_code 的失败回执存在：程序跑了一半失败时，已结算的子调用
     *   仍要进工具行（界面轨迹、落库），步数也要计入状态栏。
     */
    data class Error(
        val message: String,
        val retryable: Boolean = false,
        val code: String? = null,
        val subCalls: List<SubCall> = emptyList(),
        val steps: Int = 0,
    ) : ToolResult
}
