package com.adsh.app.runtime.termux

import com.adsh.app.seam.exec.ExecResult
import com.adsh.app.seam.exec.Shell

import android.content.Context
import com.adsh.app.seam.exec.Spill
import android.system.OsConstants
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 一次 shell 会话的启动描述：可执行文件 + 参数 + 环境。
 * PTY（[PtySession]）与一次性命令（[TermuxRuntime.run]）共用同一份。
 */
data class ShellLaunch(
    val command: String,
    val args: List<String>,
    val env: List<String>,
    /** 会话的工作目录（guest 视角的路径） */
    val cwd: String,
)

/**
 * Termux 运行时：统一提供 PREFIX 环境、bash 入口与命令执行。
 *
 * 设计依据（真机实证，见 HANDOFF.md 的『不要破的硬规矩』一节）：
 *  - bash 必须从 nativeLibraryDir 启动（app 私有目录不可 exec）；
 *  - 其余工具通过 $PREFIX/bin 的符号链接间接指向 nativeLibraryDir；
 *  - 前缀就是官方的 /data/data/com.termux/files/usr（包名 = com.termux）：339 个 ELF 里编译
 *    进去的路径天然成立，**没有任何路径改写** —— 没有等长别名，也没有 proot；
 *  - LD_PRELOAD 常挂两样：termux-exec（app 私有目录里的可执行文件与脚本靠它才能跑）与我们
 *    自己的写围栏 shim（见 preloadValue 与 fenceEnv）。
 */
class TermuxRuntime(private val context: Context) : Shell {

    private val installer = BootstrapInstaller(context)

    val prefix: File get() = installer.prefix

    /**
     * 首次使用前的准备（解压 + 建链接；升级后只重链接），外加每次启动都幂等重做一遍的
     * App 侧准备（见 [HomePrep.prepare]）。
     */
    fun ensureReady(log: (String) -> Unit = {}): File =
        installer.ensureInstalled(log).also {
            HomePrep.prepare(homeDir(), prefix)
            // 回收上次会话留下的 spill 目录 / pid 标记（超过保留时长的）：一次性、可失败，
            // 必须在后台线程上跑 —— 调用点（ChatViewModel.warmUpRuntime）已经在 Dispatchers.IO。
            runCatching { Spill.sweepStale(tmpDir()) }
        }

    /**
     * bash 入口：$PREFIX/bin/bash → nativeLibraryDir（内核解析符号链接后落在可执行的真实文件上）。
     * 走前缀路径还有个好处：bash 的 $0 与报错里显示的是自己的路径，不是 /data/app/... 那串。
     * $PREFIX/bin/bash 缺失（没装完 / 安装失败）时退回 nativeLibraryDir 里的副本，至少让终端能起来。
     */
    override val bashPath: String
        get() = File(prefix, "bin/bash").takeIf { it.isFile }?.absolutePath
            ?: File(context.applicationInfo.nativeLibraryDir, "libbash.so").absolutePath

    val nativeLibraryDir: String
        get() = context.applicationInfo.nativeLibraryDir

    /** 随包 ripgrep（静态 musl），供 grep/glob 类工具使用 */
    override val ripgrepPath: String
        get() = File(nativeLibraryDir, "librg.so").absolutePath

    /** 家目录：路径文本用官方那一串（与 HOME / TERMUX__HOME 环境变量一致） */
    fun homeDir(): File = installer.home.apply { mkdirs() }

    override fun tmpDir(): File = File(prefix, "tmp").apply { mkdirs() }

    /**
     * f2fs 上的构建目录（默认 $HOME/scratch）。
     *
     * 为什么必须有它：用户绑定的工作区在 Android 的**外部存储（FUSE / sdcardfs）**上，
     * 那个文件系统对 App 来说只能读写普通文件 —— 不能建符号链接、不能置执行位、不能执行文件、
     * chmod 不生效。于是 `npm install`（要在 node_modules/.bin 建软链）、`pip install`、
     * 各类构建脚本（要 exec 刚生成的二进制）在工作区里会以 EACCES 失败。
     * 约定：工作区只做「素材入口 + 成品出口」，重活放到 $HOME（f2fs）上做，见系统提示词的
     * android-termux 段。
     */
    override fun scratchDir(): File = File(homeDir(), "scratch").apply { mkdirs() }

    /**
     * 应用私有 cache 目录的**两种拼写**（`/data/user/0` 与 `/data/data`），都算可写根。
     *
     * 安卓上 `/data/user/0/<pkg>/cache`（`Context.getCacheDir()`、`java.io.tmpdir`）与
     * `/data/data/<pkg>/cache` 是**同一个目录**（bind 别名，谁都不是符号链接，`realpath` 解析不出
     * 等价关系）。而写围栏是按**字面前缀**判定的：白名单里只有 `/data/user/0` 那一侧时，
     * 用 `/data/data` 拼写的写入一律被拒。
     *
     * 后果是具体的：Termux 的 apt 缓存目录写的就是 `/data/data/com.termux/cache`，于是
     * workspace-write 下 apt 连 `apt-get -s`（模拟安装）都失败 —— 白名单里「已经放了 apt 缓存
     * 目录」这个意图没有真正生效。两种拼写都收进来，谁写的都算。
     */
    override fun cacheRoots(): List<File> = listOfNotNull(
        context.cacheDir,
        File(legacyDataDirOf(context.packageName) + "/cache"),
    ).filter { it.isDirectory }.distinctBy { it.absolutePath }

    /**
     * 子进程环境。cwd 由调用点决定（一次性命令 = 工作区，终端会话 = 家目录），PWD 与它一致。
     *
     * 每一项的来历、以及为什么必须是那个值，全在 [shellEnvironment] 里；这里只把启动期读到的
     * 事实收成一个快照。前缀 / 家目录一律用**官方路径文本**（/data/data/com.termux/…）：
     * bootstrap 里的 ELF 与脚本里编译进去的就是它，环境变量必须逐字一致（见 [BootstrapInstaller.prefix]）。
     */
    fun environment(workspaceRoot: File?, interactive: Boolean = false): Array<String> = shellEnvironment(
        ShellFacts(
            prefix = prefix.absolutePath,
            home = homeDir().absolutePath,
            packageName = context.packageName,
            dataDir = context.applicationInfo.dataDir,
            filesDir = context.filesDir.absolutePath,
            uid = android.os.Process.myUid(),
            pid = android.os.Process.myPid(),
            // 这两个是**两个不同的东西**：TERMUX_VERSION 是 Termux 语义的版本号（termux-tools
            // 拿它判断走不走旧 App 的兼容分支），TERMUX_APP__VERSION_CODE 才是本 App 的 versionCode
            termuxVersion = TERMUX_VERSION,
            appVersionCode = appVersionCode(context),
            targetSdk = context.applicationInfo.targetSdkVersion,
            sdkInt = android.os.Build.VERSION.SDK_INT,
            apkRelease = APK_RELEASE,
            debuggable = appDebuggable(context),
            preload = preloadValue(),
            scratch = scratchDir().absolutePath,
            // 基线：完全权限。这里什么都不给就是 bug：shim 挂着却没有模式，
            // 于是按「workspace-write + 空白名单」跑，什么目录都写不了
            fenceMode = com.adsh.app.seam.exec.Escalation.FULL_ACCESS,
            workspaceRoot = workspaceRoot?.absolutePath,
            interactive = interactive,
        )
    ).toTypedArray()

    /**
     * LD_PRELOAD 的清单（空格分隔，动态链接器认这个格式）。
     *
     * 只放**我们自己的**写围栏 shim（nativeLibraryDir 里随包发布，见 CMake 目标 adshfence）。
     * 它是原生方案：动态链接器在进程启动时加载，之后 libc 的写入口直接跳到我们的判决函数 ——
     * 没有 proot 的 ptrace，也没有额外进程。
     *
     * 清单里有两个库：Termux 官方的 termux-exec（让 $PREFIX/bin 下的脚本型命令能跑）与
     * 我们自己的写围栏 shim；前提与坑见下面那段注释。
     */
    fun preloadValue(): String {
        val libs = ArrayList<String>(3)
        // termux-exec（Termux 官方的 LD_PRELOAD 扩展，bootstrap 里自带）：
        //  - 把 app 私有目录里的可执行文件改写成「/system/bin/linker64 <path>」，绕过安卓 10+ 的 W^X；
        //  - 把 /bin、/usr/bin 之类的 shebang 改到 $TERMUX__PREFIX。
        // $PREFIX/bin 下那 79 个「脚本型」命令（pkg / apt-key / getprop / df / ping / termux-*）
        // 全靠它才能直接 exec。两个前提都已满足：TERMUX_APP__DATA_DIR 与 LEGACY_DATA_DIR 分开给
        // （可执行文件的路径要文本上落在其一之下），TERMUX__PREFIX 不带尾斜杠。
        //
        // **教训：它不能和 login shell 一起用。** login shell 会 source /etc/profile，其中
        // etc/profile.d/01-termux-bootstrap-second-stage-fallback.sh 在 termux-exec 生效后会真的去执行
        // Termux 的 bootstrap second-stage 脚本（那是给 Termux 应用首启用的，我们没跑过），
        // 于是 bash 卡在启动阶段：提示符不出来、回车没反应、Agent 的一次性命令也超时。两条对策：
        //  1) 交互会话用「bash -i」（非 login）、一次性命令用「bash -c」（非 login），都不碰 /etc/profile；
        //  2) 安装器按官方流程把 bootstrap second stage 跑完并留下 lock 文件
        //     （BootstrapInstaller.runSecondStage）—— 那个 fallback 脚本此后拿到 lock 就直接跳过，
        //     再也不会真的去跑 second stage。
        // 顺序很重要：**写围栏 shim 放第一个**。
        // LD_PRELOAD 里同名符号按清单顺序解析，第一个库的 execve 先被调用，它再用
        // dlsym(RTLD_NEXT) 把链路交给下一个（termux-exec 排在后面照样能拿到路径做事）。
        // 为什么 shim 必须排在 termux-exec 前面：/tmp 映射与围栏判决都在 shim 里，先经过它，
        // termux-exec 拿到的才是映射之后的最终路径。
        File(nativeLibraryDir, Shell.FENCE_LIB).takeIf { it.isFile }?.let { libs += it.absolutePath }
        val libDir = File(prefix, "lib")
        TERMUX_EXEC_LIBS.firstOrNull { File(libDir, it).isFile }
            ?.let { libs += File(libDir, it).absolutePath }
        return libs.joinToString(" ")
    }

    /**
     * 写围栏（dsh 的 fs-sandbox fence）的环境变量：**只读与工作区可写两种模式都挂**。
     *
     * dsh 在桌面上用内核沙箱（bwrap / seatbelt / Landlock）把「工作区外只读」变成内核判决；
     * 安卓没有给普通应用的内核沙箱，proot 又是 ptrace（用户明确否掉：每次 IO 都要绕一圈）。
     * 这里走 LD_PRELOAD：`libadshfence.so` 在 libc 的写入口上做判决，拒绝时 errno=EACCES
     * 并往 stderr 打 dsh 的两行标记 —— 与 dsh 的 `sandboxDenialMarker` + `hintMarker`
     * 逐字一致，模型据此知道可以带 sandbox_permissions 申请一次更宽的权限（App 会弹审批卡）。
     *
     * read-only 的白名单是**空的**：命令照跑，任何写都被拒（dsh 的 writableRoots 在
     * read-only 下就是空列表），于是纯读命令不再莫名其妙带上「可以提权」的提示 ——
     * 只有真的越权写时才出现标记 —— read-only 下命令照跑，不做整条 bash 拒绝。
     *
     * @param roots 可写白名单。workspace-write：工作区 + 临时目录 + $ADSH_SCRATCH
     *  （对齐 dsh 的 writableRoots，另加提示词里承诺过的 scratch）；read-only：空
     * @param mark 哨兵文件：shim 一加载就写它。App 侧用它确认 LD_PRELOAD 真的生效
     *  （拿不到就说明这次运行里围栏没起作用 —— workspace-write 只能记日志，
     *   read-only 由调用方失败关闭）
     *
     * 三条「不挂」的路与变量的写法都在 [fencePlan] / [fenceVariables]（那里也写了为什么
     * read-only 的空白名单是正常形态、而 workspace-write 的空白名单要宁可不挂）。
     */
    override fun fenceEnv(mode: String, roots: List<File?>, mark: File?): Array<String> {
        // canonical 碰文件系统、preloadValue 要看 nativeLibraryDir，都留在这一侧；
        // 判定本身是纯的（fencePlan），而且 preloadValue() 只在真要挂围栏时才求值
        val whitelist = fenceRoots(roots.map { runCatching { it?.canonicalPath }.getOrNull() })
        val plan = fencePlan(mode, File(nativeLibraryDir, Shell.FENCE_LIB).isFile, whitelist) as? FencePlan.On
            ?: return emptyArray()
        return fenceVariables(plan, preloadValue(), mark?.absolutePath).toTypedArray()
    }

    /** 一次调用的完整 argv（$PREFIX/bin/bash；缺失时退回 nativeLibraryDir 里的副本） */
    fun launchArgv(bashArgs: Array<String>): Array<String> = arrayOf(bashPath) + bashArgs

    /**
     * 交互式会话（终端页）的启动描述：command + args + env。
     * PS1 交给调用方（终端页自己会补），这里只保证前缀/环境与一次性命令一致。
     *
     * @param workspaceRoot 当前工作区：**必须传**，否则终端里 `$ADSH_WORKSPACE` 是空的
     *  （README 与系统提示词都把「$ADSH_WORKSPACE 始终指向当前工作区」当作既有约定）。
     *  进程的 cwd 仍是 $HOME（与官方 Termux 一致），工作区只体现在这两个环境变量上。
     */
    fun shellLaunch(workspaceRoot: File? = null): ShellLaunch = ShellLaunch(
        command = bashPath,
        // -i：交互但**不是** login shell（不 source /etc/profile，理由见 preloadValue 的注释）。
        // PS1 由终端页通过环境变量给，不依赖 profile。
        args = listOf("-i"),
        // interactive = true：PWD 是家目录（进程 cwd），TERM/颜色按真人终端给；
        // 工作区只经 ADSH_WORKSPACE 暴露（见 shellEnvironment）。
        env = environment(workspaceRoot, interactive = true).toList(),
        cwd = homeDir().absolutePath,
    )

    /**
     * 一条**不等待**的 shell 进程（后台任务与前台执行共用同一个启动形状）。
     *
     * dsh 把「每条命令一开始就注册成 job」当默认 —— shell 缝只有一个 execute()，前台只是对
     * 同一个 job 的有界 wait（超时转后台 = 继续等同一个 job）。后台任务与前台执行共用同一套
     * argv / 环境 / stdin / 杀树逻辑，两条路只差「等不等」。
     */
    inner class ShellProcess internal constructor(
        private val process: Process,
        /** 子 shell 自报 pid 的标记文件（见 [spawn]）；读不到就退回只杀直接子进程 */
        private val pidMarker: File,
    ) : com.adsh.app.seam.exec.ShellProcess {
        /** 杀树只做一次：重复 kill（job_kill 再来一次、前台超时与人工停止撞上）不再多起一根线程 */
        private val terminating = java.util.concurrent.atomic.AtomicBoolean(false)

        override val stdout: InputStream get() = process.inputStream
        override val stderr: InputStream get() = process.errorStream

        /** 等它结束（超时返回 false，进程继续跑） */
        fun waitFor(timeoutMs: Long): Boolean = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

        /** 一直等到结束 */
        override fun waitFor(): Int = process.waitFor()

        override fun exitValue(): Int = process.exitValue()

        /** 进程结束后的收尾：标记文件不留存量 */
        override fun dispose() {
            runCatching { pidMarker.delete() }
        }

        /**
         * 请求终止：**非阻塞**（后台线程里 SIGTERM → graceMs → SIGKILL）。
         *
         * dsh 的 JobHooks.cancel 必须「同步、幂等、最终 settle」，而调用者可能是界面的停止按钮
         * （主线程）——所以真正的杀树放到线程里，注册表只改状态、等生产者自己结算。
         */
        override fun terminate() {
            if (!terminating.compareAndSet(false, true)) return
            Thread({ terminateBlocking() }, "adsh-job-kill").apply { isDaemon = true }.start()
        }

        /** 阻塞版杀树：前台的超时/中断路径用它，判决与 [terminate] 完全一样 */
        fun terminateBlocking() {
            val pid = awaitChildPid()
            if (pid <= 0) {
                // 拿不到根 pid（标记文件还是空的）就**没有**进程树可读：只杀直接子进程。
                // 绝不退化成对 -1 发信号 —— kill(2) 把 -1 当「本 uid 的所有进程」，
                // 会把 :ptc 运行时进程一起杀掉（见 killOrder 的注释）。
                runCatching { process.destroy() }
                if (!process.waitFor(GRACE_MS, TimeUnit.MILLISECONDS)) {
                    runCatching { process.destroyForcibly() }
                    process.waitFor(300, TimeUnit.MILLISECONDS)
                }
                return
            }
            signalTree(pid, OsConstants.SIGTERM)
            if (!process.waitFor(GRACE_MS, TimeUnit.MILLISECONDS)) {
                signalTree(pid, OsConstants.SIGKILL)
                process.waitFor(300, TimeUnit.MILLISECONDS)
            }
        }

        /**
         * 这棵进程树的根 pid = 子 shell 自己的 pid（[spawn] 里它一启动就写进标记文件）。
         *
         * 不用 Process.pid() 的原因：安卓运行时里没有这个方法（Java 9 才进标准库，反射拿不到），
         * 拿不到就只能 destroy() 直接子进程 —— 真机实测，后台任务「起两个 sleep 再 wait」被杀之后，
         * 两个孙进程被 init 收养继续跑（漏杀的就是它们）。标记文件不依赖反射，也不给每条命令
         * 加延迟：只在真要杀的那一刻读一次。
         *
         * 读不到要**等一小会儿**：spawn 之后紧接着 kill（模型起完后台任务立刻 job_kill）时，
         * 子 shell 可能还没跑到写 pid 那条语句，一次读只会拿到空串；等一个上限仍拿不到就返回
         * -1，由调用方走「只杀直接子进程」的退路。
         */
        private fun awaitChildPid(): Int {
            val deadline = System.currentTimeMillis() + MARKER_WAIT_MS
            while (true) {
                markerPid(runCatching { pidMarker.readText() }.getOrDefault(""))?.let { return it }
                if (System.currentTimeMillis() >= deadline || !process.isAlive) return -1
                try {
                    Thread.sleep(MARKER_POLL_MS)
                } catch (_: InterruptedException) {
                    return -1
                }
            }
        }
    }

    /**
     * 起一个进程 —— 全项目**唯一一份** argv / 环境 / 工作目录 / stdin 拼装。
     *
     * - `-c`：命令串。**不加 -l**：login shell 会 source /etc/profile，而 termux-exec 生效后
     *   second stage 已经由安装器跑过并留下 lock，fallback 脚本不会再真的执行（见 preloadValue）。
     *   环境变量我们自己全部显式给了，不需要登录 shell 再补。
     * - stdin = /dev/null（对齐 dsh 的 `stdio.stdin: "ignore"`）。**不接不行**：ProcessBuilder 的
     *   默认形状给子进程一根 App 既不写也不关的管道，于是所有「等 stdin」的命令都会一直阻塞到
     *   超时 —— `cat`、`read`、没有路径的 `grep`、裸 `python3`、`more`…（深度实测：同一段
     *   代码在宿主上跑，默认形状阻塞 >4s，换成 /dev/null 后 28ms 结束）。
     */
    override fun spawn(
        command: String,
        workspaceRoot: File?,
        extraEnv: Array<String>?,
        separateStreams: Boolean,
    ): ShellProcess {
        // 让子 shell 一启动就把自己的 pid 写进标记文件：杀树要用它当根（见 ShellProcess.childPid）。
        // 写失败也不影响命令（读不到就退回只杀直接子进程）；写完的语句与命令同一个 -c 串，
        // 退出码仍是命令自己的。
        val marker = File.createTempFile(Spill.PID_MARKER_PREFIX, ".txt", tmpDir())
        val wrapped = "printf %s \"\$\$\" > \"" + marker.absolutePath + "\"; " + command
        val pb = ProcessBuilder(*launchArgv(arrayOf("-c", wrapped)))
            .redirectErrorStream(!separateStreams)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        envInto(pb, environment(workspaceRoot))
        // 围栏生效时把内部 pid 标记文件告诉 shim（fence.c 的 g_pid_marker）：read-only 白名单为空，
        // 不单独放行的话这个内部写会被判成越权，命令 stderr 里会多出一段与用户命令无关的拒绝。
        if (extraEnv != null && extraEnv.isNotEmpty()) {
            envInto(pb, arrayOf("ADSH_PID_MARKER=" + marker.absolutePath))
        }
        if (extraEnv != null) envInto(pb, extraEnv)
        if (workspaceRoot != null && workspaceRoot.isDirectory) pb.directory(workspaceRoot)
        val process = try {
            pb.start()
        } catch (t: Throwable) {
            runCatching { marker.delete() }
            throw t
        }
        return ShellProcess(process, marker)
    }

    /**
     * 执行一条 shell 命令。
     * @param maxOutputBytes 输出上限（对齐 dsh 的 shell.maxOutputBytes = 64000）
     * @param cancel 「用户中断了吗」的探针（每 [CANCEL_POLL_MS] 问一次）。为 null = 不可中断
     *   （一直等到超时）。命中时按 dsh 的超时同一条路**杀掉整棵进程树**，再正常返回 ——
     *   不能只是从阻塞的 waitFor 里逃出去，命令会变成孤儿继续跑（写文件、占端口）。
     */
    override fun run(
        command: String,
        workspaceRoot: File?,
        timeoutMs: Long,
        maxOutputBytes: Int,
        extraEnv: Array<String>?,
        /** true = stdout/stderr 分开捕获（dsh 的 bash）；false = 合并（探针等旧调用） */
        separateStreams: Boolean,
        cancel: (() -> Boolean)?,
    ): ExecResult {
        val started = System.currentTimeMillis()
        val shell = spawn(command, workspaceRoot, extraEnv, separateStreams)
        // 每路输出一个收集器（dsh 也是一路一个），各自往同一个每进程私有目录里 spill 全文
        val spillDir = Spill.directory(tmpDir())
        val collector = OutputCollector(shell.stdout, maxOutputBytes, Spill(spillDir, "stdout"))
        // 分开捕获时必须同时把另一条流读干，否则管道写满会卡住子进程
        val errCollector = if (separateStreams) {
            OutputCollector(shell.stderr, maxOutputBytes, Spill(spillDir, "stderr"))
        } else {
            null
        }
        var finished = false
        var cancelled = false
        if (cancel == null) {
            finished = shell.waitFor(timeoutMs)
        } else {
            // 切片等待：waitFor 是阻塞的，协程取消**不会**打断它（旧的「按了停止要等命令跑完」
            // 就是这么来的）。每 50ms 醒一次问探针，命中就跳出、走下面的杀树。
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                if (shell.waitFor(CANCEL_POLL_MS)) {
                    finished = true
                    break
                }
                if (cancel()) {
                    cancelled = true
                    break
                }
                if (System.currentTimeMillis() >= deadline) break
            }
        }
        if (!finished) {
            // dsh 的超时是「先给**整棵进程树** SIGTERM，graceMs 之后再 SIGKILL」
            // （dsh-bash-local 的 graceMs = 3000；dsh-subprocess-local 的 signalChildGroup
            // 就是 process.kill(-pid, signal)）。杀树那一套与后台任务的取消**同一份实现**。
            shell.terminateBlocking()
        }
        drain(collector, shell.stdout)
        if (errCollector != null) drain(errCollector, shell.stderr)
        shell.dispose()
        val exit = if (finished) shell.exitValue() else -1
        val out = collector.finalize()
        val err = errCollector?.finalize()
        return ExecResult(
            exitCode = exit,
            output = out.text,
            // 中断也走「没等到进程结束」这条分支，但不算超时：界面/模型要看到的是「已停止」
            timedOut = !finished && !cancelled,
            truncated = out.truncated,
            durationMs = System.currentTimeMillis() - started,
            stderr = err?.text.orEmpty(),
            stderrTruncated = err?.truncated == true,
            spillPath = out.spillPath,
            stderrSpillPath = err?.spillPath,
            timeoutMs = timeoutMs,
            cancelled = cancelled,
            outputBytes = collector.totalBytes,
            stderrBytes = errCollector?.totalBytes ?: 0,
        )
    }

    /**
     * 收尾一条输出流：最多等 [DRAIN_GRACE_MS]；超时就**关掉读端**让读线程退出。
     *
     * 孙进程继承了 stdout/stderr 的写端时，管道永远不 EOF —— 不关读端就会留下一个永久阻塞的
     * 读线程，而且随后 [OutputCollector.finalize] 还会跟它在同一个环形缓冲上赛跑
     * （撕裂/重复的尾部）。dsh 在同样的位置也是「等一截、然后强制关掉收集器」。
     */
    private fun drain(collector: OutputCollector, stream: InputStream) {
        if (collector.join(DRAIN_GRACE_MS)) return
        runCatching { stream.close() }
        collector.join(DRAIN_AFTER_CLOSE_MS)
    }

    private fun envInto(pb: ProcessBuilder, entries: Array<String>) {
        val env = pb.environment()
        entries.forEach { kv ->
            val i = kv.indexOf('=')
            if (i > 0) env[kv.substring(0, i)] = kv.substring(i + 1)
        }
    }

    companion object {
        /** SIGTERM 之后等多久发 SIGKILL（dsh-bash-local 的 graceMs，默认 3000） */
        private const val GRACE_MS = 3_000L

        /** 进程结束后排空输出流的宽限：之后强制关读端（见 drain） */
        private const val DRAIN_GRACE_MS = 1_000L
        private const val DRAIN_AFTER_CLOSE_MS = 200L

        /** 杀树之前等子 shell 写出 pid 标记的上限与轮询粒度（正常几毫秒；见 awaitChildPid） */
        private const val MARKER_WAIT_MS = 250L
        private const val MARKER_POLL_MS = 10L

        /**
         * 可中断执行时的探针轮询粒度：进程还在跑就每 50ms 问一次「用户停了吗」。
         * 这是「按停止到真的杀掉命令」之间的最大延迟（读取一次探针本身是内存操作）。
         */
        private const val CANCEL_POLL_MS = 50L

        /**
         * Termux 版本号（TERMUX_VERSION）。
         *
         * 真机上它是 **Termux APK 的 versionName**（termux-app 的 TermuxAppShellEnvironment），
         * termux-tools 只读它：login 用它判断 motd/是否补 TERMUX_MAIN_PACKAGE_FORMAT、
         * termux-info 打印它、第三方脚本用 [ -n "$TERMUX_VERSION" ] 判断「在不在 Termux 里」。
         * 取一个 ≥ 0.119.0 的语义化值：低于它 termux-tools 会走旧 App 的兼容分支。
         */
        const val TERMUX_VERSION = "0.119.0"
        /** 安装来源，对齐 Termux 的 TERMUX_APP__APK_RELEASE（GITHUB / F_DROID / GOOGLE_PLAY） */
        const val APK_RELEASE = "GITHUB"
    }
}
