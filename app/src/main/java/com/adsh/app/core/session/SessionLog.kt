package com.adsh.app.core.session

import com.adsh.app.core.ptc.SubCall

/**
 * 会话事件（dsh 的 `Session.append` 产物，见 spec-log §1）。
 *
 * @param seq **恒等于日志长度**（连续、从 0 起、严格递增）—— dsh 的
 *   `seq === log.length`（[session]:1441-1481）。界面不需要自己编号，也不该编号。
 */
data class SessionEvent(val seq: Long, val at: Long, val body: SessionBody)

/**
 * 事件正文的全集（目前只有工具族；turn / step / assistant 三族随重写逐步搬进来）。
 *
 * 为什么工具族先行：用户报的「执行时消失、过一会又显示、有的重复显示」全部出在
 * 「实时子调用回调」与「落库工具行」两套数据对账上 —— 把它们并进一条日志之后，
 * 归属与顺序都只剩一个来源。
 */
sealed interface SessionBody {
    /** 一次顶层工具调用开始（dsh 的 `tool/call`）。[harnessId] 由宿主分配、进程内单调。 */
    data class ToolCall(
        val callId: String?,
        val harnessId: Long,
        val name: String,
        val arguments: String,
    ) : SessionBody

    /** 一次顶层工具调用的结果（dsh 的 `tool/result`）。非零退出**不是**错误，见 [isError] 的用法。 */
    data class ToolResult(
        val callId: String?,
        val harnessId: Long,
        val name: String,
        val output: String,
        val isError: Boolean,
    ) : SessionBody

    /** PTC 子调用开始（dsh 的 `tool/ptc-dispatch-start`）。`sub.id` = `<父 harnessId>:ptc:<n>`。 */
    data class PtcDispatchStart(val sub: SubCall) : SessionBody

    /** PTC 子调用结算（dsh 的 `tool/ptc-dispatch`）。 */
    data class PtcDispatch(val sub: SubCall) : SessionBody

    /**
     * 一步 assistant 输出定稿（dsh 的 assistant 步进日志，spec-log §2）。
     *
     * 它是**流式尾巴的交班点**：日志里出现这一步，界面这一段的正文/思考就交给库里那一行、
     * 流式缓冲清零 —— 两件事在同一次状态更新里发生，于是「同一段文字既在库行里、又当尾巴挂着」
     * 这一帧在结构上不存在（第 111 轮删掉了「拿流式正文与落库正文逐字比对」的去重）。
     */
    data class Step(
        val text: String,
        val reasoning: String,
        /** 这一步是以「被打断 / 已停下」收尾的（dsh 的 message.stopped） */
        val interrupted: Boolean = false,
    ) : SessionBody
}

/**
 * 唯一的追加原语（dsh 的 `Session`）。
 *
 * 契约（照抄 spec-log §1，ADSH 侧一条不少）：
 *  1. **唯一写入者**：只有 AgentLoop 调 [append]；界面只读 [events]、或者订阅 [observe]。
 *  2. **同步提交 + 提交后广播**：事件先入数组（拿 seq），**再**同步通知观察者；
 *     观察者抛异常不影响提交（dsh 的 contain 语义）。
 *  3. 顺序由「谁先 append」决定 —— 工具线程的回调与主循环的 append 争的是**同一把锁**，
 *     所以不存在「回调比宿主登记这次调用更早到」这种需要额外状态去兜的竞速。
 */
class SessionLog {
    private val lock = Any()
    private val list = ArrayList<SessionEvent>()
    private val observers = ArrayList<(SessionEvent) -> Unit>()

    /** 当前日志的一份快照（只读） */
    val events: List<SessionEvent> get() = synchronized(lock) { list.toList() }

    /** 订阅后续事件；订阅之前的那些要读 [events]（与 dsh 的「快照 + 增量」同款） */
    fun observe(observer: (SessionEvent) -> Unit) {
        synchronized(lock) { observers += observer }
    }

    /** 追加一条事件并返回它（seq = 追加前的长度） */
    fun append(body: SessionBody): SessionEvent {
        val event = SessionEvent(
            seq = synchronized(lock) { list.size.toLong() },
            at = System.currentTimeMillis(),
            body = body,
        )
        val targets: List<(SessionEvent) -> Unit>
        synchronized(lock) {
            list += event
            targets = observers.toList()
        }
        for (target in targets) runCatching { target(event) }
        return event
    }

    companion object {
        /**
         * 不变量自检（spec-log §7.2 的 ADSH 版）：真机跑完一轮调一次，返回违规说明（空 = 通过）。
         *
         * 断言的是「日志自身自洽」这一层 —— 单测与 debug 包都打它，出问题时能在设备上直接看到
         * 是哪一条不成立，而不是靠界面上「少了一行」去猜。
         */
        fun violations(events: List<SessionEvent>): List<String> {
            val bad = ArrayList<String>()
            val openCalls = HashSet<Long>()
            val closedCalls = HashSet<Long>()
            for ((index, event) in events.withIndex()) {
                if (event.seq != index.toLong()) {
                    bad += "seq 不连续：第 " + index + " 条的 seq 是 " + event.seq
                    break
                }
                when (val body = event.body) {
                    is SessionBody.ToolCall -> {
                        if (!openCalls.add(body.harnessId)) bad += "同一次调用开始了两次：" + body.harnessId
                    }
                    is SessionBody.ToolResult -> {
                        if (body.harnessId !in openCalls) bad += "结果没有对应的调用：" + body.harnessId
                        if (!closedCalls.add(body.harnessId)) bad += "同一次调用结算了两次：" + body.harnessId
                    }
                    // 一步定稿没有跨事件的不变量（它的正文/思考就是那一步自己的内容）
                    is SessionBody.Step -> Unit
                    is SessionBody.PtcDispatchStart, is SessionBody.PtcDispatch -> {
                        val sub = if (body is SessionBody.PtcDispatchStart) body.sub else (body as SessionBody.PtcDispatch).sub
                        val parent = sub.id.substringBefore(":ptc:").toLongOrNull()
                        if (parent == null || parent !in openCalls) {
                            bad += "子调用 " + sub.id + " 找不到父调用（id 前缀必须是父的 harnessId）"
                        }
                    }
                }
            }
            return bad
        }
    }
}
