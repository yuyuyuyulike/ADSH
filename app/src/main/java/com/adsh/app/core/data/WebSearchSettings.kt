package com.adsh.app.core.data

/**
 * 网页搜索那几个**存在 prefs 里的值**的判定（从 [SettingsStore] 里搬出来的一簇，R62）。
 *
 * 三件事各自都有一条容易踩的线：
 *  - **偏好键的命名**（[webStoredKey]）是升级路径的一部分：内置 DeepSeek 后端沿用历史键
 *    （升级上来的配置就存在那几个键里），改了它老用户存的地址 / 密钥 / 上限就全读不到；
 *  - **旧默认端点的纠正**（[webSearchBaseUrlFor]）：老版本把 DeepSeek 的搜索端点写成了
 *    chat 的 base（`https://api.deepseek.com`），dsh 的注释里专门强调过两者不同 ——
 *    不纠正的话升级上来的搜索会打到错的端点；
 *  - **密钥的回落**（[webSearchApiKeyFor]）：按 dsh 的 `dsh-web-search-deepseek`（它在
 *    Authentication 一节里写明「提供方不新增密钥，每次搜索解析会话凭据」），ADSH 的会话凭据
 *    就是内置 DeepSeek 提供方的 apiKey —— 内置后端没单独配过就回落到它；
 *    其它后端**不回落**（它们的密钥与 DeepSeek 无关）。
 *
 * 纯函数：prefs 读取与 [SettingsStore.WEB_SEARCH_BACKENDS] 的查找都留在调用点。
 */

/**
 * 某个后端自己的偏好键：内置的 DeepSeek 后端沿用**历史键**（升级上来的配置就是它的），
 * 其余后端在键尾接提供方 id（`base_url_exa` 这样）。
 *
 * **这是升级路径的一部分**：改了命名，老用户存的地址 / 密钥 / 上限就全读不到了。
 */
internal fun webStoredKey(base: String, provider: String): String =
    if (provider == SettingsStore.WEB_SEARCH_PROVIDER_DEEPSEEK) base else base + "_" + provider

/**
 * 某个后端的接口地址：存过就用存的（用户填什么就是什么），没存过用它自己的默认值；
 * **DeepSeek 的旧默认值当作没设置过** —— 老版本写进去的是 chat 的 base
 * （`https://api.deepseek.com`，dsh 注释里专门强调过它与搜索端点不同），
 * 不纠正的话升级上来的搜索会打到错的端点。比较时忽略尾斜杠。
 */
internal fun webSearchBaseUrlFor(provider: String, stored: String?): String {
    if (stored == null) return SettingsStore.defaultWebSearchBaseUrl(provider)
    if (provider == SettingsStore.WEB_SEARCH_PROVIDER_DEEPSEEK &&
        stored.trimEnd('/') == SettingsStore.LEGACY_WEB_SEARCH_BASE_URL
    ) {
        return SettingsStore.DEFAULT_WEB_SEARCH_BASE_URL
    }
    return stored
}

/**
 * 某个后端的 API Key：自己存过（非空白）就以它为准；内置的 DeepSeek 后端没存过就回落到
 * **DeepSeek 提供方那把密钥**（dsh 的会话凭据语义）；其它后端没存过就是空串（**不回落**）。
 *
 * [deepseekProviderKey] 是**惰性**的（一个 lambda）：只有真的走到回落那一步才去读提供方清单 ——
 * 那是 SharedPreferences 读取 + JSON 解码，不该为每个后端付这个代价。
 */
internal fun webSearchApiKeyFor(
    provider: String,
    stored: String,
    deepseekProviderKey: () -> String,
): String {
    if (stored.isNotBlank()) return stored
    if (provider != SettingsStore.WEB_SEARCH_PROVIDER_DEEPSEEK) return ""
    return deepseekProviderKey()
}

/**
 * 这个后端的密钥是不是**在跟随 DeepSeek 提供方**（自己没存过、而提供方有密钥）。
 * 设置页用它把状态标签写成「已跟随…密钥」，让「自动填上」这件事在界面上说得清楚。
 */
internal fun webSearchKeyFollowsProvider(
    provider: String,
    stored: String,
    deepseekProviderKey: () -> String,
): Boolean =
    provider == SettingsStore.WEB_SEARCH_PROVIDER_DEEPSEEK &&
        stored.isBlank() &&
        deepseekProviderKey().isNotBlank()
