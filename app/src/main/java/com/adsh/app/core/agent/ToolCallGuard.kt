package com.adsh.app.core.agent

/**
 * 一条消息之内工具调用的**死循环计数**（判据本身是纯函数 [loopGuardReason]，表在 TurnDecisionsTest）。
 *
 * 为什么要有它：AgentLoop 的派发循环以前自己维护两个局部量（`callSignatures` 与
 * `toolCallsThisTurn`），既要在循环外声明（计数跨**步**累计），又要在循环里读改写；
 * 搬成一个类之后，"记一次调用并给出该不该停"只有一处实现，拆循环时也不必再传两个可变局部量。
 *
 * 计数的生命周期是**一条用户消息**（一轮），不是一步：同一个工具 + 同一份参数在整轮里
 * 重复超过 [REPEAT_CALL_LIMIT] 次、或整轮的工具调用总数超过 [MAX_TOOL_CALLS_PER_TURN] 就停 ——
 * 这是"模型调用失误"，不是"任务很长"，两者的区别很重要。
 */
internal class ToolCallGuard {

    private val callSignatures = HashMap<String, Int>()
    private var callsThisTurn = 0

    /**
     * 记一次调用；返回非空 = **该停了**，字符串就是要给界面与日志的原因（[loopGuardReason] 的文案）。
     * 返回值非空时调用方仍要给这个未开始的调用补一对 call + result（spec-log §5.2）。
     */
    fun record(name: String, argsJson: String): String? {
        val signature = callSignature(name, argsJson)
        val repeat = (callSignatures[signature] ?: 0) + 1
        callSignatures[signature] = repeat
        callsThisTurn++
        return loopGuardReason(name, repeat, callsThisTurn)
    }
}
