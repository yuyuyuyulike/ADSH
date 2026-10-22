package com.adsh.app.ui.blocks

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.adsh.app.ui.theme.LocalDshPalette
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.kit.dshInteraction
import java.io.File

/*
 * 「用其他应用打开」：安卓的标准出路 —— 预览里读不了的格式（老式 .xls/.ppt、未知类型）交给系统里
 * 能读它的 App。走 FileProvider 的受控 content:// URI（file:// 从 Android 7 起对外的 Intent 直接抛
 * FileUriExposedException），只授予读权限。
 */

/** 文件后缀 → Intent 的 MIME；认不出给通配类型（系统会列全量可打开的 App） */
internal fun mimeTypeOf(path: String): String = when (File(path).name.substringAfterLast('.', "").lowercase()) {
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "pdf" -> "application/pdf"
    "txt", "text", "log", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml" -> "text/plain"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "zip" -> "application/zip"
    else -> "*/*"
}

/** 带 FILE_PROVIDER 的受控 URI 打开一个文件；没有任何 App 接得住就提示一句（不崩） */
internal fun openFileWithOtherApp(context: Context, path: String) {
    val file = File(path)
    if (!file.isFile) {
        Toast.makeText(context, "file not found", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }.getOrNull()
    if (uri == null) {
        Toast.makeText(context, "cannot share this file", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeTypeOf(path))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "no app can open this file type", Toast.LENGTH_SHORT).show()
    }
}

/** dsh 空态按钮的外观（32dp 高、圆角 12、0.5dp 描边）+ 打开图标 */
@Composable
internal fun OpenWithOtherAppButton(path: String) {
    val palette = LocalDshPalette.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .border(0.5.dp, palette.borderL2, shape)
            .dshClickable(interactionSource = dshInteraction()) { openFileWithOtherApp(context, path) }
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            tint = palette.labelPrimary,
            modifier = Modifier.size(14.dp),
        )
        Text("用其他应用打开", fontSize = 13.sp, lineHeight = 20.sp, color = palette.labelPrimary)
    }
}
