package com.adsh.app.core.tools

/**
 * 工具输出层的凭据脱敏（第 108 轮，实测报告问题 4 的第一优先级）。
 *
 * 为什么放在这一层：真实场景里凭据是**明文**待在工作区的（一份 .md / .env / 脚本里），
 * agent 只能靠自觉去 `sed` 过滤；一次疏忽就把密钥写进会话记录（还会跟着下一轮请求发给模型）。
 * 这里在**工具结果离开工具的那一刻**做一次兜底：命中的片段换成「前缀 + ***redacted***」。
 *
 * 覆盖范围（两个漏斗，所有工具输出都从这里出去）：
 *  - [com.adsh.app.core.agent.AgentLoop.execute]：顶层调用的正文 / 报错；
 *  - [com.adsh.app.core.ptc.QuickJsRuntime] 的子调用结果正文（界面、子调用轨迹、落库都读它）。
 *
 * **不脱敏的**是有意为之：
 *  - PTC 的 wire（结构化返回值）—— 程序自己还要用它（例如读出 key 再发请求），那条通道不进会话记录；
 *  - 工具**参数**与程序正文 —— 模型自己写的东西，改了反而让它看不懂自己在干什么。
 *
 * 判据是「已知的凭据前缀 + 足够长的随机段」，宁可漏也不能误伤普通文本。
 */
object SecretRedaction {
    private const val MASK = "***redacted***"

    /** 便宜的前置检查：文本里连这些前缀都没有就直接返回（bash 输出可能有几 MB） */
    private val HINTS = listOf(
        "ghp_", "gho_", "ghu_", "ghs_", "ghr_", "github_pat_",
        "sk-", "s2k-", "AKIA", "xox", "AIza",
    )

    private val PATTERNS = listOf(
        // GitHub: PAT（ghp_/gho_/ghu_/ghs_/ghr_）与细粒度 PAT
        Regex("(?<![A-Za-z0-9])(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"),
        // OpenAI / DeepSeek / 一切 sk- 形态（含 sk-ws-H.EXH... 这种带点号的）
        Regex("(?<![A-Za-z0-9])sk-[A-Za-z0-9._\\-]{16,}"),
        // Semantic Scholar
        Regex("(?<![A-Za-z0-9])s2k-[A-Za-z0-9]{16,}"),
        // AWS access key id
        Regex("(?<![A-Za-z0-9])AKIA[0-9A-Z]{16}(?![A-Za-z0-9])"),
        // Slack token
        Regex("(?<![A-Za-z0-9])xox[abprs]-[A-Za-z0-9\\-]{10,}"),
        // Google API key
        Regex("(?<![A-Za-z0-9])AIza[0-9A-Za-z_\\-]{35}"),
    )

    /** 脱敏一份工具输出（null / 空串原样返回）；没有命中任何前缀时是**零拷贝**返回 */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var hit = false
        for (hint in HINTS) {
            if (text.contains(hint)) {
                hit = true
                break
            }
        }
        if (!hit) return text
        var out = text
        for (pattern in PATTERNS) out = pattern.replace(out) { mask(it.value) }
        return out
    }

    /**
     * 只留「看得出是哪一类凭据」的前缀，后面一律打码。
     *
     * 例：ghp_1a2b...（40 字符）→ ghp_***redacted***；AKIAIOSFODNN7EXAMPLE → AKIA***redacted***。
     * 前缀按第一个分隔符取（最多 12 个字符），没有分隔符就取前 4 个字符。
     */
    private fun mask(token: String): String {
        val sep = token.indexOfFirst { it == '_' || it == '-' }
        val head = if (sep in 0..11) token.substring(0, sep + 1) else token.take(4)
        return head + MASK
    }
}
