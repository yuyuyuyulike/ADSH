package com.adsh.app.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress

/**
 * web_fetch 的端到端用例：跑的是**同一条**网络路径（WebFetchTool.fetchForModel）。
 *
 * 用的地址都是在国内网络实测可达的（example.com / wikipedia 在这边直接超时，
 * 拿它们当用例只会得到「环境不可达」的假红 —— 真机上那次「部分能用」里就有这个因素）。
 * 拿不到 DNS 时整组跳过。
 */
class WebFetchTest {

    private fun reachable(host: String): Boolean =
        runCatching { InetAddress.getByName(host) }.isSuccess

    @Test
    fun emptyAndBlockedUrlsFailWithoutNetwork() = runBlocking {
        val empty = WebFetchTool.fetchForModel("")
        assertTrue(empty.toString(), empty is FetchAttempt.Error)

        val loopback = WebFetchTool.fetchForModel("http://127.0.0.1/")
        assertTrue(loopback.toString(), loopback is FetchAttempt.Error)
        val message = (loopback as FetchAttempt.Error).message
        assertTrue(message, message.contains("non-public"))
    }

    /** 真站点 + 真结构：中文页面必须变成可读的 Markdown（标题 / 链接 / 图片 / 列表都在） */
    @Test
    fun rendersRealChinesePageAsMarkdown() = runBlocking {
        assumeTrue("没有 DNS，跳过联网用例", reachable("www.deepseek.com"))
        val attempt = WebFetchTool.fetchForModel("https://www.deepseek.com/news/deepseek-v3/")
        assertTrue(attempt.toString(), attempt is FetchAttempt.Ok)
        val ok = attempt as FetchAttempt.Ok
        println("=== deepseek.com 新闻页 前 800 字 ===")
        println(ok.text.take(800))
        assertTrue(ok.text, ok.text.startsWith("Fetched https://www.deepseek.com/news/deepseek-v3/ (HTTP 200)"))
        assertTrue(ok.text, ok.text.contains("External web content follows"))
        assertTrue(ok.text, ok.text.contains("# DeepSeek-V3 正式发布"))
        assertTrue(ok.text, ok.text.contains("[chat.deepseek.com](https://chat.deepseek.com)"))
        assertTrue(ok.value.toString(), ok.value.toString().contains("\"statusCode\":200"))
        assertTrue(ok.value.toString(), ok.value.toString().contains("\"kind\":\"html\""))
    }

    /**
     * 明文 http 必须能抓。Android 侧靠 network_security_config 放开，
     * 这里用的正是用户那次搜索结果里的那条 http 链接。
     */
    @Test
    fun fetchesCleartextHttpPage() = runBlocking {
        assumeTrue("没有 DNS，跳过联网用例", reachable("m.memuplay.com"))
        val attempt = WebFetchTool.fetchForModel("http://m.memuplay.com/download-com.termux.html")
        println("=== http://m.memuplay.com/... 结果前 300 字 ===")
        println(attempt.toString().take(300))
        assertTrue(attempt.toString(), attempt is FetchAttempt.Ok)
    }
}
