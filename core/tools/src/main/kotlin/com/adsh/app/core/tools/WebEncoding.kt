package com.adsh.app.core.tools

import java.nio.charset.Charset

/**
 * HTTP 正文的**类型与编码判定**（dsh 的 classifyContentType / parseCharset，外加一层 HTML meta 嗅探）
 * —— 原先埋在 [WebFetchTool] 里、零用例，而它们是「国内站点整页乱码」的唯一防线：
 * 大量站点只在 `<meta charset>` 里声明 GBK，头部压根不给 charset。
 *
 * 三个判定的口径都对着 dsh：
 *  - 类型只认 html / text 两类，其余（图片、二进制）**不猜**，由调用点报 unsupported content type；
 *  - 头部声明的 charset 若**不认识就报错**，不回落 UTF-8 —— dsh 在这里宁可让这次抓取失败，
 *    也不给模型吐一整页乱码（[DeclaredCharset.Unsupported]）；
 *  - meta 嗅探是**补的一层**（dsh 只认头部），且它不认识时回落 UTF-8 而不是报错 ——
 *    猜错的风险只落在这一个站点上，而 meta 里写错 charset 的站点并不少。
 */

/**
 * 这个正文能不能读：`html` / `text`，或 null（调用点报 unsupported content type）。
 *
 * 判据顺序即优先级（dsh 的 classifyContentType）：`text/html` 与 `application/xhtml+xml` 是 html；
 * 其余 `text/` 开头的与 `application/json` / `application/xml` 是 text；`+json` / `+xml` 后缀
 * （`application/ld+json`、`image/svg+xml`）也算 text。**没有前缀通配**：`mytext/plain` 不算。
 */
internal fun classifyContentType(contentType: String?): String? {
    val mime = (contentType ?: "").substringBefore(';').trim().lowercase()
    if (mime == "text/html" || mime == "application/xhtml+xml") return "html"
    if (mime.startsWith("text/")) return "text"
    if (mime == "application/json" || mime == "application/xml") return "text"
    if (mime.endsWith("+json") || mime.endsWith("+xml")) return "text"
    return null
}

/**
 * 头部声明的 charset 名（**小写**；没声明返回 null）。dsh 的 parseCharset：
 * `; charset=值`，值可以带引号，前后的空白不算。只看头部，不看正文。
 */
internal fun parseCharset(contentType: String?): String? =
    Regex(";\\s*charset\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
        .find(contentType ?: "")
        ?.groupValues?.get(1)?.trim()?.lowercase()

/** 头部 charset 的三种下场：没声明 / 认识 / **不认识（这次抓取直接失败）** */
internal sealed interface DeclaredCharset {

    /** 头部没给 charset → 交给 [sniffCharset] 按正文猜 */
    data object Absent : DeclaredCharset

    /** 认识，直接用 */
    data class Known(val charset: Charset) : DeclaredCharset

    /** 头部声明了一个这台设备上不存在的编码名 —— 报错，不猜 */
    data class Unsupported(val name: String) : DeclaredCharset
}

/** [parseCharset] + `Charset.forName` 的两次判定合成一个（调用点因此只剩一个 when） */
internal fun declaredCharset(contentType: String?): DeclaredCharset {
    val name = parseCharset(contentType) ?: return DeclaredCharset.Absent
    val charset = runCatching { Charset.forName(name) }.getOrNull()
        ?: return DeclaredCharset.Unsupported(name)
    return DeclaredCharset.Known(charset)
}

/**
 * 头部没写 charset 时按 HTML 自己的声明猜：把**前 4096 字节**当 ISO-8859-1 读（一个字节一个字符，
 * 不会因为截断在多字节序列中间而抛异常），找 `charset=xxx` 的第一个出现处。
 *
 * 找不到、或找到的名字不认识 → **UTF-8**（这一层不报错：宁可按 UTF-8 显示，也不让整次抓取失败）。
 */
internal fun sniffCharset(bytes: ByteArray): Charset {
    val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
    val meta = Regex("charset\\s*=\\s*[\"']?([a-zA-Z0-9_\\-]+)", RegexOption.IGNORE_CASE)
        .find(head)?.groupValues?.get(1) ?: return Charsets.UTF_8
    return runCatching { Charset.forName(meta) }.getOrNull() ?: Charsets.UTF_8
}
