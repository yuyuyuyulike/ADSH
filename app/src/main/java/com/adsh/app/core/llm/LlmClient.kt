package com.adsh.app.core.llm

import android.util.Log
import com.adsh.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容的流式对话客户端（DeepSeek 官方与自建网关走同一协议）。
 *
 * M2 阶段只做纯文本流式；tools（PTC 的 run_code）在 M3 接入。
 */
class LlmClient(
    private val configProvider: () -> ProviderConfig,
    // encodeDefaults 必须开：否则带默认值的字段会被省略，
    // tool_calls[].type 与 stream=true 都会消失（服务端直接 400 / 退化成非流式）
    //
    // explicitNulls = false：可空字段为 null 时**整个键都不写**，而不是写成 "key": null。
    // 服务端对 null 的容忍度不一样（实测 DeepSeek 会直接拒绝 "id": null 这种显式空值：
    // invalid type: null, expected a string），少发一个键永远比发一个 null 安全。
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    },
) {

    companion object {
        private const val TAG = "ADSH_LLM"
    }

    /**
     * GET /models —— OpenAI 兼容的模型清单。
     *
     * dsh 用内置目录（deepseek-official 的 DEFAULT_MODELS），但账号可见的模型由服务端决定；
     * 这里只在设置页手动触发，把结果存进 SettingsStore，模型菜单优先用它。
     */
    fun listModels(): List<String> {
        val config = configProvider()
        if (config.apiKey.isBlank()) throw IllegalStateException("未配置 API Key")
        val url = URL(config.baseUrl.trimEnd('/') + "/models")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer " + config.apiKey)
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP " + code + "：" + body.take(300))
            val root = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
                ?: throw IllegalStateException("返回不是 JSON 对象")
            val data = root["data"] as? kotlinx.serialization.json.JsonArray
                ?: throw IllegalStateException("返回里没有 data 数组")
            return data.mapNotNull { element ->
                ((element as? kotlinx.serialization.json.JsonObject)?.get("id")
                    as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            }.filter { it.isNotBlank() }.sorted()
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val config = configProvider()
        if (config.mock) {
            emitMock(request)
            return@flow
        }
        if (config.apiKey.isBlank()) {
            emit(ChatEvent.Failed("未配置 API Key（设置 → 模型）", retryable = false))
            return@flow
        }

        var connection: HttpURLConnection? = null
        try {
            val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Authorization", "Bearer " + config.apiKey)
            }
            val payload = json.encodeToString(ChatRequest.serializer(), request)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "POST " + url + " model=" + request.model +
                    " msgs=" + request.messages.size + " tools=" + (request.tools?.size ?: 0) +
                    " roles=" + request.messages.joinToString(",") { it.role })
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload) }

            val code = connection.responseCode
            if (BuildConfig.DEBUG) Log.d(TAG, "HTTP " + code + " type=" + connection.contentType)
            if (code !in 200..299) {
                val body = (connection.errorStream ?: connection.inputStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                emit(ChatEvent.Failed("HTTP " + code + "：" + body.take(500), retryable = code >= 500 || code == 429))
                return@flow
            }

            val parser = SseParser()
            val reader: BufferedReader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
            var finished = false
            var emitted = 0
            var seen = 0
            var firstLine = ""
            reader.use { r ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = r.readLine() ?: break
                    if (seen == 0) firstLine = line
                    if (seen < 6) {
                        seen++
                        if (BuildConfig.DEBUG) Log.d(TAG, "SSE< " + line.take(400))
                    }
                    // 兜底：网关忽略 stream=true 直接回一个 JSON 对象时，也按单块处理
                    val data = parser.feed(line)
                        ?: line.trim().takeIf { it.startsWith("{") }
                        ?: continue
                    if (data == "[DONE]") {
                        finished = true
                        break
                    }
                    decodeChunk(data)?.forEach { emit(it); emitted++ }
                }
                if (!finished) {
                    parser.end()?.let { tail -> decodeChunk(tail)?.forEach { emit(it); emitted++ } }
                }
            }
            if (emitted == 0) {
                // 静默空流是最难查的故障：把原始首行带出来，别让用户看到「什么都没发生」
                emit(
                    ChatEvent.Failed(
                        "服务端返回空响应（HTTP " + code + "）。首行：" + firstLine.take(300),
                        retryable = true,
                    )
                )
                return@flow
            }
            if (!finished) emit(ChatEvent.Finished("stream_closed"))
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            emit(ChatEvent.Failed(t::class.java.simpleName + "：" + (t.message ?: ""), retryable = true))
        } finally {
            runCatching { connection?.disconnect() }
        }
    }.flowOn(Dispatchers.IO)

    /** 解析一个 chunk，返回它携带的事件（可能多个） */
    internal fun decodeChunk(data: String): List<ChatEvent>? {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        val events = ArrayList<ChatEvent>(2)

        (root["usage"] as? kotlinx.serialization.json.JsonObject)?.let { usage ->
            // dsh 的 mapUsage：prompt_tokens 里**包含**命中缓存的量，
            // harness 的口径是互斥计数，所以缓存的量要从输入里减掉；
            // prompt_tokens_details.cached_tokens 优先（OpenAI 兼容网关），其次 prompt_cache_hit_tokens。
            // 取值一律走 [asPrimitive]：这里是流式解析，形状意外时 jsonPrimitive 会抛异常，
            // 抛出去整条流就变成 Failed（对非 DeepSeek 网关来说这是「一用就中断」的一种来源）。
            val prompt = usage["prompt_tokens"].asPrimitive()?.intOrNull ?: 0
            val details = usage["prompt_tokens_details"] as? JsonObject
            val cacheRead = details?.get("cached_tokens").asPrimitive()?.intOrNull
                ?: usage["prompt_cache_hit_tokens"].asPrimitive()?.intOrNull
            val completion = usage["completion_tokens"].asPrimitive()?.intOrNull ?: 0
            val completionDetails = usage["completion_tokens_details"] as? JsonObject
            events += ChatEvent.Usage(
                promptTokens = (prompt - (cacheRead ?: 0)).coerceAtLeast(0),
                completionTokens = completion,
                cacheHitTokens = cacheRead ?: 0,
                cacheMissTokens = usage["prompt_cache_miss_tokens"].asPrimitive()?.intOrNull ?: 0,
                reasoningTokens = completionDetails?.get("reasoning_tokens").asPrimitive()?.intOrNull ?: 0,
            )
        }

        val choice = (root["choices"] as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull() as? kotlinx.serialization.json.JsonObject ?: return events.ifEmpty { null }
        // 流式用 delta，非流式（或网关兜底）用 message
        val delta = (choice["delta"] as? kotlinx.serialization.json.JsonObject)
            ?: (choice["message"] as? kotlinx.serialization.json.JsonObject)
        if (delta != null) {
            delta["content"].textOf()?.takeIf { it.isNotEmpty() }?.let {
                events += ChatEvent.Delta(it)
            }
            val reasoning = delta["reasoning_content"].textOf()
                ?: delta["reasoning"].textOf()
            if (!reasoning.isNullOrEmpty()) events += ChatEvent.Reasoning(reasoning)

            (delta["tool_calls"] as? JsonArray)?.forEachIndexed { index, element ->
                val call = element as? JsonObject ?: return@forEachIndexed
                // 标准的 OpenAI 形状是 {id, type, function:{name, arguments}}。
                // 少数兼容网关把它拍平（name/arguments 直接挂在条目上），这里一并接住 ——
                // 认不出来就等于「模型说要调工具，我们当没看见」，那一轮会静默结束。
                val fn = (call["function"] as? JsonObject) ?: call
                val id = call["id"].textOf()
                    ?: call["call_id"].textOf()
                    ?: call["tool_call_id"].textOf()
                events += ChatEvent.ToolCallDelta(
                    index = call["index"].asPrimitive()?.intOrNull ?: index,
                    id = id,
                    name = fn["name"].textOf(),
                    // arguments 正常是字符串；有网关直接给一个对象，序列化回字符串照样能拼
                    argumentsChunk = fn["arguments"].let { raw ->
                        when (raw) {
                            null -> null
                            is JsonPrimitive -> raw.contentOrNull
                            else -> raw.toString()
                        }
                    },
                )
            }
        }
        choice["finish_reason"].textOf()?.takeIf { it.isNotEmpty() }?.let {
            events += ChatEvent.Finished(it)
        }
        return events.ifEmpty { null }
    }

    /** 无密钥时的回环演示流：用于验证 UI、取消、持久化链路 */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<ChatEvent>.emitMock(request: ChatRequest) {
        val lastUser = request.messages.lastOrNull { it.role == "user" }?.textContent ?: ""

        // 回环演示也能覆盖 PTC 往返：先发一次 run_code 调用，拿到 tool 结果后再给最终答复
        val toolsEnabled = !request.tools.isNullOrEmpty()
        val hasToolResult = request.messages.any { it.role == "tool" }
        if (toolsEnabled && !hasToolResult && lastUser.contains("问问我") || lastUser.contains("askme")) {
            val optionA = buildJsonObject { put("label", "选项 A"); put("description", "第一条") }
            val optionB = buildJsonObject { put("label", "选项 B"); put("description", "第二条") }
            val question = buildJsonObject {
                put("id", "q1")
                put("header", "选择")
                put("question", "这是回环演示的提问卡，请选一个（验证 ask_user_question 的 UI 往返）")
                put("options", kotlinx.serialization.json.JsonArray(listOf(optionA, optionB)))
            }
            val args = buildJsonObject { put("questions", kotlinx.serialization.json.JsonArray(listOf(question))) }
            emit(ChatEvent.ToolCallDelta(0, "call_mock_ask", "ask_user_question", args.toString()))
            emit(ChatEvent.Finished("tool_calls"))
            return
        }
        if (toolsEnabled && !hasToolResult) {
            val program = listOf(
                "const r = await tools.bash({ command: \"echo mock-ptc-ok && pwd\" });",
                "console.log(\"bash -> \" + String(r).trim());",
                "const files = await tools.glob({ pattern: \"**/*\" });",
                "return { firstLine: String(r).split(String.fromCharCode(10))[0], files: String(files) };",
            ).joinToString("\n")
            // run_code 的参数是 code + description（dsh 的 RUN_CODE_FLAVORS），
            // 回环演示以前写的是 program —— 那个键根本不在 schema 里，演示必然失败
            val argsJson = buildJsonObject {
                put("code", program)
                put("description", "回环演示：bash + glob")
            }.toString()
            emit(ChatEvent.ToolCallDelta(0, "call_mock_1", "run_code", argsJson))
            emit(ChatEvent.Finished("tool_calls"))
            return
        }
        val reply = "（回环演示）收到你的消息：" + lastUser.take(40) + "。\n" +
            "这是本地生成的流式响应，用来验证流式渲染、取消与持久化；配置 API Key 后会自动切到真实模型。"
        emit(ChatEvent.Delta(""))
        reply.chunked(6).forEach {
            currentCoroutineContext().ensureActive()
            emit(ChatEvent.Delta(it))
            kotlinx.coroutines.delay(45)
        }
        emit(ChatEvent.Usage(promptTokens = lastUser.length / 2, completionTokens = reply.length / 2))
        emit(ChatEvent.Finished("stop"))
    }
}

/**
 * 安全取原始值：形状不是 JsonPrimitive（对象 / 数组）时返回 null。
 *
 * 流式解析里**不能**用 `jsonPrimitive`：它遇到意料之外的形状会直接抛 IllegalArgumentException，
 * 而这里抛出去等于把整条流打成 Failed —— 对形状更自由的兼容网关（非 DeepSeek 的那些）来说，
 * 那就是「一用就中断」。
 */
private fun JsonElement?.asPrimitive(): JsonPrimitive? = this as? JsonPrimitive

/**
 * 一段文本字段：字符串直接用；少数网关把 content 给成 content parts 数组，就把其中的 text
 * 片段拼起来 —— 不认的话模型说的话会被丢掉，那一轮会以「服务端返回空响应」结束。
 */
private fun JsonElement?.textOf(): String? = when (this) {
    null -> null
    is JsonPrimitive -> contentOrNull
    is JsonArray -> mapNotNull { part -> (part as? JsonObject)?.get("text").asPrimitive()?.contentOrNull }
        .joinToString("")
        .ifEmpty { null }
    else -> null
}
