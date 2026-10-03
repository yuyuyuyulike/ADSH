package com.adsh.app.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 工具参数 JSON 的**容错解析**。
 *
 * 为什么需要它（第 72 轮用户实测）：库里存的子调用参数是**被截断过的** ——
 * `AgentLoop.cappedSubCalls` 按字符数砍到 2048（一条消息里的所有子调用要一起塞进 SQLite 的
 * 一行，超了 CursorWindow 的 2MB 就再也读不出来），`MessageDao.listCapped` 兜底读还会再砍。
 * 砍在字符串中间时 JSON 就坏了，`Json.parseToJsonElement` 直接抛异常 —— 界面于是退化成
 * 「显示参数原文的第一行」，也就是用户看到的那一幕：
 *
 *   执行中：`写入 · /data/.../报告.md`（参数完整，摘要取 path）
 *   执行完：`写入 · {"file_path":"/data/.../报告.md","content":"# 标题…`（JSON 坏了，摘要取原文）
 *
 * 同一个调用在两帧之间换了完全不同的长相，看起来就是「执行时和执行完成后展示的不同」。
 *
 * 两道防线：
 *  1. 写库时按**字段值**截断（AgentLoop.capArgsJson），存下来的仍然是合法 JSON —— 治本；
 *  2. 这里按 JSON 词法把已经坏掉的老数据**补全**再解析 —— 让历史会话也显示正常。
 */
internal object ToolArgs {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 严格解析 + 补全解析 + 字段扫描，**绝不返回「原始 JSON 文本」** */
    fun parse(raw: String): JsonObject? {
        if (raw.isBlank()) return null
        strict(raw)?.let { return it }
        repaired(raw)?.let { text -> strict(text)?.let { return it } }
        return scan(raw)
    }

    private fun strict(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    /**
     * 把被截断的 JSON 补成合法的：按词法走一遍，记住「在不在字符串里」和没闭合的括号栈，
     * 到结尾处补上引号与括号；再丢掉结尾那些没写完的片段（`…,` / `…:`）。
     */
    private fun repaired(raw: String): String? {
        val stack = StringBuilder()
        var inString = false
        var escaped = false
        for (ch in raw) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> stack.append(ch)
                '}' -> if (stack.isNotEmpty() && stack.last() == '{') stack.deleteAt(stack.length - 1)
                ']' -> if (stack.isNotEmpty() && stack.last() == '[') stack.deleteAt(stack.length - 1)
            }
        }
        val out = StringBuilder(raw)
        if (escaped) out.append('\\')          // 结尾是半个转义：补回去，免得把后面的引号吃掉
        if (inString) out.append('"')
        // 丢掉结尾没写完的成员：`{"a":1,` / `{"a":` / 空白
        var text = out.toString()
        while (true) {
            val trimmed = text.trimEnd()
            if (trimmed.endsWith(",") || trimmed.endsWith(":")) {
                text = trimmed.dropLast(1)
                continue
            }
            break
        }
        val result = StringBuilder(text)
        for (i in stack.indices.reversed()) {
            result.append(if (stack[i] == '{') '}' else ']')
        }
        val fixed = result.toString()
        return if (fixed == raw) null else fixed
    }

    /** 最后兜底：坏得没法补时，按 `"key":"value"` 扫出认识的那几个字段（摘要只需要它们） */
    private fun scan(raw: String): JsonObject? {
        val found = LinkedHashMap<String, JsonPrimitive>()
        for ((key, pattern) in SCAN_PATTERNS) {
            val match = pattern.find(raw) ?: continue
            val value = match.groupValues[1]
            if (value.isNotEmpty()) found[key] = JsonPrimitive(unescape(value))
        }
        if (found.isEmpty()) return null
        return JsonObject(found)
    }

    /** 只需要认出这些键：摘要在 [SUMMARY_KEYS] 里挑，展开体对文件类工具根本不铺输入 */
    private val SCAN_PATTERNS: List<Pair<String, Regex>> = listOf(
        "path", "file_path", "command", "pattern", "query", "description", "url", "content",
    ).map { key ->
        key to Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    }

    private fun unescape(value: String): String = runCatching {
        (json.parseToJsonElement("\"$value\"") as JsonPrimitive).content
    }.getOrElse { value }

    /** 摘要里用得上的取值：先在给定 keys 里挑，挑不到就取任意一个字符串值（dsh 的 deriveSummary） */
    fun pick(args: JsonObject?, keys: List<String>): String? {
        if (args == null) return null
        keys.forEach { key ->
            val value = (args[key] as? JsonPrimitive)?.contentOrNull
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    /** 任意一个非空字符串值（按 JSON 的键顺序） */
    fun firstString(args: JsonObject?): String? {
        args ?: return null
        args.values.forEach { value ->
            val text = (value as? JsonPrimitive)?.contentOrNull
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }
}
