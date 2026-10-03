package com.adsh.app.ui

import com.adsh.app.core.ptc.SubCall
import com.adsh.app.core.session.SessionBody
import com.adsh.app.core.session.SessionEvent

/**
 * 正在跑的这一轮里，工具族的界面形态 —— **由会话日志折叠出来**（dsh 的 cell 模型：
 * 界面是 `fold(log)`，不是一个「实时状态」再和落库结果对账）。
 *
 * 折叠规则就是 dsh 的归属规则：
 *  - 顶层调用按 [SessionBody.ToolCall.harnessId] 建行，结果按同一个 id 贴回（**不看到达顺序**）；
 *  - 子调用按 [SubCall.id] 贴回，`<父 harnessId>:ptc:<n>` 的前缀决定它挂在哪一行下面；
 *  - 没有对应调用的结果 / 子调用一律丢弃（日志自检 [com.adsh.app.core.session.SessionLog.violations]
 *    会先把这种情形报出来，界面不必再兜）。
 */
data class LiveTurn(
    val calls: List<LiveCall> = emptyList(),
    val subCalls: List<SubCall> = emptyList(),
    /**
     * 日志里已经定稿的步数（dsh 的 assistant 步）。流式尾巴的交班信号就是它：
     * 每多一步，界面这一段的正文/思考就交给库里那一行（见 `ChatUiState` 的 Step 分支）。
     */
    val steps: Int = 0,
)

/** 把一轮的事件折成界面形态（纯函数，桌面上直接测：见 `LiveTurnTest`） */
fun foldLiveTurn(events: List<SessionEvent>): LiveTurn {
    val calls = ArrayList<LiveCall>()
    val subs = ArrayList<SubCall>()
    var steps = 0
    for (event in events) {
        when (val body = event.body) {
            is SessionBody.ToolCall -> {
                calls += LiveCall(
                    harnessId = body.harnessId,
                    callId = body.callId,
                    name = body.name,
                    arguments = body.arguments,
                    startedAt = event.at,
                )
            }
            is SessionBody.ToolResult -> {
                val index = calls.indexOfLast { it.harnessId == body.harnessId }
                if (index >= 0) {
                    calls[index] = calls[index].copy(
                        output = body.output,
                        isError = body.isError,
                        finishedAt = event.at,
                    )
                }
            }
            is SessionBody.Step -> steps++
            is SessionBody.PtcDispatchStart -> subs += body.sub
            is SessionBody.PtcDispatch -> {
                val index = subs.indexOfLast { it.id.isNotEmpty() && it.id == body.sub.id }
                if (index >= 0) subs[index] = body.sub else subs += body.sub
            }
        }
    }
    return LiveTurn(calls = calls, subCalls = subs, steps = steps)
}
