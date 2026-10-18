package com.adsh.app.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 官方鲸鱼标识（路径取自 dsh 前端产物中的 FISH_LOGO_PATH）；资源随本模块（res/drawable） */
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
