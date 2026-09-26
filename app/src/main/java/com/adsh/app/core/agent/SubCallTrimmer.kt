package com.adsh.app.core.agent

import com.adsh.app.core.ptc.SubCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 子调用轨迹的落库裁剪。
 *
 * 一个 run_code 程序可以 await 上千次工具，全塞进一行会让这一行超过 SQLite CursorWindow 的
 * 2MB —— 之后每次进这条会话都在读库时闪退（SQLiteBlobTooBigException，实测 dropbox 里的崩溃）。
 * dsh 的做法是「每条事实一条小日志」（会话 JSONL 逐条追加、工具输出按 maxBytes 截断）；
 * ADSH 把一步的所有子调用放在一行里，所以至少要保证这一行不超预算。
 *
 * **参数必须按字段值截断，不能按字符数砍**（第 72 轮用户实测的那条）：
 * 旧写法 `args.take(2048)` 会把 JSON 从中间砍断，界面再解析就失败，于是同一个工具
 * 「执行时显示 `/path/报告.md`、执行完成后显示 `{"file_path":"/path/报告.md","content":"# …`」——
 * 用户看到的就是「执行时和执行完成后展示的不同、有明显变化」。现在截断的是每个字符串值，
 * 存下来的仍然是**合法 JSON**。
 */
internal object SubCallTrimmer {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 参数 JSON 里每个字符串值的上限（摘要只取 path/command 这类短字段，1024 足够） */
    const val ARG_VALUE_LIMIT = 1024

    /** 参数 JSON 的总长上限（按字段截断后仍然超标时的兜底） */
    const val ARGS_LIMIT = 4_096

    /** 单条子调用输出正文的上限 */
    const val RESULT_LIMIT = 8_192

    /** 截断标记：值被砍过就留一句，界面与复查的人都能看出来 */
    const val TRUNCATION_MARK = "…（已截断）"

    /** 只留最近 [keep] 条，并把参数 / 输出压进预算 */
    fun cap(all: List<SubCall>, keep: Int): List<SubCall> =
        all.takeLast(keep).map {
            it.copy(args = capArgs(it.args), result = it.result.take(RESULT_LIMIT))
        }

    /**
     * 把参数 JSON 里过长的字符串值截断，**保持结果仍是合法 JSON**；超过总预算时再硬截
     * （界面侧的容错解析会把硬截出来的尾巴补全）。
     */
    fun capArgs(raw: String): String {
        if (raw.length <= ARGS_LIMIT) return raw
        val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return raw
        val capped = capValue(parsed).toString()
        return if (capped.length <= ARGS_LIMIT) capped else capped.take(ARGS_LIMIT)
    }

    private fun capValue(value: JsonElement): JsonElement = when (value) {
        is JsonPrimitive ->
            if (value.isString && value.content.length > ARG_VALUE_LIMIT) {
                JsonPrimitive(value.content.take(ARG_VALUE_LIMIT) + TRUNCATION_MARK)
            } else {
                value
            }
        is JsonArray -> JsonArray(value.map { capValue(it) })
        is JsonObject -> JsonObject(value.mapValues { capValue(it.value) })
        else -> value
    }
}
