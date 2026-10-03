package com.adsh.app.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 老扁平配置 → 提供方清单的迁移（[migratedProviders]）与「当前提供方」的取值规则
 * （[currentProviderIdOf]）。
 *
 * 这条路径只在**升级上来的第一次读取**跑一次，判错的后果不是「显示不对」而是
 * 「老用户的地址 / 密钥 / 勾过的模型消失」，或者（R59 挖到的那个）让用户顶着
 * **内置提供方 + 网关的密钥**去请求 api.deepseek.com。跑过一次之后 prefs 里就有
 * providers_json 了，再也回不来 —— 所以每条分支都要有用例。
 */
class ProvidersMigrationTest {

    private val officialBase = BuiltInProviders.DEEPSEEK_BASE_URL

    // ------------------------------------------------------------ 地址是官方默认

    @Test
    fun `地址是官方默认：只留内置提供方，密钥照搬`() {
        val list = migratedProviders(officialBase, "sk-legacy", emptyList()).providers
        assertEquals(1, list.size)
        assertEquals(BuiltInProviders.DEEPSEEK_ID, list.single().id)
        assertEquals("sk-legacy", list.single().apiKey)
        assertEquals(officialBase, list.single().baseUrl)
        assertTrue(!list.single().custom)
    }

    /** 比较时忽略尾斜杠，但**存下来的写法原样保留**（那是用户自己存的地址） */
    @Test
    fun `官方地址带尾斜杠也算等价，且尾斜杠原样保留`() {
        val list = migratedProviders(officialBase + "/", "sk-legacy", emptyList()).providers
        assertEquals(1, list.size)
        assertEquals(officialBase + "/", list.single().baseUrl)
    }

    @Test
    fun `老配置勾过的模型按内置目录补齐档案`() {
        val list = migratedProviders(officialBase, "", listOf("deepseek-flash", "已经退役的-id")).providers
        val models = list.single().models
        assertEquals(listOf("deepseek-flash", "已经退役的-id"), models.map { it.id })
        // 认识的模型连显示名、容量与图片能力一起回来（不补齐的话图片会被换成「image omitted」）
        val flash = models.first()
        assertEquals("DeepSeek-V4.1-Flash", flash.name)
        assertEquals(1_000_000L, flash.contextWindow)
        assertTrue(flash.acceptsImages)
        // 不认识的只剩 id
        assertEquals(null, models[1].name)
        assertTrue(!models[1].acceptsImages)
    }

    @Test
    fun `老配置没勾过模型就用内置默认目录`() {
        val list = migratedProviders(officialBase, "", emptyList()).providers
        assertEquals(BuiltInProviders.DEEPSEEK_MODELS.map { it.id }, list.single().models.map { it.id })
    }

    // ------------------------------------------------------------ 地址是自定义网关

    @Test
    fun `地址不是官方默认：包成一个自定义网关，老配置整个归它`() {
        val result = migratedProviders("https://gw.example/v1", "sk-gw", listOf("m1", "m2"))
        val list = result.providers
        assertEquals(listOf(BuiltInProviders.DEEPSEEK_ID, "custom"), list.map { it.id })
        // 老的扁平密钥**只归自定义那份**：按 dsh 的凭据模型，每个提供方路由各自一份凭据
        // （deriveKeyRef(provider) = <PROVIDER>_API_KEY，dsh-settings-models 的 refFor/1502）
        assertEquals("", list.first().apiKey)
        val custom = list[1]
        assertEquals("gw.example", custom.displayName)
        assertEquals("https://gw.example/v1", custom.baseUrl)
        assertEquals("sk-gw", custom.apiKey)
        assertTrue(custom.custom)
        // 自定义分支不做目录补齐：模型只有 id
        assertEquals(listOf("m1", "m2"), custom.models.map { it.id })
        assertEquals(null, custom.models[0].name)
    }

    /**
     * 迁移出自定义网关时必须把当前提供方一起指过去：providerId 的兜底是「清单第一条」= 内置，
     * 不指的话老用户的网关配置等于没用上（请求会打到 api.deepseek.com）。
     */
    @Test
    fun `迁移出自定义网关会把当前提供方指过去`() {
        assertEquals("custom", migratedProviders("https://gw.example/v1", "sk-gw", emptyList()).providerId)
    }

    /** 官方地址那条分支不动当前提供方：保持原来的兜底（清单第一条 = 内置提供方） */
    @Test
    fun `官方地址分支不动当前提供方`() {
        assertEquals(null, migratedProviders(officialBase, "sk-legacy", emptyList()).providerId)
    }

    @Test
    fun `网关主机名解析不出来时用「自定义提供方」`() {
        val list = migratedProviders("这不是一个地址", "", emptyList()).providers
        assertEquals("自定义提供方", list[1].displayName)
    }

    /** 现状：只忽略尾斜杠、**不 trim 空格** —— 前后带空格的地址会被当成自定义网关 */
    @Test
    fun `前后带空格的官方地址会被当成自定义网关（现状）`() {
        val list = migratedProviders("  " + officialBase + "  ", "sk", emptyList()).providers
        assertEquals(2, list.size)
        assertEquals("custom", list[1].id)
    }

    // ------------------------------------------------------------ 当前提供方的取值规则

    @Test
    fun `存过的 id 还在清单里就用它`() {
        val list = listOf(BuiltInProviders.deepseek(), ProviderDef(id = "gw", displayName = "GW"))
        assertEquals("gw", currentProviderIdOf("gw", list))
        assertEquals(BuiltInProviders.DEEPSEEK_ID, currentProviderIdOf(BuiltInProviders.DEEPSEEK_ID, list))
    }

    @Test
    fun `存过的 id 不在清单里就落到第一条`() {
        val list = listOf(BuiltInProviders.deepseek(), ProviderDef(id = "gw", displayName = "GW"))
        // 用户把那个提供方删了，或者老版本存下来的值
        assertEquals(BuiltInProviders.DEEPSEEK_ID, currentProviderIdOf("已经删掉的", list))
        assertEquals(BuiltInProviders.DEEPSEEK_ID, currentProviderIdOf("", list))
        // 清单第一条**不是**内置时，兜底必须跟着第一条走（而不是无条件回落到 DeepSeek）——
        // 少了这条，「兜底不再看第一条」那个变异不会响（R61 实测过）
        val customFirst = listOf(ProviderDef(id = "gw", displayName = "GW"), BuiltInProviders.deepseek())
        assertEquals("gw", currentProviderIdOf("已经删掉的", customFirst))
        assertEquals("gw", currentProviderIdOf("", customFirst))
    }

    @Test
    fun `清单为空才回落到内置 DeepSeek`() {
        assertEquals(BuiltInProviders.DEEPSEEK_ID, currentProviderIdOf("", emptyList()))
        assertEquals(BuiltInProviders.DEEPSEEK_ID, currentProviderIdOf("gw", emptyList()))
    }
}
