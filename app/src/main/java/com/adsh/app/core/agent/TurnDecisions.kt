package com.adsh.app.core.agent

import com.adsh.app.core.data.BuiltInProviders
import com.adsh.app.core.llm.ThinkingOption

/**
 * Agent 单轮循环（[AgentLoop.runTurnBody]）里的**判定**：全是「给定状态 → 下一个决定」的
 * 纯函数，不碰协程、库、网络。R11 把这几条从那个大函数里提出来，为的是**能测** ——
 * 每一条判错，用户在真机上看到的是「DeepSeek 思考模式整轮 400」「同一个调用被反复重试」
 * 「停下之后界面上什么都不说」这类现场，而长在协程与 IO 之间的判定一行都测不了。
 *
 * 留在 AgentLoop 里的只有「照判定去写库 / 发事件」这一步；表的另一边在 TurnDecisionsTest。
 */

/** 单条消息里允许的工具调用总数（死循环判据：模型调用失误时不会无限刷下去） */
internal const val MAX_TOOL_CALLS_PER_TURN = 300

/** 同一组「工具 + 参数」重复多少次就判定为死循环并停下 */
internal const val REPEAT_CALL_LIMIT = 3

/**
 * 当前路由是不是 DeepSeek。
 *
 * 是的话，带 tool_calls 的 assistant 消息**必须**把 reasoning_content 回传（思考模式的硬要求，
 * 空串也合法，见 [com.adsh.app.core.llm.ChatMessage]）；别的提供方不认识这个字段，就不发。
 * 三个判据任一命中即可：目录里的提供方 id、地址里带 deepseek、模型名以 deepseek 开头 ——
 * 用户自建的中转常常只改其中一处。
 */
internal fun isDeepSeekRoute(providerId: String, baseUrl: String, model: String): Boolean =
    providerId == BuiltInProviders.DEEPSEEK_ID ||
        baseUrl.contains("deepseek") ||
        model.startsWith("deepseek")

/** 一轮请求要发的思考字段（dsh 的 resolveThinking）。两个字段都可能缺席，缺席 = 走提供方默认 */
internal data class ThinkingWire(val reasoningEffort: String?, val thinking: ThinkingOption?)

/**
 * [com.adsh.app.core.data.Reasoning.wireEffort] 给出的等级 → 真正发出去的字段：
 *  - `null`（它说「两个字段都别发」）→ 都不发；
 *  - `"off"` → 只发 `thinking = disabled`（off 不是一档，**不发 reasoning_effort**）；
 *  - 其余档位 → `thinking = enabled` + `reasoning_effort = 档位`。
 */
internal fun thinkingWire(effort: String?): ThinkingWire = ThinkingWire(
    reasoningEffort = effort?.takeIf { it != "off" },
    thinking = effort?.let { ThinkingOption(if (it == "off") "disabled" else "enabled") },
)

/**
 * 一次**只要几十个输出 token 的小调用**（目前只有会话标题）该发的 thinking 字段。
 *
 * 为什么需要它（用户报的 bug，R79 实测）：DeepSeek V4 默认**先推理**，
 * `max_tokens = 64` 会被推理整段吃掉 —— 实测 `finish_reason = length`、`content` 为空、
 * `reasoning_content` 175 字，于是标题永远生成不出来（别的模型不推理，所以看着是
 * 「只有 DeepSeek 不总结」）。加上 `thinking = disabled` 之后同一次调用是
 * `finish_reason = stop`、`content` 10 字、6 个 token。
 *
 * 只对**认识这个字段的路由**发（判据与 AgentLoop 的 [isDeepSeekRoute] 同一条，理由也一样：
 * 别的提供方不认识它，就不发）；其余路由返回 null = 两个字段都不发，走提供方默认。
 */
internal fun noThinkFor(providerId: String, baseUrl: String, model: String): ThinkingOption? =
    if (isDeepSeekRoute(providerId, baseUrl, model)) thinkingWire("off").thinking else null

/**
 * 同一次小调用在 **DashScope 兼容模式（qwen 系）** 上要发的 enable_thinking：
 * false = 这次不思考，null = 不发（别的路由没有这个字段）。
 *
 * 为什么要它（用户报的「生成标题」这条路上的实测，2026-10-06 真机 + 同一把 key 的直连复现）：
 *  - 不关思考：标题这次 64 token 的调用先推理 187-290 字、2.8-7.3 秒，content 为空 ⇒
 *    ADSH 只能写兜底标题（真机上那条会话的标题就是首条消息前 5 个词，模型标题从没落地）；
 *  - enable_thinking=false：推理 0 字、1.6 秒、标题正常返回；
 *  - thinking={type:disabled}（DeepSeek 那个字段）在这条路由上反而要 4.4-9.9 秒 ——
 *    所以两条路由各发各认识的字段，判据都写在这里。
 *
 * 判据只认 DashScope 系端点（阿里云 maas / dashscope 域名，或用户按 qwen 建的提供方）：
 * 别的网关（例如 Cerebras 上的 qwen 模型）不认识这个字段，发了只会 400。
 */
internal fun noThinkDashScope(providerId: String, baseUrl: String, model: String): Boolean? =
    if (isDashScopeRoute(providerId, baseUrl, model)) false else null

/** DashScope 兼容模式的路由判据（[noThinkDashScope] 用；与 [isDeepSeekRoute] 同一层含义） */
internal fun isDashScopeRoute(providerId: String, baseUrl: String, model: String): Boolean =
    providerId == "qwen" ||
        baseUrl.contains("dashscope") ||
        baseUrl.contains("aliyuncs.com") ||
        baseUrl.contains("aliyun.com")

/**
 * 死循环判据的签名：**同一个工具 + 同一份参数**。参数按原始 JSON 串比，不做任何规范化 ——
 * 宁可漏判，也不能把两次不同的调用算成一次（分隔符用 NUL：工具名与参数拼起来不会串味）。
 */
internal fun callSignature(name: String, argsJson: String): String = name + "\u0000" + argsJson

/**
 * 死循环判据：返回「要停下」的原因，`null` = 继续。
 *
 * 这两条不是轮数上限的替代品，而是**模型调用失误**的判据（任务很长 ≠ 失误）：
 * 同一组调用重复超过 [REPEAT_CALL_LIMIT] 次，或本条消息的工具调用总数超过
 * [MAX_TOOL_CALLS_PER_TURN]，立刻停 —— 不要写出一条几 MB 的记录。
 *
 * @param repeat 这组签名在本条消息里第几次出现（1 = 第一次）
 * @param callsThisTurn 本条消息累计的工具调用数（含这一次）
 */
internal fun loopGuardReason(name: String, repeat: Int, callsThisTurn: Int): String? = when {
    repeat > REPEAT_CALL_LIMIT ->
        "同一个工具调用重复了 " + repeat + " 次（" + name + "），疑似陷入循环"
    callsThisTurn > MAX_TOOL_CALLS_PER_TURN ->
        "单条消息的工具调用总数超过 " + MAX_TOOL_CALLS_PER_TURN + " 次"
    else -> null
}

/**
 * 这一步之后是不是「本轮结束」：拿不到可执行的调用就是结束 —— 模型没有工具调用，或者网关把
 * tool_calls 截断了（只有参数没有名字，装配时拿不出可落库的调用）。PTC 没开时也一样。
 *
 * **为什么不能照样往下写**：那种情况下若写一条「空 tool_calls 的 assistant 行」再继续循环，
 * 库里的行序会变成 [assistant(c1)] [tool(c1)] [assistant(空)] [tool(c2)] —— 装配时中间那条空行
 * 被丢掉，两组工具结果贴到了一起，服务端 400（「assistant 的 tool_calls 后面必须紧跟它的每个
 * tool 结果」）。
 */
internal fun turnEndsAfterStep(executableCalls: Int, ptcEnabled: Boolean): Boolean =
    executableCalls == 0 || !ptcEnabled

/**
 * 这一步有没有值得落库的 assistant 内容。**空回复不落库**：库里出现 content 与 tool_calls
 * 都为空的 assistant 行，会让后续每一轮请求都被服务端以 400 拒绝。
 */
internal fun hasAssistantContent(answer: CharSequence, reasoning: CharSequence): Boolean =
    answer.isNotEmpty() || reasoning.isNotEmpty()

/**
 * 打断（CancellationException）时那条「已停止」要不要落库。
 *
 * 口径与 [hasAssistantContent] **不同**：这里纯空白不算内容（isNotBlank），而且工具调用非空也算
 * —— 历史留下的差异，本阶段只搬运、不统一（要统一另开一步，先想清楚哪种口径对）。
 */
internal fun hasInterruptedContent(answer: CharSequence, reasoning: CharSequence, toolCalls: Int): Boolean =
    answer.isNotBlank() || reasoning.isNotBlank() || toolCalls > 0

/**
 * 死循环判据命中、主动停下时写进会话的那条说明。
 *
 * **用户与模型都会看到**：不能让界面停在「看起来还在跑」的状态，所以原因要说清楚
 * （dsh 的「未开始就取消」路径也是这么收尾的）。
 */
internal fun stoppedText(reason: String): String =
    "（已停下：" + reason + "。可以换个说法让我继续，或先检查工作区状态）"
