import io, sys
P = '/mnt/d/WSN2005/Android1/App/ADSH/app/src/main/java/com/adsh/app/ui/ChatViewModel.kt'
s = io.open(P, encoding='utf-8').read()

def rep(old, new):
    global s
    if old not in s:
        print('!! 未匹配: ' + old[:70].replace('\n', ' | ')); sys.exit(1)
    s = s.replace(old, new, 1)

# 1) LiveCall 数据类 + 状态字段
rep('''/** token 估算：CJK 按 1 token/字，其余按 4 字符/token（够用且可解释） */''',
'''/**
 * 正在跑的一轮里的工具调用（dsh 的 live tool row）：
 * 开始即出行、运行中带扫光、结束后钉上用时与状态。
 */
data class LiveCall(
    val id: Long,
    val name: String,
    val arguments: String,
    val startedAt: Long,
    val output: String? = null,
    val isError: Boolean = false,
    val finishedAt: Long? = null,
) {
    val running: Boolean get() = finishedAt == null
    val durationMs: Long? get() = finishedAt?.minus(startedAt)
}

/** token 估算：CJK 按 1 token/字，其余按 4 字符/token（够用且可解释） */''')

rep('''    val toolLog: List<String> = emptyList(),''',
'''    val toolLog: List<String> = emptyList(),
    /** 本轮正在跑 / 刚跑完的工具调用（流式展示用） */
    val liveCalls: List<LiveCall> = emptyList(),''')

# 2) 事件：工具开始 / 结束
rep('''                        is ChatEvent.ToolStarted -> _state.update {
                            it.copy(toolLog = it.toolLog + ("▶ " + event.name + " " + event.arguments.take(120)))
                        }''',
'''                        is ChatEvent.ToolStarted -> _state.update {
                            it.copy(
                                toolLog = it.toolLog + ("▶ " + event.name + " " + event.arguments.take(120)),
                                liveCalls = it.liveCalls + LiveCall(
                                    id = it.liveCalls.size + 1L,
                                    name = event.name,
                                    arguments = event.arguments,
                                    startedAt = System.currentTimeMillis(),
                                ),
                            )
                        }''')

rep('''                        is ChatEvent.ToolFinished -> _state.update {
                            it.copy(
                                toolLog = it.toolLog + (
                                    (if (event.isError) "✗ " else "✓ ") + event.name + "：" +
                                        event.output.take(200).replace('\\n', ' ')
                                    )
                            )
                        }''',
'''                        is ChatEvent.ToolFinished -> _state.update { current ->
                            // 结束的是最后一个还在跑的那条（PTC 里同一轮可能连着几次）
                            val calls = current.liveCalls.toMutableList()
                            val index = calls.indexOfLast { it.running }
                            if (index >= 0) {
                                calls[index] = calls[index].copy(
                                    output = event.output,
                                    isError = event.isError,
                                    finishedAt = System.currentTimeMillis(),
                                )
                            }
                            current.copy(
                                toolLog = current.toolLog + (
                                    (if (event.isError) "✗ " else "✓ ") + event.name + "：" +
                                        event.output.take(200).replace('\\n', ' ')
                                    ),
                                liveCalls = calls,
                            )
                        }''')

# 3) 开始新一轮时清空
rep('''                    streaming = "",
                    reasoning = "",
                    toolLog = emptyList(),
                    pendingAttachments = emptyList(),
                )
            }
            // 用户消息先落库并立刻上屏：不再等 AI 的第一个 token 才出现''',
'''                    streaming = "",
                    reasoning = "",
                    toolLog = emptyList(),
                    liveCalls = emptyList(),
                    pendingAttachments = emptyList(),
                )
            }
            // 用户消息先落库并立刻上屏：不再等 AI 的第一个 token 才出现''')

io.open(P, 'w', encoding='utf-8').write(s)
print('ChatViewModel liveCalls 完成')
