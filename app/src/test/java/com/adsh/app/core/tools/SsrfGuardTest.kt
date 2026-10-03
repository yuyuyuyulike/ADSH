package com.adsh.app.core.tools

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SSRF 守卫的确定性用例（不依赖 DNS：IP 字面量与协议判定都是纯本地逻辑）。
 *
 * 这里的存在意义是钉住契约：**check 返回 null 表示「通过」**。
 * web_fetch 之前就是把「通过」和「DNS 超时」混在一个 null 上，导致每个 URL 都报 dns_timeout。
 */
class SsrfGuardTest {

    @Test
    fun blocksLoopbackAndPrivateLiterals() {
        assertNotNull(SsrfGuard.check("http://127.0.0.1/"))
        assertNotNull(SsrfGuard.check("http://192.168.1.1/"))
        assertNotNull(SsrfGuard.check("http://10.0.0.1/"))
        assertNotNull(SsrfGuard.check("http://100.64.0.1/"))
        assertNotNull(SsrfGuard.check("http://169.254.1.1/"))
    }

    @Test
    fun blocksUnsupportedSchemesAndCredentials() {
        assertNotNull(SsrfGuard.check("ftp://example.com/x"))
        assertNotNull(SsrfGuard.check("file:///etc/passwd"))
        assertNotNull(SsrfGuard.check("https://user:pass@example.com/"))
    }

    @Test
    fun malformedUrlIsRejected() {
        assertNotNull(SsrfGuard.check("not a url"))
    }

    /** 通过时必须是 null —— 调用方靠这个区分「没问题」和「超时」 */
    @Test
    fun publicLiteralPassesWithNull() {
        assertNull(SsrfGuard.check("http://93.184.216.34/"))
    }
}
