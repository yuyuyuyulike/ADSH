package com.adsh.app.core.jobs

/**
 * 有界输出环（dsh-jobs-local 的 OutputRing）—— 原先嵌在 [Jobs] 里的两个私有类，零用例。
 *
 * 为什么值得单独钉：整个后台任务子系统里**只有这一处按字节算账**，而它的三条规则都是
 * 「错一位就静默丢数据」的那种：
 *  - 偏移是**已追加的 UTF-8 字节总数**（不是字符数、不是块数）；
 *  - trim 从头部**整块**丢，只推进 earliest，**已分配的偏移永不改动**；
 *  - 单独一块就超上限时只留它的 **UTF-8 安全尾**（绝不从码点中间切）并打 `gapBefore` ——
 *    切在续字节上会让模型看到一个半个汉字。
 *
 * 另外两条与它配套的口径也在这里：[utf8Tail]（安全尾本身）与 [settleRetainCap]（结算时的保留上限）。
 */

/** 环里的一块的内部形状（缓存字节长度，省掉重复编码） */
internal class RingChunk(
    val at: Int,
    val text: String,
    val bytes: Int,
    val channel: Jobs.Channel?,
    val gapBefore: Boolean,
)

/**
 * 追加整块落地；偏移是**已追加的 UTF-8 字节总数**。[retained] 是当前留在环里的字节数
 * （与 [total] 的差就是已经被头部淘汰掉的量）。
 */
internal class OutputRing {

    private val chunks = ArrayList<RingChunk>()

    /** 当前还在环里的字节数 */
    var retained = 0
        private set

    /** 从注册到现在**累计追加**的字节数（块偏移的基准，永不回退） */
    var total = 0
        private set

    /** 环里最老那个块（可能被截尾）的偏移；空环时等于 [total] */
    var earliest = 0
        private set

    /** 追加一块。空串不算一块（返回 false，什么都不动） */
    fun append(text: String, channel: Jobs.Channel?, gapBefore: Boolean, cap: Int): Boolean {
        if (text.isEmpty()) return false
        val bytes = text.toByteArray(Charsets.UTF_8).size
        chunks += RingChunk(total, text, bytes, channel, gapBefore)
        total += bytes
        retained += bytes
        trim(cap)
        return true
    }

    /**
     * 裁到 [cap] 字节：先从头部**整块**丢（只有一块时不再丢，留给下面那一步处理），
     * 再看剩下的唯一一块是不是自己就超了 —— 超了就只留它的 UTF-8 安全尾。
     * [cap] 小于 1 按 1 算（0 会让「留一块」这条不变量没法维持）。
     */
    fun trim(cap: Int) {
        val limit = cap.coerceAtLeast(1)
        while (retained > limit && chunks.size > 1) retained -= chunks.removeAt(0).bytes
        val single = chunks.singleOrNull()
        if (single != null && single.bytes > limit) {
            val raw = single.text.toByteArray(Charsets.UTF_8)
            val tail = utf8Tail(raw, limit)
            chunks[0] = RingChunk(
                at = single.at + raw.size - tail.size,
                text = String(tail, Charsets.UTF_8),
                bytes = tail.size,
                channel = single.channel,
                gapBefore = true,
            )
            retained = tail.size
        }
        earliest = if (chunks.isEmpty()) total else chunks[0].at
    }

    /**
     * 与 `[from, total)` 相交的块（**新对象**）；offset 落在某块内部时**整块**返回
     * （dsh 的同一条规则：调用方自己按偏移裁）。
     *
     * 返回的布尔 = 这次读是不是 **lossy**（[from] 落在 [earliest] 之前 = 有一部分已经被淘汰了）。
     */
    fun readFrom(from: Int): Pair<List<Jobs.Chunk>, Boolean> {
        val out = ArrayList<Jobs.Chunk>()
        for (chunk in chunks) {
            if (chunk.at + chunk.bytes <= from) continue
            out += Jobs.Chunk(chunk.at, chunk.text, chunk.channel, chunk.gapBefore)
        }
        return out to (from < earliest)
    }
}

/**
 * 结算时的保留上限：**模型游标还没读过的字节一定要留到它第一次终态读**
 * （dsh 的 `max(settledRetainBytes, total - modelCursor)`）。
 * 差值是负的（游标被推到 total 之后，理论上不该发生）也不会低于 settled 那个下限。
 */
internal fun settleRetainCap(total: Int, modelCursor: Int): Int =
    maxOf(Jobs.SETTLED_RETAIN_BYTES, total - modelCursor)

/** 一块输出的 UTF-8 安全尾：切点若落在续字节上就**往后跳过**（dsh 的 utf8Tail） */
internal fun utf8Tail(raw: ByteArray, maxBytes: Int): ByteArray {
    var start = (raw.size - maxBytes).coerceAtLeast(0)
    while (start < raw.size && (raw[start].toInt() and 0xC0) == 0x80) start++
    return raw.copyOfRange(start, raw.size)
}
