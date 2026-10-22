package com.adsh.app.runtime.termux

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** PTY JNI 门面（与 Termux 的 com.termux.terminal.JNI 语义一致）。 */
object Pty {
    init {
        System.loadLibrary("adshpty")
    }

    external fun createSubprocess(
        cmd: String,
        cwd: String?,
        args: Array<String>,
        envVars: Array<String>,
        pidOut: IntArray,
        rows: Int,
        cols: Int,
        xpix: Int,
        ypix: Int,
    ): Int

    external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int, xpix: Int, ypix: Int)
    external fun waitFor(pid: Int): Int
}

/** 一个跑在 PTY 上的会话：master 端在 App 进程，slave 端是子进程的 0/1/2。 */
class PtySession(
    command: String,
    args: Array<String> = emptyArray(),
    env: Array<String>,
    cwd: String?,
    rows: Int,
    cols: Int,
) {
    private val master: ParcelFileDescriptor
    val pid: Int
    val input: InputStream
    val output: OutputStream

    /** 当前 PTY 尺寸（行/列）——[resize] 用它去重，避免同一尺寸反复发 SIGWINCH */
    private var rows: Int = rows
    private var cols: Int = cols

    /** 收尾只做一次：离开终端页与读循环 EOF 是两条独立的到达路径 */
    private val closed = AtomicBoolean(false)

    /**
     * 回收子进程的线程（**唯一的 waitpid 调用者**）。PTY 的子进程是我们 fork 出来的，
     * 不 waitpid 就会一直是僵尸 —— 以前 `waitFor()` 没有任何调用者，用户敲 exit / 关掉终端页
     * 之后那个 bash 就以 `Z` 状态挂到 App 进程死掉。现在由这根线程独占回收，
     * [close] 只负责关 fd 与补信号，不再碰 waitpid。
     */
    private val reaper: Thread

    /** 退出码（reaper 填；null = 还在跑） */
    @Volatile
    private var exitCode: Int? = null

    init {
        val pidHolder = IntArray(1)
        val fd = Pty.createSubprocess(command, cwd, args, env, pidHolder, rows, cols, 0, 0)
        check(fd >= 0) { "createSubprocess failed for $command" }
        pid = pidHolder[0]
        master = ParcelFileDescriptor.adoptFd(fd)
        input = FileInputStream(master.fileDescriptor)
        output = FileOutputStream(master.fileDescriptor)
        reaper = Thread({
            exitCode = runCatching { Pty.waitFor(pid) }.getOrDefault(-1)
        }, "adsh-pty-wait").apply { isDaemon = true; start() }
    }

    /**
     * 视口变了就把 PTY 尺寸改掉（内核会向前台进程组发 SIGWINCH）。
     *
     * 终端里的程序（apt 的进度条、ls 的分栏、less 的分页）都按 PTY 尺寸排版，
     * 尺寸不对就会出现「明明只有 45 列宽，程序却按 100 列换行」。
     * 只有尺寸真的变了才发（readline 收到 SIGWINCH 会重画提示符）。
     */
    fun resize(rows: Int, cols: Int) {
        if (rows <= 0 || cols <= 0) return
        if (rows == this.rows && cols == this.cols) return
        this.rows = rows
        this.cols = cols
        runCatching { Pty.setPtyWindowSize(master.fd, rows, cols, 0, 0) }
    }

    /**
     * 关闭会话：先关 fd（内核随即向这个 PTY 会话发 SIGHUP），再确认子进程真的退出。
     *
     * pty_bridge.c 里子进程 `setsid()` 过，所以 **pid 就是它的进程组 id**：关掉 master 之后
     * 若还有后台任务 / 忽略 SIGHUP 的程序活着，就对 `-pid` 依次发 SIGTERM、SIGKILL。
     * 「关掉 fd」不等于「进程没了」——dsh 的 PTY teardown 同样 TERM → grace → KILL 并确认退出。
     *
     * 可能阻塞到 [PTY_REAP_GRACE_MS] + [PTY_TERM_GRACE_MS]；调用点都在后台线程上
     * （终端页的读循环 / onDispose）。
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { output.close() }
        runCatching { input.close() }
        runCatching { master.close() }
        if (awaitExit(PTY_REAP_GRACE_MS)) return
        // 会话里还有活口：先礼后兵。两个信号之间再确认一次，免得 pid 刚好被回收
        if (exitCode == null) runCatching { Os.kill(-pid, OsConstants.SIGTERM) }
        if (awaitExit(PTY_TERM_GRACE_MS)) return
        if (exitCode == null) runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
        awaitExit(PTY_KILL_GRACE_MS)
    }

    /** 等 reaper 收到退出码；true = 子进程已经退出 */
    private fun awaitExit(timeoutMs: Long): Boolean {
        reaper.join(timeoutMs)
        return !reaper.isAlive
    }
}

/** 关 master 之后等子进程自己退出（SIGHUP 送达通常几毫秒；给足慢机器） */
private const val PTY_REAP_GRACE_MS = 2_000L
/** 补 SIGTERM 之后等它退出 */
private const val PTY_TERM_GRACE_MS = 1_500L
/** 补 SIGKILL 之后等 waitpid 回来（内核立刻回收，不该等太久） */
private const val PTY_KILL_GRACE_MS = 500L
