package com.adsh.app.runtime.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子进程环境的清单装配（[shellEnvironment]）。
 *
 * 这一段原先在 [TermuxRuntime.environment] 里、零用例，而它全是字符串拼接：改错一个字，
 * 症状是「termux-exec 静默失效」「pkg 以为不在 Termux 里」「apt 说 admindir 不在 instdir 里」
 * 这类要 Logcat 加真机才看得见的东西。下面每条都对着真机上踩过的坑。
 */
class TermuxEnvTest {

    private val p = "/data/data/com.termux/files/usr"
    private val home = "/data/data/com.termux/files/home"

    private fun facts(): ShellFacts = ShellFacts(
        prefix = p,
        home = home,
        packageName = "com.termux",
        dataDir = "/data/user/0/com.termux",
        filesDir = "/data/user/0/com.termux/files",
        uid = 10394,
        pid = 5019,
        termuxVersion = "0.119.0",
        appVersionCode = 12L,
        targetSdk = 28,
        sdkInt = 36,
        apkRelease = "GITHUB",
        debuggable = true,
        preload = "/data/app/x/lib/libadshfence.so " + p + "/lib/libtermux-exec.so",
        scratch = home + "/scratch",
        fenceMode = "danger-full-access",
        workspaceRoot = "/storage/emulated/0/ws",
    )

    private fun env(f: ShellFacts = facts()): Map<String, String> =
        shellEnvironment(f).associate { it.substringBefore('=') to it.substringAfter('=') }

    // ---------------------------------------------------------------- 路径与 PATH

    @Test
    fun `PATH 里前缀的 bin 在最前、然后才是 system bin`() {
        // 前缀优先：termux 自己的工具不能被系统的同名工具盖掉（第 125 轮 T0 的口径）
        assertEquals(p + "/bin:/system/bin", env()["PATH"])
    }

    @Test
    fun `前缀与家目录用官方路径文本`() {
        val e = env()
        assertEquals(p, e["PREFIX"])
        assertEquals(home, e["HOME"])
        assertEquals(p, e["TERMUX__PREFIX"])
        assertEquals(home, e["TERMUX__HOME"])
        assertEquals(p + "/bin/bash", e["SHELL"])
    }

    /**
     * 尾斜杠是 termux-exec 与 termux-tools 眼里的非法值（它们要求路径末尾不能是斜杠，
     * 判非法就静默回退到编译期的 com.termux 前缀）—— 所以路径值一律不带尾斜杠。
     */
    @Test
    fun `路径值不带尾斜杠`() {
        val e = env()
        for (key in listOf(
            "PREFIX", "HOME", "TMPDIR", "TMP", "TEMP", "ADSH_TMP_REDIRECT", "ADSH_SCRATCH",
            "TERMUX__PREFIX", "TERMUX__HOME", "TERMUX__ROOTFS", "DPKG_ADMINDIR",
            "TERMUX_APP__DATA_DIR", "TERMUX_APP__LEGACY_DATA_DIR", "TERMUX_APP__FILES_DIR",
        )) {
            assertFalse(key + " 不该以斜杠结尾：" + e[key], e.getValue(key).endsWith("/"))
        }
    }

    /**
     * **M0 里「termux-exec 实测无效」的真正原因**：两个都给了 /data/user/0/… 时，
     * termux-exec 不认为可执行文件在自己的数据目录下，preload 加载了却什么都不改写。
     * 必须分成 /data/data/<pkg> 与 dataDir 两种拼写。
     */
    @Test
    fun `旧形态的 data dir 与 data dir 分开给`() {
        val e = env()
        assertEquals("/data/data/com.termux", e["TERMUX_APP__LEGACY_DATA_DIR"])
        assertEquals("/data/user/0/com.termux", e["TERMUX_APP__DATA_DIR"])
        assertNotEquals(e["TERMUX_APP__DATA_DIR"], e["TERMUX_APP__LEGACY_DATA_DIR"])
        // rootfs 是「旧形态 data dir + /files」，apt/dpkg 编译期认的是它
        assertEquals("/data/data/com.termux/files", e["TERMUX__ROOTFS"])
    }

    @Test
    fun `apt 的包数据库指到真实前缀`() {
        assertEquals(p + "/var/lib/dpkg", env()["DPKG_ADMINDIR"])
    }

    // ------------------------------------------------------------- Termux 身份

    @Test
    fun `Termux 身份的那几个变量`() {
        val e = env()
        assertEquals("0.119.0", e["TERMUX_VERSION"])
        assertEquals("debian", e["TERMUX_MAIN_PACKAGE_FORMAT"])
        assertEquals("apt", e["TERMUX_APP_PACKAGE_MANAGER"])
        assertEquals("com.termux", e["TERMUX_APP__PACKAGE_NAME"])
        assertEquals("GITHUB", e["TERMUX_APP__APK_RELEASE"])
    }

    /**
     * `ANDROID__BUILD_VERSION_SDK` 是 termux-exec 的 system-linker-exec 要的
     * （postinst 原文：不给它、又没有 /system/bin/getprop 时改写会失败）。官方 App 导出它，
     * ADSH 早先漏了 —— 这条钉住不许再漏。
     */
    @Test
    fun `ANDROID 构建版本必须导出`() {
        assertEquals("36", env()["ANDROID__BUILD_VERSION_SDK"])
    }

    /** 兼容别名：termux-tools 的旧分支与第三方脚本还在读它们，值必须与主键一致 */
    @Test
    fun `兼容别名与主键一致`() {
        val e = env()
        assertEquals(e["TERMUX_APP__PID"], e["TERMUX_APP_PID"])
        assertEquals(e["TERMUX_APP__APK_RELEASE"], e["TERMUX_APK_RELEASE"])
        assertEquals(e["TERMUX_APP__IS_DEBUGGABLE_BUILD"], e["TERMUX_IS_DEBUGGABLE_BUILD"])
    }

    @Test
    fun `可调试标记写成 1 与 0`() {
        assertEquals("1", env()["TERMUX_APP__IS_DEBUGGABLE_BUILD"])
        assertEquals("0", env(facts().copy(debuggable = false))["TERMUX_APP__IS_DEBUGGABLE_BUILD"])
    }

    /**
     * VERSION_NAME 是 **Termux 语义**的版本号（termux-tools 拿它判断走不走旧 App 的兼容分支），
     * VERSION_CODE 才是本 App 的 versionCode —— 两个不同的东西，别顺手统一。
     */
    @Test
    fun `VERSION_NAME 用 Termux 版本号、VERSION_CODE 用 App 的`() {
        val e = env()
        assertEquals("0.119.0", e["TERMUX_APP__VERSION_NAME"])
        assertEquals("12", e["TERMUX_APP__VERSION_CODE"])
    }

    @Test
    fun `uid pid targetSdk 原样进清单`() {
        val e = env()
        assertEquals("10394", e["TERMUX_APP__UID"])
        assertEquals("5019", e["TERMUX_APP__PID"])
        assertEquals("28", e["TERMUX_APP__TARGET_SDK"])
    }

    // ------------------------------------------------------------- 临时目录与围栏

    @Test
    fun `临时目录四个变量指同一个地方`() {
        val e = env()
        for (key in listOf("TMPDIR", "TMP", "TEMP", "ADSH_TMP_REDIRECT")) {
            assertEquals(key, p + "/tmp", e[key])
        }
        assertEquals(home + "/scratch", e["ADSH_SCRATCH"])
    }

    /**
     * **第 64 轮的 bug**：完全权限下这里什么都不给，于是 shim 挂着却没有模式、
     * 按「workspace-write + 空白名单」跑 —— 什么目录都写不了。基线必须显式写「关」。
     */
    @Test
    fun `完全权限的基线显式写成 danger-full-access`() {
        assertEquals("danger-full-access", env()["ADSH_FENCE_MODE"])
    }

    @Test
    fun `LD_PRELOAD 有清单就写、空清单就不写`() {
        val preload = "/data/app/x/lib/libadshfence.so " + p + "/lib/libtermux-exec.so"
        assertEquals(preload, env()["LD_PRELOAD"])
        assertFalse(env(facts().copy(preload = "")).containsKey("LD_PRELOAD"))
    }

    // ------------------------------------------------------------------ 工作区

    @Test
    fun `绑了工作区就给 PWD 与 ADSH_WORKSPACE`() {
        val e = env()
        assertEquals("/storage/emulated/0/ws", e["PWD"])
        assertEquals("/storage/emulated/0/ws", e["ADSH_WORKSPACE"])
    }

    @Test
    fun `没绑工作区（终端页、探针调用）就不给这两个`() {
        val e = env(facts().copy(workspaceRoot = null))
        assertFalse(e.containsKey("PWD"))
        assertFalse(e.containsKey("ADSH_WORKSPACE"))
        // 其余变量一个不少
        assertEquals(p, e["PREFIX"])
    }

    // ------------------------------------------------------------------ 形态

    /** [TermuxRuntime.envInto] 按第一个 `=` 拆键值：重复的键会让后者静默胜出 */
    @Test
    fun `键不重复`() {
        val keys = shellEnvironment(facts()).map { it.substringBefore('=') }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `每一项都是 KEY=VALUE 且键非空`() {
        for (entry in shellEnvironment(facts())) {
            assertTrue(entry, entry.contains('='))
            assertTrue(entry, entry.substringBefore('=').isNotEmpty())
        }
    }

    // ------------------------------------------------------------- 共用的两条规则

    /** 顺序有语义：LD_PRELOAD 里同名符号按清单顺序解析，安装期与运行期挂的必须是同一份 */
    @Test
    fun `termux-exec 候选：ld-preload 变体在前`() {
        assertEquals(listOf("libtermux-exec-ld-preload.so", "libtermux-exec.so"), TERMUX_EXEC_LIBS)
    }

    @Test
    fun `旧形态 data dir 只有一条规则`() {
        assertEquals("/data/data/com.termux", legacyDataDirOf("com.termux"))
        // 装配出来那个值必须就是这条规则的结果（M0 的坑：两个 data dir 不能给成同一种拼写）
        assertEquals(legacyDataDirOf("com.termux"), env()["TERMUX_APP__LEGACY_DATA_DIR"])
    }

    @Test
    fun `清单是 List 不是数组：调用点自己转`() {
        val list = shellEnvironment(facts())
        assertTrue(list.isNotEmpty())
        assertEquals(listOf("PREFIX=" + p), list.filter { it.startsWith("PREFIX=") })
    }
}
