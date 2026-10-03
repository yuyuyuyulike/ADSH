package com.adsh.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务清单的**会话隔离**（第 71 轮用户实测：换个会话，上一个会话的任务横窗还挂在输入框上面）。
 *
 * 对齐 dsh 的 session projection「todos」：清单属于写它的那条会话，
 * 并且 `turn/start` 会把它清空。
 */
class TodoStoreTest {

    private val a = TodoItem("改代码", "in_progress")
    private val b = TodoItem("出包", "pending")

    @Test
    fun listsAreKeptPerConversation() {
        TodoStore.replace(9001L, listOf(a))
        TodoStore.replace(9002L, listOf(a, b))

        assertEquals(listOf(a), TodoStore.flow(9001L).value)
        assertEquals(listOf(a, b), TodoStore.flow(9002L).value)
        // 没写过的会话是空的：换到新会话时输入框上方不该有东西
        assertTrue(TodoStore.flow(9003L).value.isEmpty())
    }

    @Test
    fun clearOnlyAffectsItsOwnConversation() {
        TodoStore.replace(9011L, listOf(a))
        TodoStore.replace(9012L, listOf(a))

        TodoStore.clear(9011L)

        assertTrue(TodoStore.flow(9011L).value.isEmpty())
        assertEquals(listOf(a), TodoStore.flow(9012L).value)
    }

    @Test
    fun forgetDropsTheList() {
        TodoStore.replace(9021L, listOf(a))
        TodoStore.forget(9021L)

        assertTrue(TodoStore.flow(9021L).value.isEmpty())
    }
}
