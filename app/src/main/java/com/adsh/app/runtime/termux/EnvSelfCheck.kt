package com.adsh.app.runtime.termux

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * 内嵌 Termux 的环境自检（第 72 轮新增）。
 *
 * **为什么要它**：内嵌 Termux 与官方 Termux 的差异几乎全落在两件应用层读不到的事情上 ——
 * 进程落在哪个 SELinux 域、`exec` 走的是「直接执行」还是「经系统 linker」。一旦走后者，官方文档
 * 写明的副作用就是 `/proc/<pid>/exe` 指向 `linker64`，于是「靠自身路径定位资源」的程序
 * （Chromium / Electron 的 ICU、任何 `uv_exepath` 用法）启动即崩、静态链接二进制跑不了、
 * 直接调 `execve()` 的程序要打补丁。这些都是**看不见的**：用户只会看到某个工具莫名其妙地失败。
 * 一份审查报告为了定位到 `/proc/self/exe` 花了 30+ 步；有了这条自检就是一条命令。
 *
 * 两个入口，同一批事实：
 *  - **终端里敲 `adsh-env-check`**（[installScript] 把脚本写进 `$PREFIX/bin`）：用户/模型视角，
 *    在**真实会话环境**里跑，结论直接可读；
 *  - **App 启动时（仅 debug 构建）**把同样的事实按行写进 logcat，tag = [TAG]：
 *    开发期 `adb logcat -s ADSH_ENVCHECK` 就能核对，不用碰界面（真机探针不必再驱动 UI）。
 *
 * 判据（与 termux-exec 的 `termux-exec-system-linker-exec is-enabled` 一致，源码在
 * `bin/termux-exec-system-linker-exec`：安卓 ≥ 10 且进程域不是 `untrusted_app_25` / `_27` 时启用）：
 * `targetSdk ≤ 28` 的设备把应用放进 `untrusted_app_27`（见 `/system/etc/selinux/plat_seapp_contexts`
 * 的 `minTargetSdkVersion=28 → untrusted_app_27`），于是**什么都不改写**，与 stock Termux 同路。
 */
object EnvSelfCheck {

    /** logcat tag（`adb logcat -s ADSH_ENVCHECK`） */
    const val TAG = "ADSH_ENVCHECK"

    /** `$PREFIX/bin` 下的命令名 */
    const val COMMAND = "adsh-env-check"

    /**
     * 自检脚本。写成 POSIX sh（前缀下的 sh 是 bootstrap 的 bash/dash 兼容模式），
     * 只依赖 bootstrap 自带的东西（`readlink` / `grep` / `uname` / `termux-exec-system-linker-exec`）。
     */
    private val SCRIPT = """
        #!/data/data/com.termux/files/usr/bin/sh
        # ADSH 环境自检 —— 由 App 写入（每次启动按内存里的版本覆盖），不要手改。
        #
        # 输出三段：应用信息 / exec 模式 / 现场探针。判读方法在最后一段。

        printf '%s\n' "== 应用信息 =="
        printf '%-34s %s\n' TERMUX_VERSION "${'$'}{TERMUX_VERSION:-（未设置）}"
        printf '%-34s %s\n' TERMUX_APP__TARGET_SDK "${'$'}{TERMUX_APP__TARGET_SDK:-（未设置）}"
        printf '%-34s %s\n' TERMUX__PREFIX "${'$'}{TERMUX__PREFIX:-（未设置）}"
        printf '%-34s %s\n' TERMUX_APP__APK_RELEASE "${'$'}{TERMUX_APP__APK_RELEASE:-（未设置）}"
        printf '%-34s %s\n' "SELinux 域" "$(cat /proc/self/attr/current 2>/dev/null || echo 未知)"
        printf '%-34s %s\n' "内核" "$(uname -r 2>/dev/null)"

        printf '\n%s\n' "== exec 模式 =="
        if command -v termux-exec-system-linker-exec >/dev/null 2>&1; then
            printf '%-34s %s\n' system_linker_exec "$(termux-exec-system-linker-exec is-enabled 2>/dev/null)"
        else
            printf '%-34s %s\n' system_linker_exec "（没有这个命令）"
        fi
        printf '%-34s %s\n' '/proc/self/exe' "$(readlink /proc/self/exe 2>/dev/null)"
        printf '%-34s %s\n' TERMUX_EXEC__PROC_SELF_EXE "${'$'}{TERMUX_EXEC__PROC_SELF_EXE:-（未设置）}"
        printf '%-34s %s\n' LD_PRELOAD "${'$'}{LD_PRELOAD:-（未设置，见下面的预载库）}"

        printf '\n%s\n' "== 现场探针（app 私有目录里的直接 exec）=="
        probe="${'$'}HOME/.adsh-env-check"
        mkdir -p "${'$'}probe" 2>/dev/null
        # 1) 脚本：安卓 10+ 且 targetSdk >= 29 时，内核 exec 脚本自己那一步就 EACCES
        printf '#!/data/data/com.termux/files/usr/bin/sh\necho "  [1] 脚本直接执行：ok"\n' > "${'$'}probe/probe.sh"
        chmod 755 "${'$'}probe/probe.sh" 2>/dev/null
        "${'$'}probe/probe.sh" 2>/dev/null || echo "  [1] 脚本直接执行：失败（EACCES —— 只能靠 linker 改写或 shebang 兜底）"
        # 2) ELF：复制一个真实的动态可执行文件到 app 私有目录再执行
        if cp /system/bin/sh "${'$'}probe/probe-elf" 2>/dev/null; then
            chmod 755 "${'$'}probe/probe-elf" 2>/dev/null
            "${'$'}probe/probe-elf" -c 'echo "  [2] ELF 直接执行：ok"' 2>/dev/null ||
                echo "  [2] ELF 直接执行：失败（EACCES —— 被 app_data_file 的 exec 限制挡住）"
        else
            echo "  [2] ELF 直接执行：跳过（无法把 /system/bin/sh 复制到私有目录）"
        fi
        # 3) 预载库：围栏 shim 与 termux-exec 是否真的进了每个进程。
        #    注意 LD_PRELOAD 这个环境变量**永远**是空的：shim 在 constructor 里把它从进程环境里摘掉
        #    （免得原样漏给 glibc/musl 子进程），随后在每次 exec 前按内存里的清单重新写回子进程。
        #    所以唯一的硬证据是 /proc/<pid>/maps 里有没有真的加载它。
        #    （`wc -l < /proc/xxx/maps` 在 coreutils 上会因为 proc 文件 st_size=0 报 0，别用它。）
        echo "  [3] 本进程里已加载的预载库（maps 命中行数）："
        echo "      libadshfence.so        $(grep -c adshfence /proc/self/maps 2>/dev/null)"
        echo "      libtermux-exec-*.so    $(grep -c termux-exec /proc/self/maps 2>/dev/null)"
        echo "      maps 总行数            $(cat /proc/self/maps 2>/dev/null | wc -l)"
        echo "      （LD_PRELOAD 环境变量按设计为空；shim 每次 exec 会把它按内存里的清单重新写回子进程）"

        printf '\n%s\n' "== 存储 =="
        if [ -n "${'$'}ADSH_WORKSPACE" ] && [ -d "${'$'}ADSH_WORKSPACE" ]; then
            printf '%-34s %s\n' 工作区 "${'$'}ADSH_WORKSPACE（可读）"
        else
            printf '%-34s %s\n' 工作区 "${'$'}{ADSH_WORKSPACE:-（未绑定）}"
        fi

        printf '\n%s\n' "== 浏览器（渲染网页 / 截图）=="
        browser="$(command -v chromium 2>/dev/null || command -v chromium-browser 2>/dev/null || \
            command -v headless_shell 2>/dev/null || true)"
        if [ -n "${'$'}browser" ]; then
            printf '%-34s %s\n' 浏览器 "${'$'}browser"
            printf '%-34s %s\n' 版本 "$("${'$'}browser" --version 2>&1 | head -1)"
            if [ -x "${'$'}PREFIX/bin/adsh-shot" ]; then
                echo "  实渲染自检（adsh-shot --check，约 3-6 秒）："
                "${'$'}PREFIX/bin/adsh-shot" --check 2>&1 | sed 's/^/      /'
            else
                echo "  （adsh-shot 不在，跳过实渲染自检）"
            fi
        else
            printf '%-34s %s\n' 浏览器 "（没装）"
            echo "  装一个再渲染：pkg install chromium；之后用 adsh-shot <URL|本地路径> [out.png]"
        fi

        printf '\n%s\n' "== 怎么读这份结果 =="
        exe="$(readlink /proc/self/exe 2>/dev/null)"
        case "${'$'}exe" in
            *linker64*)
                echo "  /proc/self/exe 指向 linker64 = 走 system_linker_exec："
                echo "    - 读 /proc/self/exe 定位自身资源的程序（Chromium/Electron…）会崩，需改读"
                echo "      ${'$'}TERMUX_EXEC__PROC_SELF_EXE，或把 targetSdk 降到 28 让系统直接执行；"
                echo "    - 静态链接二进制（musl/zig 的 ET_EXEC）不适用，需 static-pie 构建；"
                echo "    - 直接调 execve() 的程序要打补丁。"
                ;;
            *)
                echo "  /proc/self/exe 指向真实程序 = 直接执行模式（与 stock Termux 一致）："
                echo "    - 上面三段里的 [1] [2] 都应当是 ok；"
                echo "    - 静态链接仍需 PIE：安卓 15+ 一律拒绝 ET_EXEC（unexpected e_type: 2），"
                echo "      与本环境无关，是工具自身要用 static-pie 构建。"
                ;;
        esac
    """.trimIndent() + "\n"

    /** 把自检脚本写进 `$PREFIX/bin`（幂等安装见 [EnvScripts.install]） */
    fun installScript(prefix: File) = EnvScripts.install(prefix, COMMAND, SCRIPT)

    /**
     * 在**应用自己的域里**跑一遍自检并把结果写进 logcat（debug 构建启动时调用）。
     *
     * 走的是 [TermuxRuntime.run]，也就是 bash -c + 我们全套环境 —— 与终端/工具里的子进程同一条路，
     * 所以它报出来的 exec 模式就是真实会生效的那一种。
     */
    fun runAndLog(context: Context, runtime: TermuxRuntime, workspaceRoot: File?) {
        val shimDiag = File(context.filesDir, "shim-diag.txt")
        runCatching { shimDiag.delete() }
        runCatching {
            // 先看应用进程自己的域（子进程继承它），这是 termux-exec 判断要不要改写的唯一依据
            val domain = runCatching { File("/proc/self/attr/current").readText().trim() }.getOrDefault("未知")
            Log.i(
                TAG,
                "app targetSdk=" + context.applicationInfo.targetSdkVersion +
                    " deviceSdk=" + Build.VERSION.SDK_INT +
                    " domain=" + domain +
                    " isExternalStorageManager=" + Environment.isExternalStorageManager(),
            )
            // 预载库的实际取法（Kotlin 侧直接问，比在 shell 里猜可靠）：LD_PRELOAD 为空时，
            // 写围栏与 termux-exec 都不会生效 —— 这是最该先看到的一条。
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val fence = File(nativeDir, "libadshfence.so")
            val execLibs = File(runtime.prefix, "lib").listFiles()
                ?.filter { it.name.startsWith("libtermux-exec") }?.map { it.name }.orEmpty()
            Log.i(
                TAG,
                "nativeLibraryDir=" + nativeDir +
                    " fenceExists=" + fence.isFile +
                    " termuxExecLibs=" + execLibs +
                    " preloadValue=[" + runtime.preloadValue() + "]",
            )
            val result = runtime.run(
                command = COMMAND,
                workspaceRoot = workspaceRoot,
                // 里面有一步「实渲染一张本地 HTML」（浏览器启动 + 截图），给足时间
                timeoutMs = 60_000,
                maxOutputBytes = 64_000,
                // 围栏 shim 自己的现场记录（不依赖 /proc 的读法）：只要它在**子进程**里被加载，
                // 这个文件里就会有 `[shim] init done …`，exec 时还会留下 `[fixup] …` 一行。
                extraEnv = arrayOf("ADSH_SHIM_DIAG=" + shimDiag.absolutePath),
            )
            Log.i(TAG, "exit=" + result.exitCode + " timedOut=" + result.timedOut + " durationMs=" + result.durationMs)
            result.output.lineSequence().filter { it.isNotBlank() }.forEach { Log.i(TAG, it) }
            if (result.stderr.isNotBlank()) Log.w(TAG, result.stderr)
            // 围栏/termux-exec 到底进没进子进程 —— 这条是写围栏是否生效的唯一硬证据
            val lines = runCatching { shimDiag.readLines() }.getOrDefault(emptyList())
            Log.i(TAG, "shimDiag=" + shimDiag.absolutePath + " exists=" + shimDiag.isFile + " lines=" + lines.size)
            lines.take(6).forEach { Log.i(TAG, "  shim| " + it) }
        }.onFailure { Log.w(TAG, "self check failed", it) }
    }
}
