package com.adsh.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * todo_write / present 两张卡（第九十轮）的纯逻辑部分。
 *
 * 这两张卡的行摘要与清单差异都是**纯函数**（dsh 的 plan-summary.ts / todo-diff-model.ts /
 * PresentRow 的 fileNames），而且判据容易错在细节上：状态词、差异字形、"上一次清单"取哪一条、
 * 或把流式半截 JSON 当成合法清单。桌面单测直接打这几个函数。
 */
class TodoPresentCardTest {

    private fun todos(vararg items: Pair<String, String>): String =
        items.joinToString(",", prefix = "{\"todos\":[", postfix = "]}") { (content, status) ->
            "{\"content\":\"" + content + "\",\"status\":\"" + status + "\"}"
        }

    // ---------------------------------------------------------------- todo：解析

    @Test
    fun todoItemsRejectMalformedLists() {
        assertNull(todoItemsOf(""))
        assertNull(todoItemsOf("{\"todos\":\"x\"}"))
        assertNull(todoItemsOf(todos("a" to "completed", "a" to "pending")))
        assertNull(todoItemsOf(todos("a" to "done")))
        assertNull(todoItemsOf(todos("  " to "pending")))
        assertEquals(
            listOf("a" to "in_progress", "b" to "pending"),
            todoItemsOf(todos("a" to "in_progress", "b" to "pending")),
        )
    }

    // ---------------------------------------------------------------- todo：差异

    /** 找不到上一次清单（旧会话 / 第一条）：只画当前清单，挂 dsh 的「旧清单不可用」 */
    @Test
    fun todoWithoutBaselineShowsThePlainList() {
        val (summary, card) = todoDiffOf(todoItemsOf(todos("a" to "pending", "b" to "completed"))!!, null)
        assertEquals("旧清单不可用", card.caption)
        assertEquals("", summary)
        assertEquals(listOf("a" to "pending", "b" to "completed"), card.items.map { it.content to it.status })
        assertTrue(card.items.all { it.change == null })
        assertTrue(card.unchanged.isEmpty())
    }

    @Test
    fun todoFirstRecordMarksEverythingAdded() {
        val (summary, card) = todoDiffOf(todoItemsOf(todos("a" to "pending"))!!, todos())
        assertEquals("首次记录", card.caption)
        assertEquals(listOf(TodoCardItem("a", "pending", change = "added")), card.items)
        assertEquals("新增 1", summary)
    }

    @Test
    fun todoDiffSplitsAddedUpdatedRemovedAndUnchanged() {
        val baseline = todos("a" to "pending", "b" to "pending", "c" to "pending")
        val current = todos("a" to "completed", "b" to "pending", "d" to "in_progress")
        val (summary, card) = todoDiffOf(todoItemsOf(current)!!, baseline)
        assertEquals("与上次清单相比", card.caption)
        // a 状态变了、d 新增、c 移除；b 原样 → 收进「未变化」
        assertEquals(listOf("a", "d", "c"), card.items.map { it.content })
        assertEquals("updated", card.items[0].change)
        assertEquals("待处理", card.items[0].previousStatus)
        assertEquals("added", card.items[1].change)
        assertEquals("removed", card.items[2].change)
        assertEquals(listOf("b"), card.unchanged.map { it.content })
        assertEquals("新增 1 · 更新 1 · 移除 1", summary)
    }

    /** 只是顺序挪了：算「更新」，但没有 previousStatus（dsh 的 movedItem） */
    @Test
    fun todoReorderedItemCountsAsUpdatedWithoutPreviousStatus() {
        val (summary, card) = todoDiffOf(
            todoItemsOf(todos("b" to "pending", "a" to "pending"))!!,
            todos("a" to "pending", "b" to "pending"),
        )
        assertTrue(card.unchanged.isEmpty())
        assertEquals(listOf("b", "a"), card.items.map { it.content })
        assertTrue(card.items.all { it.change == "updated" && it.previousStatus == null })
        assertEquals("更新 2", summary)
    }

    @Test
    fun todoUnchangedListSaysNoChanges() {
        val same = todos("a" to "pending")
        val (summary, card) = todoDiffOf(todoItemsOf(same)!!, same)
        assertTrue(card.items.isEmpty())
        assertEquals(listOf("a"), card.unchanged.map { it.content })
        assertEquals("清单没有变化", summary)
    }

    // ---------------------------------------------------------------- todo：行摘要

    @Test
    fun todoSummaryCountsDoneAndNamesTheActiveItem() {
        assertEquals("4/4 已完成", todoRowSummary(todos("a" to "completed", "b" to "completed", "c" to "completed", "d" to "completed"), null))
        assertEquals(
            "1/3 已完成 · 正在做的事",
            todoRowSummary(todos("a" to "completed", "正在做的事" to "in_progress", "c" to "pending"), null),
        )
        // 多个进行中：命名第一条，剩下的算 +N（dsh 的 activeExtra）
        assertEquals(
            "0/2 已完成 · 甲 · +1",
            todoRowSummary(todos("甲" to "in_progress", "乙" to "in_progress"), null),
        )
    }

    @Test
    fun todoSummaryAppendsTheDiffAgainstTheBaseline() {
        val baseline = todos("a" to "pending", "b" to "pending")
        val current = todos("a" to "completed", "b" to "pending")
        assertEquals("1/2 已完成 · 更新 1", todoRowSummary(current, baseline))
        assertNull(todoRowSummary("{\"todos\":", baseline))
    }

    // ---------------------------------------------------------------- present

    @Test
    fun presentSummaryListsFileNames() {
        val args = "{\"files\":[{\"path\":\"/storage/emulated/0/1/App/工具自测报告.md\"," +
            "\"description\":\"结论\"},{\"path\":\"/storage/emulated/0/1/App/tool-selftest.txt\"}]}"
        assertEquals("已交付 · 工具自测报告.md, tool-selftest.txt", presentRowSummary(args, false, false))
        assertEquals("正在交付 · 工具自测报告.md, tool-selftest.txt", presentRowSummary(args, true, false))
        assertEquals("交付失败 · 工具自测报告.md, tool-selftest.txt", presentRowSummary(args, false, true))
    }

    /**
     * 流式半截 JSON：容错解析能把 `path` 抠出来就显示它的文件名（dsh 是「结果没出来就把参数原文
     * 显示着」），抠不出来就只剩状态词，绝不崩。
     */
    @Test
    fun presentSummarySurvivesPartialArguments() {
        assertEquals("已交付 · a", presentRowSummary("{\"files\":[{\"path\":\"/a/", false, false))
        assertEquals("已交付", presentRowSummary("{}", false, false))
        assertEquals("已交付", presentRowSummary("{\"files\":\"x\"}", false, false))
    }
}
