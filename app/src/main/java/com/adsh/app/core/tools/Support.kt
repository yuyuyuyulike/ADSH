package com.adsh.app.core.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

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
 * 旧实现是一个**全局单例**：它不认会话，换个会话上一个会话的任务横窗还挂在输入框上面
 * （用户实测反馈）。现在按会话 id 分开，清单只在它自己的会话里出现，切换会话自然看不见；
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

// ---------------------------------------------------------------- ask user

data class QuestionOption(val label: String, val description: String? = null)

data class Question(
    val id: String,
    val question: String,
    val header: String? = null,
    /** dsh 的 detail：题目下方的 Markdown 详情（计划待审时就是整份计划） */
    val detail: String? = null,
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
    /** dsh 的 intent.kind：plan-review 时界面渲染成「计划待审」卡而不是选择题 */
    val intent: String? = null,
)

/** dsh-plan-mode 的计划审阅约定（REVIEW_ID / APPROVE_LABEL / KEEP_PLANNING_LABEL 逐字） */
object PlanReview {
    const val ID = "plan-review"
    const val APPROVE = "Approve"
    const val KEEP_PLANNING = "Keep planning"
    const val KIND = "plan-review"
}

data class Answer(val id: String, val selected: List<String>, val custom: String? = null)

/**
 * 提问通道：工具挂起等待，UI 观察 [pending] 并调用 [answer] 解除挂起。
 * 超时或 App 不在前台时返回失败（与 dsh 的 fail-closed 一致）。
 */
object UserQuestionChannel {

    data class Pending(
        val token: String,
        val questions: List<Question>,
        val completer: CompletableDeferred<List<Answer>?>,
    )

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /** @return 用户答案；null 表示超时/被跳过 */
    suspend fun ask(questions: List<Question>, timeoutMs: Long): List<Answer>? {
        val completer = CompletableDeferred<List<Answer>?>()
        val token = UUID.randomUUID().toString()
        _pending.value = Pending(token, questions, completer)
        try {
            return withTimeoutOrNull(timeoutMs) { completer.await() }
        } finally {
            _pending.value = null
        }
    }

    fun answer(answers: List<Answer>) {
        _pending.value?.completer?.complete(answers)
    }

    fun skip() {
        _pending.value?.completer?.complete(null)
    }
}
