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
