import io, sys
R = '/mnt/d/WSN2005/Android1/App/ADSH/app/src/main/java/com/adsh/app/'
def load(p): return io.open(R + p, encoding='utf-8').read()
def save(p, s): io.open(R + p, 'w', encoding='utf-8').write(s)
def rep(s, old, new):
    if old not in s:
        print('!! 未匹配: ' + old[:80].replace('\n', ' | ')); sys.exit(1)
    return s.replace(old, new, 1)

p = 'ui/AppRoot.kt'
s = load(p)
s = rep(s, '''    data object WorkspacePicker : Dest
    /** 从抽屉 / 输入框 chip 进入的「添加工作区」（选一个手机文件夹） */
    data object WorkspaceAdd : Dest''', '''    data object WorkspacePicker : Dest''')
s = rep(s, '''    val stack = remember { mutableStateListOf<Dest>(Dest.Conversation) }''',
'''    val context = LocalContext.current
    val stack = remember { mutableStateListOf<Dest>(Dest.Conversation) }''')
s = rep(s, '''    fun closeDrawer() = scope.launch { progress.animateTo(0f, tween(190, easing = FastOutSlowInEasing)) }''',
'''    fun closeDrawer() = scope.launch { progress.animateTo(0f, tween(190, easing = FastOutSlowInEasing)) }

    // 绑定工作区 = 系统文件管理器里选一个文件夹（与导入附件同一套 SAF 流程，只选文件夹、不列文件）
    val workspacePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val path = folderPathFromTreeUri(uri)
        if (path == null) {
            android.widget.Toast.makeText(context, "这里不是手机上的文件夹，换一个位置再试", android.widget.Toast.LENGTH_LONG).show()
        } else {
            uri?.let { persistTreePermission(context, it) }
            viewModel.bindWorkspaceFolder(path)
        }
    }
    val addWorkspace = {
        workspacePicker.launch(android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A"))
    }''')
s = rep(s, '''            onAddWorkspace = { closeDrawer(); push(Dest.WorkspaceAdd) },''',
'''            onAddWorkspace = { closeDrawer(); addWorkspace() },''')
s = rep(s, '''                    // 添加工作区：选一个手机文件夹绑定成工作区（dsh 的 add workspace 目录流）
                    is Dest.WorkspaceAdd -> DirectoryBrowserPanel(
                        startPath = workspace.path ?: "/storage/emulated/0",
                        onPick = { path ->
                            viewModel.bindWorkspaceFolder(path)
                            pop()
                        },
                        onBack = { pop() },
                    )

''', '')
s = rep(s, '''                        onAddWorkspace = { closeDrawer(); push(Dest.WorkspaceAdd) },''',
'''                        onAddWorkspace = addWorkspace,''')
s = rep(s, '''                DshPopup(onDismiss = { onMenuOpenChange(false) }, alignStart = false, below = true) {
                    DshMenuCard(Modifier.widthIn(min = 168.dp)) {''',
'''                DshPopup(onDismiss = { onMenuOpenChange(false) }, alignStart = true, below = true) {
                    DshMenuCard(Modifier.width(156.dp)) {''')
s = rep(s, '''import androidx.compose.ui.platform.LocalDensity''',
'''import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity''')
save(p, s)

p = 'ui/Composer.kt'
s = load(p)
s = rep(s, '''        // dsh 的 heroWorkspaceRow：输入框「外」左上角的工作区入口（文件夹图标 + 名称 + 倒角）
        WorkspaceChipRow(''',
'''        // dsh 的 heroWorkspaceRow：输入框「外」左上角的工作区入口（文件夹图标 + 名称 + 倒角）
        // 只在「新对话且还没有内容」时出现（dsh 的 hero 阶段），开始对话后自动隐藏
        if (showWorkspace) WorkspaceChipRow(''')
s = rep(s, '''    /** 已绑定的工作区（dsh 侧栏的树）与当前会话所属工作区 */''',
'''    /** 是否显示输入框外的工作区 chip（dsh 只在 hero 阶段显示） */
    showWorkspace: Boolean = false,
    /** 已绑定的工作区（dsh 侧栏的树）与当前会话所属工作区 */''')
s = rep(s, '''    Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp)) {''',
'''    Box(Modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp)) {''')
s = rep(s, '''                tint = palette.labelPrimary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = current?.name ?: "选择工作区",''',
'''                tint = palette.labelPrimary,
                modifier = Modifier.size(19.dp),
            )
            Text(
                text = current?.name ?: "选择工作区",''')
s = rep(s, '''            DshPopup(onDismiss = { onOpenChange(false) }, alignStart = true) {
                DshMenuCard(Modifier.widthIn(min = 220.dp, max = 300.dp)) {
                    workspaces.forEach { workspace ->
                        DshMenuRow(
                            label = workspace.name,
                            icon = DshIcons.FolderClose,
                            selected = workspace.id == workspaceId,
                            modifier = Modifier.widthIn(min = 200.dp),
                            onClick = { onPick(workspace.id) },
                        )
                    }
                    DshMenuRow(
                        label = "添加工作区…",
                        icon = DshIcons.Plus,
                        modifier = Modifier.widthIn(min = 200.dp),
                        onClick = onAdd,
                    )
                }
            }''',
'''            DshPopup(onDismiss = { onOpenChange(false) }, alignStart = true) {
                DshMenuCard(Modifier.width(200.dp)) {
                    workspaces.forEach { workspace ->
                        DshMenuRow(
                            label = workspace.name,
                            icon = DshIcons.FolderClose,
                            selected = workspace.id == workspaceId,
                            onClick = { onPick(workspace.id) },
                        )
                    }
                    DshMenuRow(
                        label = "添加工作区…",
                        icon = DshIcons.Plus,
                        onClick = onAdd,
                    )
                }
            }''')
save(p, s)

p = 'ui/ChatScreen.kt'
s = load(p)
s = rep(s, '''                workspaces = workspaces,''', '''                showWorkspace = !hasConversation,
                workspaces = workspaces,''')
save(p, s)
print('三项调整已应用')
