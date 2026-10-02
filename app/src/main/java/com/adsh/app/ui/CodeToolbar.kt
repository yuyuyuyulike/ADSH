package com.adsh.app.ui

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.coroutines.delay

/*
 * 代码卡横幅（dsh 的 CodeToolbar，ui-primitives/src/CodeToolbar.tsx）的聊天行变体。
 *
 * dsh 的 CodeCard 是一条「刘海 + 正文」的结构：顶上一条 .header（padding 10px 18px 8px 22px）
 * 左起语言标签、最右是动作按钮；下面是正文。diff 卡（DiffBlock）与行号卡（ReadBlock）
 * 都用这条横幅，动作是 24×24 的图标按钮 —— 复制（IconCopyOutlineRegular）成功后换成勾
 * （IconCheckOutlineRegular），1 秒后还原；aria-label 用 zh 字典的 '复制' / '复制成功'。
 *
 * 手机侧偏离只有一条：dsh 的横幅还有「自动换行 / 取消自动换行」那个切换按钮，用来在
 * 横向滚动与折行之间切；手机的正文恒为折行（横滚会和根层抽屉的横向拖动抢手势），
 * 没有可切的状态，所以不画那个按钮。终端卡（TerminalBlock）不用这里的图标按钮 ——
 * dsh 的终端横幅复制按钮本来就是文字（'复制' / '复制成功'）。
 */

/** dsh 的 codeBlock.title（zh 字典）：语言认不出来时横幅上显示的标签 */
internal const val CODE_BLOCK_LABEL = "代码块"

/** 写入剪贴板（dsh 的 writeClipboard；标签统一 "adsh"，与各处老代码一致） */
internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("adsh", text))
}

/**
 * 「刚复制过」的瞬时状态：置 true 后 1 秒自动复位。
 *
 * 第 94 轮把原先 6 处逐字相同的 `var copied by remember { mutableStateOf(false) }` +
 * `LaunchedEffect(copied) { if (copied) { delay(1000); copied = false } }` 合并到这里 ——
 * 复制按钮的反馈时长只该有一个出处。
 */
@Composable
internal fun rememberCopiedFlag(): MutableState<Boolean> {
    val copied = remember { mutableStateOf(false) }
    LaunchedEffect(copied.value) {
        if (copied.value) {
            delay(1000)
            copied.value = false
        }
    }
    return copied
}

/**
 * 横幅最右的复制动作（dsh 的 .action：24×24、圆角、无底色，图标 14px）。
 *
 * @param payload 点下去要写进剪贴板的文本（**原文**，不是屏幕上折中显示的那几行）。
 *   传**函数**而不是字符串：卡片每一帧都会重组，正文拼接不该每帧算一遍。
 */
@Composable
internal fun RailCopyAction(payload: () -> String, modifier: Modifier = Modifier) {
    val palette = LocalDshPalette.current
    val context = LocalContext.current
    var copied by rememberCopiedFlag()
    Box(
        modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                copyToClipboard(context, payload())
                copied = true
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
            contentDescription = if (copied) "复制成功" else "复制",
            modifier = Modifier.size(14.dp),
            tint = palette.labelSecondary,
        )
    }
}
