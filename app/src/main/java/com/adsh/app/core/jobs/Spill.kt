package com.adsh.app.core.jobs

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * 命令输出的**全文 spill 文件** —— dsh `dsh-subprocess-local/lib/output.js` 里
 * `OutputCollector` 的另一半（第 185 轮按用户口径「以 dsh 为准」补上）。
 *
 * dsh 的原话（output.js 的类注释）：
 *  - Collect mode keeps the last `maxBytes` of a stream in memory —— **只留尾部**，
 *    理由是 errors and final results cluster at the end of command output；
 *  - 一旦越过内存上限就**开一个 spill 文件，把完整流写进去**（含已经收在内存里的那些块），
 *    the spill file covers the head；
 *  - 流超过 spill 上限（`maxSpillBytes` 默认 67,108,864）就把不完整的 spill **丢掉**，
 *    只返回带截断标记的尾部；
 *  - spill 是 best-effort：开文件/写文件失败就丢弃 spill、报一次，绝不打断内存收集。
 *
 * ADSH 先前没有这一层（`Jobs` 的 KDoc 写着「没有 spill 文件」），于是 dsh 那条
 * 「只留尾部」的理由不成立 —— 头部是真丢了（第 105 轮就把收集器改成「头 + 尾」来兜）。
 * 现在补齐 spill，收集器回到 dsh 的形状：**尾部在内存、全文在文件、路径交给模型**。
 *
 * 文件落在每进程一个的私有目录里（dsh 的 `privateSpillDir`：`mkdtempSync(join(tmpdir(), "dsh-subprocess-"))`）：
 * 一次性创建、不重建（被外部清理器删掉之后 spill 降级为「只有内存尾部」，与 dsh 同一条退路）。
 */
internal class Spill(
    private val dir: File,
    private val label: String,
    private val maxBytes: Long = MAX_SPILL_BYTES,
) {

    /** 已经完整写进文件时可以交给模型的那个路径；没落盘或已被丢弃时为 null */
    @Volatile
    var path: String? = null
        private set

    private var stream: FileOutputStream? = null
    private var file: File? = null
    private var dead = false

    /** 已经在写盘（调用方据此决定后面每块还要不要都送进来） */
    val active: Boolean get() = stream != null

    /** 已经放弃（超上限或 IO 失败）：调用方不必再送，也不会重开文件（dsh 的 spillDisabled） */
    val discarded: Boolean get() = dead

    /**
     * 记一块（dsh 的 `spillAll`）：第一次落盘时先把 [prior]（内存里已经收着的同一路输出）
     * 补写进去，再写这一块；[totalBytes] 是全流累计字节数，越过 [maxBytes] 就丢弃整个 spill。
     *
     * 任何 IO 失败都只落到 [dead]：收集器继续用内存尾部工作。
     */
    fun spill(totalBytes: Long, prior: List<ByteArray>, chunk: ByteArray) {
        if (dead) return
        if (totalBytes > maxBytes) {
            discard()
            return
        }
        try {
            var out = stream
            if (out == null) {
                dir.mkdirs()
                val target = File(dir, fileName())
                val opened = FileOutputStream(target)
                stream = opened
                file = target
                path = target.absolutePath
                out = opened
                for (block in prior) opened.write(block)
            }
            out.write(chunk)
        } catch (t: Throwable) {
            discard()
        }
    }

    /** 流结束了：关文件。关不掉就不再对外宣称这个路径（dsh 的 `seal`） */
    fun seal() {
        val out = stream ?: return
        stream = null
        try {
            out.close()
        } catch (t: Throwable) {
            file = null
            path = null
        }
    }

    /** 停止 spilling 并删掉文件（dsh 的 `discardSpill`：文件再也装不下完整流了） */
    private fun discard() {
        dead = true
        val out = stream
        val target = file
        stream = null
        file = null
        path = null
        runCatching { out?.close() }
        runCatching { target?.delete() }
    }

    private fun fileName(): String =
        "adsh-subprocess-" + seq.incrementAndGet() + "-" + randomSuffix() + "-" + label + ".log"

    private fun randomSuffix(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    companion object {
        /** dsh 的 `maxSpillBytes` 默认值（README：67,108,864） */
        const val MAX_SPILL_BYTES = 67_108_864L

        private val seq = AtomicInteger(0)

        /** 每进程一个 spill 目录（懒创建；被删掉之后不再重建 —— dsh 的同一条退路） */
        @Volatile
        private var directory: File? = null

        fun directory(base: File): File {
            directory?.let { return it }
            synchronized(this) {
                directory?.let { return it }
                val made = File(base, "adsh-subprocess-" + java.util.UUID.randomUUID().toString().replace("-", "").take(10))
                runCatching { made.mkdirs() }
                directory = made
                return made
            }
        }
    }
}
