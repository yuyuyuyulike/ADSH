package com.adsh.app.ui.chat

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsh.app.seam.exec.Permissions
import com.adsh.app.ui.theme.AdshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 没有绑定工作区的惰性输入框（dsh 的 .cardWorkspaceTrigger）：卡片没有可编辑的输入框，
 * 占位是 dsh 的 placeholder.workspace，整张卡点一下就是「打开工作区详情卡」。
 *
 * 界面测试底座见 QueuedBarUiTest（Robolectric 真跑组合，Application 换成裸的 ——
 * AdshApp.onCreate 会 loadLibrary，宿主 JVM 上没有那个 .so）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ComposerWorkspaceTriggerUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun composer(
        workspaceTrigger: Boolean,
        onRequestWorkspace: () -> Unit = {},
    ) {
        compose.setContent {
            AdshTheme {
                DshComposer(
                    draft = "",
                    onDraftChange = {},
                    sending = false,
                    busyEnter = "queue",
                    modelGroups = emptyList(),
                    currentModel = "deepseek-flash",
                    currentProviderId = "deepseek",
                    balances = emptyMap(),
                    onRefreshBalance = {},
                    efforts = emptyList(),
                    currentEffort = "",
                    onSelectModel = { _, _ -> },
                    onSelectEffort = {},
                    permission = Permissions.READ_ONLY,
                    onSelectPermission = {},
                    paletteCommands = emptyList(),
                    paletteVisible = false,
                    onPaletteVisibleChange = {},
                    onPaletteDismiss = {},
                    onSend = {},
                    onCancel = {},
                    planMode = false,
                    onTogglePlan = {},
                    attachments = emptyList(),
                    onRemoveAttachment = {},
                    requestPermission = false,
                    onRequestHandled = {},
                    showWorkspace = false,
                    workspaceTrigger = workspaceTrigger,
                    onRequestWorkspace = onRequestWorkspace,
                    workspaces = emptyList(),
                    workspaceId = null,
                    onPickWorkspace = {},
                    onAddWorkspace = {},
                    draftRevision = 0,
                    menu = null,
                    onMenuChange = {},
                )
            }
        }
    }

    @Test
    fun `惰性态没有输入框，占位是「选择一个工作区开始」`() {
        composer(workspaceTrigger = true)

        compose.onNodeWithText("选择一个工作区开始").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun `点整张卡就是打开工作区详情卡`() {
        var opened = 0
        composer(workspaceTrigger = true) { opened++ }

        compose.onAllNodesWithContentDescription("选择工作区")
            .filterToOne(hasClickAction())
            .performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `有工作区时输入框可编辑`() {
        composer(workspaceTrigger = false)

        assertEquals(1, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        compose.onNodeWithText(COMPOSER_HINT).assertIsDisplayed()
    }
}
