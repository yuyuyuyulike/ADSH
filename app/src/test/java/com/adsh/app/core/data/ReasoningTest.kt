package com.adsh.app.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 推理等级是**逐模型**的（dsh：dsh-llm-deepseek 是常量四档，dsh-llm-pi-ai 查 pi-ai 的
 * getSupportedThinkingLevels）。表里没有的模型按约定退回 DeepSeek 的四档。
 */
class ReasoningTest {

    private fun names(provider: String, model: String): List<String> =
        Reasoning.menuOptions(provider, model).map { it.second }

    private fun ids(provider: String, model: String): List<String> =
        Reasoning.menuOptions(provider, model).map { it.first }

    @Test
    fun deepSeekProviderUsesTheAdapterConstants() {
        // dsh 的 REASONING_EFFORTS：Off / Low / High / Max
        assertEquals(listOf("Off", "Low", "High", "Max"), names("deepseek-official", "deepseek-flash"))
        assertEquals(listOf("off", "low", "high", "max"), ids("deepseek-official", "deepseek-flash"))
    }

    @Test
    fun deepSeekHasNoProviderDefaultRowButDefaultsToHigh() {
        // DeepSeek 那一侧没有「提供方默认」这一档（它自己有默认档），默认就是 high
        assertEquals(false, names("deepseek-official", "deepseek-flash").contains("Default"))
        assertEquals("high", Reasoning.defaultEffortFor("deepseek-official", "deepseek-flash"))
        // 其他模型的默认档是 null —— 菜单里的 Default 行照旧
        assertNull(Reasoning.defaultEffortFor("openai", "gpt-5.4"))
        assertEquals("Default", names("openai", "gpt-5.4").first())
    }

    @Test
    fun everyDeepSeekModelGetsTheSameFourLevels() {
        assertEquals(names("deepseek-official", "deepseek-flash"), names("deepseek-official", "deepseek-v4-pro"))
    }

    @Test
    fun piAiModelsUseTheirOwnLevels() {
        // pi-ai 的 openai/gpt-5.4：off / low / medium / high / xhigh（没有 minimal、没有 max）
        assertEquals(
            listOf("Default", "Off", "Low", "Medium", "High", "Xhigh"),
            names("openai", "gpt-5.4"),
        )
        // 同一族里最小的那个也可能是另一套（这里与 gpt-5.4 相同，用「非空且含 Xhigh」兜住形状）
        assertEquals(true, names("openai", "gpt-5.4-mini").contains("Xhigh"))
    }

    @Test
    fun modelsWithoutOffStartAtLow() {
        // moonshotai 的 kimi-k3：只有 low / high / max（off 被 thinkingLevelMap 映射成 null）
        assertEquals(listOf("Default", "Low", "High", "Max"), names("moonshotai-cn", "kimi-k3"))
    }

    @Test
    fun unknownModelsFallBackToDeepSeekNames() {
        assertEquals(listOf("Default", "Off", "Low", "High", "Max"), names("custom", "some-unknown-model"))
    }

    @Test
    fun modelsWithoutReasoningOfferNothing() {
        // pi-ai 的 reasoning: false → dsh 连 reasoning 字段都不下发，菜单只有一句「未提供推理等级」
        assertEquals(emptyList<String>(), names("mistral", "mistral-large-latest"))
    }

    @Test
    fun wireEffortOmitsBlankAndPassesSupportedLevels() {
        assertNull(Reasoning.wireEffort("deepseek-official", "deepseek-flash", ""))
        assertEquals("high", Reasoning.wireEffort("deepseek-official", "deepseek-flash", "high"))
        assertEquals("off", Reasoning.wireEffort("deepseek-official", "deepseek-flash", "off"))
    }

    @Test
    fun wireEffortClampsALevelFromAnotherProvider() {
        // 给 GPT 选了 medium 之后换回 DeepSeek：往上找最近的（high），而不是把 medium 发出去
        assertEquals("high", Reasoning.wireEffort("deepseek-official", "deepseek-flash", "medium"))
        // moonshotai 的 kimi-k3 没有 off：off 往上找第一档是 low
        assertEquals("low", Reasoning.wireEffort("moonshotai-cn", "kimi-k3", "off"))
    }

    @Test
    fun wireEffortSendsNothingForModelsWithoutReasoning() {
        assertNull(Reasoning.wireEffort("mistral", "mistral-large-latest", "high"))
        // 目录里没有的模型按原样放行（菜单给的选项就是兜底表）
        assertEquals("high", Reasoning.wireEffort("custom", "some-unknown-model", "high"))
    }
}
