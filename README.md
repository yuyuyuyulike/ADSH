# ADSH

（不知你有没有发现：linux 也就是使用 bash 的 deepseek 模型表现会比用 pwsh 时聪明很多，众多用户早已发现，
pwsh 正在肘击模型，而本软件内嵌的 termux 用的正是原生 bash，哈哈）

DeepSeek Harness（dsh）的安卓原生客户端：**dsh 的 PTC 语义 + DeepSeek App 的界面风格**，把 dsh 的原版工具
**静态化**搬进 App —— 没有 Node 运行时、没有插件加载、没有运行期下载。

- Kotlin + Jetpack Compose 单 Activity，界面按 dsh / DeepSeek App 的视觉规范逐项对齐
- 工具 13 个（原版语义）：`run_code`（PTC / QuickJS）、`bash`、`read`、`write`、`edit`、`glob`、`grep`、
  `todo_write`、`present`、`web_search`、`web_fetch`、`ask_user_question`、`exit_plan_mode`。
  只有 `run_code` 能被模型直接调用，其余在系统提示词的 `tools:sdk` 段声明、由程序内
  `await tools.<name>(args)` 组合调用；`ToolCallError`、输出 schema、截断上限都对着 dsh 源码核过
- 内置 Termux 用户态（bootstrap 静态入包，前缀就是官方前缀 `/data/data/com.termux/files/usr`）：
  `bash` 与随包分发的 CLI 工具直接可跑，无需 root / proot；bootstrap second stage 已按官方流程跑完，
  `apt` / `pkg` / `dpkg` / CA 证书 / `ld.so.cache` 都是「装好即可用」的状态
- 工作区：SAF 选目录 + 「所有文件访问」拿真实路径，bash 与文件工具直接操作手机文件夹
- 会话：流式正文与思考、工具调用轨迹（含 PTC 子调用）、用量与用时、计划模式、上下文压缩、
  附件（文件与图片，按设备能力识别 png / jpeg / gif / webp / bmp / heic / avif 并等比缩放进预算）
- 输入框的 `＋` 与键入 `/` 是**同一个菜单**（「添加」文件 / 计划，「指令」压缩 / 权限 / 下载日志）；
  只列当前能用的命令，打全命令名或点过一行后就收起；上下文占用圆环在顶栏「会话统计」右侧
- Markdown 按 dsh 的 `MarkdownText` / `CodeBlock` 渲染（标题阶梯、嵌套列表、任务列表、引用、表格、
  可点链接、带语言横幅的代码块、LaTeX 公式）；边缘防误触可调（设置 → 通用设置）
- 模型：官方 DeepSeek（`deepseek-flash` 原生多模态 / `deepseek-v4-pro`）+ 供应方目录
  （OpenAI、OpenRouter、Kimi、智谱、通义、小米、xAI、Groq、Mistral、Together、NVIDIA 等，贴密钥即用），
  也支持手写自定义提供方

## 截图

<p align="center">
  <img src="docs/screenshots/01-chat.jpg" width="23%" alt="对话与图片理解" />
  <img src="docs/screenshots/02-drawer.jpg" width="23%" alt="会话抽屉" />
  <img src="docs/screenshots/03-workspace-files.jpg" width="23%" alt="工作区文件" />
  <img src="docs/screenshots/04-settings.jpg" width="23%" alt="设置" />
</p>

## 安装

下载 [`ADSH-0.1.4-release.apk`](https://github.com/yuyuyuyulike/ADSH/releases/download/v0.1.4-20260926/ADSH-0.1.4-release.apk)：
arm64-v8a，Android 8.0（API 26）以上；release 已开 R8（minify + shrink）。

> ### ⚠️ 不能与官方 Termux 共存
> 本应用的包名**就是 `com.termux`** —— Termux 的二进制、dpkg 数据库、`.deb` 里的 shebang 都把
> `/data/data/com.termux/files/usr` 编译死在程序里，而官方明确「前缀安装后不可重定位」。
> 所以手机上有官方 Termux（或任何 `com.termux` 应用）时必须**先卸载它**才能装本应用，反之亦然；
> 两者共用同一个数据目录但格式不兼容，**卸载等于清空内置用户态**（`apt` 装过的东西都要重装）。

## 手机上怎么放东西

- **工作区**（你绑定的文件夹）在 Android 外部存储上：只能读写普通文件 —— 建不了软链、置不了执行位、
  不能执行里面的文件。适合放素材与最终产物。
- **重活**（`npm install` / `pip install` / 构建 / git）放到 `$ADSH_SCRATCH`（= `$HOME/scratch`，f2fs，
  每次启动建好并导出），做完把产物拷回工作区。`$ADSH_WORKSPACE` 始终指向当前工作区，
  `/tmp` 自动映射到 `$TMPDIR`，工作区里的 `git` 已预置 `safe.directory`。
- **遇到怪事先跑自检**：终端里敲 `adsh-env-check`（exec 模式 / SELinux 域 / 预载库 / 工作区可读性，
  末尾给判读）。渲染网页与截图用 `adsh-shot <文件|URL> [out.png]`（`--which` 打印浏览器路径，
  `--pdf` 出整页 PDF；**不要给它加 `--user-data-dir`**，真机上会挂住）。

**`targetSdk` 有意钉在 28**（与官方 Termux 一样）：安卓 10 起 `targetSdk >= 29` 的应用不能执行自己数据
目录里的文件，官方靠 termux-exec 改写 exec，而那会让 `/proc/<pid>/exe` 指向 `linker64` ——
靠自身路径找资源的程序（Chromium / Electron）、静态二进制、直接 `execve()` 的程序一起坏掉。
钉在 28 就什么都不用改写；代价是上不了 Google Play，本项目只走 GitHub Release 侧载。
细节见 [docs/NOTES.md](docs/NOTES.md)。

## 构建

JDK 17 + Android SDK 37 + NDK 28；`app/src/main/assets/bootstrap/*.zip` 与
`app/src/main/execLibs/`（`.gitignore` 里，用 `scripts/fetch-bootstrap.sh` /
`scripts/prepare-execlibs.py` 生成）必须先备好，且**必须原样复制**（它们运行期从 nativeLibraryDir 执行，
改文本只会把编译期前缀改成不存在的路径）。

```bash
cmd.exe /c "scripts\build-debug.bat :app:testDebugUnitTest :app:assembleRelease"
```

实现细节、平台约束与有意偏离 dsh 的地方见 [docs/NOTES.md](docs/NOTES.md)，
构建 / 发版 / 快照流程见 [HANDOFF.md](HANDOFF.md)。

## 许可

MIT
