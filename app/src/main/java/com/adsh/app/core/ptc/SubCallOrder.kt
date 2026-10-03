package com.adsh.app.core.ptc

/**
 * PTC 子调用的**提交序**（dsh 的 `commitQueue`，spec-log §4.1-B）。
 *
 * dsh 的规矩是「派发可重叠，入档严格按提交序」：子调用按提交序排队、单车道逐条 start
 * （start 事件落进日志就是提交序），跑完的先放一边 —— **队头没结算，谁都别想提交**
 * （head-of-line）。于是日志顺序永远等于模型写下的顺序，与「谁先跑完」无关。
 *
 * 这里只保留这条不变量需要的最小状态：`size` 个结算槽 + 一个已提交游标。
 * 子调用在各自的协程上跑完，所以状态用锁保护（宿主回调全部是同步的，锁内不做挂起的事）。
 */
class SubCallOrder(private val size: Int) {
    private val settled = arrayOfNulls<SubCall>(size)
    private var committed = 0
    private val lock = Any()

    /**
     * 第 [index] 条跑完了。返回**此刻可以按提交序入日志**的那些（通常是 0 或 1 条：
     * 队头一直没结算时，后面跑完的会攒着，等队头一到就一次放出来）。
     */
    fun settle(index: Int, entry: SubCall): List<SubCall> = synchronized(lock) {
        settled[index] = entry
        flush(stopAtGap = true)
    }

    /**
     * 中断收尾：把已经跑完、还压在后面的那些按提交序放出来。
     *
     * 没跑完的号**永远不进日志**（dsh 的 `abandon()`：排队时被取消的子调用从不写日志），
     * 所以这里跳过空洞 —— 否则整条队会卡死在一个永远不会结算的号上。
     */
    fun drain(): List<SubCall> = synchronized(lock) { flush(stopAtGap = false) }

    private fun flush(stopAtGap: Boolean): List<SubCall> {
        val ready = ArrayList<SubCall>(1)
        while (committed < size) {
            val entry = settled[committed]
            if (entry == null) {
                if (stopAtGap) break
                committed++
                continue
            }
            ready += entry
            committed++
        }
        return ready
    }
}
