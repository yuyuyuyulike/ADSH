package com.adsh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette

/**
 * 文件类型图标（dsh 的 `FileTypeIcon`）：按扩展名给一枚带底色的角标，未知类型回退成灰色文件图标。
 *
 * 第八十四轮从 `FileTree.kt` 里搬出来共用 —— 工作区文件树、轮尾的改动卡片、交付物卡片
 * 三处都要它；以前交付物卡片用的是通用的文档图标，用户点名「文件的图标太单调」。
 */
private data class FileBadge(val label: String, val background: Color, val foreground: Color)

/** 文件类型角标：与工作区文件树的配色一致（JS 黄、MD 蓝、IMG 绿…），未知类型 null */
private fun badgeOf(name: String): FileBadge? = when (name.substringAfterLast('.', "").lowercase()) {
    "js", "mjs", "cjs", "jsx" -> FileBadge("JS", Color(0xFFF7DF1E), Color(0xFF0F1115))
    "ts", "tsx", "mts", "cts" -> FileBadge("TS", Color(0xFF3178C6), Color.White)
    "md", "markdown", "mdx" -> FileBadge("MD", Color(0xFF4D93F8), Color.White)
    "json" -> FileBadge("{}", Color(0xFFE8A33D), Color.White)
    "html", "htm" -> FileBadge("<>", Color(0xFFE44D26), Color.White)
    "css", "scss", "less" -> FileBadge("CSS", Color(0xFF2965F1), Color.White)
    "py" -> FileBadge("PY", Color(0xFF3572A5), Color.White)
    "sh", "bash", "zsh" -> FileBadge("SH", Color(0xFF4EAA25), Color.White)
    "kt", "kts" -> FileBadge("KT", Color(0xFF7F52FF), Color.White)
    "java" -> FileBadge("JV", Color(0xFFE76F00), Color.White)
    "go" -> FileBadge("GO", Color(0xFF00ADD8), Color.White)
    "rs" -> FileBadge("RS", Color(0xFFDEA584), Color(0xFF0F1115))
    "yml", "yaml" -> FileBadge("Y", Color(0xFFCB171E), Color.White)
    "xml" -> FileBadge("<>", Color(0xFF8A8A8A), Color.White)
    "toml", "ini", "cfg", "conf" -> FileBadge("=", Color(0xFF6B7280), Color.White)
    "png", "jpg", "jpeg", "webp", "gif" -> FileBadge("IMG", Color(0xFF10B981), Color.White)
    "zip", "gz", "tar", "7z" -> FileBadge("ZIP", Color(0xFF8B5CF6), Color.White)
    "pdf" -> FileBadge("PDF", Color(0xFFD93025), Color.White)
    "csv", "tsv" -> FileBadge("CSV", Color(0xFF0F9D58), Color.White)
    "txt", "log", "text" -> FileBadge("TXT", Color(0xFF6B7280), Color.White)
    else -> null
}

/** 角标底框：圆角方块 + 底色（dsh 的 FileTypeIcon 就是「一枚带底色的圆角角标」） */
@Composable
private fun BadgeBox(size: Dp, background: Color, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(if (size > 16.dp) 4.dp else 3.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 文件类型图标：已知类型给角标，未知类型给灰色文件图标 */
@Composable
internal fun FileTypeIcon(name: String, size: Dp = 16.dp) {
    val palette = LocalDshPalette.current
    val badge = badgeOf(name)
    if (badge == null) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = null,
            tint = palette.labelTertiary,
            modifier = Modifier.size(size),
        )
        return
    }
    BadgeBox(size, badge.background) {
        Text(
            text = badge.label,
            fontSize = when {
                badge.label.length > 2 -> (size.value * 0.34f).sp
                else -> (size.value * 0.44f).sp
            },
            fontWeight = FontWeight.Bold,
            color = badge.foreground,
            maxLines = 1,
        )
    }
}

/** dsh 的 `FileTypeIcon kind="code"` 的底色（与 MD 角标同一个蓝） */
private val CODE_BADGE_BACKGROUND = Color(0xFF4D93F8)

/**
 * 通用「代码」角标（dsh 的 `FileTypeIcon kind="code"`）：**不代表某一个具体文件**时用它的角标 ——
 * dsh 的改动卡片在多文件时画的就是它（`ChangedFiles.tsx:55`），图一里那个蓝底 `</>` 就是这枚。
 *
 * 字号单独给（0.42）：三个字形挤在 20dp 的方框里，走 `FileTypeIcon` 那条
 * 「超过两个字符就 0.34」的规则会糊成一团。
 */
@Composable
internal fun CodeTypeIcon(size: Dp = 20.dp) {
    BadgeBox(size, CODE_BADGE_BACKGROUND) {
        Text(
            text = "</>",
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
        )
    }
}
