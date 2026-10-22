package com.adsh.app.core.agent

import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.workspace.Workspace
import com.adsh.app.core.workspace.WorkspaceForm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 计划模式退出的叙述：dsh 的 plan-mode narration 是一条**独立的 user 消息**
 * （source = plan-mode、form = notice、summary = 正文），**不进 runtime-context 快照**。
 *
 * 这里同时钉住「只写一次」：写完这条叙述后，同一次 [AgentLoop.recordContext] 会追加一条
 * 不带 plan 段的新快照；下一次发送的上一条快照不再带 marker，于是不会再写一遍。
 */
class PlanNarrationTest {

    private val conversationId = 7L
    private val root = File(System.getProperty("java.io.tmpdir"), "adsh-plan-narration").apply { mkdirs() }

    private fun newLoop(store: FakeTurnStore): AgentLoop = AgentLoop(
        llm = FakeTurnLlm(emptyList<List<com.adsh.app.core.llm.ChatEvent>>()),
        repository = store,
        settings = FakeTurnSettings(),
        logDebug = false,
    )

    @Test
    fun leavingPlanModeWritesOneNarrationRowAndKeepsSnapshotsClean() = runBlocking {
        val store = FakeTurnStore()
        store.addMessage(
            conversationId = conversationId,
            role = "context",
            content = PromptAssembler.snapshotText(PromptAssembler.PLAN_SECTION),
            subCallsJson = PromptAssembler.FORM_SNAPSHOT,
        )
        val loop = newLoop(store)
        val workspace = Workspace(WorkspaceForm.PRIVATE, root, "ws")

        loop.recordContext(conversationId, workspace, planMode = false)

        val narration = store.rows(conversationId).lastOrNull { it.name == ConversationRepository.PLAN_MODE_NARRATION }
        assertNotNull("退出计划模式必须写一条 narration 行", narration)
        assertEquals(PromptAssembler.PLAN_OFF_NARRATION, narration!!.content)

        val snapshots = store.rows(conversationId).filter {
            it.role == "context" && it.subCallsJson == PromptAssembler.FORM_SNAPSHOT
        }
        assertTrue("追加了新快照", snapshots.size >= 2)
        assertTrue(
            "叙述不许并进快照正文",
            snapshots.none { it.content.contains(PromptAssembler.PLAN_OFF_NARRATION) },
        )

        // 第二次发送：上一条快照不再带 marker，既不再写叙述、也不再追加快照
        val rowCount = store.rows(conversationId).size
        loop.recordContext(conversationId, workspace, planMode = false)
        assertEquals("第二次不应再写 narration", 1, store.rows(conversationId).count { it.name == ConversationRepository.PLAN_MODE_NARRATION })
        assertEquals("快照没变就不追加", rowCount, store.rows(conversationId).size)
    }
}
