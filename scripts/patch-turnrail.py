import io, sys
P = '/mnt/d/WSN2005/Android1/App/ADSH/app/src/main/java/com/adsh/app/ui/TurnRail.kt'
s = io.open(P, encoding='utf-8').read()

def rep(old, new):
    global s
    if old not in s:
        print('!! 未匹配: ' + old[:70].replace('\n', ' | ')); sys.exit(1)
    s = s.replace(old, new, 1)

# 1) 图标改用 Material（dsh 的工具图标按工具类型各自不同，这里沿用已有的近似映射）
rep('''private fun railSpecOf(name: String): Pair<ImageVector, String> = when (name) {
    "代码", "run_code", "bash" -> DshSidebarIcons.BashOrCode to "Bash"
    "写入", "write", "edit", "编辑" -> DshSidebarIcons.WriteOrEdit to "写入"
    "读取", "read" -> DshSidebarIcons.ReadOrFile to "读取"
    "查找", "glob", "grep" -> DshSidebarIcons.SearchOrFind to "查找"
    "联网", "web_search", "web_fetch" -> DshSidebarIcons.WebOrNet to "联网"
    else -> DshSidebarIcons.BashOrCode to name
}

/** 程序的高亮渲染（复用 DocumentPreview 里的高亮器，深色模式也有对应配色） */
@Composable
private fun highlightProgram(program: String, palette: com.adsh.app.ui.theme.DshPalette) =
    highlightCodeLines(program, palette)''',
'''private fun railSpecOf(name: String): Pair<ImageVector, String> = when (name) {
    "代码", "run_code" -> Icons.Outlined.Terminal to "代码"
    "bash", "Bash" -> Icons.Outlined.Terminal to "Bash"
    "写入", "write", "edit", "编辑" -> Icons.Outlined.Edit to "写入"
    "读取", "read" -> Icons.Outlined.Description to "读取"
    "查找", "glob", "grep" -> Icons.Outlined.Search to "查找"
    "联网", "web_search", "web_fetch" -> Icons.Outlined.Language to "联网"
    else -> Icons.Outlined.Terminal to name
}''')

# 2) 程序块用纯文本渲染（高亮留待 MarkdownBody 那条路径）
rep('''                        Text(
                            text = highlightProgram(tool.program, palette),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            softWrap = false,
                        )''',
'''                        Text(
                            text = tool.program,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            softWrap = false,
                            color = palette.labelPrimary,
                        )''')

# 3) 滚动修饰符：纵向 + 横向各一个（代码块与 io 段都需要）
rep('''/** 代码块里的滚动容器（横向 + 纵向都不裁内容） */
@Composable
private fun Modifier.verticalScrollSafe(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))''',
'''/** 代码块 / IO 段里的滚动容器：纵向 + 横向都滚，不裁内容 */
@Composable
private fun Modifier.scrollableBody(): Modifier =
    this.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())''')
s = s.replace('.verticalScrollSafe()', '.scrollableBody()')

# 4) 去掉 RunningSweep 里那行占位引用
rep('''        // palette 引用一次，保持与主题同步（避免未使用告警）
        if (palette.labelPrimary.alpha == 2f) drawRect(Color.Transparent)''', '')

# 5) 补 import
rep('''import androidx.compose.foundation.rememberScrollState''',
'''import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal''')

io.open(P, 'w', encoding='utf-8').write(s)
print('TurnRail 修正完成')
