package com.adsh.app.ui.chat

import android.content.Context
import android.content.Intent
import com.adsh.app.core.data.AttachmentStore
import com.adsh.app.seam.model.UserAttachment
import java.io.File

/** 导入结果：成功引用 + 逐条失败原因（必须有可见反馈，不能静默失败） */
data class ImportResult(
    val attachments: List<UserAttachment> = emptyList(),
    val failures: List<String> = emptyList(),
) {
    /** 待发附件（输入框只存路径；描述在发送时由 describeAttachments 统一做） */
    val paths: List<String> get() = attachments.map { it.path }
}

/**
 * 把系统文件管理器选中的文件收进**附件仓库**（[AttachmentStore]，应用私有、内容寻址、只读）：
 * 图片按 dsh 的准入上限校验后归一化（EXIF 方向 / ≤2048² / ≤4 MiB），其它文件逐字节原样存。
 *
 * 与 dsh 一致的两条：文件没有大小/类型上限；同一份内容重复导入只多一个别名（sha 一样 → 同一个对象）。
 */
fun importAttachments(context: Context, uris: List<android.net.Uri>): ImportResult {
    if (uris.isEmpty()) return ImportResult()
    val root = AttachmentStore.root(context.filesDir)
    val staging = File(root, ".staging")
    val imported = ArrayList<UserAttachment>()
    val failures = ArrayList<String>()
    uris.forEach { uri ->
        // 有些 provider 只在拿到持久化授权后才可读；拿不到也不影响本次会话内读取
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = queryDisplayName(context, uri)
        val outcome = runCatching {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw java.io.FileNotFoundException("cannot open the input stream")
            AttachmentStore.save(context.filesDir, staging, stream, name)
        }
        outcome.fold(
            onSuccess = { attachment ->
                android.util.Log.i("ADSH_IMPORT", "imported " + attachment.path + " (" + attachment.bytes + " bytes)")
                imported += attachment
            },
            onFailure = { error ->
                android.util.Log.w("ADSH_IMPORT", "import failed for " + uri + ": " + error)
                val reason = if (error is AttachmentStore.Rejected) error.message ?: "" else error.message ?: error::class.java.simpleName
                failures += (name ?: uri.toString()) + "：" + reason
            },
        )
    }
    return ImportResult(imported, failures)
}

private fun queryDisplayName(context: Context, uri: android.net.Uri): String? {
    val cursor = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
    }.getOrNull() ?: return null
    return cursor.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}
