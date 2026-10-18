package com.adsh.app.core.tools

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Flow 测试底座的样本：Turbine 按事件顺序断言 [TodoStore] 的 StateFlow。
 * 用真的生产 Flow，不另造一个只为测试存在的发射器。
 */
class TodoStoreFlowTest {

    @Test
    fun flowEmitsEveryTodoChange() = runTest {
        val id = 9101L
        TodoStore.forget(id)

        TodoStore.flow(id).test {
            assertEquals(emptyList<TodoItem>(), awaitItem())

            TodoStore.replace(id, listOf(TodoItem("改代码", "in_progress")))
            assertEquals(listOf(TodoItem("改代码", "in_progress")), awaitItem())

            TodoStore.clear(id)
            assertEquals(emptyList<TodoItem>(), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
