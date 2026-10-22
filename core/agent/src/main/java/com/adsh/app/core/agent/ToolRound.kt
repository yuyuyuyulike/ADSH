package com.adsh.app.core.agent

import com.adsh.app.core.data.TurnStore
import com.adsh.app.core.llm.ToolCall
import com.adsh.app.core.session.SessionBody
import com.adsh.app.core.session.SessionLog
import com.adsh.app.seam.ptc.SubCall
import com.adsh.app.seam.tool.collectDeliverables
import com.adsh.app.seam.tool.encodeDeliverables
import com.adsh.app.seam.tool.encodeToolImages
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 一步的**顶层工具轮**：每个调用先补一对日志（tool/call 必须在**真正执行之前**，
 * 见 `packages/core/agent-loop/src/tool-calls.ts`），再执行、再写 tool 结果与库行
 * （先日志后落库：红点/结果不该等一次 SQLite 写入）。
 *
 * 工具执行的规范化结果（[com.adsh.app.core.tools.ToolOutcome]）里已经带齐子调用轨迹 / 图片 /
 * 交付物 / 步数：这一层只负责**按原顺序**落库与记账，不再碰任何全局通道。
 */
internal class ToolRound(
    private val store: TurnStore,
    private val json: Json,
    private val nextExecToken: () -> Long,
) {

    /**
     * 这一步工具轮的结果。
     *
     * @property stopReason 非空 = **死循环判据命中**（模型调用失误，不是「任务很长」，见 [ToolCallGuard]）
     * @property steps 这一步的工具步数（顶层调用各计 [com.adsh.app.core.tools.ToolOutcome.steps]）
     */
    data class Result(val stopReason: String?, val steps: Int)

    /**
     * 执行这一步模型要求的工具调用。
     *
     * 返回非空 stopReason = 死循环判据命中：调用方据此落一条「已停止」的消息收尾。判据命中时
     * 未开始的调用也要补一对 call + result，日志里不留悬空结果。
     *
     * 取消（[kotlinx.coroutines.CancellationException]）从工具层原样冒出 —— 那表示用户按了停止，
     * 不是工具失败，本轮由上层按 interrupted 收尾。
     */
    suspend fun run(
        conversationId: Long,
        calls: List<ToolCall>,
        tools: TurnTools,
        log: SessionLog,
        guard: ToolCallGuard,
        meter: TurnMeter,
    ): Result {
        var steps = 0
        for (call in calls) {
            currentCoroutineContext().ensureActive()
            val name = call.function.name ?: continue
            val argsJson = call.function.arguments ?: "{}"
            // 模型「调用失误」时的死循环：同一个工具 + 同一份参数反复来，或者这一步里的
            // 工具调用总数彻底失控 —— 立刻停，不要写出一条几 MB 的记录。
            // 签名与阈值见 [callSignature] / [loopGuardReason]（纯函数，有表）
            val why = guard.record(name, argsJson)
            if (why != null) {
                // dsh 的「未开始就取消」路径：宿主补一对 call + result（见 packages/core/session/src/repair.ts），
                // 日志里不留悬空的结果
                val stoppedToken = nextExecToken()
                log.append(SessionBody.ToolCall(call.id, stoppedToken, name, argsJson))
                log.append(SessionBody.ToolResult(call.id, stoppedToken, name, why, true))
                return Result(stopReason = why, steps = steps)
            }
            // 顶层调用的身份：进程内单调、永不复用（dsh 里缺 id 的 tool_call 由宿主补
            // `call-<index>`，见 packages/llm/llm/src/assembler.ts；模型给的 callId 不保证
            // 唯一 —— 有些网关每条响应都从 call_0 重编号）。
            // PTC 子调用的 id 就是 "<execToken>:ptc:<n>"，界面按它归属，不再靠到达顺序。
            val execToken = nextExecToken()
            // 顶层调用写进日志（dsh 的 tool/call 必须落在**真正执行之前**）
            log.append(SessionBody.ToolCall(call.id, execToken, name, argsJson))
            val toolStarted = System.currentTimeMillis()
            // 每次调用都带上自己的 callId：审批弹窗要显示是哪一次调用申请的越权。
            // execToken 只用于子调用轨迹的归属（见 ToolContext.onSubCall）。
            val outcome = tools.run(name, argsJson, call.id, execToken)
            val toolMillis = System.currentTimeMillis() - toolStarted
            meter.addToolMillis(toolMillis)
            // 步数：顶层调用计 1，程序内子调用由工具结果带上（dsh 状态栏「步」的口径）
            steps += outcome.steps
            // 子调用轨迹在这一刻定型（下面落库与界面事件用的是同一份）
            val subCalls = outcome.subCalls
            // 子调用产出的图片（目前只有 read_image）：落在这条工具行上。
            // 装配请求时按 dsh 的 tools-ptc deferContext 作为一条 user 消息回灌模型 ——
            // 图片不进 run_code 的返回值（dsh 的原话：attached after the run）。
            val images = outcome.images + subCalls.flatMap { it.images }
            // present 声明的交付物（dsh 的 deliverables/presented）：同样落在工具行上，
            // 界面按轮把它们画成轮尾的文件卡片。**只有成功的子调用才有**
            // （dsh：Blocked results publish none；这里 ok=false 的子调用不收集）。
            val deliverables = collectDeliverables(
                listOf(outcome.deliverables) + subCalls.filter { it.ok }.map { it.deliverables },
            )
            // 先写日志、再落库：工具早就返回了，红点/结果不该等一次 SQLite 写入
            // （还要序列化子调用与图片）才出现 —— 否则就是「报错的红点比正文慢半拍」。
            // 库里那一行仍在同一步内写完（这一步的 Step 事件之前），落库失败也不会改变程序行为。
            log.append(SessionBody.ToolResult(call.id, execToken, name, outcome.text, !outcome.ok))
            store.addMessage(
                conversationId = conversationId,
                role = "tool",
                // 单行必须有上限：CursorWindow 是 2MB，超了这一行就再也读不出来
                content = outcome.text.take(AgentLoop.MAX_TOOL_CONTENT_CHARS),
                toolCallId = call.id,
                name = name,
                subCallsJson = if (subCalls.isEmpty()) null else json.encodeToString(
                    ListSerializer(SubCall.serializer()),
                    cappedSubCalls(subCalls),
                ),
                imagesJson = encodeToolImages(images),
                deliverablesJson = encodeDeliverables(deliverables),
                isError = !outcome.ok,
                durationMs = toolMillis,
            )
        }
        return Result(stopReason = null, steps = steps)
    }

    /**
     * 子调用轨迹的落库上限（细节与「为什么不能按字符数砍参数」见 [SubCallTrimmer]）：
     * 只留最近 [AgentLoop.MAX_SUB_CALLS_PER_STEP] 条，并压进 CursorWindow 的预算内。
     */
    private fun cappedSubCalls(all: List<SubCall>): List<SubCall> =
        SubCallTrimmer.cap(all, AgentLoop.MAX_SUB_CALLS_PER_STEP)
}
