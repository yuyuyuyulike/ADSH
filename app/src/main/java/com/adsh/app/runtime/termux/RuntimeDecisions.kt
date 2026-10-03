package com.adsh.app.runtime.termux

import com.adsh.app.core.tools.Escalation

/**
 * runtime/termux 里另外几处**判定**（原先埋在 [TermuxRuntime] 的 800 行里、零用例）。
 *
 * 三件事的共同点：判错都不会崩，只会**静默做错事** ——
 *  - 围栏（[fencePlan]）：判松了 = 只读预设下放行写；判紧了 = 完全权限却什么都写不了（第 64 轮）；
 *  - 杀进程树（[killOrder]）：顺序反了 = 子进程被 init 收养、再也找不回来（父先死）；
 *  - 读 /proc（[ppidFromStat]）：切错位置 = 进程树整个读空，[killOrder] 只拿得到根。
 *
 * 全是布尔与字符串进出，能在纯 JVM 单测里逐条打表。
 */

// ------------------------------------------------------------------ 写围栏

/** 这次调用要不要挂围栏 */
internal sealed interface FencePlan {

    /** 不挂：调用点返回空数组，子进程里只剩 [shellEnvironment] 给的显式「关」 */
    data object Off : FencePlan

    /** 挂：模式 + 可写白名单（read-only 的白名单就是**空表**，shim 认得这个形态） */
    data class On(val mode: String, val whitelist: List<String>) : FencePlan
}

/**
 * 白名单规范化：解析不出来的（null）、空的、根目录 "/" 全部丢掉，最后去重。
 *
 * 入参是**已经 canonical 过**的路径（调用点做 `canonicalPath`，失败给 null）：
 * canonical 会碰文件系统，提纯只留纯判断。
 *
 * 根目录必须丢：白名单里出现 "/" 等于「哪里都能写」，那是完全权限而不是围栏。
 */
internal fun fenceRoots(canonical: List<String?>): List<String> = canonical
    .filterNotNull()
    .filter { it.isNotEmpty() && it != "/" }
    .distinct()

/**
 * 三条「不挂」的路，顺序即优先级：
 *
 *  1. 模式不是只读/工作区可写（完全权限）→ 不挂。子进程里 ADSH_FENCE_MODE 就是
 *     [shellEnvironment] 给的那个「关」，fence.c 据此不做任何写判决；
 *  2. shim 不在（nativeLibraryDir 里没有 libadshfence.so）→ 不挂，**与白名单无关**；
 *  3. workspace-write 但**一个可写根都解析不出来** → 不挂（环境坏了，宁可不围）。
 *
 * 第 3 条**只管 workspace-write**：read-only 的白名单本来就是空的（dsh 的 writableRoots 在
 * read-only 下就是空列表），空表是正常形态而不是环境坏了 —— 这一处不对称是刻意的，
 * 写反了等于「只读预设下整条 bash 都不挂围栏」。
 *
 * 这条判定是**唯一真相**：挂 shim 却不给模式曾经被当成「workspace-write + 空白名单」，
 * 把完全权限变成什么都写不了（第 64 轮）。
 */
internal fun fencePlan(mode: String, shimPresent: Boolean, whitelist: List<String>): FencePlan {
    val confining = mode == Escalation.WORKSPACE_WRITE || mode == Escalation.READ_ONLY
    if (!confining) return FencePlan.Off
    if (!shimPresent) return FencePlan.Off
    if (whitelist.isEmpty() && mode != Escalation.READ_ONLY) return FencePlan.Off
    return FencePlan.On(mode, whitelist)
}

/**
 * 围栏那几个环境变量。**入参就是 [FencePlan.On]**：不挂的那条路（[FencePlan.Off]）由调用点
 * 在更早的地方返回空数组 —— 这里再判一次「Off 就给空表」是一句生产上永远走不到的分支
 * （只有用例会走），所以干脆让类型把这件事说清楚。
 *
 * `ADSH_FENCE_ACTIVE=1` 是「这次进程真的被围栏管着」的**唯一**来源（第 114 轮）。shim 侧从前
 * 会自己 setenv 这个变量，两个问题：一是它毁掉本进程之后所有的 getenv（bionic 的 setenv 在
 * 「环境还是内核给的那一份」时会把可见环境换成只含新变量的数组）—— workspace-write 下白名单
 * 因此永远解析成空表；二是它只说「shim 加载过」，完全权限下照样是 1（用户第 113 轮点名的假信号）。
 * 现在由 App 给，且只在真的挂了围栏时才有它。
 *
 * `ADSH_FENCE_ROOTS` 即使为空也**必须写出这一项**（`ADSH_FENCE_ROOTS=`）：空表就是
 * 「一个可写根都没有」，shim 靠「变量在不在」区分 read-only 与「调用点压根没给」。
 */
internal fun fenceVariables(plan: FencePlan.On, preload: String, mark: String?): List<String> = buildList {
    add("LD_PRELOAD=" + preload)
    add("ADSH_FENCE_ROOTS=" + plan.whitelist.joinToString(":"))
    add("ADSH_FENCE_MODE=" + plan.mode)
    add("ADSH_FENCE_ACTIVE=1")
    if (mark != null) add("ADSH_FENCE_MARK=" + mark)
}

// ---------------------------------------------------------------- 杀进程树

/**
 * 要发信号的 pid 顺序：**先叶子、后根**。
 *
 * 安卓上没有 dsh 那条路（Node 用 `detached: true` 让子进程自成进程组再 `kill(-pid)`），
 * ProcessBuilder 起的子进程和 App 同组，对组发信号会把自己也杀掉 —— 所以从 /proc 读出父子关系
 * 逐个 kill。父进程先死的话，子进程会被 init 收养、ppid 变成 1，就再也找不回来了。
 *
 * [seen] 那道闸只对**畸形表**生效（PID 复用、表读到一半都可能造出环）：正常表里每个 pid 只
 * 出现一次，结果完全一样；有环而不设闸的话这里会死循环 —— 而它跑在 App 的收尾路径上。
 */
internal fun killOrder(rootPid: Int, childrenOf: Map<Int, List<Int>>): List<Int> {
    val order = ArrayList<Int>()
    val seen = HashSet<Int>()
    val queue = ArrayDeque<Int>()
    queue.add(rootPid)
    while (queue.isNotEmpty()) {
        val pid = queue.removeFirst()
        if (!seen.add(pid)) continue
        order += pid
        childrenOf[pid]?.forEach { queue.add(it) }
    }
    return order.reversed()
}

/**
 * 从 `/proc/<pid>/stat` 里取 ppid。
 *
 * 格式是 `pid (comm) state ppid …`，而 **comm 里可能有空格和括号**（`(my prog)`、`(a)b)`），
 * 所以只能从**最后一个 ')'** 之后开始切。用第一个 ')' 或按空格切会读错 ppid，
 * 后果是进程树整个读空、[killOrder] 只拿得到根（子进程留成孤儿）。
 *
 * 畸形输入（没有 ')'、')' 后面没内容、ppid 不是数字）一律返回 null，调用点跳过这一行。
 */
internal fun ppidFromStat(stat: String): Int? {
    val close = stat.lastIndexOf(')')
    if (close < 0 || close + 2 >= stat.length) return null
    val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
    return fields.getOrNull(1)?.toIntOrNull()
}
