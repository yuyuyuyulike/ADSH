package com.adsh.app.seam.exec

/**
 * 输出截断的**唯一一份**口径：**只留尾部**，全文在 spill 文件里 —— 尾部进上下文、头部进文件。
 *
 * 不变量：前台命令与后台任务的读（job_output、前台转后台的种子读）走同一份实现；尾部起点按
 * **UTF-8 安全尾**取（切点落在续字节上要往后跳）；提示只在真的丢过字节时补一行
 * （文案见 BashRender 的调用点）。
 */
object OutputCap {

    /** 尾部窗口的起点：首字节若落在某个多字节字符中间，就跳过续字节（10xxxxxx） */
    fun safeTailStart(bytes: ByteArray, from: Int = 0): Int {
        var i = from.coerceIn(0, bytes.size)
        while (i < bytes.size && (bytes[i].toInt() and 0xC0) == 0x80) i++
        return i
    }

    /**
     * 只留尾部的截断（超过 [maxBytes] 才动它）。
     * @return 截断后的文本与「这次真的丢了字节」的标记
     */
    fun tail(text: String, maxBytes: Int): Pair<String, Boolean> {
        val limit = maxBytes.coerceAtLeast(1)
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.size <= limit) return text to false
        val start = safeTailStart(raw, raw.size - limit)
        return String(raw, start, raw.size - start, Charsets.UTF_8) to true
    }
}
