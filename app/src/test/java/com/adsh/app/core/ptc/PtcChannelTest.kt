package com.adsh.app.core.ptc

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 控制通道的回归测试（第 184 轮真机事故）。
 *
 * 事故现场：测试 agent 让程序 `tools.read()` 一个 300KB 的单行文件，宿主回信封走的是
 * `Messenger.send` + `Bundle` —— 主进程抛
 * `TransactionTooLargeException: data parcel size 600568 bytes`，**整个 app 闪退**
 * （驱动它的线程就在主进程里）。这一组测试把 [PtcChannel] 的判据钉死：大帧必须过得去，
 * 超限/空帧/坏帧必须报协议错误（而不是异常崩溃），流断了必须能认出来。
 */
class PtcChannelTest {

    private fun frame(type: String, payload: String) = buildJsonObject {
        put(PtcProtocol.TYPE, type)
        put("payload", payload)
    }

    private fun reader(input: ByteArray, maxBytes: Long = PtcChannel.MAX_FRAME_BYTES): PtcChannel =
        PtcChannel(ByteArrayInputStream(input), ByteArrayOutputStream(), maxBytes)

    private fun protocolError(block: () -> Unit): String = try {
        block()
        throw AssertionError("expected PtcProtocolException")
    } catch (e: PtcProtocolException) {
        e.message.orEmpty()
    }

    /** 读进来的每一块最多 [chunk] 字节：帧被切碎也必须能拼回来 */
    private class Dribble(private val source: ByteArray, private val chunk: Int) : InputStream() {
        private var offset = 0

        override fun read(): Int = if (offset < source.size) source[offset++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (offset >= source.size) return -1
            val n = minOf(chunk, len, source.size - offset)
            System.arraycopy(source, offset, b, off, n)
            offset += n
            return n
        }
    }

    @Test
    fun thePayloadThatCrashedTheAppRoundTrips() {
        // 真机那一帧 600568 字节（600KB 的 Bundle 就炸了）；这里给到 700KB
        val payload = "Q".repeat(700_000)
        val wire = ByteArrayOutputStream()
        PtcChannel(ByteArrayInputStream(ByteArray(0)), wire).send(frame(PtcProtocol.TYPE_DONE, payload))

        val received = reader(wire.toByteArray()).receive()
        assertEquals(PtcProtocol.TYPE_DONE, PtcProtocol.typeOf(received))
        assertEquals(payload, PtcProtocol.text(received, "payload"))
    }

    @Test
    fun consecutiveFramesAreReadOneByOne() {
        val wire = ByteArrayOutputStream()
        val sender = PtcChannel(ByteArrayInputStream(ByteArray(0)), wire)
        sender.send(frame(PtcProtocol.TYPE_READY, "1"))
        sender.send(frame(PtcProtocol.TYPE_CALL, "2"))
        sender.send(frame(PtcProtocol.TYPE_DONE, "3"))

        val channel = reader(wire.toByteArray())
        assertEquals("1", PtcProtocol.text(channel.receive(), "payload"))
        assertEquals("2", PtcProtocol.text(channel.receive(), "payload"))
        assertEquals("3", PtcProtocol.text(channel.receive(), "payload"))
        assertNull("流在帧边界上结束必须是 null", channel.receive())
    }

    @Test
    fun aFrameSplitAcrossReadsIsReassembled() {
        val wire = ByteArrayOutputStream()
        PtcChannel(ByteArrayInputStream(ByteArray(0)), wire)
            .send(frame(PtcProtocol.TYPE_BOOT, "x".repeat(5_000)))

        val channel = PtcChannel(Dribble(wire.toByteArray(), 3), ByteArrayOutputStream())
        assertEquals("x".repeat(5_000), PtcProtocol.text(channel.receive(), "payload"))
        assertNull(channel.receive())
    }

    @Test
    fun anEmptyFrameIsAProtocolError() {
        val message = protocolError { reader(byteArrayOf(0, 0, 0, 0)).receive() }
        // dsh 原文：control frame exceeds N bytes or is empty
        assertTrue(message, message.contains("or is empty"))
    }

    @Test
    fun aFrameOverTheCapIsAProtocolErrorAndIsNotAllocated() {
        val message = protocolError { reader(byteArrayOf(0, 0, 0, 5), maxBytes = 4).receive() }
        assertTrue(message, message.startsWith("control frame exceeds 4 bytes"))
    }

    @Test
    fun aBodyThatIsNotAJsonObjectIsAProtocolError() {
        val body = "not json".toByteArray()
        val raw = byteArrayOf(0, 0, 0, body.size.toByte()) + body
        assertEquals("invalid control frame", protocolError { reader(raw).receive() })
    }

    @Test
    fun aFrameOverTheCapIsRefusedOnSend() {
        val channel = PtcChannel(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), maxBytes = 64)
        val message = protocolError { channel.send(frame(PtcProtocol.TYPE_DONE, "y".repeat(1_000))) }
        assertTrue(message, message.startsWith("control output exceeds 64 bytes"))
    }

    @Test
    fun aTruncatedFrameEndsTheChannel() {
        val wire = ByteArrayOutputStream()
        PtcChannel(ByteArrayInputStream(ByteArray(0)), wire).send(frame(PtcProtocol.TYPE_DONE, "z".repeat(100)))
        val raw = wire.toByteArray().copyOf(20)
        assertNull(reader(raw).receive())
    }
}

/**
 * 帧字段的读取必须对「缺字段 / null / 类型不对」都免疫：帧来自另一个进程，
 * 形状不能假定（dsh 同样逐字段校验，不合规就判协议错误）。
 */
class PtcFrameAccessTest {

    @Test
    fun accessorsIgnoreMissingNullAndMismatchedFields() {
        val frame = buildJsonObject {
            put("type", PtcProtocol.TYPE_CALL)
            put("id", 7)
            put("all", true)
            put("text", kotlinx.serialization.json.JsonNull)
            put("tools", buildJsonArray {
                add("bash")
                add(1)
            })
            put("object", buildJsonObject { put("nested", 1) })
        }
        assertEquals(PtcProtocol.TYPE_CALL, PtcProtocol.typeOf(frame))
        assertEquals(7, PtcProtocol.int(frame, "id"))
        assertEquals(7L, PtcProtocol.long(frame, "id"))
        assertTrue(PtcProtocol.flag(frame, "all"))
        assertFalse(PtcProtocol.flag(frame, "missing"))
        assertNull(PtcProtocol.text(frame, "text"))
        assertNull(PtcProtocol.text(frame, "missing"))
        assertNull(PtcProtocol.text(frame, "object"))
        assertNull(PtcProtocol.text(null, "type"))
        assertEquals(listOf("bash", "1"), PtcProtocol.strings(frame, "tools"))
        assertTrue(PtcProtocol.strings(frame, "missing").isEmpty())
    }
}
