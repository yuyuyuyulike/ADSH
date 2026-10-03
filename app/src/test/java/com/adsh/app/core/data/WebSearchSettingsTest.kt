package com.adsh.app.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页搜索那几个「存在 prefs 里的值」的判定（[webStoredKey] / [webSearchBaseUrlFor] /
 * [webSearchApiKeyFor] / [webSearchKeyFollowsProvider]）。
 *
 * 三处以前都零用例，而它们各自都有一条会静默出错的线：键名改了老配置读不到、
 * 旧默认端点不纠正就打到错端点、回落规则写反了要么搜索用不了要么拿错密钥。
 */
class WebSearchSettingsTest {

    private val deepseek = SettingsStore.WEB_SEARCH_PROVIDER_DEEPSEEK
    private val exa = SettingsStore.WEB_SEARCH_PROVIDER_EXA
    private val tavily = SettingsStore.WEB_SEARCH_PROVIDER_TAVILY
    private val metaso = SettingsStore.WEB_SEARCH_PROVIDER_METASO

    // ------------------------------------------------------------ 偏好键的命名

    @Test
    fun `内置 DeepSeek 沿用历史键，其余后端在键尾接 id`() {
        assertEquals("base_url", webStoredKey("base_url", deepseek))
        assertEquals("base_url_exa", webStoredKey("base_url", exa))
        assertEquals("web_search_max_uses_tavily", webStoredKey("web_search_max_uses", tavily))
    }

    // ------------------------------------------------------------ 接口地址

    @Test
    fun `没存过就用后端自己的默认值`() {
        assertEquals(SettingsStore.DEFAULT_WEB_SEARCH_BASE_URL, webSearchBaseUrlFor(deepseek, null))
        assertEquals(SettingsStore.DEFAULT_WEB_SEARCH_BASE_URL_EXA, webSearchBaseUrlFor(exa, null))
        assertEquals(SettingsStore.DEFAULT_WEB_SEARCH_BASE_URL_TAVILY, webSearchBaseUrlFor(tavily, null))
        assertEquals(SettingsStore.DEFAULT_WEB_SEARCH_BASE_URL_METASO, webSearchBaseUrlFor(metaso, null))
    }

    @Test
    fun `存过就用存的，不规范化用户的写法`() {
        assertEquals("https://my-gw/search", webSearchBaseUrlFor(deepseek, "https://my-gw/search"))
        assertEquals("https://my-gw/", webSearchBaseUrlFor(exa, "https://my-gw/"))
    }

    @Test
    fun `DeepSeek 的旧默认端点当作没设置过（带尾斜杠也算）`() {
        val legacy = SettingsStore.LEGACY_WEB_SEARCH_BASE_URL
        assertEquals(SettingsStore.DEFAULT_WEB_SEARCH_BASE_URL, webSearchBaseUrlFor(deepseek, legacy))
        assertEquals(SettingsStore.DEFAULT_WEB_SEARCH_BASE_URL, webSearchBaseUrlFor(deepseek, legacy + "/"))
    }

    /** 纠正只针对内置后端的历史键：别的后端真填了同一个字符串也保持原样 */
    @Test
    fun `旧默认端点的纠正只对 DeepSeek`() {
        assertEquals(
            SettingsStore.LEGACY_WEB_SEARCH_BASE_URL,
            webSearchBaseUrlFor(exa, SettingsStore.LEGACY_WEB_SEARCH_BASE_URL),
        )
    }

    // ------------------------------------------------------------ 密钥与回落

    @Test
    fun `自己存过就以它为准`() {
        assertEquals("sk-mine", webSearchApiKeyFor(deepseek, "sk-mine") { "sk-provider" })
        assertEquals("sk-exa", webSearchApiKeyFor(exa, "sk-exa") { "sk-provider" })
    }

    @Test
    fun `内置后端没存过就回落到提供方密钥`() {
        assertEquals("sk-provider", webSearchApiKeyFor(deepseek, "") { "sk-provider" })
        // 只有空白也算没存过
        assertEquals("sk-provider", webSearchApiKeyFor(deepseek, "   ") { "sk-provider" })
    }

    @Test
    fun `其它后端不回落`() {
        assertEquals("", webSearchApiKeyFor(exa, "") { "sk-provider" })
        assertEquals("", webSearchApiKeyFor(metaso, "") { "sk-provider" })
    }

    /** 提供方清单是 SharedPreferences + JSON 解码：不该为每个后端都付这个代价 */
    @Test
    fun `只有真的要回落时才去读提供方密钥（惰性）`() {
        var reads = 0
        webSearchApiKeyFor(exa, "") { reads += 1; "sk" }
        assertEquals(0, reads)
        webSearchApiKeyFor(deepseek, "sk-mine") { reads += 1; "sk" }
        assertEquals(0, reads)
        webSearchApiKeyFor(deepseek, "  ") { reads += 1; "sk" }
        assertEquals(1, reads)
    }

    @Test
    fun `是否有密钥跟随：内置 + 没存过 + 提供方有密钥`() {
        assertTrue(webSearchKeyFollowsProvider(deepseek, "") { "sk" })
        assertFalse(webSearchKeyFollowsProvider(deepseek, "sk-mine") { "sk" })
        assertFalse(webSearchKeyFollowsProvider(deepseek, "") { "" })
        assertFalse(webSearchKeyFollowsProvider(exa, "") { "sk" })
    }

    @Test
    fun `跟随判定对其它后端也惰性`() {
        var reads = 0
        webSearchKeyFollowsProvider(exa, "") { reads += 1; "sk" }
        assertEquals(0, reads)
    }
}
