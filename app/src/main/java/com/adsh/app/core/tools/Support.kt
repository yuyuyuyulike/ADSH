package com.adsh.app.core.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

// ---------------------------------------------------------------- todo

data class TodoItem(val content: String, val status: String)

/** 任务列表（MVP 为会话内内存态；M4 会接到会话持久化） */
object TodoStore {
    private val _items = MutableStateFlow<List<TodoItem>>(emptyList())
    val items: StateFlow<List<TodoItem>> = _items.asStateFlow()

    fun replace(newItems: List<TodoItem>) {
        _items.value = newItems
    }

    /** 输入框上方的任务横窗上的「清除」（dsh 的 TodoPanel 没有这个动作，是 ADSH 自己加的） */
    fun clear() {
        _items.value = emptyList()
    }

    fun render(): String = if (_items.value.isEmpty()) {
        "(空)"
    } else {
        _items.value.joinToString("\n") { "- [" + statusMark(it.status) + "] " + it.content }
    }

    private fun statusMark(status: String) = when (status) {
        "completed" -> "x"
        "in_progress" -> "/"
        else -> " "
    }
}

// ---------------------------------------------------------------- present

data class Deliverable(val name: String, val path: String, val note: String?, val at: Long)

/** 交付物登记表（供 M4 的交付物面板消费） */
object DeliverableRegistry {
    private val _items = MutableStateFlow<List<Deliverable>>(emptyList())
    val items: StateFlow<List<Deliverable>> = _items.asStateFlow()

    fun add(item: Deliverable) {
        _items.value = _items.value.filterNot { it.path == item.path } + item
    }

    fun clear() {
        _items.value = emptyList()
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
