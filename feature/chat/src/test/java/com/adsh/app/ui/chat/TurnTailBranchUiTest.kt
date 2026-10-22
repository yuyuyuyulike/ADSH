package com.adsh.app.ui.chat

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.adsh.app.ui.theme.AdshTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 轮尾分支按钮的可见 / 禁用形态（dsh 的 MessageIconActions）：每个已完成轮次都画；
 * 不可用时**可见但变淡、不可点**，原因写在 contentDescription 里（Android 没有 hover tooltip）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TurnTailBranchUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val enabledLabel = "在新对话中分支"
    private val unavailableLabel = "仅可从已完成轮次的最后一条消息分支"

    /** @param tailAfterAnswer 收尾回答之后还有一行（dsh 的 hasLaterChatNode） */
    private fun view(answer: Boolean, tailAfterAnswer: Boolean = false, closed: Boolean = true): TurnView {
        val entries = buildList {
            if (answer) add(ProcessEntry.Text("答案"))
            if (tailAfterAnswer) add(ProcessEntry.Text("后记"))
        }
        return TurnView(
            key = 1L,
            entries = entries,
            answerIndex = if (answer) 0 else -1,
            closed = closed,
            lastMessageId = if (answer) (if (tailAfterAnswer) 3L else 2L) else 1L,
            closingMessageId = if (answer) 2L else null,
        )
    }

    private fun show(turn: TurnView) {
        compose.setContent {
            AdshTheme { TurnTailRow(view = turn, onBranch = {}) }
        }
    }

    @Test
    fun `已完成轮次的分支按钮可见`() {
        show(view(answer = true))
        compose.onNodeWithContentDescription(enabledLabel).assertIsDisplayed()
    }

    @Test
    fun `回答之后还有内容时按钮可见但禁用`() {
        show(view(answer = true, tailAfterAnswer = true))
        compose.onNodeWithContentDescription(unavailableLabel).assertExists()
        compose.onNodeWithContentDescription(enabledLabel).assertDoesNotExist()
    }

    @Test
    fun `没有收尾回答时整颗按钮不画`() {
        show(view(answer = false, closed = true))
        compose.onNodeWithContentDescription(enabledLabel).assertDoesNotExist()
        compose.onNodeWithContentDescription(unavailableLabel).assertDoesNotExist()
    }
}
