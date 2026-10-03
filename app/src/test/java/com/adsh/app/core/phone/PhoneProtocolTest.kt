package com.adsh.app.core.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `phone` 通道的线协议（[parsePhoneRequest] / [encodePhoneResponse] / 两个目标判定）。
 *
 * 这条协议两边各写一遍（Kotlin 与 `$PREFIX/bin/phone` 那个 sh 脚本），所以形状必须钉死：
 * 请求第 1 行是命令、之后每行一个参数；应答第 1 行是退出码、之后是正文。
 */
class PhoneProtocolTest {

    @Test
    fun `三个 T1 命令各自解析出来`() {
        assertEquals(PhoneRequest.Open("https://example.com"), parsePhoneRequest("open\nhttps://example.com"))
        assertEquals(PhoneRequest.Info, parsePhoneRequest("info"))
        assertEquals(PhoneRequest.ClipGet, parsePhoneRequest("clip\nget"))
        assertEquals(PhoneRequest.ClipSet("你好"), parsePhoneRequest("clip\nset\n你好"))
        assertEquals(PhoneRequest.Help, parsePhoneRequest("help"))
    }

    @Test
    fun `多行文本原样拼回去（剪贴板要能写多行）`() {
        assertEquals(PhoneRequest.ClipSet("第一行\n第二行"), parsePhoneRequest("clip\nset\n第一行\n第二行"))
    }

    @Test
    fun `不认识或参数不够就是 null（调用方回用法错）`() {
        assertNull(parsePhoneRequest(""))
        assertNull(parsePhoneRequest("nope"))
        assertNull(parsePhoneRequest("open"))
        assertNull(parsePhoneRequest("clip"))
        assertNull(parsePhoneRequest("clip\nnope"))
        assertNull(parsePhoneRequest("clip\nset"))
    }

    @Test
    fun `脚本那边可能带 CR，解析要容忍`() {
        assertEquals(PhoneRequest.Info, parsePhoneRequest("info\r\n"))
        assertEquals(PhoneRequest.Open("https://x"), parsePhoneRequest("open\r\nhttps://x\r\n"))
    }

    @Test
    fun `应答第一行是退出码，正文在之后`() {
        assertEquals("0\n已打开：https://x\n", encodePhoneResponse(0, "已打开：https://x"))
        assertEquals("1\n失败：打不开\n", encodePhoneResponse(1, "失败：打不开"))
        // 正文自带的行尾换行不重复
        assertEquals("0\nabc\n", encodePhoneResponse(0, "abc\n"))
    }

    @Test
    fun `网址与域名的判定`() {
        assertTrue(looksLikeUrl("https://example.com"))
        assertTrue(looksLikeUrl("http://192.168.1.1:8080/x"))
        assertFalse(looksLikeUrl("example.com"))
        assertFalse(looksLikeUrl("com.example.app"))

        assertTrue(looksLikeDomain("example.com"))
        assertTrue(looksLikeDomain("com.example.app"))
        assertFalse(looksLikeDomain("带 空格.com"))
        assertFalse(looksLikeDomain("a/b.com"))
        assertFalse(looksLikeDomain("没有点"))
    }

    // ------------------------------------------------------------ open 的目标判定（T-熵减）

    @Test
    fun `带 scheme 的直接当网址，而且**一次都不去查包管理器**`() {
        var probed = 0
        val resolved = resolveOpenTarget("https://example.com") { probed += 1; true }
        assertEquals(PhoneOpenTarget.Url("https://example.com"), resolved)
        assertEquals(0, probed)
    }

    @Test
    fun `包已安装就拉起应用`() {
        assertEquals(
            PhoneOpenTarget.Package("com.example.app"),
            resolveOpenTarget("com.example.app") { it == "com.example.app" },
        )
    }

    @Test
    fun `没装但看着像域名就补 https 当网址`() {
        assertEquals(PhoneOpenTarget.Domain("https://example.com"), resolveOpenTarget("example.com") { false })
        assertEquals(PhoneOpenTarget.Domain("https://com.example.app"), resolveOpenTarget("com.example.app") { false })
    }

    @Test
    fun `都不像就报「打不开」`() {
        assertEquals(PhoneOpenTarget.Unsupported("没有点"), resolveOpenTarget("没有点") { false })
        assertEquals(PhoneOpenTarget.Unsupported("a/b.com"), resolveOpenTarget("a/b.com") { false })
    }

    // ------------------------------------------------------------ info 的文案（T-熵减）

    @Test
    fun `info 正文四行：屏幕、电量、网络、版本`() {
        assertEquals(
            "屏幕：亮\n电量：94%\n网络：已连接\nADSH：0.2.0-debug（SDK 36）",
            phoneInfoText(
                screenOn = true,
                batteryPercent = 94,
                networkConnected = true,
                versionName = "0.2.0-debug",
                sdk = 36,
            ),
        )
    }

    @Test
    fun `没网时也要把屏幕电量版本报全（以前那段提前 return 抄了一遍）`() {
        assertEquals(
            "屏幕：灭\n电量：0%\n网络：无\nADSH：0.2.0（SDK 26）",
            phoneInfoText(
                screenOn = false,
                batteryPercent = 0,
                networkConnected = false,
                versionName = "0.2.0",
                sdk = 26,
            ),
        )
    }
}
