package com.adsh.app.runtime.termux

/**
 * bootstrap 安装器里那几处**判定**（原先埋在 [BootstrapInstaller] 的 572 行里，整个 package
 * 只有 [OutputCollectorTest] 一个用例文件，这些判定一条都没被钉过）。
 *
 * 为什么值得单独钉：这个文件的判定判错，代价是全库最高的一处 ——
 *  - 安装期把「已装好」判成「版本不同」会走到 [BootstrapInstaller.installLocked] 的
 *    `removeTree(prefix)`，把用户装了半年的 Termux（dpkg 数据库、用户自己 apt 装的包）整个删掉；
 *  - 重链接期（每次 APK 更新都会走）判错会把 dpkg 升级过的包**打回 bootstrap 的版本号**
 *    （真机实测：`lib/libxxhash.so.0` 被重建指向已被 apt 删掉的 `0.8.3`，链接悬空）；
 *  - 回退拷贝期判错会顺着**绝对路径**的符号链接去读内容，全新安装必死在
 *    `NoSuchFileException`。
 *
 * 而它们的输入输出全是字符串与布尔。这些函数**不碰文件系统**：`File.isFile` / `Os.readlink`
 * 的读取留在调用点，这里只拿结果做判断（也因此能在纯 JVM 单测里逐条打表）。
 */

// ------------------------------------------------------------------ 安装走哪条路

/** [BootstrapInstaller.installLocked] 的三种走法 */
internal sealed interface InstallPlan {

    /** 全量：解压 → 建链 → **删掉旧前缀** → rename（manifest 缺失/读不出，或版本不符） */
    data object Full : InstallPlan

    /** 只重建指向 nativeLibraryDir 的链接（APK 更新导致 nativeLibraryDir 变化，毫秒级） */
    data object Relink : InstallPlan

    /** 已装好（版本与 nativeLibraryDir 都对得上），除 ensureHome 与可能的 second stage 补跑外无事可做 */
    data object Installed : InstallPlan
}

/**
 * manifest 里记的版本/nativeLibraryDir + 当前 App 的两项 → 走哪条路。
 *
 * [installedVersion] 为 null 同时表示**两种情况**：manifest 不存在，以及 manifest 在但读不出
 * （文件损坏 / Properties 解析失败）。两种都只能当成「不知道装的是什么」→ 全量重装；
 * 这正是现状，也是刻意的取舍：manifest 是我们自己写的、唯一记录 nativeLibraryDir 的地方，
 * 读不出它就无法重建链接。
 *
 * 版本**相同**但 [installedNativeDir] 与当前 [nativeDir] 不一致（含 manifest 里压根没这个键，
 * 例如被手改过）→ 只重建链接，**绝不动前缀里的文件**。
 */
internal fun installPlan(
    installerVersion: Int,
    installedVersion: String?,
    installedNativeDir: String?,
    nativeDir: String,
): InstallPlan = when {
    installedVersion == null -> InstallPlan.Full
    installedVersion != installerVersion.toString() -> InstallPlan.Full
    installedNativeDir != nativeDir -> InstallPlan.Relink
    else -> InstallPlan.Installed
}

/** 全量安装遇到一个已经存在的前缀时的处置 */
internal sealed interface PrefixInstall {
    /** 前缀是一棵真实目录：把新 bootstrap 里**缺的东西补进去**，已存在的一律不动 */
    data object Merge : PrefixInstall

    /** 前缀不存在（或不是真实目录，例如老版本留下的自指链接）：清掉重建 */
    data object Replace : PrefixInstall
}

/**
 * 全量安装要不要删掉旧前缀。
 *
 * Termux 官方安装器只在前缀不存在时解压（"$PREFIX already exist" 就直接收工），所以升级
 * bootstrap 从不会删用户装的东西。ADSH 的 [BootstrapInstaller.INSTALLER_VERSION] 变化会走到
 * 全量安装；直接 removeTree(prefix) 会连用户 apt 装的包与 dpkg 数据库一起删掉 —— 这超出了
 * 「更新 bootstrap」的授权范围。
 *
 * [Merge] 只**补缺**：已存在的文件一律不动（用户 apt 升级过的库和程序优先于 bootstrap 里的旧
 * 版本 —— 全量覆盖会把 libc++ 这类库打回 bootstrap 版本，用户装的 chromium 当场链接失败）。
 * 缺的 SYMLINKS 与 exec 槽位随后由重链接补齐 / 修到当前 nativeLibraryDir。
 *
 * 判据用「真实目录」而不是「bin/bash 在」：半坏的前缀（bash 恰好缺了）也应该救，而不是清掉；
 * 只有符号链接（老方案留下的自指别名）不能合并 —— 往链接目标里写正是旧安装挂 ELOOP 的那条路。
 */
internal fun prefixInstallPlan(prefixIsRealDirectory: Boolean): PrefixInstall =
    if (prefixIsRealDirectory) PrefixInstall.Merge else PrefixInstall.Replace

/**
 * in-prefix manifest 读不出时，能不能用**前缀外那份副本**（\`filesDir/bootstrap-manifest.properties\`）？
 *
 * 副本在每次成功安装后与 in-prefix manifest 一起原子写。它能替代 in-prefix manifest 的唯一前提是
 * 前缀确实像一棵装好的树（bash 在）——否则「用户删了前缀」会被误判成「已装好」。
 * 这条路的典型触发是 manifest 写入时进程被杀 / 磁盘故障：树本身完好，副本能把它救回来，
 * 不会掉进 [InstallPlan.Full] 的 removeTree(prefix)（会删掉用户 apt 装的包与 dpkg 数据库）。
 */
internal fun useFallbackManifest(
    prefixManifestReadable: Boolean,
    prefixLooksInstalled: Boolean,
    fallbackReadable: Boolean,
): Boolean = !prefixManifestReadable && prefixLooksInstalled && fallbackReadable

/**
 * second stage（184 个包的 postinst）需不需要补跑：**脚本在、lock 不在**。
 *
 * 官方脚本开跑前会建 `termux-bootstrap-second-stage.sh.lock`（一个指向脚本自己的符号链接）
 * 保证只跑一次，且从不删除它（真机实测：`lock -> termux-bootstrap-second-stage.sh`）。
 * 于是「脚本在 + lock 不在」只有两种可能：从没跑过（老安装），或上次死在脚本建 lock 之前
 * （被系统杀掉 / 崩溃）。前者是「文件都在、包没配置」，后者官方也不管，这里在启动时补一次。
 *
 * [lockIsSymlink] 传的是**是不是符号链接**而不是「文件在不在」：lock 本身是链接，
 * 用 `File.exists()` 判会在链接悬空（脚本还在、链接目标被换掉）时得出相反结论。
 */
internal fun secondStagePending(scriptPresent: Boolean, lockIsSymlink: Boolean): Boolean =
    scriptPresent && !lockIsSymlink

// ------------------------------------------------------- $PREFIX/bin 里的一个槽位

/** `$PREFIX/bin` 下一个可执行槽位的处置（[BootstrapInstaller.linkExecutables]） */
internal sealed interface ExecLinkAction {

    /** 删掉再建（我们的槽位：安装期一律重建；重链接期只剩「指向旧 nativeLibraryDir 的悬空链接」） */
    data object Create : ExecLinkAction

    /** 一律不动：真实文件（dpkg / 用户装的）、别人的符号链接、以及**压根不存在**的槽位 */
    data object Skip : ExecLinkAction

    /** 已经指向当前 nativeLibraryDir：算一次成功，但不做任何文件操作 */
    data object KeepCurrent : ExecLinkAction
}

/**
 * 一次 APK 更新只该修**我们自己建的**链接：目标是 nativeLibraryDir 里的 `libbin_*.so`。
 *
 * 四种输入各自明确（前三者都是 Skip/KeepCurrent，都不碰文件）：
 *  - [existingTarget] == null：不是符号链接 —— 真实文件（dpkg / 用户装的，能不能跑由 termux-exec
 *    的 linker64 改写负责）、或压根不存在（dpkg 卸掉的东西不该被我们复活）；
 *  - 目标不以 `/<lib>` 结尾：别人的链接（update-alternatives 之类）；
 *  - 目标就是 [sourcePath]：已经指对了，不必删了重建；
 *  - 其余（以 `/<lib>` 结尾但指向别的路径）= **指向旧 nativeLibraryDir 的悬空链接** → 修。
 *
 * 安装期（[relink] = false，root 是我们刚解压出来的 staging）没有这层顾虑：那里的每一个
 * ELF 都是我们自己的，一律换成链接。
 */
internal fun execLinkAction(
    relink: Boolean,
    existingTarget: String?,
    lib: String,
    sourcePath: String,
): ExecLinkAction {
    if (!relink) return ExecLinkAction.Create
    if (existingTarget == null) return ExecLinkAction.Skip
    if (!existingTarget.endsWith("/" + lib)) return ExecLinkAction.Skip
    if (existingTarget == sourcePath) return ExecLinkAction.KeepCurrent
    return ExecLinkAction.Create
}

// ------------------------------------------------------------- SYMLINKS.txt 里的一条

/**
 * SYMLINKS.txt 里的这一条是不是**已经有主、别动**。
 *
 * 重链接期**只补缺**：已存在的条目一律不动 —— 这些链接的目标是**前缀内部的相对路径**
 * （`lib/libxxhash.so ← libxxhash.so.0`），与 nativeLibraryDir 无关，也就是说重链接期
 * 根本没有它们要修的东西；而 dpkg 升级过的库会被无条件重建打回 bootstrap 的版本号。
 * 补缺仍然必要：前缀被部分恢复（备份还原）时，缺的那几条要建出来。
 *
 * [present] 是**惰性探针**：安装期（[relink] = false）一次文件系统调用都不做 ——
 * 那棵树是我们刚解压出来的、全是真实文件，没什么可探的。探针本身必须把**悬空**的符号链接
 * 算作「在」（调用点的 `exists()` 是 `File.exists() || readlink() != null` 两问）：只看前者
 * 会把悬空链接当成缺口，重建出来的正是那个谁也加载不了的链接。
 */
internal fun keepExistingEntry(relink: Boolean, present: () -> Boolean): Boolean = relink && present()

// --------------------------------------------------------- 回退拷贝时一个目录项

/** 回退拷贝（rename 失败）时一个目录项的处置 */
internal sealed interface CopyEntry {

    /** 按符号链接**原样重建**（[target] 就是 readlink 读到的目标），绝不顺着它读内容 */
    data class Symlink(val target: String) : CopyEntry

    /** 递归下去 */
    data object Directory : CopyEntry

    /** 拷文件 + 尽量带上权限位 */
    data object File : CopyEntry
}

/**
 * 符号链接**优先于目录**：bootstrap 里有一批**绝对路径**的链接
 * （`etc/apt/trusted.gpg.d` 下的密钥链接指向 `/data/data/com.termux/files/usr/share/termux-keyring`），
 * 它们在 `usr-staging` 里必然悬空（目标要等这份树搬到 `usr` 之后才存在）。
 * 若先判目录，指向目录的链接会被递归进去读内容 —— 与 `copyRecursively` 顺着链接读
 * 死在 `NoSuchFileException: .../grimler.gpg` 是同一类错误。
 */
internal fun copyEntryKind(linkTarget: String?, isDirectory: Boolean): CopyEntry = when {
    linkTarget != null -> CopyEntry.Symlink(linkTarget)
    isDirectory -> CopyEntry.Directory
    else -> CopyEntry.File
}
