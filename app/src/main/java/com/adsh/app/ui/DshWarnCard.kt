package com.adsh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette

/**
 * 警示卡容器：**审批卡与计划待审卡用的是同一个壳**（第 2 阶段熵减前，两张卡各自抄了 19 行）。
 *
 * 壳 = 外层的警示色描边卡 + 顶上的圆点标题条 + 可滚动正文槽 + 底部动作槽：
 *
 *  - 外卡：`border 1dp state-warn-secondary(45%)` + `--dsw-specific-input-major` 底 + 圆角 16，
 *    两侧 10dp、底部 6dp 的外边距（dsh 的 .frame）；
 *  - 标题条 strip：warnBg 底 + 8dp 圆点 + 13/18 warnLabel 文案，横向 16dp、纵向 10dp；
 *  - 正文槽：`heightIn(max)` 封顶 + 卡片内滚动（滚到头之后剩下的位移/惯性留在卡片里，
 *    见 [ScrollEdgeEater]）—— 内边距由调用方给，两张卡的正文内边距本来就不一样；
 *  - 动作槽（[footer]，可选）：右下角一排按钮，布局与内边距由调用方决定。
 *
 * 之所以把 maxBodyHeight 也收进来：两个调用点都在算同一句
 * `minOf(520, screenHeightDp * 0.6f)`（dsh 的 `max-height:min(60vh,520px)`），抄两遍迟早分叉。
 */
@Composable
fun DshWarnCard(
    headerText: String,
    modifier: Modifier = Modifier,
    bodyModifier: Modifier = Modifier,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalDshPalette.current
    val configuration = LocalConfiguration.current
    val cardShape = RoundedCornerShape(16.dp)
    val stripShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val maxBodyHeight = minOf(520, (configuration.screenHeightDp * 0.6f).toInt()).dp

    Column(modifier.fillMaxWidth().padding(horizontal = DshSpacing.Xxl).padding(bottom = DshSpacing.Lg)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(palette.inputMajor)
                .border(1.dp, palette.warnLabel.copy(alpha = 0.45f), cardShape),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(stripShape)
                    .background(palette.warnBg)
                    .padding(horizontal = DshSpacing.Card, vertical = DshSpacing.Xxl),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(palette.warnLabel))
                Text(headerText, fontSize = 13.sp, lineHeight = 18.sp, color = palette.warnLabel)
            }
            Column(
                bodyModifier
                    .fillMaxWidth()
                    .heightIn(max = maxBodyHeight)
                    // 卡片滚到头之后剩下的位移/惯性留在卡片里（第 115 轮，见 ScrollEdgeEater）
                    .nestedScroll(ScrollEdgeEater)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
                content = body,
            )
            footer?.invoke(this)
        }
    }
}