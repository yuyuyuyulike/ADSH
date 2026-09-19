# 交接说明（给下一个会话）

## 这是什么

ADSH：把 deepseek harness（dsh）的交互语义（PTC：模型写一段程序组合调用工具）用 Kotlin + Compose
原生重写为安卓 App —— 无 Node 运行时、无插件、无运行期下载。
仓库：https://github.com/yuyuyuyulike/ADSH （public / MIT）。工作区：D:\WSN2005\Android1\App\ADSH

## 当前状态（2026-09-19 第六次快照）

- 源码 = **单个快照提交**（每次快照都用 `git checkout --orphan` 起一条全新历史，旧的快照提交
  连同对象一起清掉：`git reflog expire --expire=now --all && git gc --prune=now`，再 `push --force`）。
  本次（第六次）快照把第五次快照 `33801a3` 与其上的死代码清理增量 `0704048` 折叠进来。
- **只保留最近两次快照**（用户第五十五轮点名的策略）：旧快照按「快照时打包一份 bundle 到仓库外」
  的方式留档，只留最近那一份，更早的连 bundle 一起删。现在仓库外只有
  `../ADSH-backup-20260919-5th.bundle`（第五次快照，`git bundle verify` 通过，含 refs/heads/main）；
  第四次及更早的那份 `../ADSH-backup-20260919.bundle` 已按这条策略删除。
  要回滚：`git clone ADSH-backup-20260919-5th.bundle <dir>`。
- 手机上装的是 **release 包**（`dist/ADSH-0.1.0-release.apk`，与 debug 同包名同签名，可互相覆盖安装；
  第 49 轮前的实测用的是 debug 包），**包名就是 com.termux**（方案 A：只有包名保持 com.termux，
  Termux 编译期写死的前缀 /data/data/com.termux/files/usr 才真实存在）。所有变体同包名、
  不再有 .debug / .side 后缀，也不能与官方 Termux 共存（同包名不同签名）。
  别名 / 等长改写 / dpkg 根树 / chrootless 那一整套在方案 A 下都是 no-op，**第 46 轮已经整批删除**
  （连构建期的 `patchExecLibs*` 一起），现在只剩：符号链接农场 + termux-exec + 写围栏 shim，见第 22 节。
- 逐轮实现与验证记录：`docs/UI-v6-report.md`（第五十轮为止；第 17、18 节记的是方案 A 把 applicationId 换成 com.termux，以及它带来的三个真机坑：自指符号链接 ELOOP、构建期别名残留、官方 bootstrap second stage）。第三十二～三十六轮修的是
  死循环＋空请求、全新安装误判未配密钥、缺「所有文件访问」、消息超 CursorWindow 2MB 闪退、
  并行工具超上限、工具行 key 用绝对下标闪位、附件图片重复解码卡顿。
  第三十七～四十三轮：封住第四条读路径 + 死代码清理 + 许可证统一；按 dsh 的 PTC 源码重做工具契约
  （ToolCallError、bash 留尾部、glob/grep 上限、web_search/web_fetch 的错误码与文案）；
  工具行「从下冒出再跳上去」与终端往返卡顿；终端/Agent 循环设置对齐 dsh + 并行闸门可重入；
  glob 目录与字典序、grep 值封顶、轮数上限去掉；附件图片判定/尺寸/路径；
  设置-模型的供应方目录；deepseek 模型目录按官网订正。
  第四十四～四十六轮：写围栏 + 审批卡 + 键盘跟随；`/tmp` 映射六项缺口（chdir / chmod / 裸 syscall 等）；
  方案 A 死代码清理（别名、前缀改写、dpkg 根树、apt 缓存覆盖、构建期 execLibs 补丁全部删除）。
  第四十七～四十八轮：硬链接被 SELinux 拒（avc denied { link } on app_data_file）→ shim 用复制顶替；
  静态提示词按 dsh ptc 现文订正（glob 段与实现相反的错、PTC 反引号、SDK 段点明 QuickJS）。
  第四十九轮：会话滚动「是否在底部」的判据改成**整条会话的最后一项**（`ChatScreen.bottomGap()`，
  原来用「当前露出来的最后一项」→ 按钮在一轮回答的底边处闪灭、松手被拽回底部）；触摸语义
  （按下即停，松手只在「本来就在跟随且没滑走」或「已回到真底部」时恢复）；终端关掉安卓 12+
  的越界拉伸（`verticalScroll(scroll, overscrollEffect = null)`）。
  第五十轮：转录正文按 200 行分块渲染（原来整段一个 Text，输出一大就每帧 500ms、整页 2fps）；
  覆盖页改成叠层，被盖住的页面**不销毁**（原来 push 终端会销毁设置页 → 退出卡 500ms + 卡片展开态丢）；
  回车后把键盘要回来（豆包输入法在回车上是 `ORIGIN_IME` 自收键盘，收键盘动画看起来像文字被拉伸）。
  第五十轮补：叠层的 `coverReady` 必须用 `remember(layerStamp) { mutableStateOf(false) }` 这种
  **组合期重置**的写法（同一帧生效）；用 `LaunchedEffect` 置 false 会慢一帧，push 那一帧底下就被压成
  0×0 → 打开终端时设置页闪一下。
  第五十一轮：推理等级改成**逐模型**的英文名（dsh：DeepSeek 是 Off/Low/High/Max 且默认就是 high、
  没有 Default 档；pi-ai 那边查 `getSupportedThinkingLevels`，目录里没有的模型按用户约定退回
  DeepSeek 那四档）；网页搜索的后端可换（内置 DeepSeek 与 Exa，卡片脚部「更换」→ 弹窗选引擎 →
  保存才切换，放弃修改全还原；Exa 每条 query 取 10 条候选、最终最多 7 条来源）。
  第五十四轮：**死代码清理**（先在 git 里打了第五次快照 `33801a3`，清理是它之上的增量提交）——
  删掉没人引用的声明 / import / 语法垃圾，并**移除了没用到的依赖** `com.termux.termux-app:terminal-view`
  （全仓库没有任何 `import com.termux.*`；终端是自己写的 Compose 面板 + `pty_bridge`），
  APK 少了一个 `libtermux.so`、体积 −170,667 字节。
  第五十五轮：出 **release 包**（R8 + shrinkResources）装到手机；打第六次快照并把**最旧的快照包删掉**
  （只留最近两次：第六次在 git 里、第五次在 `../ADSH-backup-20260919-5th.bundle`）。
  第五十二轮：**工具调用的 id 必须非空**（qwen 那类网关流式 tool_calls 不带 id，空 id 让历史装配
  把每次调用都判成「有调用没结果」，模型收到 "The tool call was interrupted…" —— 就是「一用就中断」）；
  装配侧改成先按 id、再按顺序兜底配对；`execute()` 只允许 run_code 直接被调用（dsh 的 tools:ptc-only，
  其余名字回 unknown tool 文案，不再替模型跑注册表里同名的工具）。

## 构建

- JDK 17：D:\WSN2005\Android\jbr；Android SDK 37；NDK 28.2.13676358；CMake 3.22.1
- 命令（WSL → cmd.exe → gradlew.bat）：./scripts/build-debug.sh :app:assembleRelease
- 单元测试：:app:testDebugUnitTest（83 个用例）；产物：app/build/outputs/apk/release/app-release.apk
- 发布产物：**`dist/ADSH-0.1.0-release.apk`（入库，README 的下载链接指它）** —— 每次出 release 后
  覆盖它再 push，这就是本项目的发布渠道
- 手机：FQJZF6U4TCVC7DB6；adb：D:\WSN2005\Android1\platform-tools\adb.exe（传参要用 Windows 路径）
- 本机网络：**只有 SSH 通**（`~/.ssh/config` 把 github.com 指到 ssh.github.com:443），
  `api.github.com` 不通 —— 第五十五轮试过 `gh release create`：`gh` 里存的 token 已失效，
  且 API 走加速器也只回 502「Could not find any IP that can be successfully connected」。
  所以 **GitHub Releases 用不了**，release 包一律走 git（dist/ + push）

## 构建依赖（不要删）

- app/src/main/assets/bootstrap/usr.zip（32MB，Termux 前缀，运行期解压）
- app/src/main/execLibs/（17MB，随包分发的可执行文件 + bin/ 下的 79 个脚本，运行期在 $PREFIX/bin 下建同名符号链接）、assets-src/
  **这些副本必须原样复制**：它们是从 nativeLibraryDir 执行的，改文本只会把编译期前缀改成
  不存在的路径（第 18 节坑 2）。第 46 轮删掉了 `patchExecLibs*` 任务与 `execLibAlias()`，
  `sourceSets` 直接指向 `src/main/execLibs` —— 新旧 APK 的 292 个 lib 条目 CRC 完全相同（第 22.4 节）。
- local.properties / keystore.properties / keystore/adsh-side.jks
- dist/ADSH-0.1.0-release.apk（发布产物，已入库；README 的下载链接指它）
- ~/.gradle（依赖缓存，在仓库外）

## 约定

- release 必须保持 minifyEnabled = true + shrinkResources = true
- 不要动手机上 com.termux 的数据（那就是 App 自己的数据）；装任何东西前先确认
- 界面与行为一律以 dsh 源码为准（D:\WSN2005\node-v24.19.0-win-x64\node_modules\@deepseek-ai\dsh\），
  有意偏离必须写进 docs/UI-v6-report.md
- 每轮改动追加一节到 docs/UI-v6-report.md
- **流式正文的重绘节流必须放在 ChatScreen 自己的作用域里**（`sampledStreaming` 直接声明在这一层，
  按「会话 + 这一轮」在组合期重建）。两个坑都踩过：写在列表项里面 → 行「先长高、下一帧才贴底」
  （从下面闪一下再跳，第 53 轮）；抽成「返回采样值」的子 composable → 写入只失效子作用域，
  这一层不重组 ⇒ 列表不重建 ⇒ 正文直到定稿才整段出现（第 53 轮补）。贴底本身只能靠
  `SideEffect` + `requestScrollToItem`（第 41 轮），三者缺一不可
- **别把 `com.termux:terminal-view` 加回来**：终端页是自研 Compose 面板 + `pty_bridge.c`，
  全仓库没有一处 `import com.termux.*`（第五十四轮删依赖时确认过）。真要用它得先有调用点
- **死代码扫描脚本**（第五十四轮留下的）：`scripts/remove-unused-imports.py` 删没用的 import
  （带锚点校验，`getValue`/`setValue`/`provideDelegate` 必须保留）。找「没人引用的声明」的做法是
  把 main/test/res/xml/manifest/gradle/cpp/docs/scripts 拼成一份文本按名字数出现次数，
  **只删「除声明处之外没人提」的**，删前逐条人工核对（`"command"` 那种老数据兼容分支别删）
- **工具调用与结果靠 id 配对，id 必须非空**：解析时缺 id 就补合成 id（`CallAccumulator`），
  装配历史时先按 id、配不上再按**顺序**兜底（`pairToolResults`，有单测）。改这块之前先想清楚
  「网关没给 id」这种情况 —— 第五十二轮那份 qwen 对话就是这么坏掉的（工具跑了，模型却收到中断文案）
- **PTC 模式下只有 run_code 能被模型直接调用**：`AgentLoop.execute()` 里那道闸门不能撤
  （dsh 的 tools:ptc-only）。撤了模型就会绕过 run_code 直接点名 web_search/bash 之类，
  子调用轨迹与并行闸门全都不在
- `LlmClient.decodeChunk` 是**流式解析**：一律用 `asPrimitive()` / `textOf()` 取值，
  别用会抛异常的 `jsonPrimitive`（形状意外时抛出去 = 整条流变成 Failed）
- 会话滚动的「是否在底部」**必须**用 `LazyListState.bottomGap()`（判列表最后一项），
  不能拿 `visibleItemsInfo.lastOrNull()` 当整条会话的底 —— 那会在每一轮的边界处误判（第 49 轮）
- 覆盖页（设置 / 终端 / 工作区文件）是**叠层**：`AppRoot` 里每层都保持组合，被盖住的用
  `Modifier.layout { layout(0,0) {} }` 压成 0×0（不测量/不绘制/不吃触摸）。**不要再改回
  `AnimatedContent`** —— 那会销毁设置页的组合，退出终端卡顿 + 卡片展开态都会回来（第 50 轮）
- 终端转录正文**必须分块**（`transcriptChunks`，每块 200 行）：整段（上限 20 万字）一个 Text 时，
  每批输出都要重排整段，实测一帧 ~500ms（第 50 轮，logcat 的 BufferQueueProducer 可复现）
- **静态提示词**（身份 / PTC 规则 / @ 引用 / 工具指导 / SDK 段 / 沙箱策略 / plan 段）的参照物只有两个：
  **dsh ptc 模式的渲染结果**与**本项目实现**。抄 dsh 的句子前先确认「ADSH 也这么做吗」——glob 那段
  就因此与实现相反了好几轮；ADSH 有意不抄的三处（fs-observation-policy、图片结果、web-surface/app-boot）
  见第 24 节。动态部分（`{{model}}` / cwd / AGENTS.md 链 / 用户 suffix / env:android-termux / SDK 声明）
  不要写死进静态文本
- **推理等级是逐模型的**：菜单走 `core/data/Reasoning.kt`（`menuOptions`），发请求走它的
  `wireEffort`（不支持思考的模型一个字段都不发，别的提供方留下的等级按 pi-ai 的 clamp 就近夹）。
  逐模型等级表 `core/data/ModelThinkingLevels.kt` 是**生成物**：`scripts/gen-reasoning-levels.py`
  从 pi-ai 的 `dist/providers/data/*.json` 生成，别手改；升级 pi-ai 之后重跑一次即可
- **网页搜索一次只有一个后端**（`SettingsStore.webSearchProvider`：`deepseek-official` / `exa`）：
  地址 / 密钥 / 一次搜索上限**都按后端分开存**（非 DeepSeek 的键尾接后端 id），换后端 = 关掉另一个；
  工具契约（名字 / 参数 / 输出 / 文案骨架）不随后端变，只有 `WebSearchTool` 里那两个 `search*` 分支不同。
  Exa 侧：每次 query 取 10 条候选（`numResults`，默认值见 `defaultWebSearchMaxUses`），
  最终最多给 7 条来源（`EXA_MAX_RESULTS`，用户约定；DeepSeek 侧仍是 dsh 的 8）
- **工作区在 FUSE 上，只能读写普通文件**（建不了软链、置不了执行位、chmod 无效、不能执行）。
  固定约定：工作区 = 素材入口 + 成品出口；装依赖 / 构建 / git 这些要真实文件系统的动作去
  `$ADSH_SCRATCH`（= `$HOME/scratch`，f2fs）。这条约定的唯一出处是提示词的
  `PromptAssembler.androidEnvText()`（两边都别只改一处）
- App 每次启动幂等做两件事（`TermuxRuntime.prepareHome()`）：建 `$HOME/scratch`、给
  `$HOME/.gitconfig` 补 `[safe] directory = *`（工作区文件属主是 media_rw，不补 git 全拒）
- `/tmp` 由 shim 映射到 `$PREFIX/tmp`（fence.c 的 redirect，同时导出 TMPDIR/TMP/TEMP）；
  `/data/local/tmp` 保持不可写（adb 目录，官方 Termux 也写不进）
- `ANDROID__BUILD_VERSION_SDK` 必须导出：termux-exec 的 system-linker-exec 拿不到它就会整个跳过
  （官方 app 导出，第 19 节补上）
- shim **必须常驻 LD_PRELOAD**，但它只管两件事：**写围栏**与 **/tmp 映射**（第 22.3 节）。
  app 私有目录里的可执行文件与脚本（含 shebang 解析）一律靠 **termux-exec** —— 它的 `.so` 里
  就有 `interpreter_path` / `prefixed_interpreter` / `/system/bin/linker64` 这些符号；安装器的
  second stage 只挂它、不挂 shim，照样跑完 184 个 postinst。**别再往 shim 里加 exec 逻辑。**
- **shim 的初始化只能走惰性路径，而且要允许重试**（第 20 节）：preload 库最早的几次被拦截调用
  发生在 libc 装好 `environ` 之前，`getenv` 全 NULL —— 用 `pthread_once` 一锤定音会让整个 shim
  永远空转（`/tmp` 映射与写围栏一起失效，真机实测踩过）。另外**不要在它的 constructor 里
  调 dlsym**（bionic 还握着 linker 锁 → 进程直接卡死）。诊断开关：`ADSH_SHIM_DIAG=<文件>`
- **只包 libc 函数不够**：GNU coreutils 的 `mv` 走的是裸 `syscall(SYS_renameat2, …)`（gnulib 在 configure
  时没找到 bionic 的 renameat2 就退化成 syscall），LD_PRELOAD 完全够不着 —— shim 里因此加了一个
  `syscall()` 拦截，只特判 renameat2/renameat 两个号。**新遇到「路径没被映射」先用
  `nm -D --undefined-only <二进制> | grep -E 'syscall|rename'` 看它到底走了哪条路。**
- 硬链接（`ln` 不带 -s）在 app 私有目录里被 **SELinux 拒绝**（`avc: denied { link } on app_data_file`，
  域 `untrusted_app`，enforcing）—— 内核层无解。shim 现在用**复制**顶替（`copy_as_link`：保留权限位与
  时间戳、目标已存在时 `EEXIST`），所以 `ln` / `cp -l` / `git clone --local` / ccache / npm 缓存都能继续跑；
  代价是 inode 不共享、占双份空间（`ADSH_LINK_EMULATE=0` 可关）。**它不是真硬链接**，别按 inode 共享去用（第 23 节）

## 待办

- 仓库 topics 未设置（android / deepseek / jetpack-compose）
- termux-exec **必须**挂进 LD_PRELOAD（运行时新装的脚本 / dpkg 维护脚本都在 app 私有目录里，
  targetSdk 37 下只能靠它的 linker 改写才能 exec）。配套的两条规矩不要破：
  1) 终端与 Agent 一律 `bash -i` / `bash -c`，**不用 login shell**（`-l` 会 source /etc/profile）；
  2) bootstrap second stage 已由安装器在装完后跑过并留下 lock（第 18 节坑 3），profile.d 里那个
     fallback 脚本拿到 lock 会直接跳过 —— 不要再把安装器改成删它
- pi-ai 的另外两个协议（openai-responses / anthropic-messages）没有实现，
  所以供应方目录里只用得到 openai-completions 的那些；模型编辑器里也还没有 dsh 的 input 模态开关
