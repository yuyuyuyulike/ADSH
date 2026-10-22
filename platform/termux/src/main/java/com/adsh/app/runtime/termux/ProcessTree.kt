package com.adsh.app.runtime.termux

import android.system.Os
import java.io.File

/**
 * 给整棵进程树发信号（dsh 的 signalChildGroup）。
 *
 * 安卓上没有 dsh 那条路：Node 用 `detached: true` 让子进程自成进程组，再 `kill(-pid)`；
 * 而 ProcessBuilder 起的子进程和 App 同组，对组发信号会把自己也杀掉。
 * 所以从 /proc 读出父子关系，按「先叶子、后根」逐个 kill。
 */
internal fun signalTree(rootPid: Int, signal: Int, procRoot: File = File("/proc")) {
    // 叶子在前（顺序与理由见 killOrder）：父进程先死的话，子进程会被 init 收养、再也找不回来
    for (pid in killOrder(rootPid, readProcessTable(procRoot))) {
        runCatching { Os.kill(pid, signal) }
    }
}

/**
 * 读一份 ppid → 子进程 的表。
 * /proc/<pid>/stat 的格式是 `pid (comm) state ppid …`，comm 里可能有空格和括号，
 * 所以要从**最后一个 ')'** 之后开始切。
 *
 * [procRoot] 是为了能在纯 JVM 单测里拿一棵假 /proc 目录逐条打表（真机固定是 /proc）。
 */
internal fun readProcessTable(procRoot: File = File("/proc")): Map<Int, List<Int>> {
    val table = HashMap<Int, MutableList<Int>>()
    val entries = runCatching { procRoot.list() }.getOrNull() ?: return table
    for (name in entries) {
        name.toIntOrNull() ?: continue
        val stat = runCatching { File(procRoot, name + "/stat").readText() }.getOrNull() ?: continue
        val ppid = ppidFromStat(stat) ?: continue
        table.getOrPut(ppid) { ArrayList() }.add(name.toInt())
    }
    return table
}
