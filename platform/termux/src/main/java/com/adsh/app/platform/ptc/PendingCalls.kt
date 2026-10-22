package com.adsh.app.platform.ptc

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 在途 `tools.x()` 的预算（dsh 的 `maxPendingCalls` / `maxMessageBytes` 两道上限）。
 *
 * 每个调用在宿主侧是一个协程；程序在一个循环里发几千个同时未结算的调用，就会把主进程的
 * 协程与内存吃光。dsh 在收到调用帧时先记账，超限直接把整次运行判成 `protocol` 失败
 * （原文：pending binding calls exceed configured limits）—— 这里保持同一条口径与文案。
 */
internal class PendingCalls(
    private val maxCalls: Int = MAX_CALLS,
    private val maxBytes: Long = MAX_BYTES,
) {

    private val calls = AtomicInteger(0)
    private val bytes = AtomicLong(0)

    /**
     * 登记一次调用；null = 放行，否则返回协议错误原文（调用方按 protocol failure 抛出去）。
     * 返回值只可能来自两个上限之一，释放走 [release]。
     */
    fun tryAcquire(sizeBytes: Long): String? {
        if (calls.incrementAndGet() > maxCalls) {
            calls.decrementAndGet()
            return ERROR
        }
        if (bytes.addAndGet(sizeBytes) > maxBytes) {
            bytes.addAndGet(-sizeBytes)
            calls.decrementAndGet()
            return ERROR
        }
        return null
    }

    fun release(sizeBytes: Long) {
        calls.decrementAndGet()
        bytes.addAndGet(-sizeBytes)
    }

    companion object {
        /** dsh 的 ptc-runtime-node config 默认值：128 个同时在途的绑定调用 */
        const val MAX_CALLS = 128

        /** dsh 的 maxMessageBytes 默认值：在途调用的参数总字节上限 */
        val MAX_BYTES: Long = PtcChannel.MAX_FRAME_BYTES

        /** dsh 的 protocolFailure 原文 */
        const val ERROR = "pending binding calls exceed configured limits"
    }
}
