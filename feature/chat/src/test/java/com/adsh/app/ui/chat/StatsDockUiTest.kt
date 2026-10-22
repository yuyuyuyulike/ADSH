package com.adsh.app.ui.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.adsh.app.core.llm.SessionStats
import com.adsh.app.ui.theme.AdshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 输入框下方胶囊行**只影响自己那一行**：数字 / token 变化只改宽度，行高（= footer 高度的一部分）
 * 一动不动 —— 行高一变就会经底部内边距把会话流顶一下。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StatsDockUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun updatingNumbersChangesTheRowWithoutMovingIt() {
        var stats by mutableStateOf(
            SessionStats(
                turns = 1,
                steps = 2,
                promptTokens = 1_000,
                completionTokens = 500,
                llmMillis = 4_000,
                ttftMillis = 1_000,
            ),
        )
        compose.setContent {
            AdshTheme {
                ChatStatsDock(
                    stats = stats,
                    context = ContextUsage(window = 1_000_000),
                    open = null,
                    onToggle = {},
                    // 会话载入那一帧：必须瞬间出现（没有入场动画），否则高度会从 0 长出来
                    animateEntrance = false,
                )
            }
        }
        compose.waitForIdle()

        val statsPill = compose.onNodeWithContentDescription("会话统计")
        val tokensPill = compose.onNodeWithContentDescription("Token 用量")
        val beforeTop = statsPill.getUnclippedBoundsInRoot().top
        val beforeTokensTop = tokensPill.getUnclippedBoundsInRoot().top

        // 一轮跑下去：数字变长（宽度会变），行高不许变
        stats = stats.copy(promptTokens = 123_456, completionTokens = 65_432, toolMillis = 9_000)
        compose.waitForIdle()

        val afterTop = statsPill.getUnclippedBoundsInRoot().top
        val afterTokensTop = tokensPill.getUnclippedBoundsInRoot().top
        assertEquals("统计胶囊的行位置不许动", beforeTop.value, afterTop.value, 0.5f)
        assertEquals("用量胶囊的行位置不许动", beforeTokensTop.value, afterTokensTop.value, 0.5f)
        assertTrue("行必须还在", afterTop.value >= 0f)
        // 高度同一行的证据：两个胶囊的顶边差不变（同样的行高、同样的居中对齐）
        assertEquals(
            "同一行内的相对位置不许变",
            (beforeTokensTop - beforeTop).value,
            (afterTokensTop - afterTop).value,
            0.5f,
        )
    }
}
