package com.adsh.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 导出落盘根（[exportDirectory]）：必须落在**当前会话绑定的工作区**，而不是应用私有目录 ——
 * 之前的实现读 ChatUiState.workspacePath（启动时的兜底工作区），绑定 / 切换工作区后不更新，
 * 命令栏「下载日志」就会写进应用目录。
 */
class SessionExportTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `工作区真实路径存在时写到工作区`() {
        val workspace = temporaryFolder.newFolder("workspace")
        val fallback = temporaryFolder.newFolder("fallback")
        assertEquals(workspace, exportDirectory(workspace.absolutePath, fallback))
    }

    @Test
    fun `工作区路径已不存在时退回应用私有目录`() {
        val fallback = temporaryFolder.newFolder("fallback")
        val missing = temporaryFolder.root.resolve("gone")
        assertEquals(fallback, exportDirectory(missing.absolutePath, fallback))
    }

    @Test
    fun `没有绑定工作区时退回应用私有目录`() {
        // SAF 引用形态没有真实路径
        val fallback = temporaryFolder.newFolder("fallback")
        assertEquals(fallback, exportDirectory(null, fallback))
    }
}
