package com.adsh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * present 的展开体与行摘要 —— 对齐 dsh 的 PresentRow（ui-deliverables/src/client/PresentRow.tsx）。
 *
 *  - **行摘要**：dsh 是 `row.<状态>` + 参数里那些文件的路径（`, ` 连起来，长了省略）；
 *  - **展开体**：dsh 是一个 `<pre>`（`.output`：bg-layer-1 底、圆角、12px、pre-wrap），
 *    里面就是结果原文（`Presented A\nPresented B`）——它没有做成卡片列表，因为交付物清单
 *    本来就在同一轮的轮尾卡片里（ChangedFiles 那一张）。
 *
 * 手机侧偏离：行摘要里显示**文件名**而不是完整路径（用户第 86 轮点名的口径，也与轮尾那张卡一致）——
 * 手机上行摘要是单行省略的，三段完整路径只会显示第一段。
 */

/** 摘要里的状态词（dsh 的 row.running / row.ok / row.error / row.stopped） */
private fun presentStateLabel(running: Boolean, error: Boolean): String = when {
    running -> "正在交付"
    error -> "交付失败"
    else -> "已交付"
}

/**
 * 行摘要：`已交付 · a.md, b.png`（dsh 的 PresentRow 的 collapsedContent）。
 * 参数解析不出来（流式半截 JSON）时只有状态词。
 */
internal fun presentRowSummary(arguments: String, running: Boolean, error: Boolean): String {
    val names = presentFileNames(arguments)
    val state = presentStateLabel(running, error)
    return if (names.isEmpty()) state else state + " · " + names.joinToString(", ")
}

/** 参数里 files[].path 的文件名（dsh 用完整路径；这里按手机口径只取文件名） */
private fun presentFileNames(arguments: String): List<String> {
    val args = ToolArgs.parse(arguments) ?: return emptyList()
    val files = args["files"] as? JsonArray ?: return emptyList()
    return files.mapNotNull { element ->
        val file = element as? JsonObject ?: return@mapNotNull null
        val path = (file["path"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (path.isEmpty()) return@mapNotNull null
        path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
    }
}

/** present 卡（dsh 的 PresentRow：结果原文一个 pre 框） */
@Composable
internal fun RailPresentBlock(output: String, modifier: Modifier = Modifier) {
    val palette = LocalDshPalette.current
    Text(
        text = output,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.bgLayer1)
            .padding(12.dp),
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = palette.labelSecondary,
    )
}
