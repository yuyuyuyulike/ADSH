package com.adsh.app.runtime.termux

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** 一次非交互命令的执行结果 */
data class ExecResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
    val truncated: Boolean,
    val durationMs: Long,
    /**
     * 单独捕获的 stderr。只有 separateStreams = true 的调用会填它；
     * 否则 stderr 已经并进 [output]（默认行为，探针类调用依赖合并后的文本）。
     * dsh 的 bash 工具 stdout/stderr 是两个字段，模型看到的正文里 stderr 带 [stderr] 头，
     * 所以 bash 工具走单独捕获这条路径。
     */
    val stderr: String = "",
    val stderrTruncated: Boolean = false,
    /** 本次调用的超时上限（dsh 的输出里会回显 [timed out after Nms]） */
    val timeoutMs: Long = 0,
    /**
     * 这次执行是因为**用户中断**而停下的（[TermuxRuntime.run] 的 `cancel` 探针命中）。
     * 与超时区分开：超时是「命令太慢」，中断是「人按了停止」——工具行上的文案不一样。
     */
    val cancelled: Boolean = false,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

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
 * 设计依据（M0 与第 44 轮真机实证，见 HANDOFF.md 的『不要破的硬规矩』一节）：
 *  - bash 必须从 nativeLibraryDir 启动（app 私有目录不可 exec）；
 *  - 其余工具通过 $PREFIX/bin 的符号链接间接指向 nativeLibraryDir；
 *  - 前缀就是官方的 /data/data/com.termux/files/usr（包名 = com.termux）：339 个 ELF 里编译
 *    进去的路径天然成立，**没有任何路径改写** —— 没有等长别名，也没有 proot；
 *  - LD_PRELOAD 常挂两样：termux-exec（app 私有目录里的可执行文件与脚本靠它才能跑）与我们
 *    自己的写围栏 shim（见 preloadValue 与 fenceEnv）。
 */
class TermuxRuntime(private val context: Context) {

    private val installer = BootstrapInstaller(context)

    val prefix: File get() = installer.prefix

    /**
     * 首次使用前的准备（解压 + 建链接；升级后只重链接），外加每次启动都幂等重做一遍的
     * App 侧准备（见 [prepareHome]）。
     */
    fun ensureReady(log: (String) -> Unit = {}): File =
        installer.ensureInstalled(log).also { prepareHome() }

    /**
     * bash 入口：$PREFIX/bin/bash → nativeLibraryDir（内核解析符号链接后落在可执行的真实文件上）。
     * 走前缀路径还有个好处：bash 的 $0 与报错里显示的是自己的路径，不是 /data/app/... 那串。
     * $PREFIX/bin/bash 缺失（没装完 / 安装失败）时退回 nativeLibraryDir 里的副本，至少让终端能起来。
     */
    val bashPath: String
        get() = File(prefix, "bin/bash").takeIf { it.isFile }?.absolutePath
            ?: File(context.applicationInfo.nativeLibraryDir, "libbash.so").absolutePath

    val nativeLibraryDir: String
        get() = context.applicationInfo.nativeLibraryDir

    /** 随包 ripgrep（静态 musl），供 grep/glob 类工具使用 */
    val ripgrepPath: String
        get() = File(nativeLibraryDir, "librg.so").absolutePath

    /** 家目录：路径文本用官方那一串（与 HOME / TERMUX__HOME 环境变量一致） */
    fun homeDir(): File = installer.home.apply { mkdirs() }

    fun tmpDir(): File = File(prefix, "tmp").apply { mkdirs() }

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
    fun scratchDir(): File = File(homeDir(), "scratch").apply { mkdirs() }

    /**
     * 应用私有 cache 目录的**两种拼写**，都算可写根（第九十一轮，审查报告 F3）。
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
    fun cacheRoots(): List<File> = listOfNotNull(
        context.cacheDir,
        File("/data/data/" + context.packageName + "/cache"),
    ).filter { it.isDirectory }.distinctBy { it.absolutePath }

    /** 每次启动都跑一遍的 App 侧准备（都是幂等的小操作） */
    private fun prepareHome() {
        runCatching { scratchDir() }
        runCatching { ensureGitSafeDirectory() }
        // 环境自检命令（第 72 轮）：终端里敲 `adsh-env-check` 就能把「exec 模式 / SELinux 域 /
        // 预载库 / 存储 / 浏览器」一次打出来 —— 内嵌 Termux 与官方基准的差异全在这里，出问题先看它。
        runCatching { EnvSelfCheck.installScript(prefix) }
        // 网页渲染 / 截图助手（第 73 轮）：自己解析浏览器、本地路径转 file://，
        // 第三方工具还能用 `adsh-shot --which` 拿到浏览器路径（省掉写死的 /usr/bin/chrome）。
        runCatching { AdshShot.installScript(prefix) }
    }

    /**
     * 让 git 认工作区。
     *
     * 工作区里的文件属主是 u0_a221(media_rw)，而我们的进程是 u0_a388：git 2.35+ 会直接拒绝
     * 一切操作（fatal: detected dubious ownership in repository）。官方 Termux 的 git 也有同样
     * 的坑，标准解法就是 safe.directory。
     *
     * 写的是 $HOME/.gitconfig（git 的全局配置），只追加、不覆盖用户自己写的内容；已经有一条
     * 同样的声明就直接返回（幂等）。用 `*` 而不是逐个工作区路径：工作区可以由用户随时更换，
     * App 每次启动都在这里，逐个维护成本更高还容易漏（设备本身是用户自己的）。
     */
    private fun ensureGitSafeDirectory() {
        val config = File(homeDir(), ".gitconfig")
        val existing = runCatching { if (config.isFile) config.readText() else "" }.getOrDefault("")
        if (existing.contains(GIT_SAFE_LINE)) return
        val append = buildString {
            if (existing.isNotEmpty() && !existing.endsWith("\n")) appendLine()
            appendLine("# 由 ADSH 追加：工作区在外部存储上，属主与 App 不同")
            appendLine("[safe]")
            appendLine("\t" + GIT_SAFE_LINE)
        }
        config.appendText(append)
    }

    /**
     * 子进程环境。cwd 由工作区决定，注入 PWD 便于脚本自省。
     *
     * 前缀 / 家目录一律用**官方路径文本**（/data/data/com.termux/...）：bootstrap 里的 ELF 与
     * 脚本里编译进去的就是它，环境变量必须逐字一致（见 BootstrapInstaller.prefix）。
     */
    fun environment(workspaceRoot: File?): Array<String> {
        val p = prefix.absolutePath
        val home = homeDir().absolutePath
        val tmp = p + "/tmp"
        val dataDir = context.applicationInfo.dataDir
        // 旧形态的 data dir（/data/data/<pkg>）。**必须与 dataDir 分开给**：
        // termux-exec 的 shouldEnableSystemLinkerExecForFile() 只在「可执行文件路径文本上落在
        // DATA_DIR 或 LEGACY_DATA_DIR 之下」时才把 execve 改写成 linker64（安卓 10+ 的 W^X 绕过）。
        // 我们的前缀文本是 /data/data/com.termux/files/usr，只有 LEGACY_DATA_DIR 能把它盖住 ——
        // 早先两个都给成 /data/user/0/<pkg>，于是库判定「不是我的 app 数据目录」，preload 加载了
        // 却什么都不改写（M0 里「termux-exec 实测无效」的真正原因）。
        val legacyDataDir = "/data/data/" + context.packageName
        val list = mutableListOf(
            "PREFIX=" + p,
            "HOME=" + home,
            "PATH=" + p + "/bin",
            "LD_LIBRARY_PATH=" + p + "/lib",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "TMPDIR=" + tmp,
            // TMP / TEMP 一起给：安卓没有可写的 /tmp（/tmp 属主是 shell、0711），第三方工具
            // 有的读 TMPDIR、有的读 TMP/TEMP。shim 另有一层「/tmp → ADSH_TMP_REDIRECT」的路径映射
            // （fence.c 的 redirect），所以连把 /tmp 写死在代码里的工具也能落在这里。
            "TMP=" + tmp,
            "TEMP=" + tmp,
            "ADSH_TMP_REDIRECT=" + tmp,
            // 构建目录（f2fs）：工作区在 FUSE 上装不了依赖、执行不了脚本，重活来这里
            "ADSH_SCRATCH=" + scratchDir().absolutePath,
            "SHELL=" + p + "/bin/bash",
            // ---- Termux 官方的那套环境变量 ----
            // 内嵌 bootstrap 之后，「我是不是在 Termux 里」全靠这些变量：包管理脚本、configure、
            // termux-* 工具、以及 termux-exec 自己都会读它们。缺了就是用户看到的那类怪事
            // （TERMUX_VERSION 为空、工具按「非 Termux」分支走）。值一律指到真实前缀 ——
            // 包名就是 com.termux，所以真实前缀**就是**官方前缀 /data/data/com.termux/…。
            "TERMUX_VERSION=" + TERMUX_VERSION,
            "TERMUX_MAIN_PACKAGE_FORMAT=debian",
            "TERMUX_APP_PACKAGE_MANAGER=apt",
            "TERMUX_APP__PACKAGE_NAME=" + context.packageName,
            "TERMUX_APP__VERSION_NAME=" + TERMUX_VERSION,
            "TERMUX_APP__VERSION_CODE=" + versionCode(),
            "TERMUX_APP__TARGET_SDK=" + context.applicationInfo.targetSdkVersion,
            "TERMUX_APP__UID=" + android.os.Process.myUid(),
            "TERMUX_APP__PID=" + android.os.Process.myPid(),
            "TERMUX_APP__FILES_DIR=" + context.filesDir.absolutePath,
            "TERMUX_APP__DATA_DIR=" + dataDir,
            "TERMUX_APP__LEGACY_DATA_DIR=" + legacyDataDir,
            "TERMUX_APP__APK_RELEASE=" + APK_RELEASE,
            "TERMUX_APP__IS_DEBUGGABLE_BUILD=" + debuggableFlag(),
            // termux-exec 的 system-linker-exec 需要它：termux-exec.postinst 的原文是
            // 「termux-exec-system-linker-exec called by termux-exec-ld-preload-lib will fail if
            //   ANDROID__BUILD_VERSION_SDK is not exported (by Termux app) and getprop at
            //   /system/bin/getprop is not accessible」——官方 app 导出它，我们以前漏了。
            "ANDROID__BUILD_VERSION_SDK=" + android.os.Build.VERSION.SDK_INT,
            // 兼容旧脚本的别名（termux-tools 的旧分支与第三方脚本还在读它们）
            "TERMUX_APP_PID=" + android.os.Process.myPid(),
            "TERMUX_APK_RELEASE=" + APK_RELEASE,
            "TERMUX_IS_DEBUGGABLE_BUILD=" + debuggableFlag(),
            // 路径值**不要带尾斜杠**：termux-exec 与 termux-tools 的校验是「/*[!/]」，
            // 带尾斜杠会被判非法并回退到编译期的 com.termux 前缀。
            "TERMUX__PREFIX=" + p,
            "TERMUX__HOME=" + home,
            "TERMUX__ROOTFS=" + legacyDataDir + "/files",
        )
        // LD_PRELOAD：termux-exec + 写围栏 shim（顺序与理由见 preloadValue）。
        // **围栏是否生效只看有没有约束模式**（fence.c 的门闸），所以这里给一个显式的「关」：
        // danger-full-access 会让 shim 只做 /tmp 映射与硬链接替身，一次写判决都不做。
        // 需要围栏的调用由 [fenceEnv] 用真实模式（read-only / workspace-write）+ 白名单覆盖它。
        // 第 64 轮的 bug 就是这里「什么都不写」：完全权限下 LD_PRELOAD 挂着 shim 却没有任何
        // 模式变量，shim 于是按「workspace-write + 空白名单」跑 —— 什么目录都写不了。
        preloadValue().takeIf { it.isNotEmpty() }?.let { list += "LD_PRELOAD=" + it }
        list += "ADSH_FENCE_MODE=" + com.adsh.app.core.tools.Escalation.FULL_ACCESS
        // 关于 /tmp 与 /data/local/tmp（用户点名过的两个「内嵌 Termux 常见坑」）：
        //  - 安卓没有 /tmp，官方 Termux 也没有 —— 官方的 /tmp 语义就是 $PREFIX/tmp，
        //    并且由 App 导出 TMPDIR（我们上面已经这么做，bootstrap 里也建好了这个目录）；
        //  - /data/local/tmp 属于 shell 用户（0771，other 只有 x），**任何 App 都写不进去**，
        //    真机上的 Termux 同样写不进去（它是 adb/调试用的目录）。官方没有替代路径，
        //    要临时目录就用 TMPDIR/$PREFIX/tmp，App 侧用 cacheDir/filesDir。
        // 这两条都属于「安卓/官方的既有事实」，不是我们的配置错误，所以不做路径重定向
        // （重定向会让 stat/ls 与 open 看到不同的地方，得不偿失）。
        // dpkg 的包数据库：给真实前缀下的路径。
        //
        // **不给 DPKG_ROOT**：环境变量会被维护脚本（preinst/postinst 里再调 dpkg 的那种）继承，
        // 那时 dpkg 同时拿到 instdir 与 admindir，而 admindir 文本上不在 instdir 里 → dpkg 报
        // 「admindir must be inside instdir for dpkg to work properly」，装 nodejs 这种带
        // postinst 再调 dpkg 的包就会挂。方案 A 下 apt/dpkg 的编译期 instdir 就是 `/`，与官方
        // 完全一致，不需要任何 apt.conf.d 覆盖（见 BootstrapInstaller.ensureAptCache）。
        list += "DPKG_ADMINDIR=" + p + "/var/lib/dpkg"
        if (workspaceRoot != null) {
            list += "PWD=" + workspaceRoot.absolutePath
            // 供脚本/模型自省：当前工作区（素材入口 + 成品出口）的真实路径
            list += "ADSH_WORKSPACE=" + workspaceRoot.absolutePath
        }
        return list.toTypedArray()
    }

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
        File(nativeLibraryDir, FENCE_LIB).takeIf { it.isFile }?.let { libs += it.absolutePath }
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
     * 只有真的越权写时才出现标记。以前 read-only 是把整条 bash 拒掉。
     *
     * @param roots 可写白名单。workspace-write：工作区 + 临时目录 + $ADSH_SCRATCH
     *  （对齐 dsh 的 writableRoots，另加提示词里承诺过的 scratch）；read-only：空
     * @param mark 哨兵文件：shim 一加载就写它。App 侧用它确认 LD_PRELOAD 真的生效
     *  （拿不到就说明这次运行里围栏没起作用 —— workspace-write 只能记日志，
     *   read-only 由调用方失败关闭）
     *
     * 返回空数组 = **这次调用不要围栏**（完全权限、可写根一个都解析不出来、shim 缺失）：
     * 子进程里只剩 environment() 给的显式「关」，fence.c 据此不做任何写判决。这条是唯一
     * 真相，别在别处再补一个默认模式 —— 「挂了 shim 却没给模式」曾经被当成
     * workspace-write + 空白名单，把完全权限变成什么都写不了（第 64 轮）。
     */
    fun fenceEnv(mode: String, roots: List<File?>, mark: File?): Array<String> {
        val confining = mode == com.adsh.app.core.tools.Escalation.WORKSPACE_WRITE ||
            mode == com.adsh.app.core.tools.Escalation.READ_ONLY
        if (!confining) return emptyArray()
        val shim = File(nativeLibraryDir, FENCE_LIB)
        if (!shim.isFile) return emptyArray()
        val whitelist = roots.filterNotNull()
            .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
            .filter { it.isNotEmpty() && it != "/" }
            .distinct()
        // workspace-write 必须有根：一个都解析不出来说明环境坏了，宁可不挂（老行为）
        if (whitelist.isEmpty() && mode != com.adsh.app.core.tools.Escalation.READ_ONLY) return emptyArray()
        val env = mutableListOf(
            "LD_PRELOAD=" + preloadValue(),
            // 空的 ADSH_FENCE_ROOTS 就是「一个可写根都没有」：shim 认得这个形态（read-only）
            "ADSH_FENCE_ROOTS=" + whitelist.joinToString(":"),
            "ADSH_FENCE_MODE=" + mode,
            // 「这次进程真的被围栏管着」的**唯一**来源（第 114 轮）。shim 侧从前会自己 setenv 这个
            // 变量，两个问题：一是它会毁掉本进程之后所有的 getenv（bionic 的 setenv 在「环境还是
            // 内核给的那一份」时会把 getenv 的可见环境换成只含新变量的数组）—— workspace-write 下
            // 白名单因此永远解析成空表；二是它只说「shim 加载过」，完全权限下照样是 1（用户第 113
            // 轮点名的假信号）。现在由 App 给：只有真的挂了围栏（read-only / workspace-write 且
            // shim 存在）才有它，完全权限与终端页都没有，fixup_exec_env 也只在 g_enabled 时往下传。
            "ADSH_FENCE_ACTIVE=1",
        )
        if (mark != null) env += "ADSH_FENCE_MARK=" + mark.absolutePath
        return env.toTypedArray()
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
        env = environment(workspaceRoot).toList(),
        cwd = homeDir().absolutePath,
    )

    /**
     * 一条**不等待**的 shell 进程（后台任务与前台执行共用同一个启动形状）。
     *
     * 为什么要它：dsh 把「每条命令一开始就注册成 job」当默认 —— shell 缝只有一个 execute()，
     * 前台只是对同一个 job 的有界 wait（超时转后台 = 继续等同一个 job）。ADSH 的进程启动原先
     * 埋在 [run] 里，后台任务需要同一套 argv / 环境 / stdin / 杀树逻辑；抽出来之后两条路就只差
     * 「等不等」。
     */
    inner class ShellProcess internal constructor(
        private val process: Process,
        /** 子 shell 自报 pid 的标记文件（见 [spawn]）；读不到就退回只杀直接子进程 */
        private val pidMarker: File,
    ) {
        /** 杀树只做一次：重复 kill（job_kill 再来一次、前台超时与人工停止撞上）不再多起一根线程 */
        private val terminating = java.util.concurrent.atomic.AtomicBoolean(false)

        val stdout: InputStream get() = process.inputStream
        val stderr: InputStream get() = process.errorStream

        /** 等它结束（超时返回 false，进程继续跑） */
        fun waitFor(timeoutMs: Long): Boolean = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

        /** 一直等到结束 */
        fun waitFor(): Int = process.waitFor()

        fun exitValue(): Int = process.exitValue()

        /** 进程结束后的收尾：标记文件不留存量 */
        fun dispose() {
            runCatching { pidMarker.delete() }
        }

        /**
         * 请求终止：**非阻塞**（后台线程里 SIGTERM → graceMs → SIGKILL）。
         *
         * dsh 的 JobHooks.cancel 必须「同步、幂等、最终 settle」，而调用者可能是界面的停止按钮
         * （主线程）——所以真正的杀树放到线程里，注册表只改状态、等生产者自己结算。
         */
        fun terminate() {
            if (!terminating.compareAndSet(false, true)) return
            Thread({ terminateBlocking() }, "adsh-job-kill").apply { isDaemon = true }.start()
        }

        /** 阻塞版杀树：前台的超时/中断路径用它，判决与 [terminate] 完全一样 */
        fun terminateBlocking() {
            val pid = childPid()
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
         * 两个孙进程被 init 收养继续跑（旧实现就是这样漏的）。标记文件不依赖反射，也不给每条命令
         * 加延迟：只在真要杀的那一刻读一次。
         */
        private fun childPid(): Int {
            val text = runCatching { pidMarker.readText().trim() }.getOrNull().orEmpty()
            return text.toIntOrNull() ?: -1
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
     *   超时 —— `cat`、`read`、没有路径的 `grep`、裸 `python3`、`more`…（第 116 轮深度实测：同一段
     *   代码在宿主上跑，默认形状阻塞 >4s，换成 /dev/null 后 28ms 结束）。
     */
    fun spawn(
        command: String,
        workspaceRoot: File?,
        extraEnv: Array<String>? = null,
        separateStreams: Boolean = false,
    ): ShellProcess {
        // 让子 shell 一启动就把自己的 pid 写进标记文件：杀树要用它当根（见 ShellProcess.childPid）。
        // 写失败也不影响命令（读不到就退回只杀直接子进程）；写完的语句与命令同一个 -c 串，
        // 退出码仍是命令自己的。
        val marker = File.createTempFile("adsh-pid-", ".txt", tmpDir())
        val wrapped = "printf %s \"\$\$\" > \"" + marker.absolutePath + "\"; " + command
        val pb = ProcessBuilder(*launchArgv(arrayOf("-c", wrapped)))
            .redirectErrorStream(!separateStreams)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        envInto(pb, environment(workspaceRoot))
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
     *   （旧行为：一直等到超时）。命中时按 dsh 的超时同一条路**杀掉整棵进程树**，再正常返回 ——
     *   不能只是从阻塞的 waitFor 里逃出去，命令会变成孤儿继续跑（写文件、占端口）。
     */
    fun run(
        command: String,
        workspaceRoot: File?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
        extraEnv: Array<String>? = null,
        /** true = stdout/stderr 分开捕获（dsh 的 bash）；false = 合并（探针等旧调用） */
        separateStreams: Boolean = false,
        cancel: (() -> Boolean)? = null,
    ): ExecResult {
        val started = System.currentTimeMillis()
        val shell = spawn(command, workspaceRoot, extraEnv, separateStreams)
        val collector = OutputCollector(shell.stdout, maxOutputBytes)
        // 分开捕获时必须同时把另一条流读干，否则管道写满会卡住子进程
        val errCollector = if (separateStreams) OutputCollector(shell.stderr, maxOutputBytes) else null
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
        collector.join(1000)
        errCollector?.join(1000)
        shell.dispose()
        val exit = if (finished) shell.exitValue() else -1
        return ExecResult(
            exitCode = exit,
            output = collector.text(),
            // 中断也走「没等到进程结束」这条分支，但不算超时：界面/模型要看到的是「已停止」
            timedOut = !finished && !cancelled,
            truncated = collector.truncated,
            durationMs = System.currentTimeMillis() - started,
            stderr = errCollector?.text().orEmpty(),
            stderrTruncated = errCollector?.truncated == true,
            timeoutMs = timeoutMs,
            cancelled = cancelled,
        )
    }

    /**
     * 给整棵进程树发信号（dsh 的 signalChildGroup）。
     *
     * 安卓上没有 dsh 那条路：Node 用 `detached: true` 让子进程自成进程组，再 `kill(-pid)`；
     * 而 ProcessBuilder 起的子进程和 App 同组，对组发信号会把自己也杀掉。
     * 所以从 /proc 读出父子关系，按「先叶子、后根」逐个 kill。
     */
    private fun signalTree(rootPid: Int, signal: Int) {
        val childrenOf = readProcessTable()
        val order = ArrayList<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(rootPid)
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            order += pid
            childrenOf[pid]?.forEach { queue.add(it) }
        }
        // 反过来 = 叶子在前：父进程先死的话，子进程会被 init 收养、ppid 变成 1，就再也找不回来了
        for (index in order.indices.reversed()) {
            runCatching { Os.kill(order[index], signal) }
        }
    }


    /**
     * 读一份 ppid → 子进程 的表。
     * /proc/<pid>/stat 的格式是 `pid (comm) state ppid …`，comm 里可能有空格和括号，
     * 所以要从**最后一个 ')'** 之后开始切。
     */
    private fun readProcessTable(): Map<Int, List<Int>> {
        val table = HashMap<Int, MutableList<Int>>()
        val entries = runCatching { File("/proc").list() }.getOrNull() ?: return table
        for (name in entries) {
            name.toIntOrNull() ?: continue
            val stat = runCatching { File("/proc/" + name + "/stat").readText() }.getOrNull() ?: continue
            val close = stat.lastIndexOf(')')
            if (close < 0 || close + 2 >= stat.length) continue
            val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
            val ppid = fields.getOrNull(1)?.toIntOrNull() ?: continue
            table.getOrPut(ppid) { ArrayList() }.add(name.toInt())
        }
        return table
    }

    /** 本 App 的 versionCode（对齐 Termux 的 TERMUX_APP__VERSION_CODE） */
    private fun versionCode(): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    /** 可调试标记（对齐 Termux 的 TERMUX_APP__IS_DEBUGGABLE_BUILD） */
    private fun debuggableFlag(): String =
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) "1" else "0"

    private fun envInto(pb: ProcessBuilder, entries: Array<String>) {
        val env = pb.environment()
        entries.forEach { kv ->
            val i = kv.indexOf('=')
            if (i > 0) env[kv.substring(0, i)] = kv.substring(i + 1)
        }
    }

    companion object {
        /** 对齐 dsh：shell.timeoutMs 默认 120000，maxOutputBytes 默认 64000 */
        const val DEFAULT_TIMEOUT_MS = 120_000L
        const val DEFAULT_MAX_OUTPUT_BYTES = 64_000

        /** SIGTERM 之后等多久发 SIGKILL（dsh-bash-local 的 graceMs，默认 3000） */
        private const val GRACE_MS = 3_000L

        /**
         * 可中断执行时的探针轮询粒度：进程还在跑就每 50ms 问一次「用户停了吗」。
         * 这是「按停止到真的杀掉命令」之间的最大延迟（读取一次探针本身是内存操作）。
         */
        private const val CANCEL_POLL_MS = 50L

        /** 写围栏 shim 的文件名（CMake 目标 adshfence，落在 nativeLibraryDir） */
        const val FENCE_LIB = "libadshfence.so"

        /** $HOME/.gitconfig 里那条 safe.directory（见 ensureGitSafeDirectory） */
        private const val GIT_SAFE_LINE = "directory = *"

        /** termux-exec 的候选库名：bootstrap 里是哪个就挂哪个（见 preloadValue） */
        private val TERMUX_EXEC_LIBS = listOf(
            "libtermux-exec-ld-preload.so",
            "libtermux-exec.so",
        )

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

/**
 * 读取子进程输出并限流，避免 OOM 与上下文爆炸。
 *
 * **头 + 尾**（第 105 轮按测试 agent 的实测报告改）：dsh 的输出收集器
 * （dsh-subprocess-local 的 OutputCollector）是一个按字节滑动的窗口，只保留**尾部**
 * （"Tail-keep rationale (pi/OpenCode): errors and final results cluster at the end of command
 * output; the spill file covers the head."）—— 那条理由的前提是 **dsh 另有 spill 文件兜住头部**，
 * 而 ADSH 没有落盘，头部就是真的丢了。
 *
 * 报告里那次代价很典型：`apt install chromium` 失败（`E: Unable to correct problems, you have
 * held broken packages.` 在最前面），尾部却全是 `Setting up lld ...` 之类的正常噪音 ——
 * 只看返回值会得出「装成功了」，而它根本没装上。对 apt / make / pip / terraform 这类
 * 「错误先出、成功噪音在后」的工具全都成立。
 *
 * 现在的策略：**头部保留 [HEAD_SHARE] 那一段、尾部保留其余**，中间被掐掉的部分在正文里
 * 用一行标记说明（`[output truncated: N bytes elided in the middle]`）—— 两头都在，
 * 丢的是哪一段也不再靠猜。
 *
 * 之前还有过一版「填满就不再往后写」（= 只保留头部）：5 万行日志时被丢掉的恰恰是最后那段异常，
 * 与工具说明里的 "truncated to its tail" 正好相反。
 */
internal class OutputCollector(
    stream: InputStream,
    maxBytes: Int,
) {
    /** 上限至少 1 字节，避免配置成 0 时在 append 里算出负长度 */
    private val limit = maxBytes.coerceAtLeast(1)

    /** 头部窗口：上限的四分之一，最多 [HEAD_MAX_BYTES]（默认 64 KB ⇒ 16 KB 头 / 48 KB 尾） */
    private val headLimit = (limit / OutputCap.HEAD_SHARE_DIVISOR)
        .coerceIn(1, minOf(OutputCap.HEAD_MAX_BYTES, limit))

    /** 尾部窗口：剩下的都给它 */
    private val tailLimit = (limit - headLimit).coerceAtLeast(1)

    private val head = ByteArray(headLimit)
    private var headLength = 0

    /** 尾部用环形缓冲：窗口满了之后，最老的字节被新字节覆盖 */
    private val tail = ByteArray(tailLimit)
    private var tailLength = 0
    private var tailWrite = 0

    /** 从流里读到的总字节数（用来算中间掐掉多少） */
    private var total = 0L

    @Volatile var truncated = false
        private set

    private val thread = Thread {
        try {
            val chunk = ByteArray(8192)
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                if (n == 0) continue
                synchronized(this) { append(chunk, n) }
            }
        } catch (_: Throwable) {
        }
    }.apply { isDaemon = true; start() }

    private fun append(chunk: ByteArray, n: Int) {
        total += n
        var offset = 0
        // ① 先填头部窗口（一次就填满，之后再也不动它）
        if (headLength < headLimit) {
            val take = minOf(headLimit - headLength, n)
            System.arraycopy(chunk, 0, head, headLength, take)
            headLength += take
            offset = take
        }
        // ② 剩下的进尾部环形窗口；一旦有字节进不了头部，这次读取就是「被截断过」的
        if (offset < n) {
            writeTail(chunk, offset, n - offset)
            truncated = true
        }
    }

    private fun writeTail(chunk: ByteArray, offset: Int, count: Int) {
        if (count >= tailLimit) {
            // 一个块就比尾部窗口还大：只留它自己的尾巴
            System.arraycopy(chunk, offset + count - tailLimit, tail, 0, tailLimit)
            tailLength = tailLimit
            tailWrite = 0
            return
        }
        var src = offset
        var remaining = count
        while (remaining > 0) {
            val space = tailLimit - tailWrite
            val take = minOf(space, remaining)
            System.arraycopy(chunk, src, tail, tailWrite, take)
            tailWrite = (tailWrite + take) % tailLimit
            tailLength = minOf(tailLimit, tailLength + take)
            src += take
            remaining -= take
        }
    }

    fun join(millis: Long) = thread.join(millis)

    fun text(): String {
        val headBytes: ByteArray
        val tailBytes: ByteArray
        val elided: Long
        synchronized(this) {
            // 头部末尾那个多字节字符可能被切断：整个丢掉（不然解码出 U+FFFD）
            val headEnd = OutputCap.safeHeadLength(head, headLength)
            headBytes = head.copyOf(headEnd)
            tailBytes = linearTail()
            elided = (total - headEnd - tailBytes.size).coerceAtLeast(0)
        }
        // 尾部开头那个字节同样可能落在某个多字节字符中间：跳过续字节（10xxxxxx）
        val start = OutputCap.safeTailStart(tailBytes)
        val headText = String(headBytes, 0, headBytes.size, Charsets.UTF_8)
        val tailText = String(tailBytes, start, tailBytes.size - start, Charsets.UTF_8)
        if (elided <= 0L) return headText + tailText
        return headText +
            (if (headText.isEmpty() || headText.endsWith("\n")) "" else "\n") +
            "[output truncated: " + elided + " bytes elided in the middle]\n" +
            tailText
    }

    /** 环形缓冲拉直成「从最老到最新」的字节数组 */
    private fun linearTail(): ByteArray {
        if (tailLength == 0) return ByteArray(0)
        val out = ByteArray(tailLength)
        if (tailLength < tailLimit) {
            System.arraycopy(tail, 0, out, 0, tailLength)
            return out
        }
        val first = tailLimit - tailWrite
        System.arraycopy(tail, tailWrite, out, 0, first)
        System.arraycopy(tail, 0, out, first, tailWrite)
        return out
    }

}

/**
 * 输出截断的**唯一一份**口径：头 + 尾，中间就地标出省略了多少字节。
 *
 * 为什么头尾都要（第 105 轮按实测报告改，见 HANDOFF.md）：dsh 的输出收集器只留尾部 ——
 * 前提是它另有 spill 文件兜住头部；ADSH 从不落盘，头部丢了就是真丢了（`apt install` 的
 * `E: ...` 在最前面、尾部全是 `Setting up ...` 的噪音，只看尾部会以为装成功了）。所以：
 *  - 前台命令（[OutputCollector]）与后台任务的读（job_output、前台转后台的种子读）**同一份实现**；
 *  - 头占上限的 1/4（最多 16 KB），尾占其余；标记是**就地插入**的，模型看得到丢的是哪一段。
 */
internal object OutputCap {
    /** 头部窗口占上限的几分之一 */
    const val HEAD_SHARE_DIVISOR = 4

    /** 头部窗口的字节上限（默认 64 KB 上限 ⇒ 16 KB；上限更小时按比例缩） */
    const val HEAD_MAX_BYTES = 16 * 1024

    /**
     * 头部窗口的**字符完整结尾**：末尾若是一个不完整的多字节序列，就退到它的起始字节之前。
     * 返回可以安全解码的长度。
     */
    fun safeHeadLength(bytes: ByteArray, length: Int): Int {
        if (length == 0) return 0
        var i = length - 1
        var back = 0
        while (i >= 0 && back < 3 && (bytes[i].toInt() and 0xC0) == 0x80) {
            i--
            back++
        }
        if (i < 0) return 0
        val lead = bytes[i].toInt() and 0xFF
        val need = when {
            lead < 0x80 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        return if (i + need <= length) length else i
    }

    /** 尾部窗口的起点：首字节若落在某个多字节字符中间，就跳过续字节（10xxxxxx） */
    fun safeTailStart(bytes: ByteArray, from: Int = 0): Int {
        var i = from.coerceIn(0, bytes.size)
        while (i < bytes.size && (bytes[i].toInt() and 0xC0) == 0x80) i++
        return i
    }

    /**
     * 头 + 尾地截断一段文本（超过 [maxBytes] 才动它）。
     * @return 截断后的文本与「这次真的截断了」的标记
     */
    fun cap(text: String, maxBytes: Int): Pair<String, Boolean> {
        val limit = maxBytes.coerceAtLeast(1)
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.size <= limit) return text to false
        val headLimit = (limit / HEAD_SHARE_DIVISOR).coerceIn(1, minOf(HEAD_MAX_BYTES, limit))
        val headEnd = safeHeadLength(raw, headLimit)
        val start = safeTailStart(raw, raw.size - (limit - headEnd))
        val headText = String(raw, 0, headEnd, Charsets.UTF_8)
        val tailText = String(raw, start, raw.size - start, Charsets.UTF_8)
        val elided = raw.size - headEnd - (raw.size - start)
        val body = buildString {
            append(headText)
            if (headText.isNotEmpty() && !headText.endsWith("\n")) append('\n')
            append("[output truncated: ").append(elided).append(" bytes elided in the middle]\n")
            append(tailText)
        }
        return body to true
    }
}
