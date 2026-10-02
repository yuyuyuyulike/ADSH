package com.adsh.app.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * publicUrlVerdict 的三态契约：null = 超时、"" = 通过、其它 = 拒绝。
 *
 * 这个契约就是 web_fetch「每个 URL 都报 dns_timeout」那个 bug 的根因：
 * 之前把 SsrfGuard.check 的「通过（null）」和 withTimeoutOrNull 的「超时（null）」当成了一回事。
 */
class PublicUrlVerdictTest {

    @Test
    fun publicLiteralPassesAsEmptyString() = runBlocking {
        // 通过 ⇒ 空串（**不是** null）
        assertEquals("", publicUrlVerdict("http://93.184.216.34/"))
    }

    @Test
    fun blockedUrlReturnsReason() = runBlocking {
        val verdict = publicUrlVerdict("http://127.0.0.1/")
        assertNotNull(verdict)
        assert(verdict!!.isNotEmpty()) { "被拒的原因不能是空串（空串专门表示通过）" }
    }

    @Test
    fun unsupportedSchemeReturnsReason() = runBlocking {
        val verdict = publicUrlVerdict("ftp://example.com/")
        assertNotNull(verdict)
        assert(verdict!!.isNotEmpty())
    }
}
