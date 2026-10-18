package com.adsh.app.ui.blocks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.ui.theme.LocalDshPalette
import com.adsh.app.ui.kit.DshSpacing
import com.adsh.app.ui.kit.dshClickable
import com.adsh.app.ui.kit.dshInteraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 工作区文件树（对齐 dsh 的 ui-sidebar-files / FilesBody）：
 *   - 文件夹点一下在**原位展开**，不跳页；文件点一下交给预览面板
 *   - 表头只有「路径 + 刷新」，没有返回 / 上一级 / 重新读取
 *   - 文件夹用 dsh 的 IconFolderOpen16 / IconFolderClose16，文件按类型给角标（图标 + 颜色）
 *
 * 行距与缩进取自 dsh 的 FilesBody.module.css：行 5px/10px 内边距、圆角 10px、每级缩进 18px、
 * 表头 38px 高 + 0.5px 下边框、刷新按钮 28dp 圆形。
 */

private data class FileNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val depth: Int,
)

@Composable
private fun FileTreePanel(rootPath: String, onOpenFile: (String) -> Unit) {
    val palette = LocalDshPalette.current
    val expanded = remember(rootPath) { mutableStateListOf<String>() }
    var refreshTick by remember(rootPath) { mutableStateOf(0) }
    var nodes by remember(rootPath) { mutableStateOf(emptyList<FileNode>()) }
    var error by remember(rootPath) { mutableStateOf<String?>(null) }

    val expandedKey = expanded.joinToString("|")
    LaunchedEffect(rootPath, refreshTick, expandedKey) {
        val result = withContext(Dispatchers.IO) { runCatching { buildTree(rootPath, expanded.toSet()) } }
        nodes = result.getOrDefault(emptyList())
        error = result.exceptionOrNull()?.message
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        // 手机文件夹是用真实路径（File API）列的：没有「所有文件访问」时这里只能列出空树。
        // 之前连提示都没有，用户以为手机上没文件，还得自己去系统设置里翻出那个开关。
        if (!hasAllFilesAccess()) {
            val allFilesContext = androidx.compose.ui.platform.LocalContext.current
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.Xxxl, vertical = DshSpacing.Xl),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DshSpacing.Xl),
            ) {
                Text(
                    text = "需要「所有文件访问」权限才能读写手机文件夹",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                androidx.compose.material3.TextButton(
                    onClick = { openAllFilesAccessSettings(allFilesContext) },
                ) {
                    Text("去授权")
                }
            }
        }
        // 表头：路径 + 刷新（对应 dsh 的 FilesBody header，38px 高、下边框 0.5px）
        Row(
            // dsh 的 FilesBody header：38 高、padding 0 6 0 16、右侧 28dp 刷新按钮
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Md),
        ) {
            val trimmed = rootPath.trimEnd('/')
            val directory = trimmed.substringBeforeLast('/', "")
            val name = trimmed.substringAfterLast('/')
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    DshIcons.FolderOpen,
                    contentDescription = null,
                    tint = Color(0xFFE8A33D),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = directory.ifEmpty { "/" },
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 12.sp,
                    color = palette.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = name,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = palette.labelPrimary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            ToolIconButton(DshIcons.Refresh, "刷新") { refreshTick++ }
        }
        HorizontalDivider(thickness = DshSpacing.Hairline, color = palette.borderL3)

        val failure = error
        if (failure != null) {
            Text(
                text = "无法读取：" + failure,
                modifier = Modifier.padding(DshSpacing.Xxxl),
                fontSize = 13.sp,
                color = palette.labelSecondary,
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 8.dp,
                end = 8.dp,
                top = 8.dp,
                bottom = 24.dp,
            ),
        ) {
            items(nodes, key = { it.path }) { node ->
                FileRow(
                    node = node,
                    expanded = node.path in expanded,
                    onToggle = { path ->
                        if (path in expanded) expanded.remove(path) else expanded.add(path)
                    },
                    onOpenFile = onOpenFile,
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    node: FileNode,
    expanded: Boolean,
    onToggle: (String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val palette = LocalDshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(start = (10 + node.depth * 18).dp, end = DshSpacing.Xxl)
            .clip(RoundedCornerShape(10.dp))
            .dshClickable(interactionSource = dshInteraction()) { if (node.isDirectory) onToggle(node.path) else onOpenFile(node.path) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
    ) {
        if (node.isDirectory) {
            Icon(
                imageVector = if (expanded) DshIcons.FolderOpen else DshIcons.FolderClose,
                contentDescription = null,
                tint = palette.labelTertiary,
                modifier = Modifier.size(16.dp),
            )
        } else {
            FileTypeIcon(node.name)
        }
        Text(
            text = node.name,
            fontSize = 13.sp,
            color = palette.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** dsh 的 .k-1LKG_tool：28dp 圆形图标按钮，15px 图标 */
@Composable
private fun ToolIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(28.dp))
            .dshClickable(interactionSource = dshInteraction(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = palette.labelSecondary, modifier = Modifier.size(15.dp))
    }
}

/** 展开状态下的可见节点（目录在前、其余按名字；与 dsh 的 orderEntries 一致） */
private fun buildTree(root: String, expanded: Set<String>): List<FileNode> {
    val rootFile = File(root)
    if (!rootFile.isDirectory) throw IllegalStateException("目录不存在：" + root)
    val out = ArrayList<FileNode>()
    fun walk(directory: File, depth: Int) {
        val children = directory.listFiles() ?: return
        children
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .forEach { child ->
                out += FileNode(child.absolutePath, child.name, child.isDirectory, depth)
                if (child.isDirectory && child.absolutePath in expanded) walk(child, depth + 1)
            }
    }
    walk(rootFile, 0)
    return out
}

/**
 * 工作区文件「窗口」（对齐 dsh 的做法）：顶部标签条里常驻一个「工作区文件」窗口，
 * 点文件夹在原位展开、点文件在**同一个标签条**里新开一个窗口，点回「工作区文件」即可回到目录树。
 */
@Composable
fun FileWorkspacePanel(rootPath: String, tabs: List<String>, activeIndex: Int, onSelect: (Int) -> Unit, onClose: (Int) -> Unit, onOpenFile: (String) -> Unit) {
    val palette = LocalDshPalette.current
    // 预览的两种读者状态（dsh 按 tab 存：换行默认开、重载只影响当前文件）。这里手机只有一个可见 tab，
    // 换 tab 时重置；同一文件重开时 dsh 也是新 tab（状态从默认值起）。
    var wrap by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        // dsh 的 dock 标签条：一行 38 = 10 上留白 + 28 内容带；padding 10 6 0 10、gap 4、不画边框
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 6.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            WorkspaceTab(title = "工作区文件", path = null, active = activeIndex == 0, closable = false, onSelect = { onSelect(0) }, onClose = {})
            tabs.forEachIndexed { index, path ->
                WorkspaceTab(
                    title = File(path).name,
                    path = path,
                    active = activeIndex == index + 1,
                    closable = true,
                    onSelect = { onSelect(index + 1) },
                    onClose = { onClose(index + 1) },
                )
            }
        }
        val file = tabs.getOrNull(activeIndex - 1)
        if (file == null) {
            FileTreePanel(rootPath = rootPath, onOpenFile = onOpenFile)
        } else {
            DocumentHeader(
                file = file,
                wrap = wrap,
                onToggleWrap = { wrap = !wrap },
                onReload = { reloadKey++ },
            )
            FilePreviewBody(file, wrap = wrap, reloadKey = reloadKey)
        }
    }
}

/**
 * 文档头（dsh 的 TextPreview.module.css）：38 高、padding 0 6 0 16、底部 0.5px borderL3。
 *
 * 左边是路径：目录段三级色、文件名一级色、12sp 单行；右边是换行开关（只有 text/md/code 有）
 * 与「重新读取文件」。手机没有 hover，完整路径用文件名 + 目录两段直接显示。
 */
@Composable
private fun DocumentHeader(file: String, wrap: Boolean, onToggleWrap: () -> Unit, onReload: () -> Unit) {
    val palette = LocalDshPalette.current
    val path = File(file)
    val name = path.name
    val directory = path.parent.orEmpty()
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(38.dp).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = directory,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = name,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (previewSupportsWrap(file)) {
                HeaderIconButton(
                    icon = if (wrap) Icons.AutoMirrored.Outlined.WrapText else Icons.AutoMirrored.Outlined.Notes,
                    description = if (wrap) "取消自动换行" else "自动换行",
                    onClick = onToggleWrap,
                )
            }
            HeaderIconButton(icon = DshIcons.Refresh, description = "重新读取文件", onClick = onReload)
        }
        HorizontalDivider(thickness = DshSpacing.Hairline, color = palette.borderL3)
    }
}

/** 文档头图标按钮：28×28、圆角 8、透明底（dsh 的 TextPreview header button） */
@Composable
private fun HeaderIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .dshClickable(interactionSource = dshInteraction(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = palette.labelSecondary, modifier = Modifier.size(16.dp))
    }
}

/**
 * dsh 的 dock chip：高 28、min 80 / max 170、padding 0 10、13sp、圆角 8；
 * 激活态是一块**填充**（markdown-tag），不是下划线；文件 chip 前置 16dp 的 FileTypeIcon。
 */
@Composable
private fun WorkspaceTab(
    title: String,
    path: String?,
    active: Boolean,
    closable: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalDshPalette.current
    Row(
        modifier = Modifier
            .height(28.dp)
            .widthIn(min = 80.dp, max = 170.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) palette.markdownTag else Color.Transparent)
            .dshClickable(interactionSource = dshInteraction(), onClick = onSelect)
            .padding(start = 10.dp, end = if (closable) 4.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (path == null) {
            Icon(
                imageVector = DshIcons.FolderOpen,
                contentDescription = null,
                tint = if (active) Color(0xFFE8A33D) else palette.labelTertiary,
                modifier = Modifier.size(16.dp),
            )
        } else {
            FileTypeIcon(name = File(path).name, size = 16.dp)
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f, fill = false),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = if (active) palette.labelPrimary else palette.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (closable) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .dshClickable(interactionSource = dshInteraction(), onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(DshIcons.CloseFill, contentDescription = "关闭", tint = palette.labelTertiary, modifier = Modifier.size(14.dp))
            }
        }
    }
}
