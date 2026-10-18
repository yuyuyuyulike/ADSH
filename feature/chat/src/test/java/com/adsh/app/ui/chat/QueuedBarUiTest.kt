package com.adsh.app.ui.chat

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsh.app.ui.theme.AdshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 界面测试底座的样本：Robolectric 在 JVM 上真跑一次组合，Compose ui-test 断言语义树里
 * 真实存在的节点。以后新增界面测试照这个形状写，不需要模拟器。
 *
 * Application 换成裸的，且只换在本类：AdshApp.onCreate 会 loadLibrary(quickjs-android-wrapper)，
 * 宿主 JVM 上没有这个 .so；界面测试不依赖应用级初始化。不写进 robolectric.properties，
 * 免得把 native/QuickJS 这类真问题在别的测试里一起盖住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QueuedBarUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun queuedBarShowsCountAndAction() {
        compose.setContent {
            AdshTheme { QueuedBar(count = 3, onClear = {}) }
        }

        compose.onNodeWithText("已排队 3 条，本轮结束后发出").assertIsDisplayed()
        compose.onNodeWithText("清空").assertIsDisplayed()
    }

    @Test
    fun clearLabelInvokesCallback() {
        var cleared = 0
        compose.setContent {
            AdshTheme { QueuedBar(count = 1, onClear = { cleared++ }) }
        }

        compose.onNodeWithText("清空").performClick()

        assertEquals(1, cleared)
    }
}
