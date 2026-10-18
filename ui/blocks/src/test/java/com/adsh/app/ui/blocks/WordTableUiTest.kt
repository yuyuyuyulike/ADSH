package com.adsh.app.ui.blocks

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import com.adsh.app.ui.theme.AdshTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Word 表格的界面回归：Compose 的 Measurable 一次测量只能量一次 —— 曾经为了算行高把同一格
 * 量了两遍，真机上「打开表格文档 / 滑动一下」直接崩在 measure 里（LazyColumn 预取也会量）。
 * 这里把表格放进 LazyColumn，让预取真的跑到，再断言内容在语义树里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WordTableUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `表格在 LazyColumn 里渲染不崩且内容可见`() {
        val table = sampleTable()
        compose.setContent {
            AdshTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(4) { WordTableBlock(table, scale = 1f) }
                }
            }
        }
        compose.waitForIdle()
        // 四份同样的表：用 onAllNodes 取第一份（能走到这里就说明 measure 没崩；
        // 下面几行可能在可视区外，只断言存在）
        compose.onAllNodesWithText("表头").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("纵向").onFirst().assertExists()
        compose.onAllNodesWithText("右下").onFirst().assertExists()
    }

    @Test
    fun `一行格子多于列数时按列数截断不越界`() {
        val table = WordBlock.Table(
            grid = List(60) { 100 },
            widthTw = null,
            align = WordAlign.LEFT,
            indentTw = 0,
            borders = WordBorderEdge.values().associateWith { WordBorderSpec(4, null) },
            cellMarginTw = 108,
            rows = listOf(WordRow(List(61) { index -> cell("格" + index) })),
        )
        compose.setContent {
            AdshTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(2) { WordTableBlock(table, scale = 1f) }
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("格0").onFirst().assertExists()
    }

    private fun para(text: String): WordPara = WordPara(
        inlines = listOf(WordInline.Text(WordRun(text, sizePt = 10.5f))),
        sizePt = 10.5f,
    )

    private fun cell(
        text: String,
        span: Int = 1,
        vContinue: Boolean = false,
    ): WordCell = WordCell(
        paragraphs = if (text.isEmpty()) emptyList() else listOf(para(text)),
        span = span,
        vContinue = vContinue,
    )

    private fun sampleTable(): WordBlock.Table = WordBlock.Table(
        grid = listOf(2000, 3000, 2000),
        widthTw = 7000,
        align = WordAlign.CENTER,
        indentTw = 0,
        borders = WordBorderEdge.values().associateWith { WordBorderSpec(4, null) },
        cellMarginTw = 108,
        rows = listOf(
            WordRow(listOf(cell("表头", span = 3))),
            WordRow(listOf(cell("纵向"), cell("中"), cell("右"))),
            WordRow(listOf(cell("", vContinue = true), cell("下"), cell("右下"))),
        ),
    )
}
