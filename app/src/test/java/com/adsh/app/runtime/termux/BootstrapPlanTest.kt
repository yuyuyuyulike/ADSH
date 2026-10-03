package com.adsh.app.runtime.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bootstrap 安装器的四处判定（[installPlan] / [secondStagePending] / [execLinkAction] /
 * [keepExistingEntry] / [copyEntryKind]）。
 *
 * 这些以前埋在 572 行的安装器里、一条用例都没有，而判错的代价是全库最高的一处
 * （判成全量会删掉用户的前缀；重链接判错会把 dpkg 升级过的包打回 bootstrap 版本）。
 */
class BootstrapPlanTest {

    // ------------------------------------------------------------ 安装走哪条路

    @Test
    fun `没有 manifest：全量安装`() {
        assertEquals(InstallPlan.Full, installPlan(6, null, null, "/native/A"))
    }

    @Test
    fun `manifest 读不出（版本为空串）：全量安装`() {
        assertEquals(InstallPlan.Full, installPlan(6, "", "/native/A", "/native/A"))
    }

    @Test
    fun `版本不同：全量安装（libdir 相同也一样）`() {
        assertEquals(InstallPlan.Full, installPlan(6, "5", "/native/A", "/native/A"))
        assertEquals(InstallPlan.Full, installPlan(6, "7", "/native/B", "/native/A"))
    }

    @Test
    fun `版本相同 + libdir 变了：只重建链接`() {
        assertEquals(InstallPlan.Relink, installPlan(6, "6", "/native/A", "/native/B"))
    }

    @Test
    fun `版本相同 + manifest 里没有 libdir 这个键：也只重建链接`() {
        assertEquals(InstallPlan.Relink, installPlan(6, "6", null, "/native/B"))
    }

    @Test
    fun `版本与 libdir 都对得上：已装好`() {
        assertEquals(InstallPlan.Installed, installPlan(6, "6", "/native/A", "/native/A"))
    }

    @Test
    fun `版本号按文本比：前导零对不上`() {
        // manifest 是我们自己写的（"6"），手改成 "06" 就当版本不符 → 全量
        assertEquals(InstallPlan.Full, installPlan(6, "06", "/native/A", "/native/A"))
    }

    // --------------------------------------------------------- second stage 补跑

    @Test
    fun `脚本在、lock 不在：补跑`() {
        assertTrue(secondStagePending(scriptPresent = true, lockIsSymlink = false))
    }

    @Test
    fun `脚本在、lock 在：不跑`() {
        assertFalse(secondStagePending(scriptPresent = true, lockIsSymlink = true))
    }

    @Test
    fun `脚本不在：一律不跑（lock 的状态无关）`() {
        assertFalse(secondStagePending(scriptPresent = false, lockIsSymlink = false))
        assertFalse(secondStagePending(scriptPresent = false, lockIsSymlink = true))
    }

    // ------------------------------------------------------ $PREFIX/bin 的槽位

    private val current = "/native/B/libbin_bash.so"
    private val old = "/native/A/libbin_bash.so"

    @Test
    fun `安装期：一律重建（哪怕已经指对了）`() {
        assertEquals(ExecLinkAction.Create, execLinkAction(false, null, "libbin_bash.so", current))
        assertEquals(ExecLinkAction.Create, execLinkAction(false, old, "libbin_bash.so", current))
        assertEquals(ExecLinkAction.Create, execLinkAction(false, current, "libbin_bash.so", current))
    }

    @Test
    fun `重链接期：不是符号链接（dpkg 装的真实文件）→ 不动`() {
        assertEquals(ExecLinkAction.Skip, execLinkAction(true, null, "libbin_x.so", current))
    }

    @Test
    fun `重链接期：别人的链接（update-alternatives 之类）→ 不动`() {
        assertEquals(
            ExecLinkAction.Skip,
            execLinkAction(true, "/data/data/com.termux/files/usr/bin/xxhsum.real", "libbin_x.so", current),
        )
    }

    @Test
    fun `重链接期：指向旧 nativeLibraryDir 的悬空链接 → 修`() {
        assertEquals(ExecLinkAction.Create, execLinkAction(true, old, "libbin_bash.so", current))
    }

    @Test
    fun `重链接期：已经指向当前 nativeLibraryDir → 不动但算成功`() {
        assertEquals(ExecLinkAction.KeepCurrent, execLinkAction(true, current, "libbin_bash.so", current))
    }

    @Test
    fun `重链接期：只按 basename 认领（现状：别处的同名文件也当成我们的槽位）`() {
        // 判据是 endsWith("/" + lib)，不是「在某个 nativeLibraryDir 下」——
        // 用户自己 ln -s /sdcard/backup/libbin_bash.so $PREFIX/bin/bash 也会被重建。
        // 这是现状（有这个 basename 的只可能是 libbin_*.so，而那批就是我们发布的），用例钉住它。
        assertEquals(
            ExecLinkAction.Create,
            execLinkAction(true, "/sdcard/backup/libbin_bash.so", "libbin_bash.so", current),
        )
    }

    @Test
    fun `重链接期：basename 只是前缀相同（多一个 old 后缀）→ 不动`() {
        assertEquals(
            ExecLinkAction.Skip,
            execLinkAction(true, "/native/A/libbin_bash.so.old", "libbin_bash.so", current),
        )
    }

    // ----------------------------------------------------- SYMLINKS.txt 的条目

    @Test
    fun `安装期：SYMLINKS 表里每一条都建，连探针都不探`() {
        var probed = false
        assertFalse(keepExistingEntry(relink = false) { probed = true; true })
        assertFalse(probed)
    }

    @Test
    fun `重链接期：只补缺`() {
        assertFalse(keepExistingEntry(relink = true) { false })
        assertTrue(keepExistingEntry(relink = true) { true })
    }

    // ------------------------------------------------------- 回退拷贝的目录项

    @Test
    fun `指向目录的符号链接：按链接重建，不递归进去`() {
        assertEquals(CopyEntry.Symlink("/usr/lib/x"), copyEntryKind("/usr/lib/x", isDirectory = true))
    }

    /** staging 里那批**绝对路径**的链接在拷贝时必然悬空（目标是 usr 搬过去之后才存在） */
    @Test
    fun `悬空的符号链接：也按链接原样重建，目标原样带过去`() {
        val key = "/data/data/com.termux/files/usr/share/termux-keyring/grimler.gpg"
        assertEquals(CopyEntry.Symlink(key), copyEntryKind(key, isDirectory = false))
    }

    @Test
    fun `真目录与真文件`() {
        assertEquals(CopyEntry.Directory, copyEntryKind(null, isDirectory = true))
        assertEquals(CopyEntry.File, copyEntryKind(null, isDirectory = false))
    }
}
