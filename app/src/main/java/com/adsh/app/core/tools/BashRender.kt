package com.adsh.app.core.tools

import com.adsh.app.core.agent.NO_OUTPUT
import com.adsh.app.runtime.termux.ExecResult

/**
 * `bash` 结果正文的渲染（dsh 的 renderResult）—— 原先埋在 [BashTool] 里的一个私有函数、零用例，
 * 而它是模型**真正读到的正文**：stdout → `[stderr]` 段 → 状态标记，什么都没有时是 `(no output)`。
 *
 * 为什么值得单独钉：三种状态标记的**互斥关系**原先只有一行注释保证（有 stopped 就不补退出码），
 * 而这个格式还有**第二个读者** —— [RunCodeTool] 的子调用摘要从正文里把标记读回来补退出状态
 * （第 105 轮：没有它，`curl` 打状态码这类「程序跑成功、命令本身失败」的探测在回执里看不出成败）。
 * 写与读是同一份格式的两端，所以放在同一个文件里成对维护。
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
 * 命令自己停了（第 105 轮补的就是超时与退出码这两种）。找不到返回 null。
 */
internal fun bashStatusMarker(body: String): String? = body.lineSequence()
    .map { it.trim() }
    .lastOrNull { it.startsWith(EXIT_CODE_PREFIX) || it.startsWith(TIMED_OUT_PREFIX) }
