package com.adsh.app.ui.blocks

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import com.adsh.app.ui.theme.AdshTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 老式二进制 .doc 的浏览已移除：入口一律落到「不支持」那一屏，并给「用其他应用打开」。
 * 这里守的是「别又悄悄接回一个解析器」—— .doc 的合并格 / 图片 / OLE 在设备上无法可靠还原。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DocUnsupportedTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `老式 doc 走不支持那一屏并给其他应用打开`() {
        compose.setContent {
            AdshTheme {
                FilePreviewBody(path = "/tmp/legacy-sample.doc")
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("legacy Office format", substring = true).onFirst().assertExists()
        compose.onAllNodesWithText("another app", substring = true).onFirst().assertExists()
    }

    @Test
    fun `不支持说明点明 doc-xls-ppt 与 Office 引擎`() {
        val note = unsupportedNoteOf("doc")
        assertTrue(note, note != null && note.contains(".doc") && note.contains("Office engine"))
        assertTrue(unsupportedNoteOf("xls")!!.contains(".xls"))
        assertTrue(unsupportedNoteOf("ppt")!!.contains(".ppt"))
    }
}
