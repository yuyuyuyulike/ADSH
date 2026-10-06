package com.adsh.app.core.data

import com.adsh.app.core.llm.ThinkingOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话标题的三条纯判定（照 dsh 的 session-title / normalize 口径，见 SessionTitle.kt 的引用）：
 * 兜底标题怎么切、模型返回的标题怎么洗、以及字节级截断不切码点。
 */
class SessionTitleTest {

    @Test
    fun `兜底标题取前五个空格分隔的词`() {
        assertEquals("one two three four five", fallbackSessionTitle("one two three four five six seven"))
        // 中文没有空格 ⇒ 整段算一个 token（dsh 的实测行为）
        assertEquals("帮我配置一下镜像", fallbackSessionTitle("帮我配置一下镜像"))
    }

    @Test
    fun `兜底标题按 40 个 UTF-8 字节截断`() {
        // 20 个汉字 = 60 字节 ⇒ 只能留下 13 个（39 字节），第 14 个会超到 42
        val long = "一二三四五六七八九十一二三四五六七八九十"
        val title = fallbackSessionTitle(long)
        assertTrue("字节数应当 ≤40：" + title.toByteArray(Charsets.UTF_8).size, title.toByteArray(Charsets.UTF_8).size <= 40)
        assertEquals("一二三四五六七八九十一二三四五六七八九十".take(13), title)
    }

    @Test
    fun `清洗去掉 ANSI 转义 零宽字符 并压缩空白`() {
        assertEquals("红色 标题", normalizeSessionTitle("\u001B[31m红色\u001B[0m\n\t标题\u200B"))
        // 不去引号、不去标点：dsh 只靠 system prompt 约束模型
        assertEquals("\"带引号的标题\"", normalizeSessionTitle("\"带引号的标题\""))
    }

    @Test
    fun `按字节截断不切码点`() {
        assertEquals("aaaa", truncateTitleUtf8("aaaa\uD83D\uDE00", 4))
        assertEquals("a\uD83D\uDE00", truncateTitleUtf8("a\uD83D\uDE00", 5))
        // 80 字节上限：21 个汉字（63 字节）原样留下
        val twenty = "一二三四五六七八九十一二三四五六七八九十"
        assertEquals(twenty, normalizeSessionTitle(twenty))
    }

    /**
     * 标题请求的**两个关键字段**：`max_tokens = 64` 与调用点算出来的 `thinking = disabled`。
     *
     * 这条钉的是用户报的 bug（DeepSeek 的会话标题没有总结）：少了 thinking，会推理的模型会把
     * 64 个 token 全花在推理上、`content` 为空 → 标题永远生成不出来。实测（api.deepseek.com，
     * model = deepseek-flash）：不加字段 finish=length / content 0 字 / reasoning 175 字；
     * 加了 `thinking: {type: disabled}` 之后 finish=stop / content 10 字 / 6 个 token。
     */
    @Test
    fun `标题请求带上 max_tokens 与关掉的 thinking`() {
        val request = titleRequest("deepseek-flash", "你好，帮我看看这个 bug", ThinkingOption("disabled"))
        assertEquals("deepseek-flash", request.model)
        assertEquals(TITLE_MAX_OUTPUT_TOKENS, request.maxTokens)
        assertEquals(ThinkingOption("disabled"), request.thinking)
        assertNull(request.reasoningEffort)
        assertEquals(2, request.messages.size)
        assertEquals(listOf("system", "user"), request.messages.map { it.role })
        assertTrue(request.stream)
    }

    /** 不给 thinking 时这个键整个不发（explicitNulls = false）——别的提供方不认识它 */
    @Test
    fun `没有 thinking 时请求里没有这个字段`() {
        assertNull(titleRequest("qwen3.8-flash", "你好", null).thinking)
    }

    @Test
    fun `user 提示词就是 dsh 那句加一个 JSON 数组`() {
        val prompt = titleUserPrompt("帮我看看这个 bug")
        assertTrue(prompt.startsWith("Generate the session title from this JSON array of human messages:"))
        assertEquals("Generate the session title from this JSON array of human messages:\n[{\"seq\":1,\"text\":\"帮我看看这个 bug\"}]", prompt)
        // 引号与反斜杠按 JSON 转义
        assertTrue(titleUserPrompt("a \"b\" c\\d").endsWith("[{\"seq\":1,\"text\":\"a \\\"b\\\" c\\\\d\"}]") || titleUserPrompt("a \"b\" c\\d").contains("\\\"b\\\""))
    }

    @Test
    fun `system 提示词逐字是 dsh 的那四行`() {
        assertTrue(TITLE_SYSTEM_PROMPT.startsWith("Create a concise title for an AI coding-assistant session"))
        assertTrue(TITLE_SYSTEM_PROMPT.contains("Use the language of the messages."))
        assertTrue(TITLE_SYSTEM_PROMPT.contains("about 5 words in non-CJK languages or 10 CJK characters"))
    }

    /**
     * 写进去的值与判据里的值必须是同一个字符串 —— 这条断言就是那个「同一个」。
     *
     * 原先这里还有 6 条钉 `sessionTitleFor`（「消息落库时给这一行取什么标题」）的用例，
     * 第 189 轮把那个函数整个删了：标题改成**一轮跑完才生成**（见 ChatViewModel.generateTitle），
     * 追加消息不再动标题，于是那套「兜底值 / 非兜底值 / 非用户角色」的判定没有调用点了。
     */
    @Test
    fun `兜底值是「新会话」三个字`() {
        assertEquals("新会话", NEW_SESSION_TITLE)
    }
}
