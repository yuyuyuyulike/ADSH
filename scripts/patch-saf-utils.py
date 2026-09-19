import io, sys
R = '/mnt/d/WSN2005/Android1/App/ADSH/app/src/main/java/com/adsh/app/'
P = R + 'ui/WorkspaceActions.kt'
s = io.open(P, encoding='utf-8').read()
old = 'fun toastPath(context: Context, path: String?) {'
new = '''/**
 * 系统文件管理器选回来的「文件夹」URI → 手机上的绝对路径。
 * DocumentsUI 的树 URI 形如 content://com.android.externalstorage.documents/tree/primary%3Aadsh-ws，
 * documentId = "primary:adsh-ws" → /storage/emulated/0/adsh-ws；解析不出来（网盘 / MTP 等）返回 null。
 */
fun folderPathFromTreeUri(uri: android.net.Uri?): String? {
    if (uri == null) return null
    if (uri.authority != "com.android.externalstorage.documents") return null
    val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val parts = docId.split(":", limit = 2)
    val volume = parts[0]
    val relative = parts.getOrNull(1).orEmpty().trim('/')
    val base = if (volume.equals("primary", true)) "/storage/emulated/0" else "/storage/" + volume
    return if (relative.isEmpty()) base else base + "/" + relative
}

/** 长期保留这个文件夹的读写权限（重启后依然有效） */
fun persistTreePermission(context: Context, uri: android.net.Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}

fun toastPath(context: Context, path: String?) {'''
if old not in s:
    print('!! 未匹配 anchor'); sys.exit(1)
io.open(P, 'w', encoding='utf-8').write(s.replace(old, new, 1))
print('SAF 工具已加入 WorkspaceActions.kt')
