package com.adsh.app.core.tools

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 网页正文的类型与编码判定（[classifyContentType] / [parseCharset] / [declaredCharset] / [sniffCharset]）。
 *
 * 它们原先埋在 [WebFetchTool] 里、零用例，而**国内站点的整页乱码**就卡在这一层：
 * 头部不给 charset、只在 `<meta charset>` 里写 GBK。**本文件不联网**（真联网的两条用例在
 * WebFetchTest 里，别混进来）。
 */
class WebEncodingTest {

    // ---------------------------------------------------------------- 正文类型

    @Test
    fun `html 两种`() {
        assertEquals("html", classifyContentType("text/html"))
        assertEquals("html", classifyContentType("application/xhtml+xml"))
    }

    @Test
    fun `分号后面的参数不参与判定`() {
        assertEquals("html", classifyContentType("text/html; charset=utf-8"))
        assertEquals("text", classifyContentType("text/plain;charset=GBK"))
    }

    @Test
    fun `大小写与前后空格都不敏感`() {
        assertEquals("html", classifyContentType("  TEXT/HTML  "))
        assertEquals("text", classifyContentType("Text/Plain"))
    }

    @Test
    fun `text 分支`() {
        assertEquals("text", classifyContentType("text/plain"))
        assertEquals("text", classifyContentType("text/css"))
        assertEquals("text", classifyContentType("text/csv"))
    }

    @Test
    fun `json 与 xml 两种拼法`() {
        assertEquals("text", classifyContentType("application/json"))
        assertEquals("text", classifyContentType("application/xml"))
        assertEquals("text", classifyContentType("application/ld+json"))
        assertEquals("text", classifyContentType("image/svg+xml"))
    }

    @Test
    fun `别的类型一律 null（不猜）`() {
        assertNull(classifyContentType("image/png"))
        assertNull(classifyContentType("application/octet-stream"))
        assertNull(classifyContentType("application/pdf"))
        assertNull(classifyContentType(null))
        assertNull(classifyContentType(""))
    }

    /** 没有前缀通配：dsh 的判据是 `startsWith("text/")`，不是「包含 text」 */
    @Test
    fun `前缀必须真的对上`() {
        assertNull(classifyContentType("mytext/plain"))
        assertNull(classifyContentType("application/jsonx"))
        assertNull(classifyContentType("application/xmlx"))
    }

    // ---------------------------------------------------------------- 头部 charset

    @Test
    fun `头部 charset：常见写法`() {
        assertEquals("utf-8", parseCharset("text/html; charset=utf-8"))
        assertEquals("gbk", parseCharset("text/html;charset=GBK"))
        assertEquals("gb2312", parseCharset("text/html; charset=\"GB2312\""))
    }

    @Test
    fun `头部 charset：前面还有别的参数`() {
        assertEquals("utf-8", parseCharset("text/html; boundary=x; charset=UTF-8"))
    }

    @Test
    fun `头部 charset：等号两边有空格、大小写混写`() {
        assertEquals("utf-8", parseCharset("text/html; CHARSET = Utf-8"))
    }

    @Test
    fun `头部 charset：没有就是 null`() {
        assertNull(parseCharset("text/html"))
        assertNull(parseCharset(null))
        assertNull(parseCharset(""))
    }

    @Test
    fun `头部 charset：值在分号处截断（引号里带分号也只取前一段）`() {
        assertEquals("gbk", parseCharset("text/html; charset=\"gbk;x\""))
    }

    @Test
    fun `头部声明的三种下场`() {
        assertEquals(DeclaredCharset.Absent, declaredCharset("text/html"))
        assertEquals(DeclaredCharset.Known(StandardCharsets.UTF_8), declaredCharset("text/html; charset=utf-8"))
        assertEquals(DeclaredCharset.Known(Charset.forName("GBK")), declaredCharset("text/html; charset=gbk"))
    }

    /** dsh 宁可让这次抓取失败，也不给模型吐一整页乱码 */
    @Test
    fun `头部声明了不认识的编码：报错而不是回落 UTF-8`() {
        assertEquals(DeclaredCharset.Unsupported("klingon"), declaredCharset("text/html; charset=klingon"))
    }

    // ---------------------------------------------------------------- meta 嗅探

    @Test
    fun `meta 嗅探：国内站点的 GBK 就是这个形态`() {
        assertEquals(Charset.forName("GBK"), sniffCharset("<meta charset=\"gbk\">".toByteArray()))
        assertEquals(Charset.forName("GBK"), sniffCharset("<META CHARSET=GBK>".toByteArray()))
        assertEquals(Charset.forName("Big5"), sniffCharset("<meta charset='big5'>".toByteArray()))
    }

    @Test
    fun `meta 嗅探：http-equiv 那种长写法也能认`() {
        val html = "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=gb2312\">"
        assertEquals(Charset.forName("GB2312"), sniffCharset(html.toByteArray()))
    }

    @Test
    fun `meta 嗅探：没有声明就是 UTF-8`() {
        assertEquals(StandardCharsets.UTF_8, sniffCharset("<html><head></head></html>".toByteArray()))
        assertEquals(StandardCharsets.UTF_8, sniffCharset(ByteArray(0)))
    }

    /** 这一层与头部那层口径**不同**：猜不出来时按 UTF-8 显示，不让整次抓取失败 */
    @Test
    fun `meta 嗅探：不认识的编码回落 UTF-8`() {
        assertEquals(StandardCharsets.UTF_8, sniffCharset("<meta charset=\"klingon\">".toByteArray()))
    }

    /** 只看前 4096 字节（窗口是钉住的：声明在 4096 之后就不认） */
    @Test
    fun `meta 嗅探：4096 字节之外的声明不认`() {
        val padding = " ".repeat(5000)
        val late = (padding + "<meta charset=\"gbk\">").toByteArray()
        assertEquals(StandardCharsets.UTF_8, sniffCharset(late))
        val early = ("<meta charset=\"gbk\">" + padding).toByteArray()
        assertEquals(Charset.forName("GBK"), sniffCharset(early))
    }

    /** 截断窗口落在多字节序列中间也不抛异常：前 4096 字节是按 ISO-8859-1（一字节一字符）读的 */
    @Test
    fun `meta 嗅探：中文正文不会因为截断炸掉`() {
        val bytes = ("中文".repeat(3000)).toByteArray()
        assertEquals(StandardCharsets.UTF_8, sniffCharset(bytes))
    }
}
