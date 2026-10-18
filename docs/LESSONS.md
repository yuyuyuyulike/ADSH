# 经验与坑

> 第二阶段（熵减 + 修复：只做等价改写与真实缺陷修复，不加新功能）留下的可复用经验；逐步提交账见
> [HANDOFF.md](../HANDOFF.md)。只留能复现、能判定的东西，判「不做」与判「做」同样重要。

## 一、踩过的坑与解法

### 1) Windows / 工具链

- `gradlew` 只能从 bash 直接跑 wrapper；`cmd.exe /c` 会被当成交互式 cmd，一个任务都不跑。
- `sed` 替换串里的 `&` 是「整个匹配」——改代码别用 sed，用 Python 脚本。
- TS/Python 替换脚本的**转义会咬人**：模板串里的反引号与 `${}`；Python 多行锚点要用三引号
  （少一个引号会让变异体编译失败，看起来像「变异没响」）。
- `adb shell` 后面**只能给一个参数**：多个会被 adb 用空格重拼、设备侧引号分组全丢
  （`sh -c 'pm list packages'` 退化成打印用法）；复合命令整串当一个参数传，或用 `sh -s <文件>`。
- `MANIFEST.properties` 的 `nativeLibraryDir` 值含等号双写：按 `=` 切分会**从中间截断**，假路径让
  shim 报 CANNOT LINK（差点当成真机 bug）。
- 全库文件必须 LF（按换行做锚点的脚本依赖它）；清理 import 的脚本曾写出 CRLF。

### 2) Kotlin / JVM

- **块注释会嵌套**：KDoc 里的正则片段会开嵌套注释，报错在**文件末尾**（Unclosed comment）；import
  扫描器会因此把整个文件当注释——它报「某 import 没用到」时先确认文件真的解析完了。
- 单测方法名不能含点号与斜杠（JVM 直接拒），用顿号或全角。
- 纯 JVM 单测里 `android.util.Log` 抛 not mocked：判定提纯到不依赖 Android 的层，日志收进
  `runCatching`，别让它盖掉行为差异。
- 抽纯函数会**切断 Kotlin 顺着 val 的智能转换**：显式 `checkNotNull` + 注释，别用 `!!` 兜。
- 别为凑 `data class` 形状塞假字段（`ClipGet(val unused: Unit = Unit)`）——用 `data object`。

### 3) Android / 设备

- app 私有目录不可 exec、模拟存储 noexec、硬链接被 SELinux 拒：都走 shim 的 `[fs:]` 提示，
  **别偷偷绕过**（等于把平台事实藏起来，模型会按错的假设干活）。
- termux-exec 必须在**执行脚本的那个进程**里，否则 `env` 这种 shebang 找不到解释器；`/bin` 只是
  `/system/bin` 的软链，`/bin/sh` 能跑是巧合，`/usr/bin/env` 靠改写。
- `script`（util-linux）会覆盖 `PS1`，别用它验证「App 给终端设的 PS1」。
- release 包不可调试（`run-as` 被拒）：要拉库 / 进沙箱 / 看私有文件就装 debug 包。

### 4) 扫描器与死代码

- 扫描器正则要覆盖修饰符组合（漏了 `internal suspend fun` 会把「有调用者的文件」报成孤儿）。
- 「只被测试引用、生产零调用」的函数只有两种身份：**有意的测试缝**（KDoc 写明）或**死代码**。
- 「生产走不到的分支」就是死代码：收紧参数类型（只接受那个分支）比留分支 + 补用例更好。
- 静态扫描器全绿不等于没有死代码，但能挡住 90%：**每一步都跑，别攒到最后**。

### 5) 诊断与根因

- **静默失败最贵**：「模型不生成会话标题」的根因是标题那次小调用 `max_tokens` 太小却没关思考，
  推理吃光预算、正文为空，`runCatching { … }.getOrNull()` 又吞得毫无痕迹。关键路径的失败要留痕。
- 「别的模型行、这个模型不行」先怀疑**请求参数**：按生产形状直接打一发真实 API（本例：不带思考字段 →
  正文空，带上 disabled → 正文正常），比通读代码快。
- 参考实现（dsh）里「空结果」往往有明确语义（抛 `produced no text`）——对齐它，别自己发明兜底。

### 8) 门禁本身的盲区（2026-10）

- **门禁里必须有一次真实组装（APK）**：把包拆成 Gradle 模块之后，`:app` 的 `side` 变体（有 keystore 时才创建）找不到库模块的匹配变体，`assemble` 直接报 `No matching variant` —— 而 `:app:testDebugUnitTest` / `:app:lint` / `:app:spotlessCheck` **全部绿**（它们只碰 debug 变体）。修法是给 `:app` 的 `side` 加 `matchingFallbacks += listOf("debug")`。教训：**单元测试通过 ≠ 能出包**，门禁的最后一环必须是把包装出来。
- **临时件不许落在仓库根目录**：队友的脚本用 `.tmp-scan.cjs` / `.script-*.sh` 这类名字写在根目录，而 `.gitignore` 只忽略 `.tmp/` —— 既污染门禁统计（扫描器一度把备份副本一起扫，总数虚高到 742），也有被误提交的风险。临时件一律放 `.tmp/`，且用 `finally` 清理。

### 7) 验证方法本身的坑（2026-10）

- **PowerShell 管道会吞掉原生命令的退出码**：`gradlew … | Select-String …` 的进程退出码来自管道末端那个 cmdlet，原生命令失败也报 0。门禁脚本必须显式读 `$LASTEXITCODE`（`scripts/gradle-gate.ps1` 就是这么做的）。我因此一度误判「Gradle 模块边界没生效」，重跑才看到 `BUILD FAILED`。
- **grep 的引号风格会骗人**：Kotlin DSL 里 `project(':x')` 与 `project(":x")` 都能写，用单引号去搜双引号写法会得到「依赖不存在」的假结论。搜构建脚本时用宽松模式。

### 6) 多智能体 / 并发编辑（2026-10）

- **按路径圈提交会圈进队友的半成品**：提交前先 `git diff -- <path>` 看清内容归属，或干脆先让相关成员停手。最阴的是**新目录** —— `build-logic/` 是 untracked，`git add <某个文件>` 永远带不上它，于是 HEAD 出现「构建脚本已切约定插件、`includeBuild` 却没进库」这种编不过的状态。
- **多个 agent 同时跑 Gradle 会互相写坏输出**，制造**假失败**：任务级 `EOFException`、`Unable to delete …/built_in_kotlinc/…classes`，而用例 XML 其实全绿。要么串行（AGENTS.md 的「跑 Gradle」要门），要么用锁。
- **Windows 上用 PowerShell 写文件默认 CRLF**；仓库钉 LF 时 `spotlessCheck` 会抓（`setLineEndings(LineEnding.UNIX)` + `.gitattributes` 两道）。批量改写用 Python 并显式写 `\n`。
- **扫描依赖前先剥注释与字符串**：只看 `import` 行会漏掉内联全限定名，把注释也算进去会造假边 —— 两个方向都错过一次，结论会差出一个数量级。

## 二、必须做 / 绝对不能做

**必须做**

- 一步一提交，提交信息写清「改了什么 + 判据 + 验收」；文档与代码同提交。
- 改 `fence.c` 之后跑 `scripts/fence-selftest.sh`（host 侧 40 项）。
- 关键判定提纯后**先织网再拆**（先补可测性端口与黄金用例，再动那个大函数）。
- 每一步都跑门禁：`:app:testDebugUnitTest :app:assembleDebug`；发版前再 `:app:assembleRelease`。

**绝对不能做**

- 不能为了「看起来整齐」改数值或格式：提示词基线、间距刻度、系统提示词的格式位都不能顺手动。
- 不能重复写同一份东西：提示词不许重复（有测试盯着）、工具说明只在 `ToolSdk.specs` 写一次、
  同一个值写两遍的地方要收成一个来源。
- 不能动：`targetSdk = 28`、包名 `com.termux`、release 的 R8 两个开关、read-only 也挂围栏这条语义。
- 不能把没验过的写成「已验证」：真机没走到的分支要在提交信息里写清楚「没走到」。
- 不能为了「可测」而包一层：包装层要么删掉，要么把判定真正搬进去（生产走不到的分支就是熵）。
- 不能删 `MIGRATION_13_14`（只为保住 13→15 路径，最像死代码）；`readMessages` 是唯一合法的整份读路径。
- 不能改 `runtime/termux` 的调用顺序（解压→建链→removeTree→rename）或「顺手规范化」路径（与 ELF 编译期前缀逐字一致）。
- 不能把 bash 三处围栏语义合并反（= 只读预设下放行写权限）。

### 模块级雷区（评审时最容易顺手改错的三条）

- **`ToolSdk.section()` 是提示词基线**：它逐字决定模型看到的工具说明 —— 别在评审里顺手改措辞，动它等于动行为，要单独一轮 + 真机验证。
- **`ChatScreen` 那层对流式采样值的「读」不能挪走**：`items` 的 remember 与贴底的 SideEffect 都在那一层；读一旦下沉到子作用域，采样落地后没人重组那一层（表现是「流式正文一直不动、定稿才整段出现」）。
- **`WebFetchTest` 里有两条真的联网用例**：别混进纯 JVM 用例组，也别在没有网络的机器上整体跑。

## 三、已判定「不做」的清单（别再捡起来）

| 候选 | 判定 | 理由 |
|---|---|---|
| 工具层「39 处绕开 `Args`」 | 不做 | 只有 2 处是工具入参（已收回）；其余是供应商响应与 rg 输出，别人定的方言 |
| 工具层「5 套截断」 | 不做 | 五个不同的东西，都进模型可见面，统一 = 改工具输出 |
| 工具层「28 个常量散落」 | 不做 | 就近定义是对的；真重复的是提示词又写了一遍数字（已在 ToolSdk 注明） |
| `Tools.kt` write/edit 12 行 | 不做 | 「各自必须写 toolName」的机械后果；抽出只省 12 行却要加返回类型，且有测试覆盖 |
| 省略号三件套（`maxLines = 1 + TextOverflow.Ellipsis`） | 不做 | Compose 声明式 API 用法，不是本项目视觉决定，没有可共享的可变决策 |
| 图标 path 数据 | 不动 | 生成物（「不要手改 path 本身」写在各文件头） |
| 六个图标对象合并成一个 | 不做 | 各被 2~13 个文件引用；合并 = 61 个图标塞进一个对象 + 改全部引用点 import，是把「按用途分层」当重复 |
| `AppRoot`/`DshComposer`/`TerminalPanel` 再拆函数 | 不做 | 剩余熵**不是函数太长**：ChatScreen 剩的代码大半是参数转发与 remember/effect，已无 ≥20 行逻辑块；TerminalPanel 是 `var by remember` + PTY/IME 时序，AppRoot 是抽屉与图层帧语义——继续拆 = 搬家 |
| 30 个回调收成一个 `ChatActions` 值对象 | 不做（留触发条件） | 代价：30 字段类型 + 三处改 + 稳定性只能真机验 + **0 新用例**；收益只是名单从两处写变一处，编译期保障反而更弱。触发条件：仍出现漏接线 bug，或真出现第二个调用场景 |
| `DshSpacing.Hairline`（0.5dp） | 保留 | 是**刻度**的一份：`border(0.5.dp, …)`/`HorizontalDivider` 仍有 5 处写字面量；删掉只会让人再发明一种写法（KDoc 写明「刻度先留着」） |
| `DshPalette.dangerHover`/`accentHover`（只写不读） | 保留 | `DshPalette` 是 **dsh 设计令牌的镜像**（逐项取自 dsh-client-ui-theme），不是「只留用得到的」；同族 `codeString` 也长期无人读、后来被用上（KDoc 注明「当前没有读取点」） |
| `Reasoning.defaultEffortFor` 的 `modelId` 参数 | 保留（KDoc 已注明） | dsh 的 catalog 是 per-model 的，这参数是将来「按模型取默认档」的钩子；现在不读是**写明了的**，不是暗坑 |
| `PromptAssembler.kt` 再分文件 | 不做 | 无巨型函数（最大 `androidEnvText` 86 行），体量来自提示词文本；`PromptAssembler.<成员>` 被 13 文件引用约 150 处，搬出去要改 150 处限定名 = 纯 churn |
| core/data 的 15 条 Room 迁移 | 不做（离线测不了） | 没有 Robolectric 也没有 `androidTest`，迁移必须真跑 Room 才能验；只把**它们周围的判定**落地 |
| `Balance.fetch`/`SessionTitleRunner` | 不做（网络 / 调模型） | 前者真 HTTP，后者要跑一次小模型；**纯逻辑**已被入口函数覆盖，剩下的是 IO 编排 |
| `webSearchMaxUsesOf` 的 `coerceAtLeast(1)`、未知 id 兜底、`isCandidateBackend` | 不做 | 各 1~2 行、无判定内容；同族四处判定已落地，继续留着只会制造「为了提纯而提纯」的函数 |

## 四、可复用的验证手段

**搬运等价性（`check-move`）**：每一步搬运式重构都做「代码行 / 注释行」多重集比对，合格差异只有被改写的
标识符、签名、可见性；`--allow-*` 可当门禁。验证脚本除了报「0 处不一致」，还要报「还有哪些没动、为什么」。

**变异测试**：故意改坏生产代码，看哪张网会响。没响的两种可能必须先分清：死代码（删掉）还是网眼（补一条
能区分的用例）。也用它验新网——没有变异测试，「补了测试」没法证伪。

**门禁与复现命令**：

```bash
JAVA_HOME="D:\\WSN2005\\Android\\jbr" PATH="/d/WSN2005/Android/jbr/bin:$PATH" \
    ./gradlew.bat --offline :app:testDebugUnitTest   # 门禁（每步）；发版前加 :app:assembleRelease
python scripts/check-move.py --old <旧> --new <新…>   # 搬运等价性
python scripts/check-icon-structure.py               # 图标结构（挡编译器没拦住的错位实参）
python scripts/tokenize-padding.py                   # 间距刻度：改（--apply 才落盘）
python scripts/verify-padding.py <旧目录> app/src/main/java   # 逐实参比对，退出码 0 = 一个都没变
"D:/WSN2005/Android/jbr/bin/java" -cp "D:/WSN2005/Android1/tools/pmd-bin-7.28.0/lib/*" \
    net.sourceforge.pmd.cli.PmdCli cpd --minimum-tokens 100 --language kotlin \
    --dir D:/WSN2005/Android1/App/ADSH/app/src/main/java --format text   # 重复度
```

**「界面卡住但活儿还在跑」先数宿主实例**：`dumpsys activity activities` 看那个 Task 的 `sz=`，再 `HOME` + `monkey -c android.intent.category.LAUNCHER 1` 看它涨不涨。会话宿主是 Activity 作用域的 `ViewModel`，多一份实例就多一个宿主 —— 看见的那份不认正在跑的那一轮（按钮变灰、正文停住，活儿在旧实例里跑完）。

**真机口径与盲区**：装机启动无 FATAL + crash buffer 空只是底线，能走到的路径都要走一遍；纯 JVM 测不了
`ImageVector`/`PathParser`，图标、布局、间距的正确性最终靠真机目视，视觉改动要在提交信息里写清**看哪几处**。

## 五、方法论

**五把刀**：

| 手法 | 判据 | 做什么 |
|---|---|---|
| 归一 | 同一模式 ≥3 次 | 同一视觉/交互只留一份公共入口（`dshClickable`/`DshIconFactory`/`DshSpacing`/`DshWarnCard`） |
| 提纯 | if/when 与副作用混在一起 | 决策搬出 Android/IO 做成纯函数 + 用例；`File.isFile`/`Os.readlink`/`canonicalPath` 这类读取留在调用点 |
| 切缝 | 互不调用的连通分量 | 只按缝拆文件、不改语义；第二轮同一手法改搬 state holder |
| 表格化 | 同形分支 ≥4 个 | 同形分支 → 一张表 |
| 删除 | 脚本或 lint 判定无引用 | 死代码 / 死资源；生产走不到的分支靠收紧参数类型消掉 |

- **一小步一提交，每步独立可回滚**；提交信息写清「改了什么 + 判据 + 验收」，文档与测试同提交。
- **先织网再拆**：动手前先补可测性端口与黄金用例（先例：`AgentLoop` 依赖抽成窄端口 + 假实现，网织好后函数本体未动）。
- **判据优先于行数**：验收看行为不变量（编译 + 单测 + 真机）；整体行数没降，降的是「最长函数」与
  「零测试的路径」。「能表达意图、易测、易维护、性能够好，就是好代码」。
- **审计的关键字计数不能直接当待办**：它会把三类不同的东西算成一类、把五个不同的算成「一套重复」，
  还可能把有真实消费者的分支当死代码——**「死代码 / 重复」结论动手前一律自己复核**。
- **按模块提纯这条线已到头**：剩下的熵在 Compose 装配、需要 Robolectric / androidTest / 真机脚本的层，
  那是换手段，不是再切一刀。

## 六、操作细节（2026-10 从 HANDOFF.md 逐字迁移，尚未与上文去重）


#### 构建 / 工具链

- `cmd.exe /c gradlew …` 一个任务都不跑 → 被当成交互式 cmd、参数被吞 → 从 bash 直接跑 `./gradlew.bat`，显式给 `JAVA_HOME` / `PATH`。
- 自动替换脚本自己会出 bug（插进 Modifier 链 / KDoc、Windows 写 CRLF）→ 改完必须复核：编译器 + `remove-unused-imports.py`。
- 变异测试「没响」多半是变异体根本没编译 → 多行锚点用 `\n` 转义少引号、`sed` 的 `&` 是整匹配 → 锚点改用三引号；一次只跑一个变异、还原放 `finally`；**被 `timeout` 杀掉时 `finally` 不执行**，下一轮开工先 grep 锚点确认还原干净。
- 报错出现在文件末尾 `Unclosed comment` → Kotlin 块注释会嵌套（KDoc 里的 `/*` 片段开了新注释）→ KDoc 里避免 `/*`。

#### Kotlin / Compose

- 提纯后报「可能为空」→ 抽纯函数切断了顺着 `val` 的智能转换 → 显式 `checkNotNull` + 注释；别放弃提纯，也别把 `!!` 当默认答案。
- 漏接参数却不报错 → Composable 的默认值兜底 → 删掉默认值，漏接线从此是**编译错误**；反过来，只写不读的字段不进库。
- **「调用方必须记得传」的参数 = 易错点，收进组件自己**（第 211 轮）：指令面板的「不占布局」修饰符原本是形参，
  谁忘了传就重犯「面板挤扁输入卡片」那个 bug —— 改成 `CommandPalette` 内部自带（`Modifier.outOfFlowAbove()`）。
  判据是"少传这一个参数它就不正确"，而不是"这个参数有时要换一个值"。
- **当天长出来的大文件当天收**（第 211 轮）：`OfficePreview.kt` 一天里长到 860 行、装了两件事（就地解析 OOXML /
  把形状画出来）→ 按关注点拆成 `OfficeRead.kt`（只吐 `WordBlock` / `SheetTable`、不碰 Compose）与 `OfficePreview.kt`（只画）。
  同类的收口一并做：图片解码那一小段 `produceState + withContext(IO) + cachedImage` 曾散在四个文件（第 211 轮共五处），
  现在只剩 `ui/ImageDecode.kt` 的 `rememberImage` / `rememberImageBytes` —— 拆完立刻跑 `scripts/deadcode.py`，
  它会逐条点名交出来的失效 import（比人眼靠得住）。
- 弹层夹取与 dsh 对不上 → Compose 弹窗默认被裁剪 → `PopupProperties(clippingEnabled = false)` 之后窗口才是整屏。
- **浮层要「不占布局」，不是「让父容器少报高度」**（第 210 轮真机踩实）：指令面板曾作为输入框那一摞的第一个孩子、
  靠一个 layout 修饰符把整摞上移、并把报给父层的高度减掉面板这一截 —— 纸上没问题，真机上**输入框那一摞的高度是被父层限死的**
  （键盘弹起只剩一百多 dp），几百 dp 的面板挤进来就把卡片自己的高度吃光：卡片最后那一行按钮（＋/权限/模型/发送）
  与卡片下方的三个胶囊全被压成 0 高、直接消失。dsh 的 .menu 是 `position:absolute`（不占布局），Compose 的等价物是把
  「不占布局」落在**面板自己**身上：`layout(placeable.width, 0) { placeable.place(0, -placeable.height) }`
  （`ui/Composer.kt` 的 `Modifier.outOfFlowAbove()`）—— 父层量到的仍是输入框原本的高度，面板多高都挤不到它，
  面板自己的高度也不再参与任何一帧的来回（顺带把「面板一开就触发贴底」也去掉了）。
- 自动滚动 / 字距 / 抽屉的坑见『硬规矩』。

#### Android / 真机

- release 包 `run-as` 被拒 → 深度诊断装 debug 包；围栏判定可免模型造现场：`run-as com.termux env LD_PRELOAD=<nativeLib>/libadshfence.so ADSH_FENCE_MODE=… bash -c '…'`（nativeLib 从 `readlink -f files/usr/bin/bash` 反推）。
- shim 报 `CANNOT LINK EXECUTABLE … not found` 差点当成真机 bug → `nativeLibraryDir` 的值含 `==`，按 `=` split 会从中间截断 → 按第一个 `=` 取剩余；先怀疑自己的解析。
- 拿不到子进程树根 pid、孙进程被 init 收养 → 安卓运行时没有反射的 `Process.pid()` → 由子 shell 写标记文件；属性与转发它的函数不许同名（真机 `StackOverflowError`）。
- `sh -c 'pm list packages'` 退化成打印用法 → adb 把多参数重拼、引号分组全丢 → 复合命令整串当一个参数传。
- 闪退 / OOM：`adb logcat -b crash -d -v time` 看 Abort message 与堆上限，`dumpsys meminfo` 盯 Dalvik Heap。
- 「保活没生效」「岛不显示」→ 真因是自绘悬浮窗外层崩溃，不是通知渠道；MIUI 焦点通知真机上**不画** → 留一条**已验证能显示**的媒体路线兜底。
- 首轮比第二轮慢 → 标题那次小调用没关思考、先受理还与主请求抢带宽 → 按 dsh 口径按路由关思考；前缀缓存命中由**服务端**决定。
- **「应用缓存很少」是正常的**（第 207 轮用户点名核过）：Android 设置里那一项只算 `cache/` 与 `code_cache/`，
  而 ADSH 的磁盘缓存只有 bootstrap/apt 的 partial（`cacheDir/apt/archives/partial`，装完就是空的，系统还会定期清）；
  **热缓存全在内存**里 —— 图片位图 32 MB、公式位图 6 MB + 尺寸表、代码高亮 64 条、消息 JSON 解码 512 条的 LRU
  （`ui/ImageDecode.kt` / `ui/Latex.kt` / `ui/DshCodeBlock.kt` / `ui/TurnList.kt`）；数据本身在 `files/`（Termux 前缀 + home，
  实测 1.9 GB）。所以「缓存几百 KB」既不说明退化，也和滑动 / 切会话的流畅度没有关系 ——
  历史上只有 0.2.0 那次熵减动过 `cacheDir`（把 apt 缓存的意图写实，不是删缓存）。
- **同一个步骤里连续两次 run_code，第二个必失败**（`code run failed (worker-exit): PTC 进程没起来（绑定超时）`，正好 5 秒）：`:ptc` 进程死在「还挂着 `BIND_AUTO_CREATE` 绑定」的状态下时，AMS 把它当「服务需要重启」再拉一次，**MIUI 的 `AutoStartManager` 直接拒绝**（`logcat`：`Reject RestartService service :ComponentInfo{…PtcWorkerService}`），于是紧跟其后的那次 `bindService` 挂在一条再也不会起来的服务记录上。两次调用之间正常隔着一次模型往返（~2 秒）所以看不出来，只有同一步的背靠背才踩中。修法两条一起：**先解绑再杀** + **杀完等 `/proc/<pid>` 消失**（`PtcProcess.close()` / `kill()`）。判据是 `logcat` 里 `ActivityManager: Start proc …:ptc` 与 `Process …:ptc (pid …) has died` 的成对出现，以及 `ADSH_TRACE` 的 `tool+ / tool-` 时间戳。

#### 与 dsh 对齐

- **会话列表必须一次性渲染（dsh 的 DOM 语义）**（`packages/client/ui-chat/src/client/chat/ChatView.tsx`：全量 `entries.map` + 宿主分页 + 每行一把 key 的脏订阅 + memo）。LazyColumn 的代价有两条：① 行高只有进过视口的行才量得到，总高度只能估，滚动时新行被量到 → 右侧滚动条长度跟着跳；② 上滚时旧行被重新组合，正是「上一轮消息出现时卡一下」。
  现在整份列表常驻（`ChatTranscript` 的 `CulledColumn` + `verticalScroll`）：滚动容器的 `maxValue` 就是真实内容高度，滚动条比例直接取它（`scrollbarThumb`），内容不变时长度恒定；滚动时没有行重建。代价是超长会话首开更慢、内存更高（用户明确选了这条路）。`CulledColumn` 在布局阶段把每行 y 记进纯数组、draw 阶段只画视口内的行（**别用 `onGloballyPositioned` 记 y**：滚动/键盘动画每帧回调 + 写状态，100+ 行时主线程 40ms/帧）。
  行内动画仍只动绘制相位、收起态定高；两轮之间 dsh **没有分割元素**（`margin-top` 变量 + 零高度占位），别再造分割线。
- **掉帧先看「视口里那一行有多大」，不是会话多长**：可见性剔除是行粒度的，展开的思考正文曾是**一整行** `MarkdownBody` —— 只要露出一像素就整段参与绘制；Compose 没有浏览器的合成器滚动（滚动每帧重画可见内容），所以大段思考展开后滚动 / 键盘 / 抽屉必掉帧，折叠或移出视口就不掉。修法是切块（`reasoningChunks`）：一块一个会话流行，每帧只画视口里的几块，流式也只让最后一块重解析。**dsh 不切**是因为浏览器滚动不重绘 + 宿主分页（`loadOlder/hasMore`），别拿它当「不需要切」的依据。
- 自己发明的方案会被否（「放弃线程」的看门狗）→ dsh 是**进程隔离 + 到点杀进程** → 行为分歧先读 dsh 现文：`gh api repos/deepseek-ai/deepseek-harness/contents/<path> -H "Accept: application/vnd.github.raw"` 或本机 `D:\tmpdsh-work`（0.2.1-rc.1 全量树）。**整体刷新**：`curl -L https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/<tag>` 再解包覆盖（Windows 无权限建符号链接时按内容展开）—— 不要 `git clone`，`github.com:443` 连不通，codeload / api 通。
- 展示细节不确定时照抄 dsh 客户端的常量 / 规格（100ms 花名册窗口、25dp 迟滞、`letter-spacing: normal`）；`scripts/dsh-extract.py` 从 `app.asar` 取原实现。
- 在 dsh 里搜不到某个常量名 → 那是 ADSH 自己的（如 `JOB_NOTICE`）→ 按 KDoc 语义钉行为，并在提交信息里说明没有原实现可对。
- **`run_code` 的 preparing 行：摘要跟着流式参数长，但这一行不可展开**（dsh `toolRowModel` + `argumentSummary` + `PartialArguments.text`）：只有工具名时行上只有标题；参数里 `description` 写出来几个字，右侧摘要就长几个字；文件类工具要 `file_path` **完整**才显示；`expandable = state !== 'preparing'`，子调用到 `tool/call` 派发后才出现。别做成「等派发才整段蹦出来」（用户点名要与 dsh 一致）。
- **UI 令牌的取法**（第 206 轮）：`scripts/dsh-extract.py --list ".module.css"` 能列出 ui-primitives 里**原样的 CSS 模块**
  （`Menu.module.css` / `MenuSurface.module.css` / `markdown/MarkdownText.module.css` / `CodeBlock.module.css` …），
  而 `@deepseek-ai/dsh-client-ui-theme/lib/client.js` 里是**令牌表**（浅/深两套）—— 想核「dsh 这个面到底是什么色 / 什么圆角」，
  直接在这两个地方查，别猜。几个关键值：菜单面 `--dsw-menu-surface-fill` 浅 `#f8f9fa94` / 深 `#43454a73`
  ＋`backdrop-filter: blur(40px) saturate(150%)`；浮层阴影 `--dsw-elevation-prominent` = `0 0 0 .5px <stroke>` +
  `0 3px 8px #0000000a` + `0 0 20px #0000000d`；`--dsw-radius-xs/sm/md/lg` = 4/8/12/16；行内代码 = 等宽 + `.875em`（紧凑档 `1em`）
  + `--dsw-alias-markdown-inline-code`（浅 `#fafafa` / 深 `#292929`）+ `.5px border-l1` + 圆角 8（紧凑档 4）+ 左右 5（紧凑档 3）。
- **文件预览的格式表照 dsh，实现只能就地方案**（第 209 轮）：dsh 的 `sidebar-documentpreview` 里
  Markdown / 代码 / 图片 / PDF / HTML 是浏览器渲染器，**.doc/.docx/.ppt/.pptx 交给 Host 的 LibreOffice**
  （`dsh-office-to-pdf`）转 PDF 再交给 PDF.js，**.xlsx/.xls/.csv/.tsv 走 FortuneSheet**（`client.excel.js`），
  未知扩展名一律**纯文本兜底**。Android 上既没有 LibreOffice 也没有那些 JS，所以 ADSH 直接读 OOXML 本身：
  `.docx`（word/document.xml 的段落 / 标题档 / 表格）、`.xlsx`（workbook + rels + sharedStrings + sheetN，值取**已保存的结果**、不重算公式）、
  `.pptx`（presentation.xml 的 sldIdLst 定页序，逐页 a:t）、`.csv/.tsv`（dsh 的表格解析口径：引号、转义、跨行字段，首行是数据）——
  全在 `ui/OfficeRead.kt`（画在 `ui/OfficePreview.kt`，第 211 轮拆开）。**老式二进制的 .doc/.xls/.ppt 是干不了的**（那正是 dsh 需要 LibreOffice 的部分），
  给一句明确说明而不是假装能预览。渲染上限（部件 6MB、500×60 单元格、400 块）是预览口径，别当解析器用。
  这四个解析器**是拿真实文件核过的**（第 209 轮）：临时探针用例 + python-docx/openpyxl/python-pptx 现生成的
  `probe.docx/.xlsx/.pptx` 与手写 `probe.csv`，逐条打印段落 / 标题档 / 表格、两张工作表、页序、引号与跨行字段 ——
  全对之后探针**已删**（它读的是 `D:\tmp` 下的绝对路径，留在仓库里会在别的机器上红）。
- **弹层背景模糊取不到源；抽屉的雾化正常**：dsh 的菜单是「半透明面 + backdrop blur」。ADSH 曾把详情卡做成同层浮层 + Haze（blur 40dp / saturate 1.5），
  实拍背景文字直接透出（浮层里的 Haze 拿不到身后的源），所以详情卡/菜单改成**不透明合成色**（`DshPalette.menuSurface`）+ 发丝边 + 两层柔光。
  抽屉那一层的 `hazeBlur`（同一窗口、普通子层、1.5dp）实测有效（开合 A/B：文字边缘明显变软）—— 别把抽屉模糊删掉，也别再在 SubcomposeLayout 浮层里依赖 Haze。
- **卡片圆角一律是 `--dsw-radius-lg` = 16，不是 12**（第 209 轮全库核对出来的系统性错误）：`--dsw-radius-xs/sm/md/lg` = 4/8/12/16，
  而 dsh 的**详情卡片**（`CodeCard` / `SearchBlock` / `TerminalBlock` / `WebBlock` / ToolRow 的 `ioCard` / `AskQuestionCard` /
  ToolDetails 的 `pmht6G_root` / ChangedFiles 的 `kuvljq_card` / deliverables 的 `jmg6Sq_output`）全部取 `radius-lg`；
  ADSH 从第 91 轮起把这一批卡片都写成了 12（radius-md），视觉上就是「角比 dsh 更收」。核法：`grep -o "<hash>_{class}{[^}]*border-radius[^}]*}"`
  在 ui-primitives / ui-tool / ui-deliverables 的 `lib/*.js` 里找那个类，再看 theme 的令牌表。**markdown 代码块的 `--dsl-code-block-border-radius` 也是 radius-lg**（16）。
  菜单与面板同理：`DshMenuCard` 的默认圆角第 211 轮从 12 改成 16（12 处调用点跟着走），行 / chip / 内嵌小卡的
  那一档仍是 12（radius-md）—— 判据是「浮层卡片 vs 行内小件」。
- **指令栏：同层渲染 + 自己不占布局**（第 209 轮改同层，第 210 轮定形）。dsh 的 `.menu` 就是输入框那一摞里的 DOM 节点
  （`position:absolute; bottom:calc(100% + 4px)`）；ADSH 原来用 `DshPopup`（Popup = 独立窗口），键盘动画期间每一帧都要重摆窗口、
  高度又跟着锚点走（窗口一改尺寸系统会先画旧尺寸那一帧）—— 用户看到的就是「下滑收起时特别卡」。
  现在它是输入框那一摞 Column 的**第一个孩子**，但**不参与这一摞的高度**：`CommandPalette` 自己带
  `Modifier.outOfFlowAbove()`（报 0 高、整块画到 -height，见 `Composer.kt`）。**别改回「让父容器少报高度」那一套**
  （第 210 轮踩实：输入框那一摞的高度被父层限死，面板几百 dp 挤进来会把卡片最后一行按钮与下方胶囊压成 0 高）。
  同层的代价是「点空白关菜单」要自己铺一层拦截 Box，且**锚点矩形必须同时包含输入框那一摞与浮层**
  （否则按下面板会被全局「点别处关闭」当成外部点击，行还没抬手就被拆掉）—— 见 `unionAnchor`（`OverlayDismiss.kt`）。
- **选中浮动栏的「全选」残留是 Compose 自己发的**（第 209 轮，读字节码确认）：`SelectionManager.updateSelectionTextToolbar` 里
  `onCopyRequested` 走 `canCopy()`（没有非空选区 = null），而 `onSelectAllRequested` **只在"整段已全选"时才是 null** ——
  选中被清掉后的那一次 `showMenu` 落下来就是"只有全选"的一排。ADSH 的选中区都是只读文本，所以自建了一层
  `TextToolbar`（`ui/SelectionToolbar.kt`，装在 App 根）：可复制 / 可剪切 / 可粘贴三样都没有时不弹，残留从此不存在。
- **会话标题的时机**（`@deepseek-ai/dsh-session-title`，从 `app.asar` 取原实现看）：① 首条**人类**消息一落库就写**兜底标题**（`ensureFallback` = 前 5 个空格分隔 token、UTF-8 ≤40 字节，**先过 `cleanTitleText`**）；② 标题小模型订在**主请求写到连接上之后**（`ChatEvent.RequestHeader` = dsh 的 `onRequestHeader`），**不是**一轮跑完 —— 第 199～204 轮订在 `finishTurn`，第 205 轮改回 dsh 口径。覆盖判据「标题仍等于兜底值」（用户手改过名就写不进去；dsh 用 `session/title` 的 `source.kind`）。真机时间线实测：`新会话` → ~1s 后兜底 → 2s 后模型标题。

#### 扫描器与门禁

- 扫描器把有调用者的文件报成孤儿 → 正则没覆盖修饰符组合；「0 候选」≠ 没有死代码。
- 审计报告的关键字计数不可直接当待办（五条里三条不成立）→ 最危险的是「`ChatEvent.Usage` 是死分支」：它在喂 token 账本，删了会**静默丢统计**、没有编译或测试会拦 → 凡「死代码 / 重复」结论，动手前先看代码。
- 等价改写用 `check-move.py` 做「代码行 / 注释行多重集比对」（`--allow-old/--allow-new` 当门禁）；提纯要配**变异测试**；只有真机才走到的代码靠逐字等价 + 用例 + 诚实说明。

#### 诊断方法

- 执行层行为靠探针量：临时测试把 `shellEnvironment` 的真实输出落盘成环境变量，再用 `sh -s` 喂进设备（探针目录用完即删）。
- App 里删不到空会话 → 改库：`am force-stop` → `run-as` 把 `databases/adsh.db` 与 `-wal` 拷到 `files/` 再 `exec-out cat` → 本机 `sqlite3` 删行（WAL 自动重放）→ `wal_checkpoint(TRUNCATE)` → `base64 -w0` 写回 → 删设备上 `-wal` / `-shm` → 起 App 核对。
- 「首轮慢在哪」：`adb logcat -c` → 发送 → `adb logcat -d -v time -s ADSH_LLM:V ADSH:V`（`POST … msgs=N tools=M` 与 HTTP 200）；再用本机 python 直连同一把 key 做 A/B，那一步量的是**服务端**行为。
- **测试 agent 报的两个「坑」的结论（第 206 轮核过）**：①「退出码会骗人」—— 属实，但**是设计**：工作规则里逐字写着
  「Failures are told apart by **what the tool returned**, not by the exit code」＋ `[sandbox: …]` / `[fs: …]` 两种标记的判读
  （已核对真机那一刻发给模型的 sysprompt 里确实有这几句），dsh 也同样靠标记而不是退出码，所以不动；
  ②「没有 `log`、`write`/`edit` 返回 before/after」—— `write` 的形状与 dsh 逐字一致（`{path, operation, before, after}`，
  `dsh-tool-fs` 的 presentationMeta 就是这么写的），SDK 段也声明了；`log` 则是 dsh 里也不存在的全局
  （Node 只有 `console`），属于模型自己记错 —— ADSH 这边**顺手给了 `log` 别名**（QuickJS PREAMBLE 一行 + SDK 段一句话），
  代价为零、少一类程序跑挂。
- **读「正在跑」的库必须连 `adsh.db-wal` 一起拉**（`run-as` cat 两个文件到同一个目录再交给 sqlite）：只拉 `adsh.db` 看到的是**上一个检查点**的状态 —— 新会话、刚落的用户消息、刚换的标题全都不在，第 205 轮据此差点误判成「兜底标题没写进去」。App 停下来时（force-stop / 干净退出）WAL 会被合并，那时才只剩一个 `.db`。
- 想看清「标题是什么时候变的」这类时间线：**连续拉 16 份库快照**（每份 ~0.8s）再逐份查同一张表 —— 比截图（每张 1.5~2.4s、还会被别的 Activity 挡住）可靠得多。
- **想知道 Compose / AndroidX 某个 API 的真实行为**，直接读字节码（第 209 轮就是这么确认「全选残留」与「超出父边界的子节点照样收得到触摸」的）：
  aar 在 `D:\WSN2005\Android1\.gradle\caches\modules-2\files-2.1\<group>\<artifact>\<ver>\*/\*.aar`，解开其中的 `classes.jar` 后
  `"D:/WSN2005/Android/jbr/bin/javap.exe" -p -c androidx/…/X.class`。KDoc / 网页都不可靠时这是**唯一权威**；
  `LocalTextToolbar` 这类接口的抽象成员是哪一个重载，也从 `javap` 的 `abstract` 行看。
- 这台 MIUI 上 **`adb shell input tap` 进不了第三方 App**（合成触摸被丢，`input keyevent` 打系统窗口却有效，Home 能回桌面）→ 想驱动一次真实回合，最省事的是往 `MainActivity` 里临时加 5 行钩子（`LaunchedEffect` 读 `intent.getStringExtra("adsh_send")` 调 `viewModel.send`），`am start -n … -e adsh_send '<prompt>'` 发一条；**提示词按整串单引号传**（`adb shell "am start … -e k 'a b c'"`），否则设备侧 sh 会把空格拆开、`am` 只把第一个词当参数值（模型收到一个 "Run"）。用完把钩子删干净。
- **Office 预览的「失真」拿真文件对拍**（第 210 轮）：用户报的焊接工艺卡就在 WPS 缓存里
  （`/sdcard/Android/data/cn.wps…/cache/…/*.docx`，`find /sdcard -iname "*.docx"` 一把捞到）→ adb pull 到本机，
  再用 **LibreOfficeKit**（`skill office-docx` 给的两个绝对路径）`render --input x.docx --output-dir ref --pages 1 --dpi 144`
  出一张参考图，另外用 lxml 把 document.xml 的 `w:tblGrid` / `w:gridSpan` / `w:vMerge` / `w:jc` / `w:sz` / `wp:extent` 摊开 ——
  **参考图看「应该长什么样」，dump 看「结构到底是什么」**。四项失真一次找齐：列宽被等分（真实 grid 从 324 到 1782 twip 不等）、
  不认 vMerge（合并格塌成空格）、不画图（接头示意图整张没有）、不认 jc/sz/b（标题不居中、字号全一样）。
  还原口径：**1 twip = 1/15 dp**（96dp = 1 英寸，与 PDF 页面同一比例）、`w:sz` 半磅 → dp 乘 2/3、EMU 除 9525；
  纵向合并用「起始格 + 续格」表达（续格只占位不画、把起始格的最小高度按行数撑大）。
- **adb 的路径参数会被 Git Bash 改写**：`adb pull /sdcard/a.docx D:/x` 里的 `/sdcard/…` 会变成 `D:/WSN2005/git/Git/sdcard/…`
  → 前面加 `MSYS_NO_PATHCONV=1`（`adb shell '…'` 里的路径不受影响，整串引号护住了）。
- 同一个进程里连开两次 Activity（`am start` 不加 flag）会留下两个实例、两套 ViewModel：界面停在旧实例上、新实例在后台跑回合，截图会看错对象 → 观察 UI 用**干净的一次启动**（`force-stop` 再 `am start`）。

- **滚动「卡一下再瞬移」的判据**（第 213 轮）：看 `dumpsys gfxinfo com.termux` 的 **99 分位**与
  **`Number Slow UI thread`** —— 不要只看 `Janky frames`：60Hz 下一帧 95ms 也可能不计入 Janky，
  但 99 分位会从 ~15ms 跳到 50~113ms。再与 `ADSH_TRACE` 的 `row+` 事件对齐：**每一个长帧都伴随某一行
  进出组合**（当时是 `row+ turn-XXXX-text:0`，即 AI 正文行），据此把「列表项太重」与其他原因分开。
- **atrace 抓 Compose 三段**：`adb shell atrace -a com.termux -b 40000 -t 34 view gfx sched > 本机文件`
  （**别用 `-o`** —— Git Bash 会把远端路径改写成 `D:/WSN2005/git/Git/...`，文件根本不落盘），
  再解析 `tracing_mark_write` 的 `B|tid|名字` / `E|tid` 配对，按名字聚合看
  `Compose:recompose` / `AndroidOwner:measureAndLayout` / `AndroidOwner:draw` / `TextAnnotatedStringNode:measure`。
- **区分「随内容增长」与「每次重录制的固定开销」**：把重帧按帧内文本测量次数分桶 —— 若「1 个节点」的帧与
  「800 个节点」的帧 draw 都是 ~42ms，那就是固定开销（本项目的答案：行内代码底框的逐字符 `getBoundingBox`），
  此时**切块帮不上忙**，要治的是那次重录制本身。
- 诊断期临时探针（逐帧位置 + `run` / `touch` / `foll`）比猜快得多：它能把「停顿」与「单帧位移 400~500px」
  直接对上；**但判据要只取惯性进行中（`run=true`）的间隔**，否则 adb 两次 `input swipe` 之间的空档会被算成停顿。
- **合成的 `adb shell input swipe` 复现不了用户手指的那一下**：release 包上 80 次往返只到 99 分位 14~29ms，
  真机手指那一次是 113ms / 7 个慢 UI 帧。合成手势能验证「不回归」，**验收仍要用户的手指**。

