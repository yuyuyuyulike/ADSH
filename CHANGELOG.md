# CHANGELOG

只保留**最近 8 轮**变更；更早历史不再保留（源码历史见 git 快照）。经验与坑在 [docs/LESSONS.md](docs/LESSONS.md)，当前状态看 [HANDOFF.md](HANDOFF.md)、稳定事实看 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 0.2.6（2026-10-22 发布，versionCode 25）

run-as 深度测试 **只测 bash**（37 用例；原始 logcat 摘要在 `.tmp/device-reports/`），非 bash 的 harness 报告先按 dsh 0.2.1-rc.1 逐条复核再决定修不修。

- **release**：`:app:assembleRelease`（R8 minify + shrink）→ `dist/ADSH-0.2.6-release.apk`（debug 签名、同包名可覆盖安装）→ 装机复验（versionName 0.2.6 / versionCode 25、启动无 crash）；GitHub 已推送并发布 `v0.2.6-20261022`。
- **死代码清理**：删除无任何 UI/调用方的「重命名工作区」整链（`ChatViewModel.renameWorkspace` → `ConversationRepository.renameWorkspace` → `WorkspaceDao.rename`；R8 `-printusage` 证实三者 release 均不可达）；`SettingsStore.fetchedModels` 与 `ChatViewModel.refreshWorkspaceInfo` 只在本文件使用，收为 private。复跑 `deadcode.py` / `find-unused-imports.py` / `find-unused-resources.py` / `find-unused-c-symbols.py` / `find-unused-catalog-entries.py` 全 0；R8 usage 里其余整类删除均为内联常量或测试口（如 `sendWithTools`、`holdingNow`），非死代码。

- **bash 测试结论**（真机，run-as + debug 探针驱动真 `BashTool`）：前台/后台/promote、stdout/stderr 分开、尾部截断 + `omittedBytes` + spill（64000 整、64001、单行 200 KB、400 KB 混合行、CJK 180 KB 字节边界）、退出码 0/1/126/127/143/137、非零退出不是工具错误、stdin=/dev/null、workdir 解析（缺失/文件/相对/scratch 覆盖）、`timeoutMs` 夹取与 promote、`job_output` 消费语义与二次读、`job_kill`/杀树（含只读下）、前台取消、10 任务上限回退前台、围栏三模式（read-only/workspace-write/danger-full-access 的标记、可写根、`/tmp` 映射、chmod/hardlink 的 `[fs:]`）、glibc（随包二进制直接跑；外部构建 PT_INTERP=/lib/... 直跑 127、`grun` 正常、`grun` 在 workspace-write 下正常）。**全部 PASS**；记录项（内部符号可见、非法 UTF-8 解码为 U+FFFD）判为 dsh 同源行为。
- **修掉的 harness 问题**：
  - **只读下每条命令 stderr 多出假拒绝（根因修复）**：`spawn` 包装用子 shell 把 `$$` 写进内部 pid 标记文件（杀树根 pid 的唯一来源），只读白名单为空 → 这次内部写被判越权，每条命令都带 `[sandbox: file access denied]` + hint，模型会误以为自己的命令被拒。`fence.c` 现在按 `ADSH_PID_MARKER` 单文件放行（`TermuxRuntime.spawn` 只在围栏生效时导出），同目录其他文件仍拒；`fence-selftest.sh` 加 P 组 4 例。
  - **重复当前模式的 `sandbox_permissions` 被误拒**：dsh 的 `approveEscalation` 对 `requestedMode === effectiveMode` 直接放行、不弹审批；ADSH 缺这条。补齐同模式提前返回；bash 族按 dsh 的 `validateBashArgs` 额外放开「同模式不校验 justification / 无模式时空白 reason 视同缺省」，write/edit 保持 dsh fs 的严格校验。用例 `EscalationSemanticsTest` 6 例。
  - **`run_code` 返回值有损序列化**：dsh 要求 completion 是无损 JSON，否则整次 `invalid-output`；ADSH 原来直接 `JSON.stringify`（Map/Set→`{}`、NaN→null、undefined 字段消失）。现在先用 `__adsh_isLossless` 校验（undefined 完成值仍合法），失败信封逐字 `program completion must be lossless JSON`、kind `invalid-output`，已成功子调用回执与 Captured output 保留。真机 `run_code` 探针 4 例过。
  - **glob 目录遍历跟随符号链接**：`sym/up` 指回父目录会把同一棵树重复枚举到 2 万次上限并产出不属于工作区的路径；现在跳过符号链接目录、真实目录按 canonical 去重。`FsSearchCapsTest` 加回归。
  - **参数类型按 dsh 严格化**：`Args.str` 只接受 JSON 字符串（write `content=42` 不再静默写 `"42"`）；read 的 `offset`/`limit` 用严格整数（`"2"`、`1.5`、`null` 报参数错误）。`ArgsTest`/`ReadShapeTest` 同步。
  - **rg 固定加 `--no-config`**（dsh search argv 同款），防 `RIPGREP_CONFIG_PATH` 注入改变搜索语义。
- **非 bash 报告复核**：D1/D2/D3/D7/D10/D13(rg stderr)/D14/D15 判为 **dsh 既有行为或报告误读，不改**（read 单行 2000 字符内联标记、grep 250 上限只在 render、read 无 truncated 字段、BOM 由 TextDecoder 吞掉、job_output 消费式读取等都与 dsh 同源）；D4/D5/D6/D9/D11 为真实偏差，本轮修 D4/D6/D9/D11，D5（PTC 沙箱内部全局符号收敛）风险大且被宿主帧校验兜底，**留作下一轮**。
- **提示词第一性原理修剪**：静态段散文 −约 0.76 KB、SDK 固定说明 −约 0.58 KB、环境细则 −约 0.61 KB（真机实测 env 段 2838 字符、系统提示词 27270 字符，其中 `ToolSdk.section()` 21540 字符）；`ToolSdk.section()` 的工具声明按「契约单源」不动。
  - **删除环境细则里的「工作区变化通知」**（用户点名）并清掉整条 `previousWorkspacePath` / `lastWorkspacePath` 链（`PromptAssembler` / `TurnContext` / `RequestMessages` / `AgentLoop` / `AppContainer` / `ChatViewModel` / `TurnSettings` / `SettingsStore`）——事件叙述不该进「当前事实」快照，且数据源是全局设置会造假叙述；旧快照已有 supersedes 抬头。
  - 静态段：删掉与当前 danger-full-access 矛盾的「only writes are fenced」绝对句（改成策略中立 + 指向 runtime context）、glob/grep 上限复述、bash 长输出复述、无工作区重复句；升级重试流程收敛为一句指针（策略事实留在 file-policy 段）。
  - 环境细则：删 `$PREFIX/$HOME`、full-access 可写根 bullet（会说成「整机可写」）、present 复述、超时建议句；FUSE/`[fs:]`/硬链接合并；`$ADSH_FENCE_ROOTS` 改为如实描述（workspace-write 导出、read-only 不导出）；glibc 句改精确（随包二进制直接跑，外部构建才要 `grun` 且必须带路径）；段标题改英文 `## Environment`（界面显示名仍是「环境细则」）。
  - SDK 段：删「What remains is the ECMAScript language itself… plus tools.*」假穷举（内部全局存在）改为「其余是实现细节，不是 API」；one-program / 预算 / OOM 段去重；升级说明指针改指 runtime context；`ProgramDiagnostics` 同步去掉 "only"。
- **验证**：全模块 `testDebugUnitTest` 全绿（新增 `EscalationSemanticsTest`、Args 严格整数、glob 符号链接回归）；`:app:assembleDebug` 过；真机 debug 探针跑完 37+22+11+9+4 条并全过（logcat 见 `.tmp/device-reports/`）。探针文件验证后删除，未进版本库。
## 2026-10-22（未发版，debug 已装机）

滚动条 / `@` 文件补全 / 随包 glibc 三件。参考调研：`.tmp/research-dsh-mention.md`（dsh 的 @ 补全面板逐条源码定位）、`.tmp/research-glibc-runner.md`（termux-pacman glibc 运行时与预置方案）。

- **右侧滚动条改成只读指示**：会话流的 `TranscriptScrollbar` 不再吃手势 —— 删掉 thumb 命中区、拖动换算、`dragging` 态、`ThumbGeometry` 与边缘防误触的 thumb 豁免（`EdgeTouchGuard` 只剩纯边缘带，`TranscriptScrollbarHitRegion` 删除）；thumb 仍按滚动容器真实 `maxValue` 取长度，**停止滚动满 1 秒**（`SCROLLBAR_HIDE_DELAY_MS` 3000→1000）后淡出、再滚重新出现。回归 `TranscriptScrollbarTest`（删拖动用例、补隐藏时延）。
- **输入框 `@` 文件补全**：照 dsh 的 `ui-input-trigger` + `ui-reference` + `file-reference-local` 复刻。规则层 `Mention.kt`：`@` 只能在当前行行首 / 空白之后（`user@host` 不弹）、`@"...` 引号内可含空格、token 必须结束在光标处；空查询或含 `/` 列该目录一级（空查询列工作区根），其余走大小写不敏感模糊评分（精确 1000 / 前缀 900 / 基名含 700 / 路径含 500 / 子序列 300+，目录 +25；同分目录优先 → 短路径 → 字典序；上限 20）。数据源 `WorkspaceFileIndex`：BFS readdir 有界索引（50k 项、按名排序、排除 `.git/node_modules/dist/build/…`，**不读 .gitignore** —— 与 dsh 一致），第一次打 `@` 时在 IO 上建、一轮结束后失效重建（dsh 在 tool/result 后失效）。面板 `MentionPanel.kt` 复用 slash 菜单的几何（贴卡片上沿 4dp、同宽、向上长、按上方空间夹高）：文件 / 文件夹图标、目录尾 `/`、右对齐父目录、目录行尾箭头 = drill（把 token 换成 `@dir/` 并保持菜单打开），点行 = 引用；含空格路径插 `@"..."`、文件选中补一个尾随空格（后一字符已是空格则不加）。硬件键盘 ↑↓/Enter/Tab/Esc 走 `onPreviewKeyEvent`（面板开时 Enter 不再落到发送 / 换行）；`ChatScreen` 在一轮结束后 +1 `fileIndexRevision`。回归 `MentionTest`（语法 / 引号 / 插入 / 目录与模糊查询 / 排序 / 有界索引）。
  **有意偏离 dsh**（均为移动端/最小改动）：目录列表走索引而非实时 readdir（新建文件要等下一次失效）、无面包屑、无会话引用（只补文件）、无 pending skeleton、无 IME 组合键特判；插入的是纯文本 `@路径`（模型可见面与 dsh 相同）。
- **随包 glibc 运行时 + `grun`**：构建期 `scripts/fetch-glibc.sh` 从 termux-glibc 源下载 `glibc 2.44` / `glibc-runner 2.0-3`（SHA 固定），用 Python 标准库解 deb（`ar` + `tarfile r:xz`），精选 58 个运行期文件（加载器 / libc 族 / C.utf8 + en_US.utf8 / getent·ldd·locale·iconv·getconf）与一个 12 行 `bin/grun`（`unset LD_PRELOAD` + `unset LD_LIBRARY_PATH` + `ld-linux-aarch64.so.1 --library-path $PREFIX/glibc/lib`），并进 `assets/bootstrap/usr.zip`（32.8 MB → 35.6 MB）；`SYMLINKS.txt` 追加 `glibc/bin/ld.so → ../lib/ld-linux-aarch64.so.1` 与 `glibc/etc/{resolv.conf,hosts} → $PREFIX/etc/...`。`BootstrapInstaller`：`INSTALLER_VERSION` 6→7、`EXEC_PREFIXES` 加 `glibc/bin/` 与加载器、空间预检 200→220 MB（安装流程 / 合并判定不变，老前缀照旧只补缺）。`androidEnvText` 补一句「glibc 二进制用 `grun ./tool`，且它不受写围栏 / `/tmp` 映射约束」+ `PromptAssemblerTest` 钉住；文档见 `docs/ARCHITECTURE.md` 硬约束 33 / `docs/TECH-STACK.md` / README。
  **语义代价**（如实记录，不是 bug）：glibc 进程不加载 bionic shim → 写围栏与 `/tmp` 映射对它不生效；`/proc/self/exe` 经 `grun` 时指向加载器；DNS 依赖 `$PREFIX/etc/resolv.conf`（已软链）；X11 / ALSA / dbus 不可用；不预置编译工具链与 `-s/-c/-f/-d/-t` 分支（依赖闭包 100 MB+）。
- **胶囊行出现动画收口（四个节点的根因修复）**：footer 是底部锚定、其高度就是会话流的底部内边距 —— 原来 `ChatStatsDock` 用 150ms `expandVertically/shrinkVertically` 把整排胶囊从 0 高度展开，动画每一帧都改 footer 高度，于是启动恢复 / 切会话 / 首轮收尾都变成「会话流被顶一下」。现在：① 可见性判据提成 `statsDockVisible(stats)`（UI 与会话载入共用一处）；② `ChatUiState.dockEntranceArmed` 只在**会话载入时统计为空**（新会话、还没跑出过胶囊）时为 true，启动恢复 / 切到已有统计的旧会话都为 false；③ `ChatStatsDock` 的初始 `MutableTransitionState` 取首次组合时的可见性、入场动画只在 armed 时播（否则 `EnterTransition.None`），退出一律瞬间（`ExitTransition.None`）—— 所以重启 / 切换「像没有动画」，只有**新会话第一轮跑出统计**那一次有 150ms 淡入 + 展开；④ 删掉 `Row.animateContentSize`：出现之后数字 / 胶囊变化只改宽度，行高与 footer 高度不变、不再触发贴底。`clearedMain()` 一并清掉武装位。回归 `StatsDockTest`（可见性 3 例）/ `StatsDockUiTest`（数字变化不移动行）/ `LiveRunStateTest`（clearedMain 清武装位）。
- **启动 / 切会话「上滚迹象」根因修复**：① footer 高度与会话流底部内边距分成两帧 —— 原来是「量 footer → 写回 state → 下一帧会话流重测并贴底」，启动实测视口在第二帧矮 204px、贴底把整页上滚（debug trace pin keep gap=204）。现在 ChatScreen 用 SubcomposeLayout 两趟同帧测量：先量 footer，再把实测高度作为会话流底部内边距，footer 高度不再回灌 state。② 首帧 WindowInsets 为 0：Compose 的 insets 在第一次 attach 后才分发，会话页首帧若已布局，下一帧补状态栏 / 导航栏内边距会让视口再缩、贴底再滚一次。现在 AppRoot 等 insets 就绪（状态栏 / 导航栏 / IME 任一非 0）再画会话页，全屏设备 500ms 兜底放行。③ 切会话的统计跟正文同一帧到齐（缓存命中路径不再先摆正文、稍后补统计）—— 胶囊出现 / 消失落在抽屉还盖着的那一帧，收起动画里不再单独改 footer 高度。④ 浅色模式滚动条由 dsh 的 neutral-200 加深到 neutral-400（DshPalette l1/l2 同值），深色档不变。回归：AutoScrollTest 删掉随旧实现下线的 chromeMeasured 用例；启动 trace 验证 pin keep 消失。
- **验证**：157 类 / 1232 例 0 失败；统一门禁（test / lint 0 error / assembleDebug / spotless）与包循环 / 分层 / 模块图（已 `--write` 重生成）/ 注释 / 文档预算 / env / QJS preamble / deadcode / 未使用 import 全绿。debug 装机后 `INSTALLER_VERSION=7` 走全量 Merge（`entries=3536`、`secondStage=ok`，用户前缀保留），重装 APK 走 Relink 且 glibc 文件未动；真机应用 shell（run-as）验证：`grun locale -a` → C / C.utf8 / POSIX / en_US.utf8，`grun getent hosts localhost` → 127.0.0.1，`LD_LIBRARY_PATH=$PREFIX/lib` 下仍正常；把一份 glibc 二进制的 PT_INTERP 改成 `/lib/ld-linux-aarch64.so.1` 后直接 exec 报 `required file not found`、`grun` 起来正常。

## 0.2.5（2026-10-21 发布，versionCode 24）

对外版本号 0.2.5（上一个公开版本 0.2.4）。0.3.0–0.3.5 是**未发版的内部批次名**，内容全部随本版发布；下面 `0.2.5-pre` 一节是发布前的开发记录。

- **release 包**：`:app:assembleRelease`（R8 minify + shrink）→ `dist/ADSH-0.2.5-release.apk`（46,440,727 字节，debug 签名、同包名可覆盖安装），已装机复验：`versionName=0.2.5` / `versionCode=24`，启动无 crash；GitHub 尚未推送。
- **本版内容**（详见下面各节）：0.3.0 tools / agent 现代化；0.3.1–0.3.2 深度测试修复；0.3.3 `:platform:termux` 优化（PTY 生命周期 / bootstrap 只补缺合并 / PTC 泵等待 / 围栏时间戳 / 环境与结构拆分）；0.3.4–0.3.5 bash 截断修复（单行大输出不空、`omittedBytes`、`Jobs.read` 的结算裁剪顺序）。
- **死代码清理**：删除无人引用的 `adsh.kotlin.jvm` 约定插件（8 个模块都是 Android library）；`package-cycles-baseline.json` 归零（包环已 0，旧基线是死配置）；`deadcode-baseline.json` 删掉已不存在的 `resetForTest` 条目；`THIRD_PARTY_NOTICES` 删掉早已移除的 haze 依赖行，proguard / `AppRoot` / `app/build.gradle.kts` 里关于 haze 与已删规则的历史注释一并清掉；`SettingsStore.contextWindow` 去掉从未被赋值的 setter、`SettingsStore.Companion.acceptsImages` 与只被测试调用的 `ToolSdk.description` / `ToolRegistry.byName` 删除（测试改读 `ToolSdk.specs`）。另用 R8 `-printusage` 核对了 release 的不可达成员，没有发现 app 类整类死代码。
- **验证**：154 类 / 1214 例 0 失败；统一门禁（test / lint 0 error / assembleDebug / spotless）与包循环 / 分层 / 模块图 / 注释 / 文档预算 / env / QJS preamble / deadcode / 未使用 import / 未使用资源 / 未使用 C 符号 / 未使用 catalog 全绿。

## 0.3.5（未发版，2026-10-21）

真机 agent 对 0.3.4 截断元数据的复测（三个新问题）+ 设备侧深度回归；本轮按 dsh 收集器的
`truncated = 真的丢过字节` 语义修根因，没有再加新的结果字段。

- **根因**：`Jobs.read` 在**结算裁剪之后**才计算 `droppedChannels` —— 结算时环会裁到 16 KiB 保留窗口，于是「这次已经完整读到」的输出也被判成丢过字节：单行 16385 / 64000 字节全量返回却 `truncated:true`；2000 行（20 KB）/6000 行（60 KB）多行输出再被行对齐砍掉首行、`omittedBytes:10`，而 spill 根本没开（没有 `spillPath`，那几个字节拿不回来）。现在 `dropped` 在 `readFrom` 之后、`trim(SETTLED_RETAIN_BYTES)` 之前算：`truncated` 只表示真的丢了字节（与 dsh 的 OutputCollector 同义）。
- **结果契约**：小于 / 等于 `bashMaxOutputBytes` 的输出原样返回（`truncated:false`，无 `omittedBytes`）；只有真的丢过字节时才 `truncated:true`，此时 `omittedBytes` 与 `spillPath` 一起出现，spill 文件大小 = 原始流字节数（尾部保留方向写进 `bash` 输出 schema 的注释）。
- **设备侧深度回归**（debug 构建里临时探针跑真实 `BashTool` + 真实 bash/Jobs/Spill，跑完删除）：
  - 20 KB / 60 KB / 恰好 64 KB / 单行 16385 字节 → 全量返回、`truncated:false`、无 omitted/spill；
  - 单行 200 KB + 换行 → 64000 字节尾巴、`truncated:true`、`omittedBytes:136001`、spill 200001 字节；
  - 400 KB 中段超长行 → `"L3\nL4\n"`、`omittedBytes:400007`、spill 400013 字节；
  - stderr 200 KB → 尾部 64000、`omittedBytes:136000`、spill 200000 字节；
  - 中文 60000 字 → UTF-8 安全尾（64000 字节 / 21334 字符）、`omittedBytes:116001`、spill 180001 字节。
- 用例 154 类 / 1215 例 0 失败（新增 5 例：20 KB/60 KB/16385/64000 全量不标 truncated，以及 64001 边界「行对齐 + omittedBytes + spill」）；门禁（test / lint 0 error / assembleDebug / spotless）与包循环 / 分层 / 模块图 / 注释 / 文档预算 / env / QJS preamble / deadcode / 未使用 import 全绿。

## 0.3.4（未发版，2026-10-21）

真机 agent 对 0.3.2 截断策略的两项残留复测（"第 3 点：主问题已修，还剩两处小问题"）。

- **单行大输出不再变成空串**：`wholeLineTail` 的行对齐只在「丢掉半截首行后还剩至少一整行」时执行；尾巴只有一条被切断的长行（`print('A'*500000)`，尾部是一个换行）时保留原尾巴，不再把整段可见内容丢成 `text:""`（调用方会误以为命令没有输出）。`dropPartialFirstLine` 对「窗口只有一个换行」仍按空处理。
- **截断策略进结构化字段**：`stdout` / `stderr` 在 `truncated` 时新增可选 `omittedBytes`（被丢掉的头部字节数，等于保留文本在完整流里的起始偏移；行对齐丢掉的半行也算在内）。`ExecResult` 补 `outputBytes` / `stderrBytes`（完整流字节数），后台任务路径由 `Jobs.Read` 的每路累计字节数提供；前台直跑与 job 两条路都填。正文不加说明（dsh 的 render / canonical 分工不变），`BashTool` 的输出 schema 两处都声明该字段。
- 用例 154 类 / 1210 例 0 失败；门禁（test / lint 0 error / assembleDebug / spotless）与包循环 / 分层 / 模块图 / 注释 / 文档预算 / env / QJS preamble / deadcode / 未使用 import 全绿。

## 0.3.3（未发版，2026-10-21）

`:platform:termux` 模块优化（对照 dsh 0.2.1-rc.1 的 subprocess / shell-env / terminal / ptc-runtime 与 Termux 官方实现；调研报告在 `.tmp/research/dsh-platform-mechanisms.md`、`community-platform-practices.md`）。

- **PTY 会话有了真正的生命周期**：之前 `PtySession.waitFor()` 没有任何调用者，用户敲 `exit` / 关掉终端页后子 bash 一直以 `Z` 状态挂着；关页面也只关 fd，后台任务（apt / 构建 / ssh）会活下来。现在每个会话一根 reaper 线程独占 `waitpid`，`close()` 先关 master（内核向会话发 SIGHUP）再确认退出，仍有活口就对会话进程组 `-pid` 依次 SIGTERM → SIGKILL；读循环 EOF 与页面 onDispose 两条路径都走它。
- **bootstrap 全量安装不再删用户装的东西**：`installPlan` 的全量分支原先 `removeTree(prefix)` 后重解压 —— 用户 `apt` 装的包与 dpkg 数据库会一起没。现在前缀是真实目录时改为**只补缺的合并**（已存在的文件一律不动，用户 apt 升级过的库/程序优先；缺的链接与 exec 槽位随后重链接），只有不存在 / 是符号链接（老方案的自指别名）才清掉重建。`MANIFEST.properties` 改为原子写（临时件 + fsync + rename），并在 `filesDir` 留一份副本：前缀里的那份读不出、而前缀仍像装好的树时用副本恢复（不再掉进全量安装）。全量安装前还做剩余空间预检（约 200 MB，bootstrap 解压 90 MB、安装峰值约 180 MB），不够时在解压前报可读错误。
- **PTC 运行时**：泵循环从「每 1 ms 求值一次空表达式」改成睡在回执队列（`ReplyQueue`）上 —— 等几十秒的工具时不再每秒一千次 QuickJS 求值 + 线程唤醒；有回执立刻醒，取消/超时仍按 20 ms 粒度检查。补上 dsh 的在途调用上限（128 个 / 参数总字节 128 MiB，超限整次运行 `protocol` 失败，原文同 dsh），bind 阶段失败不再白等满 5 s。
- **输出收集器**：`finalize()` 与 `append()` 共用锁、收尾后拒绝新字节；进程结束后最多等 1 s，超时就关掉读端让读线程退出（孙进程占着管道时不再留一个永久阻塞的线程，也不会读到撕裂的尾部）。spill 只在开文件那一次拷贝内存尾巴（大输出时每 8 KiB 拷 64 KiB 的浪费没了）。
- **shell 环境**：交互（终端页）与一次性命令分开两套终端口径 —— 一次性命令用 dsh 的 `TERM=dumb` / `NO_COLOR=1` / `PAGER=cat` / `GIT_PAGER=cat`，终端页保留 `xterm-256color` / `COLORTERM`；终端会话的 `PWD` 改成真实 cwd（家目录），工作区仍走 `ADSH_WORKSPACE`。bootstrap second stage 的环境改为复用 `shellEnvironment` 同一份装配（原先手写 18 条，漏了 `ANDROID__BUILD_VERSION_SDK` 等 termux-tools 依赖的变量）。
- **写围栏**：补 `utime / utimes / futimes / lutimes / futimesat` 五个写入口判决（在此之前改文件时间戳不受围栏管辖）；`scripts/fence-selftest.sh` 增 N 组 6 项（host 侧 64 项），并把编译器做成 `CC` 变量、H1（glibc 目标）在非桌面系统上跳过 —— 现在可以在装了 clang 的 Termux 设备上用 `CC=clang` 直接跑整套。
- **PTY bridge fork 安全**：fork 后不再 `opendir/readdir`（列表在父进程里收集）、不再 `clearenv/putenv`（直接挂 `environ`，exec 用现成 envp）、绝对路径走 `execv` 避开 `execvp` 的 PATH 搜索；exec 失败时往 PTY 写一行原因（以前是静默 `_exit(127)`，终端只剩空白）。
- **结构**：`TermuxRuntime.kt` 673→约 430 行，拆出 `OutputCollector.kt` / `ProcessTree.kt` / `HomePrep.kt`；`readProcessTable` 接受 `procRoot`，用假 /proc 目录单测（comm 空格括号、非数字项、畸形 stat、缺目录）。新增用例：`ProcessTableTest` / `AtomicFilesTest` / `ReplyQueueTest` / `PendingCallsTest` 等。
- 用例 154 类 / 1205 例 0 失败；统一门禁（test / lint 0 error / assembleDebug / spotless）与包循环 / 分层 / 模块图 / 注释 / 文档预算 / env / QJS preamble / deadcode / 未使用 import 全绿。

## 0.3.2（未发版，2026-10-21）

第二轮深测修复（0.3.1 那两处修复自己带出 / 暴露的问题）。

- **围栏写判决修掉「软链 + /..」逃逸**：`canonical()` 原先在 realpath 之前做词法归一化，`u -> $PREFIX` 时 `u/../x` 会被折回 `u` 所在目录而按工作区内放行；现在去掉预归一化，`..` 按内核语义在解析软链之后回退。真机复现：修前 `echo > $SCRATCH/lt2/u/../esc_files.txt` 落到 `$HOME` 上级（rc=0），修后同路径 rc=1 并带拒绝标记；工作区内直写 / 改名 / 删除仍 rc=0。`scripts/fence-selftest.sh` 增 M 组 5 项（host 侧 58 项）。
- **bash 截断尾巴从整行开始**：字节对齐的窗口尾巴可能从半行开始（`seq` 首行 190858 只剩 90858）；现在丢过字节时丢掉首个半截行（`wholeLineTail`），结构值与正文一致；没有换行（单行大输出）时原样保留。`truncated` / `spillPath` 语义不变（全文仍在 spill 文件里）。
- 用例 150 类 / 1180 例 0 失败（新增 2 例：半行裁剪 + 完整首行）；统一门禁（test / lint 0 error / assembleDebug / spotless）与包循环 / 分层 / 模块图 / 注释 / 文档预算 / env / QJS preamble / deadcode / 未使用 import 全绿。
## 0.3.1（未发版，2026-10-21）

真机深度 harness 测试后的修复（逐条对照 dsh 0.2.1-rc.2 审查；判定为 dsh 既有行为的项不改）。

- **写围栏不再把「删除软链」判成越权**：删 / 重命名目录项写的是父目录里的 entry，不是链接目标；`fence.c` 的 unlink / unlinkat / rmdir / remove / rename / renameat / renameat2 改按父目录判决。指向只读根的软链（以及含这种软链的目录）现在能正常删掉，`rm -rf` 不再被卡住；路径中间的软链仍解析，穿链删 / 移目标仍拒。真机验证：删外指软链 rc=0、`mv` 软链 rc=0、`rm -rf` 含软链目录 rc=0；删工作区外文件 / 穿链删 / 移出 / 移入仍 rc=1 并带拒绝标记。`scripts/fence-selftest.sh` 增 K 组 7 项（host 侧 53 项）。
- **bash 的截断提示不再污染结构化值**：`[output truncated; full output: …]` 只进正文渲染（dsh 的 render.ts），`value.stdout/stderr.text` 回到未追加提示的纯尾部文本（dsh 的 canonicalBashResult）—— 程序里 `JSON.parse` 合法输出不会再被提示行打断；`truncated` / `spillPath` 字段不变。
- **read 的 50 KiB 上限说明改成事实**：结构化值本来就没有截断字段（dsh 的 output schema 也没有）；SDK 里 `limit` 的说明改为「窗口也会在 50 KiB 处停止，compare `lines.length` with `totalLines` 才能发现」。
- **审查结论（不改，dsh 同构）**：grep 默认忽略 hidden / .gitignore、glob 显式 `--no-ignore --hidden`（dsh 两处本来就不同）；glob 带 `/` 的 pattern 锚定 cwd 而非 `path`；`ask_user_question` 允许空字符串 id / question（dsh 的 schema 只要求 string）；二进制文件在 grep 里按 rg 默认跳过；glob 工作区内相对 / 外绝对。
- 用例 150 类 / 1178 例 0 失败（新增 1 例 bash 值回归）；统一门禁（test / lint 0 error / assembleDebug / spotless）与包循环 / 分层 / 模块图 / 注释 / 文档预算 / env / QJS preamble / deadcode / 未使用 import 全绿。
