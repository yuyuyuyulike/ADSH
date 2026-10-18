package com.adsh.app.core.jobs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 有界输出环（[OutputRing]）与配套的两条口径（[utf8Tail] / [settleRetainCap]）。
 *
 * 整个后台任务子系统里**只有这里按字节算账**，而它的规则全是「错一位就静默丢数据」：
 * 偏移是 UTF-8 字节、头部整块淘汰、单独一块超限时只留 UTF-8 安全尾。
 */
class OutputRingTest {

    private fun ring(vararg texts: String, cap: Int = 1024): OutputRing =
        OutputRing().also { r -> texts.forEach { r.append(it, Jobs.Channel.STDOUT, false, cap) } }

    // ---------------------------------------------------------------- 追加与偏移

    @Test
    fun `偏移是 UTF-8 字节数，不是字符数`() {
        val r = OutputRing()
        r.append("abc", Jobs.Channel.STDOUT, false, 1024)
        r.append("中文", Jobs.Channel.STDOUT, false, 1024)
        assertEquals(3 + 6, r.total)
        assertEquals(3 + 6, r.retained)
        assertEquals(0, r.earliest)
        val (chunks, lossy) = r.readFrom(0)
        assertEquals(listOf(0, 3), chunks.map { it.at })
        assertEquals(listOf("abc", "中文"), chunks.map { it.text })
        assertFalse(lossy)
    }

    /** 空串不算一块：否则环里会堆出一堆零长度块，淘汰时白丢一轮 */
    @Test
    fun `空串不追加`() {
        val r = OutputRing()
        assertFalse(r.append("", Jobs.Channel.STDOUT, false, 1024))
        assertEquals(0, r.total)
        assertEquals(0, r.earliest)
        assertTrue(r.readFrom(0).first.isEmpty())
    }

    @Test
    fun `读出去的是新对象`() {
        val r = ring("abc")
        val first = r.readFrom(0).first
        val second = r.readFrom(0).first
        assertEquals(first, second)
        assertNotSame(first[0], second[0])
    }

    @Test
    fun `通道与 gap 标记跟着块走`() {
        val r = OutputRing()
        r.append("out", Jobs.Channel.STDOUT, false, 1024)
        r.append("err", Jobs.Channel.STDERR, true, 1024)
        val chunks = r.readFrom(0).first
        assertEquals(listOf(Jobs.Channel.STDOUT, Jobs.Channel.STDERR), chunks.map { it.channel })
        assertEquals(listOf(false, true), chunks.map { it.gapBefore })
    }

    // ------------------------------------------------------------------ 头部淘汰

    @Test
    fun `超过上限从头部整块丢：earliest 推进到下一块，已分配的偏移不动`() {
        val r = OutputRing()
        r.append("abc", Jobs.Channel.STDOUT, false, 5)
        r.append("def", Jobs.Channel.STDOUT, false, 5)
        assertEquals(6, r.total)      // 累计不变
        assertEquals(3, r.retained)   // 只剩后一块
        assertEquals(3, r.earliest)   // 最老那块现在是 def
        // 再追加：新块的偏移接在累计量上（不是接在被丢掉的位置）
        r.append("gh", Jobs.Channel.STDOUT, false, 5)
        assertEquals(8, r.total)
        assertEquals(listOf(3, 6), r.readFrom(0).first.map { it.at })
    }

    @Test
    fun `只剩一块时不再丢，交给安全尾处理`() {
        val r = OutputRing()
        r.append("abcdefgh", Jobs.Channel.STDOUT, false, 3)
        assertEquals(1, r.readFrom(0).first.size)
        assertEquals(3, r.retained)
    }

    @Test
    fun `单独一块超上限：只留 UTF-8 安全尾并打 gap`() {
        val r = OutputRing()
        r.append("abcdefgh", Jobs.Channel.STDOUT, false, 3)
        val (chunks, lossy) = r.readFrom(0)
        assertEquals(1, chunks.size)
        assertEquals("fgh", chunks[0].text)
        assertEquals(5, chunks[0].at)      // 0 + 8 - 3
        assertTrue(chunks[0].gapBefore)
        assertTrue(lossy)                  // 0 < earliest(5)
    }

    /** 切点落在续字节上要往后跳：否则模型会看到一个半个汉字 */
    @Test
    fun `安全尾不切断多字节字符`() {
        val r = OutputRing()
        r.append("中文", Jobs.Channel.STDOUT, false, 5)
        val text = r.readFrom(0).first.single().text
        assertEquals("文", text)
        assertEquals(3, r.retained)
        assertEquals(3, r.earliest)
    }

    @Test
    fun `上限小于 1 按 1 算`() {
        val r = OutputRing()
        r.append("abcdefgh", Jobs.Channel.STDOUT, false, 0)
        assertEquals(1, r.retained)
        assertEquals("h", r.readFrom(0).first.single().text)
    }

    // -------------------------------------------------------------------- 读取

    /** offset 落在某块内部时**整块**返回（dsh 的同一条规则：调用方自己按偏移裁） */
    @Test
    fun `从块中间读也返回整块`() {
        val r = ring("abc", "def")
        val chunks = r.readFrom(1).first
        assertEquals(listOf("abc", "def"), chunks.map { it.text })
        assertEquals(listOf(0, 3), chunks.map { it.at })
    }

    @Test
    fun `读到末尾：没有块，也不算 lossy`() {
        val r = ring("abc")
        val (chunks, lossy) = r.readFrom(r.total)
        assertTrue(chunks.isEmpty())
        assertFalse(lossy)
    }

    @Test
    fun `lossy 判据：只有落在 earliest 之前才算`() {
        val r = OutputRing()
        r.append("abc", Jobs.Channel.STDOUT, false, 5)
        r.append("def", Jobs.Channel.STDOUT, false, 5)   // earliest 推到 3
        assertTrue(r.readFrom(0).second)
        assertFalse(r.readFrom(3).second)
        assertFalse(r.readFrom(4).second)
    }

    @Test
    fun `空环：earliest 与 total 都是 0`() {
        val r = OutputRing()
        assertEquals(0, r.total)
        assertEquals(0, r.earliest)
        assertFalse(r.readFrom(0).second)
    }

    // ------------------------------------------------------- 结算保留与 UTF-8 尾

    /** 模型还没读过的字节一定留到它第一次终态读（dsh 的 max(settled, total - cursor)） */
    @Test
    fun `结算保留上限：以模型游标没读过的那段为准`() {
        assertEquals(100_000, settleRetainCap(100_000, 0))
        assertEquals(50_000, settleRetainCap(100_000, 50_000))
        assertEquals(Jobs.SETTLED_RETAIN_BYTES, settleRetainCap(100_000, 99_000))
        assertEquals(Jobs.SETTLED_RETAIN_BYTES, settleRetainCap(1024, 1024))
        // 游标被推到 total 之后（理论上不该发生）也不会低于下限
        assertEquals(Jobs.SETTLED_RETAIN_BYTES, settleRetainCap(1024, 4096))
    }

    @Test
    fun `utf8Tail：整份、空、普通 ASCII 的切法`() {
        val raw = "abcdefgh".toByteArray()
        assertArrayEquals(raw, utf8Tail(raw, 8))
        assertArrayEquals(raw, utf8Tail(raw, 100))
        assertArrayEquals(ByteArray(0), utf8Tail(raw, 0))
        assertArrayEquals("fgh".toByteArray(), utf8Tail(raw, 3))
        assertArrayEquals("gh".toByteArray(), utf8Tail(raw, 2))
    }

    @Test
    fun `utf8Tail：切在续字节上就往后跳`() {
        val raw = "中文".toByteArray()   // E4 B8 AD E6 96 87
        assertEquals(6, raw.size)
        assertArrayEquals("文".toByteArray(), utf8Tail(raw, 5))
        assertArrayEquals("文".toByteArray(), utf8Tail(raw, 4))
        assertArrayEquals("文".toByteArray(), utf8Tail(raw, 3))
        assertArrayEquals(ByteArray(0), utf8Tail(raw, 2))   // 2 个字节落在「文」的中间 → 跳过 → 空
    }
}
