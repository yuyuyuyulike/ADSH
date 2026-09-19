package com.adsh.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsh.app.R

/**
 * 图标按钮（对齐 dsh）：无涟漪、无背景、点击范围就是图标本身。
 * Compose 的 IconButton 默认 48dp 触摸区 + 涟漪反馈，dsh 里两者都没有。
 */
@Composable
fun IconTap(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    // dsh 的图标一律 currentColor = --dsw-alias-label-primary；这里不取 LocalContentColor，
    // 因为 Surface 遇到自定义底色会把 LocalContentColor 置成 Unspecified（深色下就画成黑的）
    tint: Color = com.adsh.app.ui.theme.LocalDshPalette.current.labelPrimary,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
            .size(size)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    )
}

/** 官方鲸鱼标识（路径取自 dsh 前端产物中的 FISH_LOGO_PATH） */
@Composable
fun DshWhale(modifier: Modifier = Modifier, width: Dp = 46.dp, tint: Color = LocalContentColor.current) {
    Image(
        painter = painterResource(R.drawable.ic_dsh_whale),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        contentScale = ContentScale.Fit,
        modifier = modifier.size(width = width, height = width * 17.04f / 23.16f),
    )
}
