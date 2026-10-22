package com.adsh.app.ui.chat

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsh.app.ui.theme.AdshTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 会话流里的模型重试行（dsh 的 model-retry）：默认折叠、状态文案与展开体的两行明细。
 * 底座的注意点与 QueuedBarUiTest 相同（Application 换成裸的，不跑 AdshApp.onCreate）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RetryRowUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(info: RetryInfo, turnClosed: Boolean = false) {
        compose.setContent {
            AdshTheme { RetryRow(info = info, turnClosed = turnClosed) }
        }
    }

    private val scheduled = RetryInfo(
        retry = 1,
        maximum = 2,
        delayMs = 2_500L,
        failure = "连接被重置",
        started = false,
    )

    @Test
    fun `等待中显示带倒计时的状态行`() {
        show(scheduled)
        // 倒计时每秒在走：只断言前缀，不钉具体秒数
        compose.onNodeWithText("正在重试模型请求（1/2） · ", substring = true).assertIsDisplayed()
    }

    @Test
    fun `已重试是静态状态行`() {
        show(scheduled.copy(retry = 2, delayMs = 3_500L, started = true))
        compose.onNodeWithText("已重试模型请求（2/2） · 4s").assertIsDisplayed()
    }

    @Test
    fun `已取消是静态状态行`() {
        show(scheduled.copy(delayMs = 3_500L), turnClosed = true)
        compose.onNodeWithText("模型请求重试已取消（1/2） · 4s").assertIsDisplayed()
    }

    @Test
    fun `默认折叠，点开后显示延迟与失败原因`() {
        show(scheduled)
        compose.onNodeWithText("重试延迟：", substring = true).assertDoesNotExist()
        compose.onNode(hasClickAction()).performClick()
        compose.onNodeWithText("重试延迟：2500毫秒").assertIsDisplayed()
        compose.onNodeWithText("失败原因：连接被重置").assertIsDisplayed()
    }
}
