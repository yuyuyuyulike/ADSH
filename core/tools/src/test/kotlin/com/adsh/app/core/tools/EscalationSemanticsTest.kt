package com.adsh.app.core.tools

import com.adsh.app.seam.exec.Permissions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沙箱升级的 dsh 语义（两个工具族各有一份校验，见 dsh-tool-bash / dsh-tool-fs）：
 *  - 重复当前模式**不需要审批**（bash 与 fs 都如此，approveEscalation 里提前返回）；
 *  - bash 族在重复当前模式时连 justification 配对都不校验，没给模式时空白 justification 视同缺省；
 *  - fs 族（write / edit）严格校验配对；严格更宽的请求在无审批通道时失败关闭。
 */
class EscalationSemanticsTest {

    private val ask = EscalationAsk(toolName = "bash", callId = "c1", subject = "command", detail = "echo x")

    private fun args(vararg pairs: Pair<String, String>): kotlinx.serialization.json.JsonObject =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun ctx(permission: String) = testToolContext().copy(
        permission = permission,
        livePermission = { permission },
        approvalTimeoutMs = 50,
    )

    @Test
    fun repeatingTheCurrentModeRunsWithoutApproval() = runBlocking {
        val granted = resolveCallMode(
            args("sandbox_permissions" to "read-only", "justification" to "already read-only"),
            ctx(Permissions.READ_ONLY),
            ask,
        )
        assertEquals("read-only", granted)
    }

    @Test
    fun bashRulesAllowRepeatingTheModeWithoutAJustification() = runBlocking {
        val granted = resolveCallMode(
            args("sandbox_permissions" to "read-only"),
            ctx(Permissions.READ_ONLY),
            ask,
            bashRules = true,
        )
        assertEquals("read-only", granted)
    }

    @Test
    fun bashRulesTreatABlankJustificationWithoutAModeAsAbsent() = runBlocking {
        val granted = resolveCallMode(
            args("justification" to "   "),
            ctx(Permissions.READ_ONLY),
            ask,
            bashRules = true,
        )
        assertEquals("read-only", granted)
    }

    @Test
    fun fsRulesStillRejectAJustificationWithoutAMode() = runBlocking {
        val failure = runCatching {
            resolveCallMode(args("justification" to "why"), ctx(Permissions.READ_ONLY), ask)
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("only valid together with sandbox_permissions"))
    }

    @Test
    fun aStrictlyWiderModeFailsClosedWithoutAnApprovalChannel() = runBlocking {
        val failure = runCatching {
            resolveCallMode(
                args("sandbox_permissions" to "danger-full-access", "justification" to "probe"),
                ctx(Permissions.READ_ONLY),
                ask,
            )
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("no approval channel is available"))
    }

    @Test
    fun aNarrowerModeIsRejectedBeforeExecution() = runBlocking {
        val failure = runCatching {
            resolveCallMode(
                args("sandbox_permissions" to "read-only", "justification" to "narrower"),
                ctx(Permissions.WORKSPACE_WRITE),
                ask,
            )
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("not strictly wider"))
    }
}
