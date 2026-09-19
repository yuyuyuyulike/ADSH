import io, sys
P = '/mnt/d/WSN2005/Android1/App/ADSH/app/src/main/java/com/adsh/app/ui/TurnRail.kt'
s = io.open(P, encoding='utf-8').read()

def rep(old, new, count=1):
    global s
    if old not in s:
        print('!! 未匹配: ' + old[:70].replace('\n', ' | ')); sys.exit(1)
    s = s.replace(old, new, count)

# formatDuration 与 StatsDialog 里的重名 → 改成 dsh 的名字 formatRunDuration
rep('internal fun formatDuration(millis: Long): String {', 'internal fun formatRunDuration(millis: Long): String {')
s = s.replace('formatDuration(millis)', 'formatRunDuration(millis)')
s = s.replace('formatDuration(runMillis)', 'formatRunDuration(runMillis)')
s = s.replace('formatDuration(call.durationMs)', 'formatRunDuration(call.durationMs)')

# ToolCall.function 的 name/arguments 是可空的
rep('title = toolTitle(call.function.name),', 'title = toolTitle(call.function.name.orEmpty()),')
rep('private fun toolSummary(call: ToolCall): String = toolSummary(call.function.name, call.function.arguments.orEmpty())',
    'private fun toolSummary(call: ToolCall): String =\n    toolSummary(call.function.name.orEmpty(), call.function.arguments.orEmpty())')
rep('private fun programOf(call: ToolCall): String = programOf(call.function.name, call.function.arguments.orEmpty())',
    'private fun programOf(call: ToolCall): String =\n    programOf(call.function.name.orEmpty(), call.function.arguments.orEmpty())')

io.open(P, 'w', encoding='utf-8').write(s)
print('TurnRail 修正 2 完成')
