package com.adsh.app.seam.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

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
