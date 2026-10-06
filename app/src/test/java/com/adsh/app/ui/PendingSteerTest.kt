package com.adsh.app.ui

import com.adsh.app.core.data.ConversationRepository
import com.adsh.app.core.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插话回显的退休规则（第 192 轮，对齐 dsh 的「提交当帧回显 + 认领后按 rpcId 去重」）。
 *
 * ADSH 落库那一行不带 rpcId，所以按「同内容一对一消耗」配对：库里每出现一条 steering 行，
 * 就抵掉最早的一条同内容回显 —— 目标是**同一时刻只看得见一条**（既不重复，也不会两条一起消失）。
 */
class PendingSteerTest {

    private fun row(id: Long, role: String, content: String, name: String? = null) =
        MessageEntity(id = id, conversationId = 1, role = role, content = content, name = name, createdAt = id)

    private fun steering(id: Long, text: String) = row(id, "user", text, ConversationRepository.STEERING)

    private fun echo(id: Long, text: String) = PendingSteer(id = id, text = text, time = id)

    @Test
    fun echoesStayWhileNothingLandedYet() {
        val pending = listOf(echo(1, "先别删那个文件"), echo(2, "改用 b 方案"))
        assertEquals(pending, pendingSteeringToShow(pending, listOf(row(9, "user", "跑一下测试"))))
    }

    @Test
    fun theLandedRowRetiresItsEcho() {
        val pending = listOf(echo(1, "先别删那个文件"))
        val messages = listOf(row(1, "user", "开头的消息"), steering(2, "先别删那个文件"))
        assertTrue(pendingSteeringToShow(pending, messages).isEmpty())
    }

    @Test
    fun twoIdenticalEchoesRetireOneByOne() {
        // 同一句话连发两次插话：认领了一条就只该少一条回显，另一条继续挂着
        val pending = listOf(echo(1, "停"), echo(2, "停"))
        val afterFirst = pendingSteeringToShow(pending, listOf(steering(7, "停")))
        assertEquals(listOf(2L), afterFirst.map { it.id })
        assertTrue(pendingSteeringToShow(pending, listOf(steering(7, "停"), steering(8, "停"))).isEmpty())
    }

    @Test
    fun aPlainUserMessageDoesNotRetireAnEcho() {
        // 内容一样的普通用户消息（不是 steering 行）不该把回显抵掉 —— 只认 name = steering 的行
        val pending = listOf(echo(1, "停"))
        val messages = listOf(row(1, "user", "停"), row(2, "assistant", "停"))
        assertEquals(pending, pendingSteeringToShow(pending, messages))
    }
}
