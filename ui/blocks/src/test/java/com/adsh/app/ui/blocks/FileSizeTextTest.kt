package com.adsh.app.ui.blocks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文件大小文案（[fileSizeText]，dsh 的 `fileSizeText` 规则）。
 *
 * 钉住的规则：dsh 的阈值是「**小于 10 才保留一位小数**」、数字与单位之间**没有空格**
 * （同一个文件大小在附件卡片与文件预览里必须逐字相同）。
 */
class FileSizeTextTest {

    @Test
    fun bytesBelowOneKilobyteHaveNoSpace() {
        assertEquals("0B", fileSizeText(0))
        assertEquals("512B", fileSizeText(512))
        assertEquals("1023B", fileSizeText(1023))
    }

    @Test
    fun underTenKeepsOneDecimalAboveTenRounds() {
        assertEquals("1.0KB", fileSizeText(1024))
        assertEquals("1.5KB", fileSizeText(1536))
        assertEquals("9.9KB", fileSizeText(10_138))
        // dsh: kb < 10 ? toFixed(1) : Math.round(kb)
        assertEquals("10KB", fileSizeText(10_240))
        assertEquals("124KB", fileSizeText(127_000))
    }

    @Test
    fun megabytesAndGigabytesFollowTheSameRule() {
        assertEquals("1.0MB", fileSizeText(1024L * 1024L))
        assertEquals("1.1MB", fileSizeText(1_200_000L))
        assertEquals("12MB", fileSizeText(12L * 1024L * 1024L))
        assertEquals("1.0GB", fileSizeText(1024L * 1024L * 1024L))
        assertEquals("2.5GB", fileSizeText((2.5 * 1024 * 1024 * 1024).toLong()))
    }
}
