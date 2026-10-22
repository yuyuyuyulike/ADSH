package com.adsh.app.runtime.termux

import com.adsh.app.seam.exec.OutputCap
import com.adsh.app.seam.exec.Spill
import java.io.InputStream

/** 一条流的收尾（dsh 的 `OutputCollector.finalize()`）：内存尾部 + 是否丢过字节 + 全文路径 */
data class Collected(
    val text: String,
    val truncated: Boolean,
    val spillPath: String? = null,
)

/**
 * 读取子进程输出并限流 —— dsh `dsh-subprocess-local/lib/output.js` 的 `OutputCollector`。
 *
 * dsh 的口径：
 *  - **内存里只留尾部**（Collect mode keeps the last `maxBytes` of a stream in memory）——
 *    理由写在 dsh 的类注释里：errors and final results cluster at the end of command output；
 *  - 越过内存上限就开一个 **spill 文件把完整流写进去**（the spill file covers the head），
 *    于是「开头那段错误」不再丢，而是换了个地方等模型来读；
 *  - 正文只在真的丢过字节时才补一行 dsh 原文的提示（见 BashRender 的调用点）：
 *    `[output truncated; full output: <路径>]`。
 *
 * 只留尾部的前提是 spill：不落盘就只能两头都留一点（头 + 尾），那只是权宜。
 */
internal class OutputCollector(
    stream: InputStream,
    maxBytes: Int,
    /** 全文落盘（null = 只留内存尾部）；见 [com.adsh.app.seam.exec.Spill] */
    private val spill: Spill? = null,
) {
    /** 内存尾部上限（至少 1 字节，避免配置成 0 时在 append 里算出负长度） */
    private val limit = maxBytes.coerceAtLeast(1)

    /** 尾部用环形缓冲：窗口满了之后，最老的字节被新字节覆盖（dsh 的 chunks 滑动窗口） */
    private val tail = ByteArray(limit)
    private var tailLength = 0
    private var tailWrite = 0

    /** 从流里读到的总字节数 */
    private var total = 0L

    @Volatile var truncated = false
        private set

    /** 这一路读到的总字节数（截断后全文在 spill 文件里；结果里的 omittedBytes 用它算） */
    val totalBytes: Long get() = synchronized(this) { total }

    /** 已经收尾：之后的 [append] 一律丢弃，[finalize] 可重复调用 */
    private var sealed = false
    private var result: Collected? = null

    private val thread = Thread {
        try {
            val chunk = ByteArray(8192)
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                if (n == 0) continue
                synchronized(this) { append(chunk, n) }
            }
        } catch (_: Throwable) {
        }
    }.apply { isDaemon = true; start() }

    /**
     * dsh 的 `OutputCollector.push`，顺序逐条对齐：
     *  ① 先累计总字节（spill 判据用的是**含本块**的总数）；
     *  ② 越过内存上限（或已经在写盘）就把这一块交给 spill —— 第一次会把已经收在内存里的块补上；
     *  ③ 再进内存尾部，超了就从最老的字节开始丢。
     *
     * 只有**开 spill 文件那一次**需要 ② 里那份「已经收在内存里的尾巴」；文件开着之后
     * Spill 会忽略 prior，所以这里只在那一次拷它（大输出时这是每块 64 KiB 的纯浪费）。
     * 已经放弃 spill（超上限/IO 失败）之后一块都不再送 [Spill.spill]。
     */
    private fun append(chunk: ByteArray, n: Int) {
        if (sealed) return
        val next = total + n
        val overflows = tailLength + n > limit
        val sink = spill
        if (sink != null && !sink.discarded && (overflows || sink.active)) {
            val prior = if (overflows && !sink.active) retained() else emptyList()
            sink.spill(next, prior, chunk.copyOf(n))
        }
        total = next
        writeTail(chunk, 0, n)
        if (overflows) truncated = true
    }

    /** 此刻内存尾部里的字节（按最老到最新） */
    private fun retained(): List<ByteArray> {
        val bytes = linearTail()
        return if (bytes.isEmpty()) emptyList() else listOf(bytes)
    }

    private fun writeTail(chunk: ByteArray, offset: Int, count: Int) {
        if (count >= limit) {
            // 一个块就比内存上限还大：只留它自己的尾巴
            System.arraycopy(chunk, offset + count - limit, tail, 0, limit)
            tailLength = limit
            tailWrite = 0
            return
        }
        var src = offset
        var remaining = count
        while (remaining > 0) {
            val space = limit - tailWrite
            val take = minOf(space, remaining)
            System.arraycopy(chunk, src, tail, tailWrite, take)
            tailWrite = (tailWrite + take) % limit
            tailLength = minOf(limit, tailLength + take)
            src += take
            remaining -= take
        }
    }

    /** 等读线程结束；true = 流确实结束了（超时返回 false，调用方负责关读端） */
    fun join(millis: Long): Boolean {
        thread.join(millis)
        return !thread.isAlive
    }

    /**
     * 收尾（dsh 的 `finalize()`）：封住 spill 文件，交出**内存尾部的文本**、是否丢过字节、
     * 以及全文的落盘路径。文本开头若落在某个多字节字符中间就跳过续字节（10xxxxxx），
     * 免得模型看到一个半个汉字。
     */
    fun finalize(): Collected = synchronized(this) {
        result?.let { return it }
        sealed = true
        spill?.seal()
        val bytes = linearTail()
        val start = OutputCap.safeTailStart(bytes)
        Collected(
            text = String(bytes, start, bytes.size - start, Charsets.UTF_8),
            truncated = truncated,
            spillPath = spill?.path,
        ).also { result = it }
    }

    /** 环形缓冲拉直成「从最老到最新」的字节数组 */
    private fun linearTail(): ByteArray {
        if (tailLength == 0) return ByteArray(0)
        val out = ByteArray(tailLength)
        if (tailLength < limit) {
            System.arraycopy(tail, 0, out, 0, tailLength)
            return out
        }
        val first = limit - tailWrite
        System.arraycopy(tail, tailWrite, out, 0, first)
        System.arraycopy(tail, 0, out, first, tailWrite)
        return out
    }
}
