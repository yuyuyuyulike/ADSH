package com.adsh.app.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 认领收件箱时的 name 改写（[injectedName]）。
 *
 * 这条规则决定**插话与任务通知落在会话的哪个位置**：有人接（正在跑的那一轮）时原样保留，
 * 没人接时它们就是下一轮的内容 —— 插话变成普通 user 节点、这一批的第一条任务通知变成
 * 那一轮的唤醒形态。以前它在生产的 [ConversationRepository.claimInjected] 与测试替身
 * `TurnFakes` 里各写一遍（注释写着「逐字一致」），现在两边共用这一个函数。
 */
class InjectedNamingTest {

    private val steering = ConversationRepository.STEERING
    private val injected = ConversationRepository.JOB_NOTICE_INJECTED
    private val notice = ConversationRepository.JOB_NOTICE

    /** 这三个字面值会落进会话历史（name 列），改了就读不出旧会话的结构 —— 钉住 */
    @Test
    fun `三个 name 的字面值不能改`() {
        assertEquals("steering", steering)
        assertEquals("tool-jobs:notice", injected)
        assertEquals("tool-jobs", notice)
    }

    // ------------------------------------------------------------ 有人接：原样保留

    @Test
    fun `那一轮接下了这批行时 name 原样保留`() {
        assertEquals(steering, injectedName(steering, 0, openTurn = false))
        assertEquals(injected, injectedName(injected, 0, openTurn = false))
        assertEquals(injected, injectedName(injected, 3, openTurn = false))
        assertNull(injectedName(null, 0, openTurn = false))
    }

    @Test
    fun `别的 name（权限切换通知这类）也原样保留`() {
        val sandboxSwitch = ConversationRepository.SANDBOX_SWITCH
        assertEquals(sandboxSwitch, injectedName(sandboxSwitch, 0, openTurn = true))
        assertEquals(sandboxSwitch, injectedName(sandboxSwitch, 0, openTurn = false))
    }

    @Test
    fun `name 为空的行两条路径都不受影响`() {
        assertNull(injectedName(null, 0, openTurn = true))
        assertNull(injectedName(null, 1, openTurn = true))
    }

    // ------------------------------------------------------------ 没人接：这批行就是下一轮

    @Test
    fun `没人接时插话去掉 steering 标记`() {
        assertNull(injectedName(steering, 0, openTurn = true))
        assertNull(injectedName(steering, 2, openTurn = true))
    }

    @Test
    fun `没人接时第一条任务通知换成唤醒形态`() {
        assertEquals(notice, injectedName(injected, 0, openTurn = true))
    }

    @Test
    fun `没人接时后面的任务通知保持注入形态`() {
        assertEquals(injected, injectedName(injected, 1, openTurn = true))
        assertEquals(injected, injectedName(injected, 2, openTurn = true))
    }

    /**
     * 「第一条」是**整批的下标 0**，不是「第一条任务通知」：下标 0 是插话时，它自己变成
     * 普通 user 节点、已经开了这一轮，后面的任务通知就不能再当轮首（一个轮次只能有一个开头）。
     */
    @Test
    fun `下标 0 是插话时，后面那条任务通知仍是注入形态`() {
        val batch = listOf(steering, injected, injected)
        val names = batch.mapIndexed { index, name -> injectedName(name, index, openTurn = true) }
        assertEquals(listOf(null, injected, injected), names)
    }

    @Test
    fun `整批都是任务通知时只有第一条变唤醒形态`() {
        val batch = listOf(injected, injected, injected)
        val names = batch.mapIndexed { index, name -> injectedName(name, index, openTurn = true) }
        assertEquals(listOf(notice, injected, injected), names)
    }

    @Test
    fun `插话在前、任务通知在后，整批认领的顺序不变`() {
        val batch = listOf(steering, steering, injected)
        val names = batch.mapIndexed { index, name -> injectedName(name, index, openTurn = true) }
        assertEquals(listOf(null, null, injected), names)
    }
}
