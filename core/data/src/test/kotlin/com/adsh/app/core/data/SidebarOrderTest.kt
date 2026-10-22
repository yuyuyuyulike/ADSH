package com.adsh.app.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 侧栏行序：置顶行整体提前，置顶组与非置顶组内部各按 updatedAt 倒序，同值稳定。
 *
 * 入参照 [ConversationRepository.conversations] 的行序（updatedAt 倒序）给。
 */
class SidebarOrderTest {

    private fun conversation(id: Long, updatedAt: Long, pinned: Boolean = false) = ConversationEntity(
        id = id,
        title = "会话 " + id,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        pinned = pinned,
    )

    private fun ids(rows: List<ConversationEntity>): List<Long> = rows.map { it.id }

    @Test
    fun `空表还是空表`() {
        assertEquals(emptyList<ConversationEntity>(), sidebarConversationOrder(emptyList()))
    }

    @Test
    fun `置顶行整体排到最前`() {
        val rows = listOf(
            conversation(3, 300, pinned = true),
            conversation(2, 200),
            conversation(1, 100, pinned = true),
        )
        assertEquals(listOf(3L, 1L, 2L), ids(sidebarConversationOrder(rows)))
    }

    @Test
    fun `两组内部都按 updatedAt 倒序`() {
        val rows = listOf(
            conversation(1, 100, pinned = true),
            conversation(2, 300),
            conversation(3, 200, pinned = true),
            conversation(4, 400),
        )
        assertEquals(listOf(3L, 1L, 4L, 2L), ids(sidebarConversationOrder(rows)))
    }

    @Test
    fun `同一分区内 updatedAt 相同则保持入参顺序`() {
        val rows = listOf(
            conversation(1, 500, pinned = true),
            conversation(2, 500),
            conversation(3, 500, pinned = true),
            conversation(4, 500),
        )
        assertEquals(listOf(1L, 3L, 2L, 4L), ids(sidebarConversationOrder(rows)))
    }

    @Test
    fun `没有置顶行时原顺序不动`() {
        val rows = listOf(
            conversation(9, 900),
            conversation(8, 800),
            conversation(7, 700),
        )
        assertEquals(listOf(9L, 8L, 7L), ids(sidebarConversationOrder(rows)))
    }
}
