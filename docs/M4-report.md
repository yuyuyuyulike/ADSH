# M4 进展报告：四面板、抽屉、设置页、终端页

日期：2026-09-14　状态：完成并通过真机验证

## 一、交付

| 组件 | 位置 | 说明 |
|---|---|---|
| `AppRoot` | `ui/` | 覆盖式页面导航（300ms、新页从右滑入、旧页左移 1/4 并变暗）+ 抽屉（视差 + 虚化 + 遮罩） |
| `Dest` 导航栈 | `ui/AppRoot.kt` | Conversation / Feed / Deliverables / Preview / Info / Settings / Terminal；系统返回键逐层退 |
| 四个面板 | `ui/panels/Panels.kt` | 消息反馈（事件时间线 + 类型过滤）、交付物（present + write 登记）、文档预览（带行号、>512KiB 截断）、右栏信息 |
| 终端页 | `ui/panels/TerminalPanel.kt` | PTY + 行式输入；`$PREFIX/bin/bash -l`，环境与 cwd 与工具链一致 |
| 设置页 | `ui/SettingsScreen.kt` | 一级只有 通用 / 模型 / 终端；模型页可编辑 baseUrl / API Key / 模型 / 回环开关；通用页可绑定/解绑工作区；终端页显示 bootstrap 状态并「进入终端」 |
| 会话事件日志 | `ui/SessionEvents.kt` | 工具调用、错误、交互反馈的统一来源，喂给消息反馈面板 |

## 二、真机验证

| 项 | 证据 |
|---|---|
| 抽屉：视差 + 虚化 | `docs/screenshots/m4-drawer4.png`（主内容右移并被虚化，抽屉含 新建会话/设置/终端/交付物/消息反馈/技术自检 + 工作区状态） |
| 覆盖式导航 | `docs/screenshots/m4-info.png`（右栏以覆盖动画推入，顶栏出现「返回」） |
| 右栏内容 | 工具状态 / 模型（deepseek-chat、回环演示）/ 工作区（REAL_PATH + 路径）/ 会话消息数 |
| 终端执行路径 | `ADSH_M4` 自检（见下） |

终端自检输出（PtySession + nativeLibraryDir/libbash.so + Termux 环境，cwd = 工作区）：
```
spawned pid = 18609  cwd=/storage/emulated/0/adsh-ws
echo TERM-OK      -> TERM-OK
pwd               -> /storage/emulated/0/adsh-ws
ls <workspace>    -> [0m[01;34mnotes[0m        ← 带 ANSI 颜色，说明是真实终端
stty size         -> 40 100                    ← TIOCSWINSZ 生效
echo TERM-DONE    -> TERM-DONE
```

## 三、本轮修掉的四个真机问题

1. **JNI 符号在包重构后失配**：M1 把 Pty 从 `com.adsh.app.pty` 移到 `com.adsh.app.runtime.termux` 时没改 C 侧符号 → `UnsatisfiedLinkError`，**终端从 M1 起就是坏的**（PoC-2 的回归也会失败）。已改 4 个符号。
2. **抽屉被遮罩盖住**：绘制顺序是「内容 → 抽屉 → 遮罩」，遮罩的 hazeBlur 采样的是主内容，于是把抽屉也盖成了灰色。改用 `Modifier.zIndex(1f)` 让抽屉最后绘制。
3. **终端输入行没到底部**：`weight(1f)` 加在了 SelectionContainer 内部的 Text 上。已移到 SelectionContainer。
4. **ViewModel 初始化顺序崩溃**：`init` 里同步调用 `refreshWorkspaceInfo()`，而 `Main.immediate` 的 launch 会同步执行到首个挂起点，此时字段尚未初始化 → NPE。把 `init` 移到类末尾。

## 四、平台限制（记录，非缺陷）

- **左边缘滑动打开抽屉被系统返回手势抢占**：Android 15 的左/右边缘属于系统手势区。当前稳定入口是左上角按钮；若要边缘手势，需要 `View.setSystemGestureExclusionRects`（P1）。
- 终端目前是**行式输入**（无 VT 全屏渲染），`vim`/`htop` 这类全屏程序还不能用；`com.termux.termux-app:terminal-view` 已在依赖里，接入需要补 `com.termux.terminal.JNI` 的同名符号（P1）。
- 终端提示符显示 `libbash.so-5.3$`（bash 用 $0 推导 PS1），可通过注入 `PS1` 美化（P1，纯外观）。

## 五、下一步（M5）

1. **提示词动态注入**：persona（cwd）+ 文件策略行 + AGENTS.md 链（root→cwd，65536 字节预算）+ 重绑定时的基线替换。
2. **工作区目录浏览器**：自绘 Miller 分栏替代手输路径（当前设置页是输入框 + 绑定）。
3. 把 M0–M4 的自检整合进一次「验收运行」，逐条核对方案书 §13.3 的验收标准。
4. 文档收尾：README 构建说明、THIRD_PARTY_NOTICES、SBOM。
