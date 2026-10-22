package com.adsh.app.core.agent.compaction

import com.adsh.app.core.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区间选择的表驱动用例：保留尾巴 16%、系统节点不进区间、不切开工具对。
 *
 * 「不切工具对」两个方向各一条：
 *  - 手动 / pressure：边界落在工具结果上就后移（工具对一起进 older）；
 *  - overflow 强制：边界前移到 assistant 调用（最新的工具对留在 keep）。
 */
class CompactionRangeTest {

    /** 非 CJK 字符每 4 个算 1 token：content 长度 = tokens * 4。 */
    private fun row(id: Long, role: String, tokens: Int, name: String? = null): MessageEntity =
        MessageEntity(
            id = id,
            conversationId = 1L,
            role = role,
            content = "x".repeat(tokens * 4),
            name = name,
            createdAt = 0,
        )

    @Test
    fun `保留尾巴按窗口 16 percent 累计、系统节点进 prefix`() {
        val history = listOf(
            row(1, "sysprompt", 100),
            row(2, "context", 100),
            row(3, "user", 100),
            row(4, "assistant", 100),
            row(5, "user", 100),
            row(6, "assistant", 100),
            row(7, "user", 100),
            row(8, "assistant", 100),
        )
        // window = 1000 → retain = 160 token：从尾部累计两行（200）就够
        val range = CompactionRange.select(history, window = 1000L)!!
        assertEquals(listOf(1L, 2L), range.prefix.map { it.id })
        assertEquals(listOf(3L, 4L, 5L, 6L), range.older.map { it.id })
        assertEquals(listOf(7L, 8L), range.keep.map { it.id })
    }

    @Test
    fun `保留边界落在工具行上时后移、工具对整组进 older`() {
        val history = listOf(
            row(1, "sysprompt", 100),
            row(2, "user", 100),
            row(3, "assistant", 100),
            row(4, "tool", 200, name = "run_code"),
        )
        // retain = 160：最后一行 200 token 已够 → 边界落在 tool 行 → 后移到末尾，keep 为空
        val range = CompactionRange.select(history, window = 1000L)!!
        assertEquals(listOf(2L, 3L, 4L), range.older.map { it.id })
        assertTrue("工具对整组进 older，keep 为空", range.keep.isEmpty())
    }

    @Test
    fun `没有可压内容时返回 null`() {
        assertNull(CompactionRange.select(emptyList(), 1000L))
        assertNull("窗口未知", CompactionRange.select(listOf(row(1, "user", 10)), 0L))
        assertNull(
            "全是系统节点",
            CompactionRange.select(listOf(row(1, "sysprompt", 10), row(2, "context", 10)), 1000L),
        )
        assertNull(
            "尾巴覆盖全部历史",
            CompactionRange.select(listOf(row(1, "sysprompt", 10), row(2, "user", 10)), 1000L),
        )
        assertNull(
            "older 里没有 user assistant 不值得摘要",
            CompactionRange.select(
                listOf(row(1, "sysprompt", 10), row(2, "tool", 100), row(3, "tool", 100)),
                1000L,
            ),
        )
    }

    @Test
    fun `强制区间只保留最后一条及其工具配对`() {
        val history = listOf(
            row(1, "sysprompt", 100),
            row(2, "context", 100),
            row(3, "user", 100),
            row(4, "assistant", 100),
            row(5, "tool", 100, name = "run_code"),
        )
        val range = CompactionRange.selectForced(history)!!
        assertEquals(listOf(1L, 2L), range.prefix.map { it.id })
        assertEquals(listOf(3L), range.older.map { it.id })
        assertEquals("边界前移到 assistant：最新工具结果留在模型眼前", listOf(4L, 5L), range.keep.map { it.id })
    }

    @Test
    fun `强制区间至少保留最后一条、没有可压内容时返回 null`() {
        assertNull(CompactionRange.selectForced(emptyList()))
        assertNull(
            "只有系统节点与最后一条",
            CompactionRange.selectForced(listOf(row(1, "sysprompt", 10), row(2, "user", 10))),
        )
        assertNull(
            "工具行前移到 floor 后没有可压区间",
            CompactionRange.selectForced(listOf(row(1, "sysprompt", 10), row(2, "tool", 10), row(3, "tool", 10))),
        )
    }
}
