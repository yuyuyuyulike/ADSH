package com.adsh.app.runtime.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [writeTextAtomically]：读者要么看到旧内容、要么看到完整的新内容。
 *
 * 目标文件是 `MANIFEST.properties`（bootstrap 安装器判断「装了什么」的唯一依据）——
 * 半截文件比没有文件更糟：下次启动会读出一个「版本对、内容缺」的 manifest。
 */
class AtomicFilesTest {

    private fun dir(): File = Files.createTempDirectory("adsh-atomic").toFile()

    private fun target(d: File) = File(d, "MANIFEST.properties")

    @Test
    fun `writes a new file`() {
        val d = dir()
        writeTextAtomically(target(d), "installerVersion=6\n")
        assertEquals("installerVersion=6\n", target(d).readText())
    }

    @Test
    fun `replaces existing content`() {
        val d = dir()
        writeTextAtomically(target(d), "old\n")
        writeTextAtomically(target(d), "new\n")
        assertEquals("new\n", target(d).readText())
    }

    @Test
    fun `leaves no temp file behind`() {
        val d = dir()
        writeTextAtomically(target(d), "a\n")
        writeTextAtomically(target(d), "b\n")
        assertFalse(File(d, "MANIFEST.properties.tmp").exists())
    }

    @Test
    fun `different content lengths replace the whole file`() {
        val d = dir()
        writeTextAtomically(target(d), "a very long line that must disappear completely\n")
        writeTextAtomically(target(d), "short\n")
        assertEquals("short\n", target(d).readText())
    }
}
