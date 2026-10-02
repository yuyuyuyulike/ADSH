package com.adsh.app.runtime.termux

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

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

    init {
        val pidHolder = IntArray(1)
        val fd = Pty.createSubprocess(command, cwd, args, env, pidHolder, rows, cols, 0, 0)
        check(fd >= 0) { "createSubprocess failed for $command" }
        pid = pidHolder[0]
        master = ParcelFileDescriptor.adoptFd(fd)
        input = FileInputStream(master.fileDescriptor)
        output = FileOutputStream(master.fileDescriptor)
    }

    fun waitFor(): Int = Pty.waitFor(pid)

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

    fun close() {
        runCatching { output.close() }
        runCatching { input.close() }
        runCatching { master.close() }
    }
}
