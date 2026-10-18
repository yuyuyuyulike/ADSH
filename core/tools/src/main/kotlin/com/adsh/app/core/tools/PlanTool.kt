package com.adsh.app.core.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.adsh.app.seam.tool.Question
import com.adsh.app.seam.tool.QuestionOption
import com.adsh.app.seam.tool.UserQuestionChannel

/**
 * dsh-plan-mode 的 exit_plan_mode：把计划交给用户审阅，批准后才离开计划模式。
 *
 * 与 dsh 对齐（dismiss 路径是有意差异，见最后一条）：
 *  - 只在计划模式里可用；
 *  - 计划必须以 # 开头的 Markdown（dsh 的 /^#\s+\S/ 校验）；
 *  - 用提问通道问一个问题（id=plan-review、header=Plan review、
 *    question=Approve this plan and leave plan mode?、detail=计划全文、
 *    选项 Approve / Keep planning），界面渲染成「计划待审」卡；
 *  - 批准 → 记下「离开计划模式」，输出 { approved: true }，
 *    正文是 "Plan approved — plan mode exited; carry out the plan starting with your next step."；
 *  - 选择继续规划 → 失败，把反馈带回模型（没有反馈时用 dsh 的原句）；
 *  - 用户点「去聊天里说」（放弃这次提问）→ 失败，让模型停下等用户回复。**与 dsh 有意不同**：
 *    dsh 返回成功 {approved:false} 并 concludeTurn 结束本步；ADSH 没有 concludeTurn 端口，
 *    返回成功会让模型继续执行未经批准的计划，所以用失败收住。
 */
object ExitPlanModeTool : Tool {
    override val name = "exit_plan_mode"
    override val description: String get() = ToolSdk.description(name)

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!ctx.planMode) return ToolResult.Error("exit_plan_mode is only available in plan mode")
        val plan = Args.str(args, "plan").orEmpty()
        if (!Regex("^#\\s+\\S").containsMatchIn(plan.trim())) {
            return ToolResult.Error("exit_plan_mode requires a non-empty markdown plan starting with a # heading")
        }
        val question = Question(
            id = PlanReview.ID,
            header = "Plan review",
            question = "Approve this plan and leave plan mode?",
            detail = plan,
            options = listOf(
                QuestionOption(PlanReview.APPROVE, "Leave plan mode; the plan is carried out from the next step."),
                QuestionOption(PlanReview.KEEP_PLANNING, "Stay in plan mode; feedback goes back to the model."),
            ),
            intent = PlanReview.KIND,
        )
        val answers = UserQuestionChannel.ask(listOf(question), ctx.askTimeoutMs)
            ?: return ToolResult.Error(
                "The user dismissed the plan review to speak instead; stay in plan mode, stop here, " +
                    "and wait for their message.",
            )
        val item = answers.firstOrNull { it.id == PlanReview.ID }
        if (item?.selected?.singleOrNull() != PlanReview.APPROVE || item.custom != null) {
            val feedback = item?.custom.orEmpty()
            return ToolResult.Error(
                if (feedback.isEmpty()) {
                    "The user chose to keep planning; revise the plan and present it again."
                } else {
                    "The user chose to keep planning; their feedback: " + feedback
                },
            )
        }
        ctx.onPlanModeChanged?.invoke(false)
        val value = buildJsonObject { put("approved", JsonPrimitive(true)) }
        return ToolResult.Ok(
            "Plan approved — plan mode exited; carry out the plan starting with your next step.",
            value,
        )
    }
}
