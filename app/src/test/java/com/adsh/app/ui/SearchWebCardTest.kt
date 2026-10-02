package com.adsh.app.ui

import com.adsh.app.core.tools.GrepMatch
import com.adsh.app.core.tools.WebOutputText
import com.adsh.app.core.tools.renderGlobPaths
import com.adsh.app.core.tools.renderGrep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * grep / glob / web_search / web_fetch 的卡片解析（第八十九轮）。
 *
 * 这四张卡都是从**工具正文**解析回来的（ADSH 的工具行只落库正文，没有 dsh 的 output meta），
 * 所以「正文形状 ↔ 卡片」这一对必须在单测里钉住：正文一改、卡片就画不出来（退回 ioCard）
 * 或者把页脚当成一条路径画进去 —— 真机上只有跑一次工具才看得见。
 *
 * glob / grep 用**真的渲染函数**造正文（round-trip），网页两种用手写的 dsh 形状。
 */
class SearchWebCardTest {

    // ---------------------------------------------------------------- glob

    @Test
    fun globEmptyResultIsAnEmptyPathsCard() {
        val card = parseGlobCard(renderGlobPaths(emptyList()))!!
        assertEquals(SearchKind.PATHS, card.kind)
        assertTrue(card.paths.isEmpty())
        assertEquals(0, card.total)
        assertTrue(!card.truncated)
        assertNull(card.recovery)
    }

    @Test
    fun globListsEveryPathWhenUnderTheCap() {
        val paths = listOf("app/src/main/AndroidManifest.xml", "build.gradle.kts", "settings.gradle.kts")
        val card = parseGlobCard(renderGlobPaths(paths))!!
        assertEquals(paths, card.paths)
        assertEquals(3, card.total)
        assertEquals(3, card.shownCount)
        assertTrue(!card.truncated)
    }

    @Test
    fun globTruncatedResultKeepsPageTotalAndRecovery() {
        val paths = (1..150).map { "src/file" + it + ".kt" }
        val card = parseGlobCard(renderGlobPaths(paths))!!
        assertEquals(100, card.paths.size)
        assertEquals(100, card.shownCount)
        assertEquals(150, card.total)
        assertTrue(card.truncated)
        assertTrue(card.recovery!!.contains("could not be saved"))
        // 页脚不能变成一条路径
        assertTrue(card.paths.none { it.contains("Showing") })
    }

    @Test
    fun globGarbageFallsBack() {
        assertNull(parseGlobCard(null))
        assertNull(parseGlobCard(""))
        assertNull(parseGlobCard("   \n  "))
    }

    /**
     * 没截断的 glob 正文形状就是「一行一条路径」，没有可校验的头部 —— 认不出形状这件事
     * 对它不适用（认形状的是带 `Found …` 头的 grep 与带抬头的网页两种）。
     * 报错行根本不进这里：`tool.error` 时调用方不解析。
     */
    @Test
    fun globWithoutFooterTreatsEveryLineAsAPath() {
        val card = parseGlobCard("app/A.kt\napp/B.kt")!!
        assertEquals(listOf("app/A.kt", "app/B.kt"), card.paths)
    }

    // ---------------------------------------------------------------- grep

    @Test
    fun grepEmptyResultIsAnEmptyMatchesCard() {
        val card = parseGrepCard(renderGrep(emptyList()))!!
        assertEquals(SearchKind.MATCHES, card.kind)
        assertTrue(card.files.isEmpty())
        assertEquals(0, card.total)
    }

    @Test
    fun grepGroupsMatchesByFile() {
        val matches = listOf(
            GrepMatch("app/A.kt", 12, "val x = 1"),
            GrepMatch("app/A.kt", 40, "val y = 2"),
            GrepMatch("app/B.kt", 7, "val z = 3"),
        )
        val card = parseGrepCard(renderGrep(matches))!!
        assertEquals(2, card.files.size)
        assertEquals("app/A.kt", card.files[0].path)
        assertEquals(listOf(12 to "val x = 1", 40 to "val y = 2"), card.files[0].matches.map { it.lineNumber to it.line })
        assertEquals("app/B.kt", card.files[1].path)
        assertEquals(3, card.shownCount)
        assertEquals(3, card.total)
        assertTrue(!card.truncated)
    }

    @Test
    fun grepTruncatedResultKeepsTotalAndRecovery() {
        val matches = (1..300).map { GrepMatch("app/A.kt", it, "hit " + it) }
        val card = parseGrepCard(renderGrep(matches))!!
        assertEquals(250, card.shownCount)
        assertEquals(300, card.total)
        assertTrue(card.truncated)
        assertTrue(card.recovery!!.contains("could not be saved"))
        // 头部的 Found 行与页脚都不许混进命中行
        assertTrue(card.files.all { file -> file.matches.none { it.line.contains("Found") } })
        assertTrue(card.files.all { file -> file.matches.none { it.line.contains("could not be saved") } })
    }

    @Test
    fun grepGarbageFallsBack() {
        assertNull(parseGrepCard(null))
        assertNull(parseGrepCard("ripgrep 失败：\nboom"))
        assertNull(parseGrepCard("Found 2 matches\n\napp/A.kt"))
    }

    // ---------------------------------------------------------------- web_search

    private fun searchText(vararg paragraphs: String): String =
        (listOf(WebOutputText.NOTICE) + paragraphs).joinToString("\n\n")

    @Test
    fun webSearchParsesSourcesWithSnippetAndDate() {
        val card = parseWebSearchCard(
            searchText(
                "Sources:\n" +
                    "- [DeepSeek](https://www.deepseek.com/) — Official site (2025-01-01)\n" +
                    "- [example.com](https://example.com/a) — A snippet",
                WebOutputText.CITE_HINT,
            ),
        )!!
        assertEquals(2, card.sources.size)
        assertEquals("https://www.deepseek.com/", card.sources[0].url)
        assertEquals("DeepSeek", card.sources[0].title)
        assertEquals("Official site", card.sources[0].snippet)
        assertEquals("2025-01-01", card.sources[0].publishedAt)
        // 没有日期的来源：整个后缀都算摘要
        assertEquals("A snippet", card.sources[1].snippet)
        assertNull(card.sources[1].publishedAt)
        assertNull(card.answer)
        assertTrue(!card.truncated)
    }

    @Test
    fun webSearchKeepsTheAnswerAboveTheSources() {
        val card = parseWebSearchCard(
            searchText(
                "### first query\n\nDeepSeek released a new model.",
                "Sources:\n- [DeepSeek](https://www.deepseek.com/)",
                WebOutputText.CITE_HINT,
            ),
        )!!
        assertEquals("### first query\n\nDeepSeek released a new model.", card.answer)
        assertEquals(1, card.sources.size)
    }

    /**
     * 真机实测的那一条（第 90 轮用户报「卡片只展示一条」）：DeepSeek 后端拿 `cited_text` 当摘要，
     * 而它是**多行 markdown、里面带空行**。按 `\n\n` 切段的旧写法只画得出第一条来源。
     */
    @Test
    fun webSearchKeepsEverySourceWhenSnippetsSpanBlankLines() {
        val text = searchText(
            "Sources:\n" +
                "- [Google 新闻](https://news.google.com/home?hl=zh-CN) — ### 焦点新闻\n" +
                "\n" +
                "新华网\n\n习近平主席的“比什凯克时间”：主动引领历史航向的大国担当\n" +
                "...\n" +
                "驻德国大使馆\n" +
                "- [主页- BBC News 中文](https://www.bbc.com/zhongwen/simp) — ## 头条新闻\n" +
                "\n" +
                "- ### 星宇股份解约风波\n" +
                "\n" +
                "9月9日，就近期在中国引起轰动的“星宇股份解约107位应届生”事件。\n" +
                "\n" +
                "2026年9月14日\n" +
                "- [消息人士：哈马斯同意美国提出的加沙停火方案 | 每经网](https://www.nbd.com.cn/articles/2025-09-27/1.html) — 消息人士：哈马斯同意停火\n" +
                "\n" +
                "据美国《政治报》网站报道。 (2025-09-27T00:00:00.000Z)",
            WebOutputText.SOURCES_TRUNCATED_PREFIX + "7 sources. Refine the query for more.)",
            WebOutputText.CITE_HINT,
        )
        val card = parseWebSearchCard(text)!!
        assertEquals(3, card.sources.size)
        assertTrue(card.truncated)
        assertEquals("https://news.google.com/home?hl=zh-CN", card.sources[0].url)
        assertEquals("Google 新闻", card.sources[0].title)
        // 多行摘要折成一段（dsh 的 HTML 把换行当空格），里面的空行不切断来源
        assertEquals(
            "### 焦点新闻 新华网 习近平主席的“比什凯克时间”：主动引领历史航向的大国担当 ... 驻德国大使馆",
            card.sources[0].snippet,
        )
        assertEquals("主页- BBC News 中文", card.sources[1].title)
        assertEquals("## 头条新闻 - ### 星宇股份解约风波 9月9日，就近期在中国引起轰动的“星宇股份解约107位应届生”事件。 2026年9月14日", card.sources[1].snippet)
        assertNull(card.sources[1].publishedAt)
        // 日期是整段摘要之后那个括号（只认末尾）
        assertEquals("2025-09-27T00:00:00.000Z", card.sources[2].publishedAt)
        assertTrue(card.sources[2].snippet!!.endsWith("据美国《政治报》网站报道。"))
    }

    @Test
    fun webSearchNoResultsAndTruncationAreRecognised() {        val empty = parseWebSearchCard(searchText(WebOutputText.NO_RESULTS, WebOutputText.CITE_HINT))!!
        assertTrue(empty.sources.isEmpty())
        assertNull(empty.answer)
        assertTrue(!empty.truncated)

        val capped = parseWebSearchCard(
            searchText(
                "Sources:\n- [DeepSeek](https://www.deepseek.com/)",
                WebOutputText.SOURCES_TRUNCATED_PREFIX + "8 sources. Refine the query for more.)",
                WebOutputText.CITE_HINT,
            ),
        )!!
        assertTrue(capped.truncated)
        assertEquals(1, capped.sources.size)
    }

    @Test
    fun webSearchGarbageFallsBack() {
        assertNull(parseWebSearchCard(null))
        assertNull(parseWebSearchCard("WebError: provider credential missing"))
        assertNull(parseWebSearchCard(WebOutputText.NOTICE))
    }

    // ---------------------------------------------------------------- web_fetch

    @Test
    fun webFetchParsesUrlStatusAndBody() {
        val text = WebOutputText.FETCH_PREFIX + "https://example.com/a (HTTP 404)\n\n" +
            WebOutputText.NOTICE + "\n\n# Not found\n\nThe page is gone."
        val card = parseWebFetchCard(text)!!
        assertEquals("https://example.com/a", card.url)
        assertEquals(404, card.statusCode)
        assertTrue(!card.truncated)
        assertEquals("# Not found\n\nThe page is gone.", card.body)
    }

    @Test
    fun webFetchStripsTheTruncationFooter() {
        val text = WebOutputText.FETCH_PREFIX + "https://example.com/b (HTTP 200)\n\n" +
            WebOutputText.NOTICE + "\n\nbody text" + WebOutputText.FETCH_TRUNCATION_FOOTER
        val card = parseWebFetchCard(text)!!
        assertTrue(card.truncated)
        assertEquals("body text", card.body)
    }

    @Test
    fun webFetchGarbageFallsBack() {
        assertNull(parseWebFetchCard(null))
        assertNull(parseWebFetchCard("web fetch timed out"))
        assertNull(parseWebFetchCard("Fetched https://example.com (HTTP 200)"))
    }
}
