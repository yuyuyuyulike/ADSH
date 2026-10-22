package com.adsh.app.runtime.termux

import android.content.Context
import android.system.Os
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Termux bootstrap 的重定位安装器（运行期执行；运行期零下载）。
 *
 * 设计要点（真机实证，见 HANDOFF.md 的『不要破的硬规矩』一节）：
 *
 *  1) App 私有目录中的真实文件**不可 exec**，只有 nativeLibraryDir 下的可以；
 *  2) 但 App 私有目录中的**符号链接指向 nativeLibraryDir 时可 exec**（内核先解析链接再检查最终文件）；
 *  3) nativeLibraryDir 的路径在每次 APK 更新后都会变化 → 必须支持「只重建链接」的轻量重链接；
 *  4) **包名就是 com.termux（方案 A）**：官方前缀 /data/data/com.termux/files/usr 就是 App 自己的
 *     files/usr（/data/data 与 /data/user/0 是同一棵树）。Termux 的 339 个 ELF 里编译进去的路径
 *     天然成立：shebang、dpkg 的 instdir、update-alternatives、apt 的 Dir::Cache 全都不用照顾，
 *     于是**没有任何路径改写** —— 没有 proot、没有别名，运行期也不给 bootstrap 打补丁。
 *
 *     历史（第 22 节把代码删了，结论留在这里）：包名还不是 com.termux 时走的是「等长别名 +
 *     全量等长字节替换」—— 在数据目录里造一个与官方前缀同长度的符号链接，再把 339 个 ELF 与
 *     全部脚本里的前缀就地换掉。方案 A 之后那套代码全是死代码，而且留在设备上的自指符号链接
 *     会把安装挂在 ELOOP 上（真机踩过），所以现在安装时只做一件事：**保证家目录是真实目录**。
 *
 * 安装分两步：解压（重）→ 建链接（轻）。manifest 记录 nativeLibraryDir，
 * 变化时只跑第二步。
 */
class BootstrapInstaller(private val context: Context) {

    companion object {

        /** Termux 的编译期前缀：339 个 ELF 里硬编码的就是它 */
        const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

        /** Termux 的官方家目录 */
        const val TERMUX_HOME = "/data/data/com.termux/files/home"

        /**
         * 安装逻辑或输入变化时必须递增。
         * 4：不再改写文本文件里的前缀（试过 proot，IO 损耗不可接受，已放弃）。
         * 5：等长别名 + 全量等长字节替换（ELF 也一起换），并给 apt 写缓存目录配置。
         * 6：方案 A（包名 = com.termux）：别名 / 前缀改写 / apt 缓存覆盖全部停用 ——
         *    官方前缀真实存在，再「建别名」只会造出自指符号链接，安装必挂在 ELOOP。
         *    第 22 节把 4/5 那套死代码删掉了，磁盘产物一个字节没变，所以版本号不 +1
         *    （+1 会强制重解压并重跑 184 个 postinst，对已装好的设备纯属浪费）。
         * 7：随包预置 Termux glibc 运行时（$PREFIX/glibc + $PREFIX/bin/grun），让 glibc 动态
         *    链接的外部二进制能通过 grun 跑；全量安装遇到已存在的前缀仍是合并且只补缺。
         */
        const val INSTALLER_VERSION = 7

        private const val MANIFEST = "MANIFEST.properties"

        /** 安装是「删暂存目录 → 解压 → 改名」的多步操作，两个调用者同时进来会互相删文件 */
        private val INSTALL_LOCK = Any()

        /**
         * in-prefix manifest 的副本（放在 `filesDir`，与 `usr` 分开）。
         *
         * 前缀里的那份可能因为进程被杀 / 磁盘故障而读不出；读不出时 [installPlan] 会判「全量安装」，
         * 而全量安装要 removeTree(prefix) —— 用户 apt 装的包会一起没。副本只要版本与 nativeLibraryDir
         * 对得上就能把这次安装救回来（见 [useFallbackManifest]）。
         */
        private const val EXTERNAL_MANIFEST = "bootstrap-manifest.properties"

        /** bootstrap second stage（184 个 postinst）的墙钟上限：超时就杀掉，不让它拖住首启 */
        private const val SECOND_STAGE_TIMEOUT_SEC = 300L

        /** bootstrap second stage 的脚本与它的 lock 文件（相对 $PREFIX） */
        private const val SECOND_STAGE_SCRIPT =
            "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh"
        private const val SECOND_STAGE_LOCK = SECOND_STAGE_SCRIPT + ".lock"

        /** second stage 的输出只留这么多字符 / 行进 logcat */
        private const val SECOND_STAGE_LOG_CHARS = 16 * 1024
        private const val SECOND_STAGE_LOG_LINES = 40

        /**
         * 全量安装前要求的最小剩余空间（约 2 × bootstrap 解压体积 + glibc 运行时）。
         *
         * bootstrap 解压后约 90 MB（3773 个文件），随包的 glibc 运行时再占约 10 MB；安装期间
         * staging 与旧前缀同时存在，峰值约 201 MB。低于这个数就在解压**之前**报一句能读懂的错误，
         * 而不是写到一半 ENOSPC。
         */
        private const val MIN_FREE_BYTES = 220L * 1024 * 1024

        /**
         * 需要执行位的路径前缀 —— 逐字照抄官方 TermuxInstaller 的判定
         * （bin/、libexec、lib/apt/apt-helper、lib/apt/methods）。
         * 官方是用 FileOutputStream 写文件后 chmod 0700，因为 zip 里的权限位它也不读。
         */
        private val EXEC_PREFIXES = listOf(
            "bin/", "libexec", "lib/apt/apt-helper", "lib/apt/methods",
            // glibc 预置：加载器要能被 exec（PT_INTERP 指回它），grun 在 bin/ 下已覆盖；
            // 其余 glibc 文件只被 mmap / 读，逐个 chmod 会拖慢首装。
            "glibc/bin/", "glibc/lib/ld-linux-aarch64.so.1",
        )
    }

    private val filesDir = context.filesDir

    /**
     * 真实前缀。
     *
     * **路径文本必须用官方那一串**（/data/data/com.termux/files/usr）：Termux 的 ELF 与脚本里
     * 编译进去的就是它，环境变量 PREFIX / TERMUX__PREFIX 也要与它逐字一致。不能用 filesDir 拼
     * ——那是 /data/user/0/...，与 /data/data/... 是同一棵树的两种写法，但字符串不一样，
     * 逐字比较的地方会不认。
     */
    val prefix = File(TERMUX_PREFIX)
    private val staging = File(filesDir, "usr-staging")

    /** 官方家目录（同上：路径文本与二进制里的一致） */
    val home = File(TERMUX_HOME)

    fun ensureInstalled(log: (String) -> Unit): File = synchronized(INSTALL_LOCK) {
        BootstrapStatus.installing = true
        try {
            installLocked(log).also { BootstrapStatus.error = null }
        } catch (t: Throwable) {
            // 失败原因必须能被界面看到：只显示「未安装」的话，用户和日志都无从下手
            BootstrapStatus.error = t::class.java.simpleName + ": " + (t.message ?: "")
            throw t
        } finally {
            BootstrapStatus.installing = false
        }
    }

    /**
     * 删掉一个文件或一棵目录树。
     *
     * 不能直接用 deleteRecursively()：上一版在官方前缀上留下的**自指符号链接**它清不掉
     * （walk 不下去，直接当叶子跳过），于是 rename 失败、copy 撞 ELOOP，整个安装报废。
     * 先 unlink 一次链接兜住这类残留（删的是链接本身，不会动它的目标）。
     */
    private fun removeTree(file: File): Boolean {
        if (readLink(file) != null) file.delete()
        return file.deleteRecursively()
    }

    /**
     * 暂存目录 → 前缀：先试 rename（同一个文件系统里是原子的、毫秒级），失败再回退到拷贝。
     *
     * 用 `Files.move` 而不是 `File.renameTo`：后者只回一个 boolean，**失败原因全丢了** ——
     * 全新安装上撞到的正是这个：rename 静默失败、回退拷贝又死在悬空符号链接上，
     * 日志里只剩一句「rename failed」，谁也说不清为什么。
     */
    private fun moveTree(from: File, to: File, log: (String) -> Unit): Boolean = try {
        java.nio.file.Files.move(
            from.toPath(),
            to.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
        true
    } catch (t: Throwable) {
        log("rename " + from.name + " -> " + to.name + " failed: " + t::class.java.simpleName + ": " + t.message)
        false
    }

    /**
     * 回退用的递归拷贝：**必须按符号链接原样重建**，不能顺着链接去读内容。
     *
     * `copyRecursively` 会 `FileInputStream(link)` —— bootstrap 里有一批**绝对路径**的链接
     * （`etc/apt/trusted.gpg.d` 下的密钥链接指向 `/data/data/com.termux/files/usr/share/termux-keyring`），
     * 它们在 `usr-staging` 里必然是悬空的（目标要等这份树搬到 `usr` 之后才存在），
     * 于是整个安装死在 `NoSuchFileException: .../grimler.gpg`（全新安装必现）。
     */
    private fun copyTree(source: File, target: File) {
        target.mkdirs()
        source.listFiles()?.forEach { child ->
            val out = File(target, child.name)
            when (val entry = copyEntryKind(readLink(child), child.isDirectory)) {
                is CopyEntry.Symlink -> {
                    out.delete()
                    runCatching { Os.symlink(entry.target, out.absolutePath) }
                }
                CopyEntry.Directory -> copyTree(child, out)
                CopyEntry.File -> {
                    // 目标可能是上一版的符号链接（例如 $PREFIX/bin 里的 exec 链接）：
                    // 不先删掉的话 FileOutputStream 会顺着链接写进 nativeLibraryDir 里的真实文件
                    if (readLink(out) != null) out.delete()
                    child.copyTo(out, overwrite = true)
                    // copyTo 不带权限位：执行位在 bin/ 与 libexec 下是有意义的（脚本、apt 方法）
                    runCatching {
                        val mode = Os.stat(child.absolutePath).st_mode and 0x1FF
                        if (mode != 0) Os.chmod(out.absolutePath, mode)
                    }
                }
            }
        }
    }

    /**
     * 合并安装用的递归拷贝：只创建**缺失**的目录项，已存在的一律不动。
     *
     * 为什么不是覆盖：前缀里可能有用户 apt 升级过的库 / 程序（dpkg 的版本比 bootstrap 新）。
     * 覆盖会把 libc++ 这类共享库打回 bootstrap 版本，用户装的 chromium 等程序当场链接失败
     * （真机实测：全量覆盖后 `CANNOT LINK EXECUTABLE … libc++_shared.so`）。缺的东西照建；
     * 已存在的东西归 dpkg / 用户，bootstrap 只负责「这棵树完整」。
     *
     * 符号链接同 [copyTree]：按链接原样重建，绝不顺着读内容。
     */
    private fun copyMissingTree(source: File, target: File) {
        target.mkdirs()
        source.listFiles()?.forEach { child ->
            val out = File(target, child.name)
            when (val entry = copyEntryKind(readLink(child), child.isDirectory)) {
                is CopyEntry.Symlink -> {
                    if (exists(out)) return@forEach
                    runCatching { Os.symlink(entry.target, out.absolutePath) }
                }
                CopyEntry.Directory -> copyMissingTree(child, out)
                CopyEntry.File -> {
                    if (exists(out)) return@forEach
                    runCatching { child.copyTo(out, overwrite = false) }
                    // copyTo 不带权限位：执行位在 bin/ 与 libexec 下是有意义的（脚本、apt 方法）
                    runCatching {
                        val mode = Os.stat(child.absolutePath).st_mode and 0x1FF
                        if (mode != 0) Os.chmod(out.absolutePath, mode)
                    }
                }
            }
        }
    }

    private fun installLocked(log: (String) -> Unit): File {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val manifest = File(prefix, MANIFEST)

        // manifest 不存在与读不出是同一件事（见 installPlan 的 KDoc）：都当「不知道装的是什么」；
        // 但先看前缀外的副本 —— 它存在且前缀像装好的树时，副本就是这次安装的真相（见 [useFallbackManifest]）
        val props = readManifest(manifest)
        val external = readManifest(File(filesDir, EXTERNAL_MANIFEST))
        val recovered = useFallbackManifest(
            prefixManifestReadable = props?.getProperty("installerVersion") != null,
            prefixLooksInstalled = File(prefix, "bin/bash").isFile,
            fallbackReadable = external?.getProperty("installerVersion") != null,
        )
        if (recovered) log("prefix manifest is unreadable; recovered the copy outside the prefix")
        val effective = if (recovered) external else props
        return when (
            installPlan(
                INSTALLER_VERSION,
                effective?.getProperty("installerVersion"),
                effective?.getProperty("nativeLibraryDir"),
                nativeDir,
            )
        ) {
            InstallPlan.Installed -> {
                ensureHome()
                if (recovered) {
                    // 把前缀里的那份补回来，下一次启动就不用再走副本
                    writeManifest(nativeDir, effective?.getProperty("entries") ?: "?", effective?.getProperty("secondStage") ?: "?")
                }
                // 上次死在 second stage 开跑之前（被系统杀掉 / 崩溃）：脚本的 lock 还没建，
                // 说明 postinst 一个都没跑完，这里补一次。跑过之后 lock 一直在，
                // 逻辑与官方一致（官方也从不删这个 lock）。
                if (shouldRunSecondStage()) {
                    log("bootstrap second stage missing its lock; running it now")
                    val status = runSecondStage(log)
                    log("re-linked executables after second stage: " + linkExecutables(prefix, log, relink = true))
                    writeManifest(nativeDir, effective?.getProperty("entries") ?: "?", status)
                }
                log("already installed: " + prefix.absolutePath)
                prefix
            }
            InstallPlan.Relink -> {
                // APK 更新导致 nativeLibraryDir 变化：只重建链接，毫秒级
                val started = System.currentTimeMillis()
                ensureHome()
                ensureAptCache()
                // 重链接期：只修我们自己的链接，dpkg / 用户装的一律不动（见两个函数的注释）
                val links = createSymlinks(prefix, log, relink = true)
                val exes = linkExecutables(prefix, log, relink = true)
                writeManifest(
                    nativeDir,
                    effective?.getProperty("entries") ?: "?",
                    effective?.getProperty("secondStage") ?: "?",
                )
                log("relinked after app update: symlinks=" + links + ", executables=" + exes +
                    " in " + (System.currentTimeMillis() - started) + "ms")
                prefix
            }
            InstallPlan.Full -> fullInstall(nativeDir, log)
        }
    }

    /**
     * 全量安装：解压 → 建链 → 换前缀 → second stage → 写 manifest。
     *
     * 顺序不能改（解压 → 建链 → 落前缀）：链接的目标要等这棵树搬到 usr 之后才存在，
     * 所以链接建在 staging 里、跟着树一起搬。second stage 放在写 manifest **之前** ——
     * 中途被杀掉的话下次启动会重来一遍（解压只要 0.6s）。
     *
     * 旧前缀的处置见 [prefixInstallPlan]：真实目录是**合并**（用户 apt 装的东西保留），
     * 只有不存在 / 是符号链接时才清掉重建。
     */
    private fun fullInstall(nativeDir: String, log: (String) -> Unit): File {
        val started = System.currentTimeMillis()
        ensureFreeSpace(log)
        removeTree(staging)
        staging.mkdirs()

        val entries = extract()
        log("extracted " + entries + " files in " + (System.currentTimeMillis() - started) + "ms")

        ensureHome()
        ensureAptCache()

        val links = createSymlinks(staging, log)
        log("created " + links + " symlinks")

        val executables = linkExecutables(staging, log)
        log("linked executables to nativeLibraryDir: " + executables)

        val strategy = prefixInstallPlan(prefix.isDirectory && readLink(prefix) == null)
        if (strategy == PrefixInstall.Merge) {
            // 只补缺：已存在的文件一律不动。用户 apt 升级过的库/程序优先于 bootstrap 里的旧版本；
            // 覆盖会把 libc++ 这类库打回 bootstrap 版本，用户装的 chromium 之类当场链接失败。
            log("prefix exists; merging the missing bootstrap files (installed files are kept)")
            // SYMLINKS 表要跟着新 bootstrap 更新：它的用途只是「补缺时建哪些链接」
            runCatching { File(staging, "SYMLINKS.txt").takeIf { it.isFile }?.copyTo(File(prefix, "SYMLINKS.txt"), overwrite = true) }
            copyMissingTree(staging, prefix)
            removeTree(staging)
            log("merged symlinks: " + createSymlinks(prefix, log, relink = true))
            log("re-linked merged executables: " + linkExecutables(prefix, log, relink = true))
        } else {
            removeTree(prefix)
            // rename 失败最常见的两个原因：目标还在（删不干净）或者跨挂载点。前者先自己说清楚
            if (prefix.exists()) log("prefix still exists after removeTree: " + prefix.absolutePath)
            if (!moveTree(staging, prefix, log)) {
                log("rename failed; falling back to copy")
                copyTree(staging, prefix)
                removeTree(staging)
            }
        }
        // 官方首次安装的收尾：跑 bootstrap second stage（也就是那 184 个包的 postinst）。
        // Termux 应用是「解压 → 跑 second stage」两步，少了第二步就只是「文件都在、
        // 包没配置」：alternatives、ldconfig 缓存、CA 证书这些都在这步生成。失败不致命，
        // 只记日志（官方也是「second stage 挂了 shell 照样能用」）。
        // 放在写 manifest 之前：中途被杀掉的话下次启动会重来一遍（解压只要 0.6s）。
        val secondStage = runSecondStage(log)
        log("re-linked executables after second stage: " + linkExecutables(prefix, log, relink = true))
        writeManifest(nativeDir, entries.toString(), secondStage)
        log("install finished in " + (System.currentTimeMillis() - started) + "ms")
        return prefix
    }

    /**
     * 全量安装前的空间预检（见 [MIN_FREE_BYTES]）。
     *
     * 拿不到 statvfs（极少数定制内核）就跳过 —— 空间不足最终仍会由写失败兜住，
     * 预检只是把「写到一半 ENOSPC」换成一句带数字的报错。
     */
    private fun ensureFreeSpace(log: (String) -> Unit) {
        val stats = runCatching { Os.statvfs(filesDir.absolutePath) }.getOrNull() ?: return
        val blockSize = stats.f_frsize.takeIf { it > 0 } ?: stats.f_bsize
        val available = stats.f_bavail * blockSize
        val needMb = (MIN_FREE_BYTES / (1024 * 1024)).toString()
        val haveMb = (available / (1024 * 1024)).toString()
        log("free space: " + haveMb + " MB available, " + needMb + " MB required")
        if (available < MIN_FREE_BYTES) {
            throw java.io.IOException(
                "not enough free space to install the embedded Termux bootstrap: need about " +
                    needMb + " MB, only " + haveMb + " MB available",
            )
        }
    }

    /**
     * 每次安装与重链接都跑一遍：保证家目录是**真实目录**。
     *
     * 真机教训：包名还不是 com.termux 时这里会建一个「等长别名」符号链接指向家目录；方案 A 之后
     * 链接与目标变成同一个 inode（自指），随后任何 open 都是 ELOOP，安装挂在 SYMLINKS.txt 上，
     * 界面只剩一句「bootstrap 未安装」。老设备可能还留着那条链接，所以先看是不是符号链接。
     */
    private fun ensureHome() {
        if (readLink(home) != null) home.delete()
        home.mkdirs()
    }

    private fun writeManifest(
        nativeLibraryDir: String,
        entries: String,
        secondStage: String,
    ) {
        val text = buildString {
            appendLine("installerVersion=" + INSTALLER_VERSION)
            appendLine("bootstrapVersion=bootstrap-2026.09.13-r1")
            appendLine("nativeLibraryDir=" + nativeLibraryDir)
            appendLine("prefix=" + prefix.absolutePath)
            appendLine("entries=" + entries)
            // second stage 的结果：ok / exit:<code> / timeout / missing
            appendLine("secondStage=" + secondStage)
        }
        // 两份同一时刻写：前缀里的那份给 apt 看到，前缀外的副本用来从「前缀 manifest 损坏」里恢复
        writeTextAtomically(File(prefix, MANIFEST), text)
        writeTextAtomically(File(filesDir, EXTERNAL_MANIFEST), text)
    }

    /** 读 manifest；文件不在返回 null（Properties 解析失败得到空表，由 [useFallbackManifest] 兜底） */
    private fun readManifest(file: File): Properties? {
        if (!file.isFile) return null
        return Properties().also { loaded -> runCatching { file.inputStream().use { loaded.load(it) } } }
    }

    /** second stage 的 lock：脚本在开跑前建的符号链接，跑过一次就一直在 */
    private fun secondStageLock(): File = File(prefix, SECOND_STAGE_LOCK)

    /** 需要补跑 second stage 吗（判定与理由见 [secondStagePending]） */
    private fun shouldRunSecondStage(): Boolean =
        secondStagePending(File(prefix, SECOND_STAGE_SCRIPT).isFile, readLink(secondStageLock()) != null)

    private fun extract(): Int {
        var count = 0
        context.assets.open("bootstrap/usr.zip").use { raw ->
            ZipInputStream(raw.buffered(1 shl 16)).use { zip ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(staging, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().buffered(1 shl 16).use { sink ->
                            while (true) {
                                val n = zip.read(buffer)
                                if (n <= 0) break
                                sink.write(buffer, 0, n)
                            }
                        }
                        if (EXEC_PREFIXES.any { entry.name.startsWith(it) }) {
                            // 0700 = 0x1C0（Kotlin 没有八进制字面量）；失败也无所谓：
                            // $PREFIX/bin 下的命令随后都会被替换成指向 nativeLibraryDir 的链接
                            runCatching { Os.chmod(out.absolutePath, 0x1C0) }
                        }
                        count++
                    }
                    zip.closeEntry()
                }
            }
        }
        return count
    }

    /**
     * Termux 官方的 bootstrap second stage：把 bootstrap 里 184 个包的 **postinst 维护脚本**
     * 跑一遍（解压安装不会执行它们，dpkg 数据库里却已经是 installed，所以必须补这一步）。
     *
     * 为什么必须跑（这是「文件都在、包没配置」和「真 Termux」的分界）：
     *  - update-alternatives 的 /etc/alternatives 链接、ldconfig 的 ld.so.cache、
     *    ca-certificates 的证书包、terminfo 数据库…… 全都只在这些 postinst 里生成；
     *  - 官方 app 也是这么做的（termux-app 的 TermuxInstaller 解压 → 跑这个脚本），
     *    脚本自己会建 termux-bootstrap-second-stage.sh.lock 保证只跑一次。
     *
     * 三个实现细节，都是从官方脚本的注释里抄的结论：
     *  1) 必须以 **bash** 运行（脚本头部就要求 $BASH_VERSION），而它自身在 zip 里没有执行位；
     *  2) 维护脚本在 app 私有目录里，targetSdk ≥ 29（安卓 10+）下**不可直接 exec**，所以必须挂 termux-exec
     *     （它会把这些路径改写成 /system/bin/linker64 <path>）—— 这就是它存在的意义；
     *  3) 失败不致命：官方也是「second stage 挂了，shell 照样能用」，这里只把输出记进日志。
     */
    private fun runSecondStage(log: (String) -> Unit): String {
        val script = File(prefix, SECOND_STAGE_SCRIPT)
        if (!script.isFile) {
            log("bootstrap second stage script not found; skipped")
            return "missing"
        }
        val bash = File(prefix, "bin/bash")
        if (!bash.isFile) {
            log("bootstrap second stage skipped: no " + bash.absolutePath)
            return "no-bash"
        }
        val process = try {
            ProcessBuilder(bash.absolutePath, script.absolutePath)
                .redirectErrorStream(true)
                // 184 个 postinst 里只要有一个 `read`，没有这一行就会挂到 300s 超时（
                // 与 TermuxRuntime.run 同一个理由：dsh 的 stdin 是 "ignore"）
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .directory(File("/"))
                .apply { environment().putAll(secondStageEnv()) }
                .start()
        } catch (t: Throwable) {
            log("bootstrap second stage failed to start: " + t.message)
            return "start-failed"
        }
        val started = System.currentTimeMillis()
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (output.length < SECOND_STAGE_LOG_CHARS) output.append(line).append('\n')
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = runCatching { process.waitFor(SECOND_STAGE_TIMEOUT_SEC, TimeUnit.SECONDS) }
            .getOrDefault(false)
        if (!finished) {
            log("bootstrap second stage timed out after " + SECOND_STAGE_TIMEOUT_SEC + "s; killing")
            runCatching { process.destroyForcibly() }
        }
        reader.join(2_000)
        val exit = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        log("bootstrap second stage exit=" + exit + " in " + (System.currentTimeMillis() - started) + "ms")
        // 只把尾部几行打进日志：postinst 的输出可能很长，logcat 有单条长度上限
        output.toString().trim().lines().takeLast(SECOND_STAGE_LOG_LINES)
            .forEach { log("  [second-stage] " + it) }
        return when {
            !finished -> "timeout"
            exit == 0 -> "ok"
            else -> "exit:" + exit
        }
    }

    /**
     * second stage 的运行环境：**与运行期共用同一份装配**（[shellEnvironment]），
     * 只把 LD_PRELOAD 换成「只有 termux-exec、没有写围栏」。
     *
     * 以前这里是手写的 18 条变量，与 [TermuxEnv] 各存一份真值 —— 漏掉
     * `ANDROID__BUILD_VERSION_SDK`（termux-exec 的 postinst 需要）、`TERMUX_APP__TARGET_SDK`、
     * `TERMUX_APP__UID` 这类变量正是两份清单漂移的典型结果。
     */
    private fun secondStageEnv(): Map<String, String> {
        // 候选与顺序见 TERMUX_EXEC_LIBS（运行期的 preloadValue 用的是同一份）
        val preload = TERMUX_EXEC_LIBS
            .map { File(prefix, "lib/" + it) }
            .firstOrNull { it.isFile }
            ?.absolutePath
            .orEmpty()
        return shellEnvironment(
            ShellFacts(
                prefix = TERMUX_PREFIX,
                home = TERMUX_HOME,
                packageName = context.packageName,
                dataDir = context.applicationInfo.dataDir,
                filesDir = context.filesDir.absolutePath,
                uid = android.os.Process.myUid(),
                pid = android.os.Process.myPid(),
                termuxVersion = TermuxRuntime.TERMUX_VERSION,
                appVersionCode = appVersionCode(context),
                targetSdk = context.applicationInfo.targetSdkVersion,
                sdkInt = android.os.Build.VERSION.SDK_INT,
                apkRelease = TermuxRuntime.APK_RELEASE,
                debuggable = appDebuggable(context),
                preload = preload,
                scratch = TERMUX_HOME + "/scratch",
                // 安装期不挂写围栏（shim 还没解压），显式给「完全权限」基线
                fenceMode = com.adsh.app.seam.exec.Escalation.FULL_ACCESS,
                workspaceRoot = null,
            ),
        ).associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    /**
     * apt 的缓存目录。
     *
     * libapt-pkg.so 里编译的 Dir::Cache 是 /data/data/com.termux/cache/apt —— 方案 A 之后那正是
     * App 自己的 cacheDir，官方怎么跑我们就怎么跑：**不写任何 apt.conf.d 覆盖片段**。
     * 兼容残留：旧安装器（前缀用别名）可能留下 00-adsh-dirs 覆盖片段，指向实际不存在的
     * cache 路径 —— 这里一并清掉。
     */
    private fun ensureAptCache() {
        File(context.cacheDir, "apt/archives/partial").mkdirs()
        File(prefix, "etc/apt/apt.conf.d/00-adsh-dirs").takeIf { it.isFile }?.delete()
    }

    /**
     * 按 SYMLINKS.txt 建链接。root = staging（安装期）或 prefix（重链接期）。
     *
     * **重链接期只补缺，绝不覆盖已存在的条目**（真机实测的 bug）。
     *
     * 为什么：这些链接的目标是**前缀内部的相对路径**（`lib/libxxhash.so ← libxxhash.so.0`），
     * 与 nativeLibraryDir 无关 —— 也就是说重链接期根本没有它们要修的东西。
     * **绝不能 `link.delete()` 后重建**：dpkg 升级过的库会被打回 bootstrap 的版本号（真机：
     * `lib/libxxhash.so.0` 被指向已被 apt 删除的 `libxxhash.so.0.8.3`，实际只剩 0.8.4）
     * → 链接**悬空**，libxxhash 谁都加载不了，用户看到的是「0.8.3 / 0.8.4 混装痕迹」。
     *
     * 补缺仍然是必要的：前缀被部分恢复（例如备份还原）时，缺的那几条要建出来。
     */
    private fun createSymlinks(root: File, log: (String) -> Unit, relink: Boolean = false): Int {
        val table = File(root, "SYMLINKS.txt")
        if (!table.isFile) return 0
        var created = 0
        var failed = 0
        var kept = 0
        table.forEachLine { raw ->
            val index = raw.indexOf('\u2190')
            if (index <= 0) return@forEachLine
            // SYMLINKS.txt 里的绝对目标就是官方前缀（= 真前缀），直接用
            val target = raw.substring(0, index)
            val linkRelative = raw.substring(index + 1).removePrefix("./")
            val link = File(root, linkRelative)
            if (keepExistingEntry(relink) { exists(link) }) {
                kept++
                return@forEachLine
            }
            link.parentFile?.mkdirs()
            link.delete()
            try {
                Os.symlink(target, link.absolutePath)
                created++
            } catch (t: Throwable) {
                failed++
                if (failed <= 3) log("symlink failed: " + linkRelative + ": " + t.message)
            }
        }
        if (kept > 0) log("symlinked entries left alone (not ours): " + kept)
        if (failed > 0) log("symlink failures: " + failed)
        return created
    }

    /** 存在吗 —— 含**悬空**的符号链接（File.exists() 对悬空链接返回 false） */
    private fun exists(file: File): Boolean = file.exists() || readLink(file) != null

    /** 读符号链接的目标；不是符号链接（或不存在）返回 null */
    private fun readLink(file: File): String? = try {
        Os.readlink(file.absolutePath)
    } catch (t: Throwable) {
        null
    }

    /**
     * 把 $PREFIX/bin 下的真实文件换成指向 nativeLibraryDir 的符号链接（可执行的关键）。
     *
     * [relink] = false（安装期，root 是我们刚解压出来的 staging）：那里的每个 ELF 都是我们自己的，
     * 一律换成链接。= true（**重链接期**，root = prefix，APK 更新导致 nativeLibraryDir 变化）：
     * 只修**我们自己建的**那些链接 —— 目标是 nativeLibraryDir 里的 `libbin_*.so`（旧的 nativeLibraryDir
     * 也算，因为那是要修的对象）。
     *
     * 真机实测的 bug：无条件 `link.delete()` 会让**每装一次 APK，dpkg 装的东西就被
     * 打回 bootstrap 版本**。真机现状：`$PREFIX/bin/xxhsum` 是指向 libbin_xxhsum.so（0.8.3）的链接，
     * 而 dpkg 说 xxhash 已是 0.8.4；用户报的「0.8.3 / 0.8.4 混装痕迹」、以及「重装 util-linux 会覆盖
     * app 的 libbin_*.so 链接」这一对互相打架的机制，根子都在这里。
     *
     * 现在的判据（三种情况各自明确）：
     *  - 真实文件 → 不动（dpkg / 用户装的，能不能跑由 termux-exec 的 linker64 改写负责）；
     *  - 别人的符号链接 → 不动（update-alternatives 之类）；
     *  - 不存在 → 不建（dpkg 卸掉的东西不该被我们复活）；
     *  - 指向 `libbin_*.so` 的链接（含指向已消失的旧 nativeLibraryDir 的悬空链接）→ 修。
     */
    private fun linkExecutables(root: File, log: (String) -> Unit, relink: Boolean = false): Int {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        var linked = 0
        var missing = 0
        var kept = 0
        context.assets.open("execlibs.map").bufferedReader().forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val parts = line.split('\t')
            if (parts.size < 2) return@forEachLine
            val lib = parts[0].trim()
            val relative = parts[1].trim()
            val source = File(nativeDir, lib)
            if (!source.isFile) {
                missing++
                return@forEachLine
            }
            val link = File(root, relative)
            // 惰性：安装期不读链接（staging 里全是刚解压的真实文件），判定只需要两个事实
            when (execLinkAction(relink, if (relink) readLink(link) else null, lib, source.absolutePath)) {
                ExecLinkAction.Skip -> {
                    kept++
                    return@forEachLine
                }
                ExecLinkAction.KeepCurrent -> {
                    linked++  // 已经指对了（同一个 nativeLibraryDir），不必删了重建
                    return@forEachLine
                }
                ExecLinkAction.Create -> Unit
            }
            link.parentFile?.mkdirs()
            link.delete()
            try {
                Os.symlink(source.absolutePath, link.absolutePath)
                linked++
            } catch (t: Throwable) {
                if (linked == 0) log("execLib link failed: " + relative + ": " + t.message)
            }
        }
        if (kept > 0) log("execLib entries left alone (installed by dpkg or the user): " + kept)
        if (missing > 0) log("execLib missing in nativeLibraryDir: " + missing)
        return linked
    }
}

/**
 * bootstrap 安装状态（进程内共享）。
 *
 * 真机教训：安装抛异常时界面只显示一句「bootstrap 未安装」，和「还没开始装」看不出区别，
 * 用户唯一能拿到线索的地方是 logcat。这里把最后一次失败原因留在内存里，由设置页显示。
 */
object BootstrapStatus {
    /** 正在解压 / 打补丁 / 建链接 */
    @Volatile
    var installing: Boolean = false

    /** 最后一次失败原因（成功安装后清空） */
    @Volatile
    var error: String? = null
}
