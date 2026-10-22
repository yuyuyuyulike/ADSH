package com.adsh.app.ui.blocks

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
import com.adsh.app.ui.kit.DshSpacing

/**
 * 警示卡容器：**审批卡与计划待审卡用的是同一个壳** —— 外层警示色描边卡 + 顶上圆点标题条 + 可滚动正文槽
 * + 底部动作槽。外卡 `border 1dp state-warn-secondary(45%)` + `--dsw-specific-input-major` 底 + 圆角 16、
 * 两侧 10dp / 底部 6dp 外边距；标题条 warnBg + 8dp 圆点 + 13/18 warnLabel。maxBodyHeight 由两处共用。
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
    val bodyScroll = rememberScrollState()

    Column(modifier.fillMaxWidth().padding(horizontal = DshSpacing.Xxl).padding(bottom = DshSpacing.Lg)) {
        Column(
            Modifier
                .fillMaxWidth()
                .lightCardShadow(cardShape)
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
                    .dshVerticalScrollbar(bodyScroll, elevated = true)
                    .verticalScroll(bodyScroll),
                verticalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
                content = body,
            )
            footer?.invoke(this)
        }
    }
}
