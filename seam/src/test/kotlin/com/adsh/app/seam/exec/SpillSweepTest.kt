package com.adsh.app.seam.exec

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [Spill.sweepStale] 的判据：只碰自己的命名空间；空目录没有可恢复的内容、不看年龄；
 * 非空目录按保留期；进程崩溃留下的 adsh-pid 标记按保留期清；当前进程正在写的目录一概不碰。
 */
class SpillSweepTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hour = 60L * 60 * 1000

    private fun dir(name: String, ageMs: Long, withFile: Boolean = false): File {
        val made = File(tmp.root, name)
        made.mkdirs()
        if (withFile) File(made, "stdout.log").writeText("x")
        made.setLastModified(System.currentTimeMillis() - ageMs)
        return made
    }

    @Test
    fun `过期目录被回收`() {
        val old = dir(Spill.SPILL_DIR_PREFIX + "old", 25 * hour, withFile = true)

        assertEquals(1, Spill.sweepStale(tmp.root))

        assertTrue(!old.exists())
    }

    @Test
    fun `空目录不看年龄一律回收`() {
        val youngEmpty = dir(Spill.SPILL_DIR_PREFIX + "young-empty", 1 * hour)

        assertEquals(1, Spill.sweepStale(tmp.root))

        assertTrue(!youngEmpty.exists())
    }

    @Test
    fun `非空的新目录保留`() {
        val youngFull = dir(Spill.SPILL_DIR_PREFIX + "young-full", 1 * hour, withFile = true)

        assertEquals(0, Spill.sweepStale(tmp.root))

        assertTrue(youngFull.exists())
    }

    @Test
    fun `过期 adsh-pid 标记文件被回收，新的保留`() {
        val old = File(tmp.root, Spill.PID_MARKER_PREFIX + "old.txt").apply { writeText("123") }
        old.setLastModified(System.currentTimeMillis() - 25 * hour)
        val young = File(tmp.root, Spill.PID_MARKER_PREFIX + "young.txt").apply { writeText("456") }

        assertEquals(1, Spill.sweepStale(tmp.root))

        assertTrue(!old.exists())
        assertTrue(young.exists())
    }

    @Test
    fun `当前进程正在写的 spill 目录不回收`() {
        val current = Spill.directory(tmp.root)

        assertEquals(0, Spill.sweepStale(tmp.root))

        assertTrue(current.isDirectory)
    }

    @Test
    fun `别的目录与同名普通文件一概不碰`() {
        val foreign = dir("some-other-tmp", 48 * hour, withFile = true)
        val file = File(tmp.root, Spill.SPILL_DIR_PREFIX + "not-a-dir").also { it.writeText("x") }
        file.setLastModified(System.currentTimeMillis() - 48 * hour)

        assertEquals(0, Spill.sweepStale(tmp.root))

        assertTrue(foreign.exists())
        assertTrue(file.exists())
    }

    @Test
    fun `目录不存在时返回 0`() {
        assertEquals(0, Spill.sweepStale(File(tmp.root, "missing")))
    }
}
