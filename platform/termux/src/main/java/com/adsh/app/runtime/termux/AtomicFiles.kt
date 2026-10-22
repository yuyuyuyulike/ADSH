package com.adsh.app.runtime.termux

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 覆盖写一个文本文件：读者要么看到旧内容、要么看到完整的新内容。
 *
 * 用于 [BootstrapInstaller] 的 MANIFEST.properties —— 它记录「装的是哪一版、链接指向哪个
 * nativeLibraryDir」，是下次启动决定「全量重装 / 只重链接 / 已装好」的唯一依据。直接
 * `writeText` 时进程被杀会留下半个文件，下次启动读不出它（[installPlan] 把「manifest 在但读不出」
 * 当「不知道装的是什么」），用户机器上就白丢一次完整安装。先写同目录临时件、fsync，再原地改名：
 * 同一文件系统内 rename 是原子的。
 */
internal fun writeTextAtomically(target: File, text: String) {
    val dir = target.parentFile
    dir?.mkdirs()
    val temp = File(dir, target.name + ".tmp")
    try {
        java.io.FileOutputStream(temp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            // fsync：改名能保证「看到的是新文件」，但不保证内容已经离开页缓存；断电/掉电重启时
            // f2fs 上可能留下一个长度对、内容空的 MANIFEST。
            out.fd.sync()
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.io.IOException) {
            // 目标已存在且平台不支持「原子改名覆盖」时（Windows 的文件语义），退回先删再改。
            // 走到这条路就失去了防崩溃保证，但读不到 manifest 只会退化成一次全量重装，
            // 不会把半个文件当成有效内容。
            target.delete()
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        runCatching { if (temp.exists()) temp.delete() }
    }
}
