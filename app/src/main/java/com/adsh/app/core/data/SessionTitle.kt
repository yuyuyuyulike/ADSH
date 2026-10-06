package com.adsh.app.core.data

/**
 * 会话标题生成（照 dsh 的 `@deepseek-ai/dsh-session-title` + `dsh-session-title-llm` 口径）。
 *
 * dsh 的做法分两步（用户第 177 轮要求 ADSH 也做）：
 *  ① **先落兜底标题**（立刻可见）：首条用户消息取前 [TITLE_FALLBACK_MAX_WORDS] 个空格分隔 token、
 *     再截到 [TITLE_FALLBACK_MAX_BYTES] 个 UTF-8 字节（中文没有空格 ⇒ 实际就是切 40 字节）；
 *  ② **异步跑一次小模型**（不阻塞主回答）：拿 [TITLE_SYSTEM_PROMPT] 当 system、把首条用户消息包成
 *     JSON 数组当 user，`max_tokens = 64`，走本会话同一个 provider / model；结果经 [normalizeSessionTitle]
 *     清洗后覆盖兜底值。
 *
 * 触发面（dsh 的 `onUserMessage` + first-prompt provider）：只在**首条用户消息**、且当前标题仍是我们
 * 写下的兜底值时排一次；用户手动改过名就永久钉住（dsh 用 `source.kind === 'user'` 判定，ADSH 用
 * 「标题是否仍等于那条兜底值」近似，见 `ConversationRepository.renameAutoTitle`）。
 */

/** dsh 的 maxTitleBytes：清洗后的标题上限（按 UTF-8 字节，且不切码点） */
internal const val TITLE_MAX_BYTES = 80
/** dsh 的 fallbackMaxWords：兜底标题取首条用户消息的前几个空格分隔 token */
internal const val TITLE_FALLBACK_MAX_WORDS = 5
/** dsh 的 fallbackMaxBytes：兜底标题的字节上限 */
internal const val TITLE_FALLBACK_MAX_BYTES = 40
/** dsh 的 maxOutputTokens：标题这次小调用的输出上限 */
internal const val TITLE_MAX_OUTPUT_TOKENS = 64

/**
 * dsh 的 `systemPrompt` 逐字原文（`dsh-session-title-llm/lib/index.js:148-155`）。
 *
 * 后三个数是 [TITLE_FALLBACK_MAX_WORDS] 与「10 个 CJK 字」的配置（targetWords=5 / targetCjkCharacters=10）；
 * 语言不靠检测代码，只靠这一句 `Use the language of the messages.`。
 */
internal val TITLE_SYSTEM_PROMPT = """Create a concise title for an AI coding-assistant session from the supplied human messages.
Return only the title on one line, **in plain text of natural language**, with no quotes, prefix, explanation, Markdown, XML, or terminal control codes. No code is allowed.
Use the language of the messages.
Aim for about 5 words in non-CJK languages or 10 CJK characters.""".trimIndent()

/**
 * dsh 的 `frameMessages`（`dsh-session-title-llm/lib/index.js:157-159`）：
 * first-prompt 提供方只塞**首条**用户消息（`selectMessages = (messages) => [messages[0]]`）。
 */
internal fun titleUserPrompt(firstUserText: String): String =
    "Generate the session title from this JSON array of human messages:\n" +
        "[{\"seq\":1,\"text\":" + jsonStringLiteral(firstUserText) + "}]"

/** dsh 的 `fallbackSessionTitle(text, 5, 40)`：前 N 个空格分隔 token，再按字节截断。 */
internal fun fallbackSessionTitle(
    text: String,
    maxWords: Int = TITLE_FALLBACK_MAX_WORDS,
    maxBytes: Int = TITLE_FALLBACK_MAX_BYTES,
): String {
    val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }.take(maxWords)
    return truncateTitleUtf8(words.joinToString(" "), maxBytes)
}

/**
 * dsh 的 `normalizeSessionTitle(text, 80)`（`dsh-session-title/lib/types/normalize.js:56`）：
 * 去掉 ANSI/OSC/CSI 转义、C0/C1 控制符、零宽与双向控制符，空白折叠成一个空格，trim，
 * 最后按 UTF-8 字节截断且**不切码点**。
 *
 * **不去引号、不去标点** —— dsh 只靠 system prompt 约束模型，这里保持一致。
 */
internal fun normalizeSessionTitle(raw: String, maxBytes: Int = TITLE_MAX_BYTES): String {
    val stripped = raw.replace(ANSI_CSI, "").replace(ANSI_OSC, "").replace(ANSI_SHORT, "")
    val sb = StringBuilder(stripped.length)
    for (ch in stripped) {
        if (isControlOrZeroWidth(ch)) continue
        sb.append(ch)
    }
    return truncateTitleUtf8(sb.toString().replace(WHITESPACE, " ").trim(), maxBytes)
}

/** 按 UTF-8 字节截断，且不把一个码点切成两半（dsh 的 `truncateTitleUtf8`）。 */
internal fun truncateTitleUtf8(text: String, maxBytes: Int): String {
    if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
    val sb = StringBuilder(text.length)
    var used = 0
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        // 代理对（emoji 等 4 字节码点）当一个整体：半个代理单独编码成 3 字节替换符，会切坏字符
        val len = if (Character.isHighSurrogate(ch) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) 2 else 1
        val piece = text.substring(i, i + len)
        val bytes = piece.toByteArray(Charsets.UTF_8).size
        if (used + bytes > maxBytes) break
        sb.append(piece)
        used += bytes
        i += len
    }
    return sb.toString().trimEnd()
}

/**
 * 「还没有标题」的占位，也是**自动标题的判据**：`openOrCreateBlank` 建空会话时写进去的是它，
 * 生成标题时拿它当「这条会话还没被起过名」的判据（`renameAutoTitle` 的 expected）——
 * **两处必须是同一个字符串**（写进去一个、比对另一个，自动标题就永远写不进去），所以只留一个常量。
 *
 * 注意 `com.adsh.app.ui.Drawer` 里那句「新会话」是**按钮文案**，不是这个占位值：
 * 文案会跟着界面改，占位值改了会让判据失效 —— 有意不耦合。
 */
internal const val NEW_SESSION_TITLE = "新会话"

private val WHITESPACE = Regex("\\s+")
/** ANSI/CSI 转义：ESC [ 参数 字母 */
private val ANSI_CSI = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
/** OSC 转义：ESC ] ... BEL */
private val ANSI_OSC = Regex("\\u001B\\][^\\u0007]*\\u0007")
/** 其余单字符转义：ESC + 一个字符 */
private val ANSI_SHORT = Regex("\\u001B.")

private fun isControlOrZeroWidth(ch: Char): Boolean =
    (ch.code < 0x20 && ch != '\n' && ch != '\t') ||
        ch.code == 0x7F ||
        (ch.code in 0x80..0x9F) ||
        ch == '\uFEFF' ||
        (ch.code in 0x200B..0x200F) ||
        (ch.code in 0x202A..0x202E) ||
        (ch.code in 0x2066..0x2069)

/** JSON 字符串字面量（与 dsh 那边的 JSON.stringify 等价，只处理标题这一处用得到的转义）。 */
private fun jsonStringLiteral(text: String): String {
    val sb = StringBuilder(text.length + 2)
    sb.append('"')
    for (ch in text) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}
