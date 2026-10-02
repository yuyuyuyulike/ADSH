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
}
