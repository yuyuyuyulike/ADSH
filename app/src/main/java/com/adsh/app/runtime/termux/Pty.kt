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

    init {
        val pidHolder = IntArray(1)
        val fd = Pty.createSubprocess(command, cwd, args, env, pidHolder, rows, cols, 0, 0)
        check(fd >= 0) { "createSubprocess failed for $command" }
        pid = pidHolder[0]
        master = ParcelFileDescriptor.adoptFd(fd)
        input = FileInputStream(master.fileDescriptor)
        output = FileOutputStream(master.fileDescriptor)
    }

    fun resize(rows: Int, cols: Int) = Pty.setPtyWindowSize(master.fd, rows, cols, 0, 0)

    fun waitFor(): Int = Pty.waitFor(pid)

    fun close() {
        runCatching { output.close() }
        runCatching { input.close() }
        runCatching { master.close() }
    }
}
