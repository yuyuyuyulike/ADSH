package com.adsh.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tools:sdk 的输出声明必须与实现产出的值一致：缺字段 / 多字段都是模型可见的谎言
 * （dsh 在 execute 前按 schema 整条校验，ADSH 靠这份声明跟实现对齐）。
 */
class ToolSdkDeclarationsTest {

    private fun output(name: String): String = ToolSdk.specs.first { it.name == name }.output

    /** dsh 的 bash 输出三臂都 required cwd；前台另有可选 stopped */
    @Test
    fun bashOutputDeclaresCwdInAllThreeArmsAndOptionalStopped() {
        val out = output("bash")
        assertEquals("cwd: string 应出现三次（background / promoted / foreground）", 3, Regex("cwd: string").findAll(out).count())
        assertTrue(out, out.contains("stopped?: string"))
        assertFalse("ADSH 没有结构化 sandbox（平台机制不同）", out.contains("sandbox:"))
    }

    /** write / edit 的值带原文/新文字节数；before/after 只是回显、可能被 256 KiB 截断 */
    @Test
    fun writeAndEditDeclareEchoByteCountsAndTheTruncation() {
        listOf("write", "edit").forEach { name ->
            val out = output(name)
            assertTrue(name, out.contains("beforeBytes: number"))
            assertTrue(name, out.contains("afterBytes: number"))
            assertTrue(name, out.contains("256 KiB"))
        }
    }

    /** read_image 的值从不产出 originalDimensions（ADSH 不做图片归一化） */
    @Test
    fun readImageOutputDropsTheUnusedOriginalDimensions() {
        val out = output("read_image")
        assertFalse(out, out.contains("originalDimensions"))
        assertTrue(out, out.contains("attachmentId: string"))
    }
}
