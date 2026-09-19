package com.adsh.app.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * 账户余额（模型菜单里显示在提供方名字最右边）。
 *
 * 接口来自 DeepSeek 官方文档 api-docs.deepseek.com 的 Get User Balance：
 *   GET https://api.deepseek.com/user/balance   Authorization: Bearer <API key>
 *   {"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"19.06",
 *     "granted_balance":"0.00","topped_up_balance":"19.06"}]}
 *
 * 只有 DeepSeek 系提供方有这个接口（其它 OpenAI 兼容网关没有通用做法），所以
 * [BalanceApi.supports] 只认官方 id 或指向 deepseek 的地址；其余提供方不显示余额。
 * 拉取是在用户打开模型菜单时按需做的（每次点开自动刷新一次），不在后台轮询。
 */
data class AccountBalance(
    val currency: String,
    /** total_balance：可用总余额（字符串，官方就是字符串，不做浮点转换以免丢精度） */
    val total: String,
    /** granted_balance：赠送余额 */
    val granted: String? = null,
    /** topped_up_balance：充值余额 */
    val toppedUp: String? = null,
    val available: Boolean = true,
) {
    /** 展示文案：CNY → ¥19.06，USD → $19.06，其它币种原样「19.06 XXX」 */
    val label: String
        get() {
            val symbol = when (currency.uppercase()) {
                "CNY" -> "¥"
                "USD" -> "$"
                else -> ""
            }
            return if (symbol.isEmpty()) total + " " + currency else symbol + total
        }
}

object BalanceApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 只有 DeepSeek 系（官方 id / 指向 deepseek 的地址）能查余额 */
    fun supports(provider: ProviderDef): Boolean =
        provider.id == BuiltInProviders.DEEPSEEK_ID || provider.baseUrl.contains("deepseek")

    /**
     * 查一次余额（阻塞，调用方放 IO 线程）。
     * 地址就是提供方自己的 baseUrl + /user/balance —— 官方带不带 /v1 都能路由（实测两种都 200）。
     */
    fun fetch(provider: ProviderDef, connectTimeoutMs: Int = 15_000, readTimeoutMs: Int = 20_000): AccountBalance {
        val key = provider.apiKey.trim()
        require(key.isNotEmpty()) { "未配置 API Key" }
        val base = provider.baseUrl.trim().trimEnd('/').ifEmpty { BuiltInProviders.DEEPSEEK_BASE_URL }
        val url = URL(base + "/user/balance")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer " + key)
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("HTTP " + code + "：" + detailOf(body).take(200))
            }
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw IllegalStateException("返回不是 JSON 对象")
            val infos = (root["balance_infos"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
            val first = infos.firstOrNull() ?: throw IllegalStateException("返回里没有 balance_infos")
            val total = first["total_balance"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("返回里没有 total_balance")
            return AccountBalance(
                currency = first["currency"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                total = total,
                granted = first["granted_balance"]?.jsonPrimitive?.contentOrNull,
                toppedUp = first["topped_up_balance"]?.jsonPrimitive?.contentOrNull,
                available = root["is_available"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
            )
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** 错误详情（和 web_search 一样：error.message / message） */
    private fun detailOf(raw: String): String {
        if (raw.isBlank()) return ""
        val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw
        val detail = ((parsed["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (parsed["message"] as? JsonPrimitive)?.contentOrNull
        return detail.orEmpty()
    }
}
