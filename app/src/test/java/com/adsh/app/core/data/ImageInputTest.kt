package com.adsh.app.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片能力的两条口径（第八十三轮，用户点名）：
 *
 *  - **新加的模型默认勾选「可识别图片」**：目录里不认识的 id 按「能收图」发 —— 收不了会报错，
 *    报错文案直接指路回设置页取消勾选；以前的静默降级（换成 image omitted 占位）等于把用户的图丢了；
 *  - 目录里认识的模型仍按目录（DeepSeek-V4-Pro 不支持图片输入）。
 */
class ImageInputTest {

    @Test
    fun unknownModelsDefaultToImagesOn() {
        listOf("qwen3.8-flash", "gpt-5.4", "glm-5.3", "some-new-model").forEach { id ->
            assertTrue("目录里没有的模型默认能收图：" + id, SettingsStore.defaultImageInput(id))
        }
    }

    @Test
    fun catalogModelsKeepTheirOwnCapability() {
        assertTrue("deepseek-flash 目录里写着能收图", SettingsStore.defaultImageInput("deepseek-flash"))
        assertFalse("deepseek-v4-pro 不支持图片输入", SettingsStore.defaultImageInput("deepseek-v4-pro"))
        // 退役的旧 id 由 deepseek-flash 承接（目录里也按它算）
        assertTrue(SettingsStore.defaultImageInput("deepseek-v4-flash"))
    }

    @Test
    fun catalogLookupIsUnchangedForKnownIds() {
        assertTrue(SettingsStore.acceptsImages("deepseek-flash"))
        assertFalse(SettingsStore.acceptsImages("deepseek-v4-pro"))
    }

    // ------------------------------------------------------------ 三岔：手动开关 / 目录 / 默认（acceptsImagesFrom）

    @Test
    fun `手动关掉目录里写着能收图的模型`() {
        val models = listOf(ModelDef(id = "deepseek-flash", acceptsImages = true, imageInput = false))
        assertFalse(acceptsImagesFrom(models, "deepseek-flash"))
    }

    @Test
    fun `手动打开目录里说不支持的模型`() {
        val models = listOf(ModelDef(id = "deepseek-v4-pro", acceptsImages = false, imageInput = true))
        assertTrue(acceptsImagesFrom(models, "deepseek-v4-pro"))
    }

    @Test
    fun `没手动拍板时按目录条目`() {
        val flash = ModelDef(id = "deepseek-flash", acceptsImages = true)
        assertTrue(acceptsImagesFrom(listOf(flash), "deepseek-flash"))
        // 目录说不支持 + defaultImageInput 也说不支持（deepseek-v4-pro 在目录里）→ 关
        assertFalse(acceptsImagesFrom(listOf(ModelDef(id = "deepseek-v4-pro")), "deepseek-v4-pro"))
    }

    @Test
    fun `目录里没有这个 id 就走默认（未知模型能收图）`() {
        assertTrue(acceptsImagesFrom(emptyList(), "qwen3.8-flash"))
        assertTrue(acceptsImagesFrom(listOf(ModelDef(id = "other")), "qwen3.8-flash"))
    }

    /** 现状：查目录用的是**精确 id**（不 trim、不忽略大小写），对不上就走「未知模型」那条 */
    @Test
    fun `目录里的 id 精确匹配，带空格就对不上（现状）`() {
        val models = listOf(ModelDef(id = " gpt-x ", imageInput = false))
        assertTrue(acceptsImagesFrom(models, "gpt-x"))
    }

    /**
     * 这一条专门区分「行上的 acceptsImages」与「目录默认」：deepseek-v4-pro 在目录里是**不支持**图片的，
     * 但行上写着 true（升级上来的旧档案、或以后目录改了而档案没跟着改）—— 按规则应当**以行为准**放行。
     * 少了这条用例，「不看目录的 acceptsImages」这个变异**不会响**（R60 实测过）。
     */
    @Test
    fun `行上的 acceptsImages 比目录默认优先`() {
        val stale = listOf(ModelDef(id = "deepseek-v4-pro", acceptsImages = true))
        assertTrue(acceptsImagesFrom(stale, "deepseek-v4-pro"))
        // 对照：目录/默认口径对同一个 id 是「不支持」
        assertFalse(SettingsStore.defaultImageInput("deepseek-v4-pro"))
    }
}
