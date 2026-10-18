package com.adsh.app.seam.exec

/** 权限预设（dsh 的 permission preset）的持久化取值 */
object Permissions {
    const val READ_ONLY = "read_only"
    const val WORKSPACE_WRITE = "workspace_write"
    const val FULL_ACCESS = "full_access"
}

/** 权限预设 → 沙箱策略模式名（dsh 的 permission → filePolicy） */
fun filePolicyOf(permission: String): String = when (permission) {
    Permissions.READ_ONLY -> Escalation.READ_ONLY
    Permissions.WORKSPACE_WRITE -> Escalation.WORKSPACE_WRITE
    else -> Escalation.FULL_ACCESS
}
