package com.adsh.app.runtime.termux

import java.io.File

/**
 * 环境脚本的安装（幂等）：内容一致就不动文件，免得每次启动都改时间戳。
 * 失败只记日志 —— 这些脚本是诊断/工具，不该影响启动。
 */
internal object EnvScripts {

    fun install(prefix: File, name: String, content: String) {
        runCatching {
            val bin = File(prefix, "bin").apply { mkdirs() }
            val target = File(bin, name)
            if (target.isFile && target.readText() == content) return
            target.writeText(content)
            target.setExecutable(true, false)
            target.setReadable(true, false)
        }.onFailure { android.util.Log.w(EnvSelfCheck.TAG, "install $name failed", it) }
    }
}

/**
 * `adsh-shot`：ADSH 自带的网页渲染 / 截图命令（第 73 轮）。
 *
 * **为什么要有它**（测试 agent 在真机上踩到的两条，都属于「工具自己解析环境」的坑）：
 *
 *  - 浏览器二进制有三个入口，名字和位置都不固定：`headless_shell`（纯 headless，截图最合适）、
 *    `chromium` / `chromium-browser`（Alpine 那个启动器包装脚本）。第三方工具常把默认路径写死成
 *    `/usr/bin/chrome`（桌面 Linux 的位置），在 Termux 上必然报
 *    `Unable to locate browser binary /usr/bin/chrome` —— 安卓根本没有 `/usr`；
 *  - 本地文件必须显式 `file://`：裸路径会被当成域名、被拼成 `https://…` → `ERR_NAME_NOT_RESOLVED`，
 *    而「渲染本地网页项目」恰恰是最常用的场景；页面之间互相引用还需要
 *    `--allow-file-access-from-files`。
 *
 * 另外两条是 Termux/安卓本身的约束，一起在这里处理掉：安卓没有 user namespace（必须
 * `--no-sandbox`），`headless_shell` 不能吃 `--headless=new` 而完整版 chromium 又必须给。
 *
 * 它同时是**给第三方工具用的浏览器解析器**：`adsh-shot --which` 打印解析到的真实路径，
 * 于是「必须给 -b」的工具可以直接 `-b "$(adsh-shot --which)"`。
 */
internal object AdshShot {

    const val COMMAND = "adsh-shot"

    val SCRIPT: String = """
        #!/data/data/com.termux/files/usr/bin/sh
        # ADSH 网页渲染 / 截图（由 App 写入 ${'$'}PREFIX/bin，每次启动按内存里的版本覆盖，不要手改）。
        #
        #   adsh-shot <URL|本地路径> [输出.png] [选项]
        #   adsh-shot --which            打印解析到的浏览器路径（给需要 -b 的第三方工具用）
        #   adsh-shot --check            自检：浏览器版本 + 真渲染一张含中文的本地 HTML
        #
        # 选项：
        #   --size WxH       视口大小（默认 1280x800）
        #   --wait MS        等页面稳定的虚拟时间预算，毫秒（默认 5000，本地大页面可以加大）
        #   --full-page      用很高的视口渲染 PNG（高度取下面那条）
        #   --height N       --full-page 时的视口高度（默认 4000）
        #   --pdf FILE       改成整页 PDF（--print-to-pdf，整份文档都在里面，不受视口限制）
        #   --browser PATH   指定浏览器二进制
        #
        # 退出码：0 成功；2 用法/环境问题（含「没装浏览器」）；1 渲染失败（浏览器 stderr 原样透出）。

        set -u

        prefix="${'$'}{TERMUX__PREFIX:-${'$'}{PREFIX:-/data/data/com.termux/files/usr}}"
        tmp="${'$'}{TMPDIR:-${'$'}prefix/tmp}"

        # 默认值（set -u 下每个变量都要有初值：漏一个就是一句「parameter not set」直接退出）
        BROWSER=""
        SIZE="1280x800"
        HEIGHT="4000"
        WAIT_MS="5000"
        PDF=""
        FULL_PAGE=0
        TARGET=""
        OUT=""

        die() { echo "adsh-shot: ${'$'}*" >&2; exit 2; }

        usage() {
            sed -n '2,18p' "${'$'}0" | sed 's/^# \{0,1\}//'
        }

        # 浏览器解析：显式覆盖 → PATH 里的常见名字 → 前缀里的已知位置
        #
        # **顺序有讲究**（第 73 轮真机实测）：Termux 的 `headless_shell` 在本环境里**什么都不做**
        # —— 各种参数组合都是 rc=0、不产出文件、连 --dump-dom 都没有输出；而完整版 `chromium`
        # 加 `--headless=new` 一次就成（`5572 bytes written to file …`）。所以优先用 chromium，
        # headless_shell 只作为兜底，并且渲染完发现没产出时会**自动换另一个再试一次**。
        resolve_browser() {
            if [ -n "${'$'}{ADSH_SHOT_BROWSER:-}" ]; then
                [ -x "${'$'}{ADSH_SHOT_BROWSER}" ] || die "ADSH_SHOT_BROWSER 不可执行：${'$'}ADSH_SHOT_BROWSER"
                echo "${'$'}{ADSH_SHOT_BROWSER}"; return 0
            fi
            for name in chromium chromium-browser chrome headless_shell; do
                found="$(command -v "${'$'}name" 2>/dev/null || true)"
                if [ -n "${'$'}found" ] && [ -x "${'$'}found" ]; then echo "${'$'}found"; return 0; fi
            done
            for candidate in "${'$'}prefix/lib/chromium/chrome" "${'$'}prefix/lib/chromium/headless_shell"; do
                if [ -x "${'$'}candidate" ]; then echo "${'$'}candidate"; return 0; fi
            done
            return 1
        }

        need_browser() {
            browser="$(resolve_browser)" || die "没有找到浏览器。装一个再试：pkg install chromium（或 apt install chromium）；\
        已经装在别处就用 --browser /path/to/headless_shell 或 ADSH_SHOT_BROWSER=…"
            echo "${'$'}browser"
        }

        # 目标解析：带协议的照原样；存在的路径转 file://（目录优先 index.html）；像域名的补 https
        resolve_target() {
            case "${'$'}1" in
                http://*|https://*|file://*|data:*|about:*|chrome:*) echo "${'$'}1"; return 0;;
            esac
            if [ -e "${'$'}1" ]; then
                case "${'$'}1" in
                    /*) abs="${'$'}1";;
                    *) abs="$(cd "$(dirname "${'$'}1")" 2>/dev/null && pwd)/$(basename "${'$'}1")";;
                esac
                if [ -d "${'$'}abs" ] && [ -f "${'$'}abs/index.html" ]; then abs="${'$'}abs/index.html"; fi
                echo "file://${'$'}abs"; return 0
            fi
            # 带斜杠又不存在 = 路径写错了（**绝不能**当成域名去补 https：那样会渲染出一个
            # ERR_NAME_NOT_RESOLVED 的错误页，用户还以为截图成功了）
            case "${'$'}1" in */*) return 1;; esac
            case "${'$'}1" in
                *.*) echo "https://${'$'}1"; return 0;;
            esac
            return 1
        }

        render() { # render <url> <out> <browser>
            url="${'$'}1"; out="${'$'}2"; browser="${'$'}3"
            flags="--no-sandbox --disable-gpu --hide-scrollbars"
            # 这是渲染工具，不该去连 dbus / 组件更新 / 同步（真机上会刷一屏无害但很吵的错误）
            flags="${'$'}flags --no-first-run --disable-sync --disable-component-update"
            case "$(basename "${'$'}browser")" in
                headless_shell*) ;;                       # 它本来就是 headless
                *) flags="${'$'}flags --headless=new";;      # 完整版 chromium 必须显式开
            esac
            case "${'$'}url" in
                file://*) flags="${'$'}flags --allow-file-access-from-files";;
            esac
            [ "${'$'}FULL_PAGE" = "1" ] && SIZE="$(echo "${'$'}SIZE" | cut -dx -f1)x${'$'}HEIGHT"
            # **绝对不要加 --user-data-dir**（第 73 轮真机实测）：带上它 chromium 会一直挂到超时
            # （试过新目录、也试过指向它自己的默认 profile 目录，都一样）；不带它时并发跑两个也
            # 都正常（两个都 rc=0、产出同一个大小）。所以就用默认 profile，别去"隔离"。
            # shellcheck disable=SC2086
            if [ -n "${'$'}PDF" ]; then
                "${'$'}browser" ${'$'}flags --window-size="${'$'}SIZE" \
                    --virtual-time-budget="${'$'}WAIT_MS" --print-to-pdf="${'$'}PDF" "${'$'}url"
            else
                "${'$'}browser" ${'$'}flags --window-size="${'$'}SIZE" \
                    --virtual-time-budget="${'$'}WAIT_MS" --screenshot="${'$'}out" "${'$'}url"
            fi
        }

        # 渲染 + **兜底换一个浏览器再试一次**：某些构建的 headless_shell 会 rc=0 却什么都不产出
        # （真机实测），这时换 chromium 就好了；反过来也成立。只重试一次。
        # 正常时浏览器的 stderr（dbus / inotify 之类）不往用户脸上刷，失败时才打末尾 15 行。
        render_any() { # render_any <url> <out>
            probed="${'$'}2"; [ -n "${'$'}PDF" ] && probed="${'$'}PDF"
            log="${'$'}tmp/adsh-shot-$$.log"
            render "${'$'}1" "${'$'}2" "${'$'}BROWSER" >"${'$'}log" 2>&1
            if [ -s "${'$'}probed" ]; then rm -f "${'$'}log"; return 0; fi
            echo "${'$'}BROWSER 没有产出文件" >&2
            alt="$(command -v chromium 2>/dev/null || true)"
            [ -z "${'$'}alt" ] && alt="$(command -v headless_shell 2>/dev/null || true)"
            if [ -n "${'$'}alt" ] && [ "${'$'}alt" != "${'$'}BROWSER" ]; then
                echo "换 ${'$'}alt 再试一次" >&2
                BROWSER="${'$'}alt"
                render "${'$'}1" "${'$'}2" "${'$'}BROWSER" >>"${'$'}log" 2>&1
                if [ -s "${'$'}probed" ]; then rm -f "${'$'}log"; return 0; fi
            fi
            echo "浏览器的输出（末尾 15 行）：" >&2
            tail -15 "${'$'}log" >&2
            rm -f "${'$'}log"
            return 1
        }

        do_check() {
            browser="$(need_browser)"
            BROWSER="${'$'}browser"
            echo "浏览器：${'$'}browser"
            version="$("${'$'}browser" --version 2>&1 | head -1)"
            echo "版本：${'$'}{version:-（--version 没有输出）}"
            page="${'$'}tmp/adsh-shot-check.html"
            png="${'$'}HOME/adsh-shot-check.png"
            cat > "${'$'}page" <<'HTML'
        <!doctype html>
        <html><head><meta charset="utf-8"><title>adsh-shot 自检</title>
        <style>
          body { font: 16px sans-serif; margin: 24px; }
          h1 { font-size: 28px; margin: 0 0 8px; }
          .cjk { font-size: 26px; }
          .box { width: 160px; height: 80px; background: #2b6; border-radius: 8px; margin: 12px 0; }
        </style></head>
        <body>
          <h1>adsh-shot check OK</h1>
          <p class="cjk">中文渲染检查：你好，世界 · 测试</p>
          <div class="box"></div>
          <p>如果这一页能截出来，渲染与字体都是好的。</p>
        </body></html>
        HTML
            SIZE="1000x600"; WAIT_MS="3000"; FULL_PAGE=0; PDF=""
            render_any "file://${'$'}page" "${'$'}png" || die "渲染失败（见上面的浏览器输出）"
            [ -s "${'$'}png" ] || die "渲染结束但没有产出 PNG：${'$'}png"
            echo "测试页：${'$'}page"
            echo "截图：${'$'}png（$(wc -c < "${'$'}png" | tr -d ' ') 字节）"
        }

        # ---- 参数 ----
        [ ${'$'}# -eq 0 ] && { usage; exit 2; }
        case "${'$'}1" in
            -h|--help) usage; exit 0;;
            --which) resolve_browser || die "没有找到浏览器。装一个：pkg install chromium"; exit 0;;
            --check) do_check; exit ${'$'}?;;
        esac

        TARGET="${'$'}1"; shift
        OUT=""
        while [ ${'$'}# -gt 0 ]; do
            case "${'$'}1" in
                --size) SIZE="${'$'}2"; shift 2;;
                --wait) WAIT_MS="${'$'}2"; shift 2;;
                --height) HEIGHT="${'$'}2"; shift 2;;
                --pdf) PDF="${'$'}2"; shift 2;;
                --browser) ADSH_SHOT_BROWSER="${'$'}2"; shift 2;;
                --full-page) FULL_PAGE=1; shift;;
                -*) die "不认识的选项：${'$'}1（-h 看用法）";;
                *) OUT="${'$'}1"; shift;;
            esac
        done

        URL="$(resolve_target "${'$'}TARGET")" || die "既不是存在的路径、也不是带协议的 URL：${'$'}TARGET\
        （本地文件要写成存在的路径，例如 ./index.html 或 /storage/emulated/0/…/index.html）"

        BROWSER="$(need_browser)"
        if [ -n "${'$'}PDF" ]; then
            [ -n "${'$'}OUT" ] && die "给了 --pdf 就不要再给输出 PNG"
            OUT="${'$'}PDF"
        else
            [ -n "${'$'}OUT" ] || OUT="adsh-shot.png"
        fi

        echo "浏览器：${'$'}BROWSER"
        echo "目标：${'$'}URL"
        render_any "${'$'}URL" "${'$'}OUT" || exit 1

        if [ -s "${'$'}OUT" ]; then
            abs="$(cd "$(dirname "${'$'}OUT")" 2>/dev/null && pwd)/$(basename "${'$'}OUT")"
            echo "输出：${'$'}abs（$(wc -c < "${'$'}OUT" | tr -d ' ') 字节）"
        else
            die "渲染结束但没有产出文件：${'$'}OUT"
        fi
    """.trimIndent() + "\n"

    fun installScript(prefix: File) = EnvScripts.install(prefix, COMMAND, SCRIPT)
}
