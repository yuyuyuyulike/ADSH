package com.adsh.app.runtime.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fencePlan] / [fenceRoots] / [fenceVariables] / [killOrder] / [ppidFromStat]。
 *
 * 这几处原先埋在 TermuxRuntime 里、零用例，而判错的后果都是静默的：围栏判松 = 只读预设下放行写，
 * 判紧 = 完全权限却什么都写不了（第 64 轮）；杀进程树的顺序反了 = 子进程被 init 收养（再也杀不到）；
 * /proc 切错位置 = 进程树整个读空。
 */
class RuntimeDecisionsTest {

    private val ws = "/storage/emulated/0/ws"
    private val scratch = "/data/data/com.termux/files/home/scratch"
    private val tmp = "/data/data/com.termux/files/usr/tmp"

    // ---------------------------------------------------------------- 白名单规范化

    @Test
    fun `白名单：解析不出来的、空的、根目录都丢掉`() {
        assertEquals(listOf(ws), fenceRoots(listOf(null, "", "/", ws)))
    }

    @Test
    fun `白名单：去重但保序`() {
        assertEquals(listOf(ws, scratch), fenceRoots(listOf(ws, scratch, ws)))
    }

    @Test
    fun `白名单：全丢光就是空表`() {
        assertEquals(emptyList<String>(), fenceRoots(listOf(null, "", "/")))
        assertEquals(emptyList<String>(), fenceRoots(emptyList()))
    }

    // -------------------------------------------------------------------- 三岔判定

    @Test
    fun `完全权限：不挂`() {
        assertEquals(FencePlan.Off, fencePlan("danger-full-access", shimPresent = true, whitelist = listOf(ws)))
    }

    @Test
    fun `不认识模式：不挂`() {
        assertEquals(FencePlan.Off, fencePlan("whatever", shimPresent = true, whitelist = listOf(ws)))
        assertEquals(FencePlan.Off, fencePlan("", shimPresent = true, whitelist = listOf(ws)))
    }

    @Test
    fun `shim 不在：不挂（与白名单无关）`() {
        assertEquals(FencePlan.Off, fencePlan("workspace-write", shimPresent = false, whitelist = listOf(ws)))
        assertEquals(FencePlan.Off, fencePlan("read-only", shimPresent = false, whitelist = listOf(ws)))
        assertEquals(FencePlan.Off, fencePlan("read-only", shimPresent = false, whitelist = emptyList()))
    }

    /**
     * **刻意的不对称**：read-only 的白名单本来就是空的（dsh 的 writableRoots 在 read-only 下就是
     * 空列表），空表是正常形态 —— 照样挂。写反了等于「只读预设下整条 bash 都不挂围栏」。
     */
    @Test
    fun `只读 + 空白名单：照样挂`() {
        assertEquals(FencePlan.On("read-only", emptyList()), fencePlan("read-only", shimPresent = true, whitelist = emptyList()))
    }

    /** workspace-write 反过来：一个可写根都解析不出来说明环境坏了，宁可不挂（老行为） */
    @Test
    fun `工作区可写 + 空白名单：不挂`() {
        assertEquals(FencePlan.Off, fencePlan("workspace-write", shimPresent = true, whitelist = emptyList()))
    }

    @Test
    fun `工作区可写 + 有根：挂，白名单原样带过去`() {
        val roots = listOf(ws, tmp, scratch)
        assertEquals(FencePlan.On("workspace-write", roots), fencePlan("workspace-write", shimPresent = true, whitelist = roots))
    }

    // ------------------------------------------------------------------ 变量清单

    /** 空的 ROOTS 项也必须在：shim 靠「变量在不在」区分 read-only 与「压根没给」 */
    @Test
    fun `只读：白名单是空串，但 ROOTS 这一项必须在`() {
        assertEquals(
            listOf(
                "LD_PRELOAD=/p/libadshfence.so",
                "ADSH_FENCE_ROOTS=",
                "ADSH_FENCE_MODE=read-only",
                "ADSH_FENCE_ACTIVE=1",
            ),
            fenceVariables(FencePlan.On("read-only", emptyList()), "/p/libadshfence.so", null),
        )
    }

    @Test
    fun `工作区可写：白名单用冒号连，标记文件最后`() {
        assertEquals(
            listOf(
                "LD_PRELOAD=/p/libadshfence.so",
                "ADSH_FENCE_ROOTS=" + listOf(ws, tmp).joinToString(":"),
                "ADSH_FENCE_MODE=workspace-write",
                "ADSH_FENCE_ACTIVE=1",
                "ADSH_FENCE_MARK=/mark",
            ),
            fenceVariables(FencePlan.On("workspace-write", listOf(ws, tmp)), "/p/libadshfence.so", "/mark"),
        )
    }

    /**
     * ACTIVE 是「这次真的被围栏管着」的唯一来源（第 114 轮）：只有挂的时候才有它。
     * 「不挂」那条路（[FencePlan.Off]）不给任何变量 —— 但那是**调用点**的事：`fencePlan` 判 Off，
     * 调用点直接返回空数组，所以 [fenceVariables] 的入参就是 [FencePlan.On]（少一条生产走不到的分支）。
     */
    @Test
    fun `ACTIVE 只在真的挂围栏时出现`() {
        val on = fenceVariables(FencePlan.On("read-only", emptyList()), "x", null)
        assertTrue(on.contains("ADSH_FENCE_ACTIVE=1"))
        assertEquals(FencePlan.Off, fencePlan("danger-full-access", shimPresent = true, whitelist = emptyList()))
    }

    // ------------------------------------------------------------------ 杀进程树

    private val tree = mapOf(
        1 to listOf(10, 20),
        10 to listOf(11, 12),
        11 to listOf(13),
    )

    @Test
    fun `单个进程：只杀它自己`() {
        assertEquals(listOf(5), killOrder(5, emptyMap()))
    }

    @Test
    fun `叶子在前、根在最后`() {
        val order = killOrder(1, tree)
        // 广度优先读出来是 [1, 10, 20, 11, 12, 13]，反过来就是下面这个
        assertEquals(listOf(13, 12, 11, 20, 10, 1), order)
        assertEquals(1, order.last())
    }

    /** 不钉死同级顺序，只钉住那条不变量：每个父都排在它的所有子之后 */
    @Test
    fun `每个父都排在子之后`() {
        val order = killOrder(1, tree)
        for ((parent, children) in tree) {
            for (child in children) {
                assertTrue("父 " + parent + " 必须晚于子 " + child, order.indexOf(parent) > order.indexOf(child))
            }
        }
    }

    @Test
    fun `表里查不到的进程：只拿得到根`() {
        assertEquals(listOf(7), killOrder(7, mapOf(1 to listOf(7))))
    }

    /**
     * 畸形表（PID 复用、/proc 读到一半都可能造出环）不能让这里死循环 ——
     * signalTree 跑在 App 的收尾路径上，死循环等于卡住界面。
     */
    @Test
    fun `表里有环：不死循环，且根仍在最后`() {
        // 用**守护线程 + 拿结果超时**来跑：真的死循环时这条用例会失败，而不是把整个测试 JVM 挂住
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "kill-order-cycle").apply { isDaemon = true }
        }
        val order = try {
            pool.submit<List<Int>> { killOrder(1, mapOf(1 to listOf(2), 2 to listOf(1))) }
                .get(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (t: java.util.concurrent.TimeoutException) {
            throw AssertionError("killOrder 在环上没有终止")
        } finally {
            pool.shutdownNow()
        }
        assertEquals(listOf(2, 1), order)
    }

    @Test
    fun `同一个 pid 出现两次也只杀一次`() {
        val order = killOrder(1, mapOf(1 to listOf(2, 2), 2 to listOf(3)))
        assertEquals(listOf(3, 2, 1), order)
    }

    // ------------------------------------------------------------------ /proc 解析

    @Test
    fun `普通 stat 行`() {
        assertEquals(1000, ppidFromStat("1234 (bash) S 1000 1234 1234 0 -1 4194560"))
    }

    @Test
    fun `comm 里有空格`() {
        assertEquals(999, ppidFromStat("1234 (my prog) S 999 1 1 0 -1"))
    }

    /** comm 里可能有括号 → 只能从**最后一个** ')' 之后切 */
    @Test
    fun `comm 里有括号：从最后一个右括号切`() {
        assertEquals(999, ppidFromStat("1234 (a)b) S 999 1 1 0 -1"))
        assertEquals(555, ppidFromStat("42 (a)b)c) R 555 0 0"))
    }

    @Test
    fun `畸形行一律 null`() {
        assertEquals(null, ppidFromStat(""))
        assertEquals(null, ppidFromStat("1234"))
        assertEquals(null, ppidFromStat("1234 (bash)"))
        assertEquals(null, ppidFromStat("1234 (bash) S"))
        assertEquals(null, ppidFromStat("1234 (bash) S xx 1"))
    }
}
