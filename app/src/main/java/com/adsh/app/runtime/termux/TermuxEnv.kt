package com.adsh.app.runtime.termux

/**
 * 子进程环境的**清单装配**（原先埋在 [TermuxRuntime.environment] 的 104 行里，整个 package
 * 只有 [OutputCollectorTest] 一个用例文件，这里一条都没被钉过）。
 *
 * 为什么值得抽：这一段里每一行几乎都是**真机上踩出来的**，而它们全是字符串拼接 —— 改错一个字，
 * 症状却是「termux-exec 静默失效」「pkg 以为不在 Termux 里」「apt 说 admindir 不在 instdir 里」
 * 这类要 Logcat 加真机才看得见的东西。事实（uid / sdk / 包名 / preload 清单 / …）在这里只是一份
 * 入参快照，装配本身能在纯 JVM 单测里逐条打表。
 *
 * dsh 的对应物是 `dsh-bash-local` 的环境注入（把 sandbox 与 shell 的 env 合并后交给子进程）；
 * ADSH 没有内核沙箱、围栏靠 LD_PRELOAD，所以这里多了 fence 那几个变量。
 */

/**
 * 一次装配需要的**全部事实**（启动期读一次就够的东西）。
 *
 * @param prefix 官方前缀文本 `/data/data/com.termux/files/usr`（见 [BootstrapInstaller.prefix]）
 * @param home 官方家目录文本
 * @param packageName 包名（= com.termux，方案 A 的前提）
 * @param dataDir `context.applicationInfo.dataDir`（可能是 /data/user/0/… 那种拼写）
 * @param filesDir `context.filesDir`（同上）
 * @param uid 本进程 uid（TERMUX_APP__UID）
 * @param pid 本进程 pid（TERMUX_APP__PID 与兼容别名 TERMUX_APP_PID）
 * @param termuxVersion TERMUX_VERSION 的值：**Termux 语义的版本号**（termux-tools 拿它判断走不走
 *   旧 App 的兼容分支），**不是** App 的 versionName
 * @param appVersionCode 本 App 的 versionCode（TERMUX_APP__VERSION_CODE）—— 与 [termuxVersion] 是
 *   两个不同的东西，别顺手统一
 * @param targetSdk `TERMUX_APP__TARGET_SDK`
 * @param sdkInt `ANDROID__BUILD_VERSION_SDK`（termux-exec 的 system-linker-exec 要它）
 * @param apkRelease TERMUX_APP__APK_RELEASE（GITHUB / F_DROID / GOOGLE_PLAY）
 * @param debuggable TERMUX_*_DEBUGGABLE_BUILD，装配时写成 1 / 0
 * @param preload `LD_PRELOAD` 清单（空串 = 这次不挂，见 [TermuxRuntime.preloadValue]）
 * @param scratch `ADSH_SCRATCH`（f2fs 上的构建目录）
 * @param fenceMode `ADSH_FENCE_MODE` 的**基线值**（完全权限）；需要围栏的调用由
 *   [TermuxRuntime.fenceEnv] 用真实模式 + 白名单覆盖它
 * @param workspaceRoot 工作区；null = 不给 PWD / ADSH_WORKSPACE（终端页与探针调用就是这样）
 */
internal data class ShellFacts(
    val prefix: String,
    val home: String,
    val packageName: String,
    val dataDir: String,
    val filesDir: String,
    val uid: Int,
    val pid: Int,
    val termuxVersion: String,
    val appVersionCode: Long,
    val targetSdk: Int,
    val sdkInt: Int,
    val apkRelease: String,
    val debuggable: Boolean,
    val preload: String,
    val scratch: String,
    val fenceMode: String,
    val workspaceRoot: String?,
)

/**
 * 装配成 `KEY=VALUE` 清单（顺序就是下面这个顺序；键不会重复 —— [TermuxRuntime.envInto] 按第一个
 * `=` 拆键值，重复的键会让后者静默胜出，有用例钉住这条）。
 */
internal fun shellEnvironment(f: ShellFacts): List<String> {
    val p = f.prefix
    val home = f.home
    val tmp = p + "/tmp"
    val dataDir = f.dataDir
    // 旧形态的 data dir（/data/data/<pkg>）。**必须与 dataDir 分开给**：
    // termux-exec 的 shouldEnableSystemLinkerExecForFile() 只在「可执行文件路径文本上落在
    // DATA_DIR 或 LEGACY_DATA_DIR 之下」时才把 execve 改写成 linker64（安卓 10+ 的 W^X 绕过）。
    // 我们的前缀文本是 /data/data/com.termux/files/usr，只有 LEGACY_DATA_DIR 能把它盖住 ——
    // 早先两个都给成 /data/user/0/<pkg>，于是库判定「不是我的 app 数据目录」，preload 加载了
    // 却什么都不改写（M0 里「termux-exec 实测无效」的真正原因）。
    val legacyDataDir = legacyDataDirOf(f.packageName)
    val list = mutableListOf(
        "PREFIX=" + p,
        "HOME=" + home,
        // PATH：前缀的 bin 在最前（termux 自己的工具不能被系统的同名工具盖掉），
        // 之后接 /system/bin —— 安卓那几个平台 CLI（pm / cmd / dumpsys / logcat / getprop /
        // settings / input / screencap）都在那儿，而**它们对 app UID 的权限差别极大**（本轮实测）：
        //   ✅ pm list packages、cmd -l、logcat、getprop 能用（只读认知）
        //   ❌ am start / cmd activity start-activity（package=com.android.shell 不属于本 uid）、
        //      monkey、input tap（INJECT_EVENTS）、screencap、settings get（INTERACT_ACROSS_USERS）、
        //      content query（ACCESS_CONTENT_PROVIDERS_EXTERNALLY）—— 这些是 shell 专属工具，
        //      失败信息会如实回到工具输出里（模型能自己看到「不行」）。
        // 想真正操控手机（拉起 app / 点屏幕）不能走这里，得由 ADSH 自己用前台身份做。
        "PATH=" + p + "/bin:/system/bin",
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
        "ADSH_SCRATCH=" + f.scratch,
        "SHELL=" + p + "/bin/bash",
        // ---- Termux 官方的那套环境变量 ----
        // 内嵌 bootstrap 之后，「我是不是在 Termux 里」全靠这些变量：包管理脚本、configure、
        // termux-* 工具、以及 termux-exec 自己都会读它们。缺了就是用户看到的那类怪事
        // （TERMUX_VERSION 为空、工具按「非 Termux」分支走）。值一律指到真实前缀 ——
        // 包名就是 com.termux，所以真实前缀**就是**官方前缀 /data/data/com.termux/…。
        "TERMUX_VERSION=" + f.termuxVersion,
        "TERMUX_MAIN_PACKAGE_FORMAT=debian",
        "TERMUX_APP_PACKAGE_MANAGER=apt",
        "TERMUX_APP__PACKAGE_NAME=" + f.packageName,
        "TERMUX_APP__VERSION_NAME=" + f.termuxVersion,
        "TERMUX_APP__VERSION_CODE=" + f.appVersionCode,
        "TERMUX_APP__TARGET_SDK=" + f.targetSdk,
        "TERMUX_APP__UID=" + f.uid,
        "TERMUX_APP__PID=" + f.pid,
        "TERMUX_APP__FILES_DIR=" + f.filesDir,
        "TERMUX_APP__DATA_DIR=" + dataDir,
        "TERMUX_APP__LEGACY_DATA_DIR=" + legacyDataDir,
        "TERMUX_APP__APK_RELEASE=" + f.apkRelease,
        "TERMUX_APP__IS_DEBUGGABLE_BUILD=" + if (f.debuggable) "1" else "0",
        // termux-exec 的 system-linker-exec 需要它：termux-exec.postinst 的原文是
        // 「termux-exec-system-linker-exec called by termux-exec-ld-preload-lib will fail if
        //   ANDROID__BUILD_VERSION_SDK is not exported (by Termux app) and getprop at
        //   /system/bin/getprop is not accessible」——官方 app 导出它，我们以前漏了。
        "ANDROID__BUILD_VERSION_SDK=" + f.sdkInt,
        // 兼容旧脚本的别名（termux-tools 的旧分支与第三方脚本还在读它们）
        "TERMUX_APP_PID=" + f.pid,
        "TERMUX_APK_RELEASE=" + f.apkRelease,
        "TERMUX_IS_DEBUGGABLE_BUILD=" + if (f.debuggable) "1" else "0",
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
    f.preload.takeIf { it.isNotEmpty() }?.let { list += "LD_PRELOAD=" + it }
    list += "ADSH_FENCE_MODE=" + f.fenceMode
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
    if (f.workspaceRoot != null) {
        list += "PWD=" + f.workspaceRoot
        // 供脚本/模型自省：当前工作区（素材入口 + 成品出口）的真实路径
        list += "ADSH_WORKSPACE=" + f.workspaceRoot
    }
    return list
}

/**
 * 旧形态的 data dir（`/data/data/<pkg>`）。**必须与 dataDir 分开给** —— 这就是上面那段注释里
 * M0 的坑（termux-exec 只在可执行文件的路径文本落在 DATA_DIR 或 LEGACY_DATA_DIR 之下时才改写
 * execve），而手写这个字符串的地方有三处：运行期的 [shellEnvironment]、安装期 second stage 的
 * [BootstrapInstaller.secondStageEnv]、以及 apt 缓存的两种拼写（[TermuxRuntime.cacheRoots]）。
 * 三处必须同一条规则，所以收成这一个函数。
 */
internal fun legacyDataDirOf(packageName: String): String = "/data/data/" + packageName

/**
 * termux-exec 的候选库名，**按优先级**：bootstrap 里两个都在 —— `libtermux-exec-ld-preload.so`
 * 是真身，`libtermux-exec.so` 是 SYMLINKS.txt 建的链接（← 前者），谁在就挂谁。
 *
 * 顺序有语义（LD_PRELOAD 里同名符号按清单顺序解析），而且**安装期的 second stage 与运行期的
 * preloadValue 必须挂同一个**：这一份是唯一来源 —— 原先两个文件各手写一份，靠一句
 * 「候选顺序与 TermuxRuntime.preloadValue 一致」的注释维持（R56 同款的两份手抄）。
 */
internal val TERMUX_EXEC_LIBS = listOf(
    "libtermux-exec-ld-preload.so",
    "libtermux-exec.so",
)
