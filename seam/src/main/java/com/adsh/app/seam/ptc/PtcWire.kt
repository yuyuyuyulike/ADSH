package com.adsh.app.seam.ptc

/**
 * 子调用信封里表示「被用户中断」的 code（dsh 的 tool-call 块 `error.code === "interrupted"`，
 * 界面据此把这一行画成 stopped 而不是 error）。
 */
const val INTERRUPTED_CODE = "interrupted"

/**
 * 失败时的 wire 形态：带上 toolName / 参数原文 / retryable / code，
 * 这样程序没 catch 时，最外层也能说清楚「哪个工具、什么参数、原始错误」（dsh 的 ToolCallError），
 * 而程序 catch 到之后还能按 code 分支（dsh 的 WebError.code 就是这么用的）。
 *
 * 三个调用方共用一份：工具失败（[com.adsh.app.core.tools.PtcToolRunner]）、用户中断（同上）、控制通道断掉
 * （[com.adsh.app.platform.ptc.PtcWorkerService] 要拿它把还等着的引擎线程放行）。
 */
fun toolErrorWire(
    name: String,
    rawArgs: String,
    message: String,
    retryable: Boolean = false,
    code: String? = null,
): String = buildString {
    append("{\"__error\":true,\"toolName\":\"").append(jsStringEscape(name))
    append("\",\"args\":\"").append(jsStringEscape(rawArgs.take(600)))
    append("\",\"message\":\"").append(jsStringEscape(message))
    append("\",\"retryable\":").append(retryable)
    append(",\"code\":")
    if (code == null) append("null") else append('"').append(jsStringEscape(code)).append('"')
    append('}')
}

/** JS 字符串转义（toolErrorWire 与给程序注入工具名列表共用一份，别再各写一遍） */
fun jsStringEscape(s: String): String = buildString(s.length + 16) {
    s.forEach { c ->
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(" ") else append(c)
        }
    }
}
