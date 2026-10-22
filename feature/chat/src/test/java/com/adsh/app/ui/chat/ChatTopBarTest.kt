package com.adsh.app.ui.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.adsh.app.ui.theme.AdshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 双击顶栏（刘海那一行）= 回到**当前**会话开头。
 *
 * 回归：`pointerInput(Unit)` 的手势协程只启动一次，回调若是组合时捕获的闭包就不会随重组更新 ——
 * 切会话后双击仍去滚上一条会话那份已经卸载的 ScrollState，真机表现是「切换过任意会话后双击完全没反应」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChatTopBarTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun doubleTapUsesTheCallbackOfTheConversationOnScreen() {
        var generation by mutableIntStateOf(0)
        var taps by mutableIntStateOf(0)
        compose.setContent {
            // 组合时捕获这一代的值：旧闭包必须拿旧值，新闭包必须拿新值
            val gen = generation
            AdshTheme {
                ChatTopBar(
                    title = "会话 " + gen,
                    conversationId = gen.toLong(),
                    readerAction = {},
                    onOpenWorkspaceFiles = {},
                    onScrollToTop = { taps += gen + 1 },
                )
            }
        }

        fun doubleTapTopBar() = compose.onRoot().performTouchInput {
            doubleClick(Offset(width / 2f, 20.dp.toPx()))
        }

        doubleTapTopBar()
        compose.waitForIdle()
        assertEquals(1, taps)

        // 换一条会话：重新组合，但顶栏节点位置不变（pointerInput 的 key 也不变）
        compose.runOnIdle { generation = 1 }
        doubleTapTopBar()
        compose.waitForIdle()

        // 命中的必须是第 1 号会话的回调（1 + 2）；旧实现会再加一次 1、停在 2
        assertEquals(3, taps)
    }
}
