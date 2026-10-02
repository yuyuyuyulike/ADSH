package com.adsh.app.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 供应方目录（第 103 轮重排 + 扩表）。
 *
 * 这些断言盯的是**用户能看到的那一层**：下拉里的顺序（常用的在上面）、id 的合法性
 * （下拉选中之后要能直接当提供方 id 用）、地址必须能直连（https）、以及「小厂已经拿掉」。
 */
class ProviderCatalogTest {

    private val presets = ProviderCatalog.presets

    @Test
    fun `常用在最上面`() {
        // 前四条 = 聚合 / 中转站（用户点名的 OpenCode 两条 + OhMyGPT + OpenRouter）
        assertEquals(
            listOf("opencode-go", "opencode", "ohmygpt", "openrouter"),
            presets.take(4).map { it.id },
        )
        // 大厂紧随其后，云平台在最后
        assertTrue(presets.indexOfFirst { it.id == "openai" } < presets.indexOfFirst { it.id == "groq" })
        assertTrue(presets.indexOfFirst { it.id == "anthropic" } < presets.indexOfFirst { it.id == "together" })
    }

    @Test
    fun `小厂已经去掉`() {
        assertFalse(presets.any { it.id == "ant-ling" })
    }

    @Test
    fun `id 唯一且都是合法的提供方 id`() {
        assertEquals(presets.size, presets.map { it.id }.distinct().size)
        presets.forEach { preset ->
            assertTrue(preset.id, BuiltInProviders.validProviderId(preset.id))
            assertTrue(preset.id, preset.displayName.isNotBlank())
        }
    }

    @Test
    fun `地址都是能直连的 https`() {
        presets.forEach { preset ->
            assertTrue(preset.id + " " + preset.baseUrl, preset.baseUrl.startsWith("https://"))
            // baseUrl 里不许留占位符（cloudflare 那种 {ACCOUNT_ID} 的 route 没收进来）
            assertFalse(preset.id, preset.baseUrl.contains("{"))
        }
    }

    @Test
    fun `新加的中转站地址取自各自的目录或官方文档`() {
        fun base(id: String) = presets.first { it.id == id }.baseUrl
        assertEquals("https://opencode.ai/zen/go/v1", base("opencode-go"))
        assertEquals("https://opencode.ai/zen/v1", base("opencode"))
        assertEquals("https://api.ohmygpt.com/v1", base("ohmygpt"))
        // Anthropic / Google 用官方给的 OpenAI 兼容端点
        assertEquals("https://api.anthropic.com/v1", base("anthropic"))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", base("google"))
        // 这两条第 103 轮修过：以前少了 /v1 与 /inference/v1，请求会打到上一层路径
        assertEquals("https://api.mistral.ai/v1", base("mistral"))
        assertEquals("https://api.fireworks.ai/inference/v1", base("fireworks"))
    }

    @Test
    fun `模型初值里没有空串`() {
        presets.forEach { preset ->
            assertTrue(preset.id, preset.models.none { it.isBlank() })
        }
    }
}
