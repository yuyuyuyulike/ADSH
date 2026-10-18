package com.adsh.app.core.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ---------------------------------------------------------------- todo

data class TodoItem(val content: String, val status: String)

/**
 * 任务清单：**按会话存**，逐条对齐 dsh 的 session projection「todos」（dsh-tool-todo 的
 * `ctx.sessionProjections.register`）。
 *
 * dsh 那份 projection 只有两条规则：
 *  - `todo/write` 事件 → 整份替换（模型每次发的是完整清单，没有局部更新）；
 *  - `turn/start` 事件 → 清空（`apply` 里 `return null`）：新一轮一开始，旧清单就不再展示。
 *
 * 任务清单不能是**全局单例**：它不认会话的话，换个会话上一个会话的任务横窗还挂在输入框上面
 * （用户实测反馈）。按会话 id 分开，清单只在它自己的会话里出现，切换会话自然看不见；
 * 会话删掉时用 [forget] 一并丢掉，不留一份没人认领的列表。
 */
object TodoStore {

    private val byConversation = java.util.concurrent.ConcurrentHashMap<Long, MutableStateFlow<List<TodoItem>>>()

    private fun holder(conversationId: Long): MutableStateFlow<List<TodoItem>> =
        byConversation.computeIfAbsent(conversationId) { MutableStateFlow(emptyList()) }

    /** 某个会话的任务清单（没写过就是空） */
    fun flow(conversationId: Long): StateFlow<List<TodoItem>> = holder(conversationId)

    /** dsh 的 `todo/write`：整份替换 */
    fun replace(conversationId: Long, items: List<TodoItem>) {
        holder(conversationId).value = items
    }

    /** dsh 的 `turn/start`：新一轮开始，清单清空（界面上输入框上方那一栏随之收起） */
    fun clear(conversationId: Long) {
        byConversation[conversationId]?.value = emptyList()
    }

    /** 会话被删除：它的清单跟着一起丢掉 */
    fun forget(conversationId: Long) {
        byConversation.remove(conversationId)
    }
}

/** dsh-plan-mode 的计划审阅约定（REVIEW_ID / APPROVE_LABEL / KEEP_PLANNING_LABEL 逐字） */
object PlanReview {
    const val ID = "plan-review"
    const val APPROVE = "Approve"
    const val KEEP_PLANNING = "Keep planning"
    const val KIND = "plan-review"
}
