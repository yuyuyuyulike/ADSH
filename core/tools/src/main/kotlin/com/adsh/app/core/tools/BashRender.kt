package com.adsh.app.core.tools

import com.adsh.app.seam.tool.NO_OUTPUT
import com.adsh.app.seam.exec.ExecResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `bash` 结果正文的渲染（dsh 的 renderResult）：stdout → `[stderr]` 段 → 状态标记，
 * 什么都没有时是 `(no output)`。
 *
 * 三种状态标记的**互斥关系**是契约（有 stopped 就不补退出码）。这个格式还有第二个读者：
 * [RunCodeTool] 的子调用摘要从正文里读回标记补退出状态；写与读成对维护。
 */

/** 状态标记的前缀：写（[bashStatusMarkers]）与读（[bashStatusMarker]）共用，改一处就是改两端 */
internal const val EXIT_CODE_PREFIX = "[exit code: "
internal const val TIMED_OUT_PREFIX = "[timed out after "
internal const val STOPPED_PREFIX = "[stopped: "

/**
 * 这次执行该补哪些标记。三条规则（都是 dsh 的口径）：
 *
 *  1. 超时与「被人停掉」**各自独立**，可以同时出现 —— 超时转后台之后人又 kill 了它；
 *  2. **只要有其中任何一条，就不再补退出码**：那两种都不是「命令自己退出的」，再补一个
 *     `[exit code: 143]` 会让模型以为命令自己失败、去重试（dsh 里同义的是 `[killed by signal: X]`）；
 *  3. 正常退出（0）不写标记 —— dsh 也是「成功就安静」，别给模型的上下文塞噪音。
 */
internal fun bashStatusMarkers(
    timedOut: Boolean,
    timeoutMs: Long,
    stopped: String?,
    exitCode: Int,
): List<String> {
    val markers = ArrayList<String>(2)
    if (timedOut) markers += TIMED_OUT_PREFIX + timeoutMs + "ms]"
    // 外部（人）停止：把原因告诉模型，别让它当成命令失败去重试 —— dsh 的 [stopped: <reason>]
    if (stopped != null) markers += STOPPED_PREFIX + stopped + "]"
    if (markers.isEmpty() && exitCode != 0) markers += EXIT_CODE_PREFIX + exitCode + "]"
    return markers
}

/**
 * 一次执行结果的正文（[BashTool] 的两个调用点走这个）：stdout，然后（有 stderr 时）一个
 * `[stderr]` 段，最后每条标记占一行。
 *
 * 两处换行口径：stdout 与 `[stderr]` 之间要有一个换行（stdout 自己没以换行结尾就补一个，
 * 否则 `[stderr]` 会粘在最后一行输出后面）；正文与标记之间同理。全空时正文是
 * [NO_OUTPUT]（与 dsh 的 serializeMessages 用同一个常量）。
 */
internal fun renderBash(result: ExecResult, stdout: String, stderr: String, stopped: String? = null): String {
    var body = stdout
    if (stderr.isNotEmpty()) {
        if (body.isNotEmpty() && !body.endsWith("\n")) body += "\n"
        body += "[stderr]\n" + stderr
    }
    if (body.isEmpty()) body = NO_OUTPUT
    val markers = bashStatusMarkers(result.timedOut, result.timeoutMs, stopped, result.exitCode)
    if (markers.isEmpty()) return body
    if (!body.endsWith("\n")) body += "\n"
    return body + markers.joinToString("\n")
}

/**
 * 从正文里把状态标记读回来（[RunCodeTool] 的摘要用）。取自**最后**一条匹配的行，
 * 每行先 trim（标记是渲染时写在行首的，但这头不假设别人不会再加工）。
 *
 * **不认 `[stopped: …]`**：那是**用户动作**，不是命令自己的退出状态；摘要里报它会让模型以为
 * 命令自己停了（摘要只报超时与退出码这两种）。找不到返回 null。
 */
internal fun bashStatusMarker(body: String): String? = body.lineSequence()
    .map { it.trim() }
    .lastOrNull { it.startsWith(EXIT_CODE_PREFIX) || it.startsWith(TIMED_OUT_PREFIX) }

/**
 * dsh 的 `streamText`（dsh-tool-bash/lib/index.js 的 render 区段，文案逐字）：这一路
 * **丢过字节**时才补一行提示，把全文 spill 文件的路径交给模型 —— 头部不再是「静默丢弃」，
 * 而是换了个地方等它来读（`[output truncated; full output: <路径>]`，没有路径就是 `(unavailable)`）。
 *
 * **只用于正文渲染**：结构值里的 stdout/stderr.text 必须是没追加过提示的纯尾部文本
 * （dsh 的 render.ts 与 canonicalBashResult 分工如此）；把提示塞进 value 会污染程序的
 * JSON 解析（合法 JSON 输出被追加一行后 `JSON.parse` 直接失败）。
 *
 * @param truncated 这一路在内存里丢过字节（环淘汰掉了，或者尾部截断了）
 * @param spillPath 全文落盘的路径
 */
internal fun streamText(text: String, truncated: Boolean, spillPath: String?): String {
    if (!truncated) return text
    return text + "\n[output truncated; full output: " + (spillPath ?: "(unavailable)") + "]"
}

/**
 * 截断后的首行是字节切出来的半行：丢掉第一个 `\n` 之前的部分。
 *
 * dsh 的 subprocess 收集器按字节对齐窗口（"byte-exact at the cap"），尾巴可能从半行开始；
 * 本客户端的 value 与正文都按文本消费，半截首行会被读成真实内容（`seq 1 200000` 的首行
 * 从 190858 只剩 90858，外观却像连续序列）。**只在确实丢过字节时用**（[wholeLineTail]）；
 * 没有任何换行（单行大输出）、或丢掉之后只剩空串时原样返回，否则会把整段可见内容丢掉。
 */
internal fun dropPartialFirstLine(text: String): String {
    val newline = text.indexOf('\n')
    // 没有换行 = 整段就是一条被切断的长行；丢掉它会把内容变成空串（调用方看到的是
    // 「命令没有任何输出」）。这种情况保留尾巴，缺了多少由结果里的 omittedBytes 说明。
    if (newline < 0) return text
    val rest = text.substring(newline + 1)
    if (rest.isNotEmpty()) return rest
    // 尾巴只有「半截首行 + 换行」：丢掉会变成空串（内容消失了），保留原尾巴；
    // 但整段只有一个换行（首行本身就是空行）时没有内容可丢，按空处理。
    return if (newline > 0) text else ""
}

/** 丢过字节时把半截首行去掉；没丢或没有换行时原样返回（见 [dropPartialFirstLine]）。 */
internal fun wholeLineTail(text: String, truncated: Boolean): String =
    if (truncated) dropPartialFirstLine(text) else text

/**
 * dsh 的 canonicalBashResult：每一路输出是 `{text, truncated, spillPath?}`（路径只在有时出现）。
 *
 * ADSH 额外给一个可选 [omittedBytes]：只留尾部时，被丢掉的头部字节数（也就是 `text` 在完整流里的
 * 起始偏移）。行对齐会把半截首行去掉、超长单行又只能保留尾巴 —— 正文保持纯净，这两件事由这个
 * 字段说明，调用方不必从「看起来像不像连续序列」去猜。
 */
internal fun streamValue(
    text: String,
    truncated: Boolean,
    spillPath: String?,
    omittedBytes: Long? = null,
): JsonObject =
    buildJsonObject {
        put("text", text)
        put("truncated", truncated)
        if (omittedBytes != null) put("omittedBytes", omittedBytes)
        spillPath?.let { put("spillPath", it) }
    }
