package com.adsh.app.seam.exec

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * 命令输出的**全文 spill 文件** —— dsh `dsh-subprocess-local/lib/output.js` 里 `OutputCollector`
 * 的另一半：内存只留尾部，越过上限就把完整流写进文件（含已收在内存里的块），路径交给模型。
 *
 * 上限 `maxSpillBytes`（默认 67,108,864）越过就丢弃整个 spill，只返回带截断标记的尾部；
 * best-effort：开文件/写文件失败就丢弃 spill，绝不打断内存收集。
 *
 * 文件落在每进程一个的私有目录里（`privateSpillDir` 同 dsh）：一次性创建、不重建 ——
 * 被外部清理器删掉之后降级为「只有内存尾部」，与 dsh 同一条退路。
 */
class Spill(
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
        SPILL_DIR_PREFIX + seq.incrementAndGet() + "-" + randomSuffix() + "-" + label + ".log"

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
                val made = File(base, SPILL_DIR_PREFIX + java.util.UUID.randomUUID().toString().replace("-", "").take(10))
                runCatching { made.mkdirs() }
                directory = made
                return made
            }
        }

        /** spill 目录与文件名的命名空间（列表里能一眼认出来，回收也靠它） */
        const val SPILL_DIR_PREFIX = "adsh-subprocess-"

        /** 子 shell 自报 pid 的标记文件前缀（崩溃残留由 [sweepStale] 回收） */
        const val PID_MARKER_PREFIX = "adsh-pid-"

        /** 非空 spill 目录与 pid 标记的保留时长：一天。更老的只可能是崩掉 / 被系统杀掉的进程留下的 */
        const val STALE_AFTER_MS: Long = 24 * 60 * 60 * 1000L

        /**
         * 回收临时目录里本进程命名空间的垃圾，返回删掉的数量。App 启动时调一次。
         *
         * 空 spill 目录没有可恢复的内容（没有任何一路输出溢出过），不看年龄直接删；非空的按
         * [maxAgeMs] 保留 —— 会话日志里可能还引用着里面的全文。当前进程正在写的目录、别的命名空间、
         * 同名普通文件一概不碰。
         */
        fun sweepStale(
            base: File,
            maxAgeMs: Long = STALE_AFTER_MS,
            now: Long = System.currentTimeMillis(),
        ): Int {
            val entries = runCatching { base.listFiles() }.getOrNull() ?: return 0
            val current = directory
            var removed = 0
            for (entry in entries) {
                if (entry == current) continue
                if (entry.name.startsWith(SPILL_DIR_PREFIX)) {
                    if (!entry.isDirectory) continue
                    val empty = runCatching { entry.list()?.isEmpty() == true }.getOrDefault(false)
                    if (!empty && now - entry.lastModified() < maxAgeMs) continue
                    if (runCatching { entry.deleteRecursively() }.getOrDefault(false)) removed++
                    continue
                }
                if (entry.name.startsWith(PID_MARKER_PREFIX) && entry.isFile && now - entry.lastModified() >= maxAgeMs) {
                    if (runCatching { entry.delete() }.getOrDefault(false)) removed++
                }
            }
            return removed
        }
    }
}
