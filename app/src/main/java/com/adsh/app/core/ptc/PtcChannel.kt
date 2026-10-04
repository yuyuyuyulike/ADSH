package com.adsh.app.core.ptc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.InputStream
import java.io.OutputStream

/** 控制通道上的协议错误：帧超限 / 空帧 / 正文不是 JSON 对象。对应 dsh 的 `kind = "protocol"`。 */
internal class PtcProtocolException(message: String) : Exception(message)

/**
 * `:ptc` 两个进程之间的**控制通道** —— dsh `JsonChannel` 的安卓版
 * （`dsh-ptc-runtime-node/lib/index.js` 的 `lib/types/channel.js` 区段）。
 *
 * dsh 的父子进程之间是一条**字节流**（spawn 出来的 `control` 管道），帧格式是
 * **4 字节大端长度 + UTF-8 JSON**，默认上限 `maxMessageBytes = 134217728`；读到空帧、
 * 超限帧、不是 JSON 一律判协议错误（`control frame exceeds N bytes or is empty`、
 * `invalid control frame`），**流断了**报
 * `control channel ended before the program settled` —— 两条路都不是进程级异常。
 *
 * 第 96 轮把程序搬进 `:ptc` 进程时用的是 `Messenger` + `Bundle`，第 184 轮真机事故
 * 证明那条路走不通：Binder 一次事务的缓冲只有约 1MB，而 `tools.read()` 的信封可以到几百 KB
 * —— 测试 agent 读一个 300KB 的单行文件时，主进程在 `Messenger.send` 上抛
 * `TransactionTooLargeException (data parcel size 600568 bytes)`，整个 app 闪退（驱动它的
 * 线程就在主进程里）。字节流没有这个上限：写会阻塞到对端读走为止。
 *
 * 只做帧，不做业务：谁在什么时候发什么帧由两侧的会话决定（[PtcProcess] / [PtcWorkerService]）。
 */
internal class PtcChannel(
    private val input: InputStream,
    private val output: OutputStream,
    private val maxBytes: Long = MAX_FRAME_BYTES,
) {

    /** 同一方向的写串行化：宿主侧「boot 帧」与「工具回执」来自两条线程 */
    private val writeLock = Any()

    /**
     * 发一帧（dsh 的 `JsonChannel.send`）。正文超过 [maxBytes] 直接判协议错误 ——
     * dsh 的 `control output exceeds N queued bytes`。
     */
    fun send(frame: JsonObject) {
        val body = frame.toString().toByteArray(Charsets.UTF_8)
        if (body.isEmpty() || body.size > maxBytes) {
            throw PtcProtocolException("control output exceeds " + maxBytes + " bytes")
        }
        val header = byteArrayOf(
            (body.size ushr 24).toByte(),
            (body.size ushr 16).toByte(),
            (body.size ushr 8).toByte(),
            body.size.toByte(),
        )
        synchronized(writeLock) {
            output.write(header)
            output.write(body)
            output.flush()
        }
    }

    /**
     * 读一帧：对端在**帧边界**上收尾（正常关闭）返回 null，调用方按
     * [PtcProtocol.CHANNEL_ENDED] 处理；帧中途断掉、长度非法、正文不是 JSON 对象都是
     * [PtcProtocolException]。
     */
    fun receive(): JsonObject? {
        val header = ByteArray(HEADER_BYTES)
        if (!readFully(header)) return null
        val length = ((header[0].toLong() and 0xFF) shl 24) or
            ((header[1].toLong() and 0xFF) shl 16) or
            ((header[2].toLong() and 0xFF) shl 8) or
            (header[3].toLong() and 0xFF)
        if (length == 0L || length > maxBytes) {
            throw PtcProtocolException("control frame exceeds " + maxBytes + " bytes or is empty")
        }
        val body = ByteArray(length.toInt())
        if (!readFully(body)) return null
        return runCatching { Json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject }
            .getOrNull() ?: throw PtcProtocolException("invalid control frame")
    }

    /** true = 读满；false = 流在中间结束了（对端关闭或被杀） */
    private fun readFully(target: ByteArray): Boolean {
        var read = 0
        while (read < target.size) {
            val n = input.read(target, read, target.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }

    companion object {
        /** 帧头：4 字节大端长度（dsh 的 `header.writeUInt32BE(body.length)`） */
        const val HEADER_BYTES = 4

        /** dsh 的 `maxMessageBytes` 默认值（`z.number().default(134217728)`）：帧长超过它判协议错误 */
        const val MAX_FRAME_BYTES = 134_217_728L
    }
}
