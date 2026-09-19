import io, sys
P = '/mnt/d/WSN2005/Android1/App/ADSH/app/src/main/java/com/adsh/app/core/llm/LlmClient.kt'
s = io.open(P, encoding='utf-8').read()

def rep(old, new):
    global s
    if old not in s:
        print('!! 未匹配: ' + old[:70].replace('\n', ' | ')); sys.exit(1)
    s = s.replace(old, new, 1)

# 一律用 as? JsonObject：开了 stream_options 之后每个 chunk 都会带 "usage": null，
# 直接 .jsonObject 会抛「JsonNull is not a JsonObject」，整轮直接失败
rep('''        root["usage"]?.jsonObject?.let { usage ->''',
    '''        (root["usage"] as? kotlinx.serialization.json.JsonObject)?.let { usage ->''')
rep('''        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return events.ifEmpty { null }
        // 流式用 delta，非流式（或网关兜底）用 message
        val delta = choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject''',
    '''        val choice = (root["choices"] as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull() as? kotlinx.serialization.json.JsonObject ?: return events.ifEmpty { null }
        // 流式用 delta，非流式（或网关兜底）用 message
        val delta = (choice["delta"] as? kotlinx.serialization.json.JsonObject)
            ?: (choice["message"] as? kotlinx.serialization.json.JsonObject)''')
rep('''            delta["tool_calls"]?.jsonArray?.forEachIndexed { index, element ->
                val call = element.jsonObject
                val fn = call["function"]?.jsonObject''',
    '''            (delta["tool_calls"] as? kotlinx.serialization.json.JsonArray)?.forEachIndexed { index, element ->
                val call = element as? kotlinx.serialization.json.JsonObject ?: return@forEachIndexed
                val fn = call["function"] as? kotlinx.serialization.json.JsonObject''')

io.open(P, 'w', encoding='utf-8').write(s)
print('LlmClient 空值安全已修')
