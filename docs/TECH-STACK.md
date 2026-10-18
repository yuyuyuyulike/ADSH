# 技术栈（选型与理由）

一句话：**只选标准件**；每项都写明「为什么」和「什么时候该换」。行数上限 120。

## 已定选型

| 项 | 选型 | 版本 | 为什么 | 换成什么的触发条件 |
|---|---|---|---|---|
| 语言 / UI | Kotlin + Jetpack Compose（单 Activity） | Kotlin 2.4.10 | 现状，无迁移收益 | — |
| 构建 | Gradle + version catalog + build-logic 约定插件 | Gradle 9.5.0；AGP 9.3.1 | 多模块前必须有约定插件，否则每加一个模块复制一份脚本 | — |
| 依赖注入 | **手写 `AppContainer`** | — | 零编译代价，先治好「ViewModel 自己 new 一切」；Hilt 每模块跑 KSP + 字节码转换，单模块 4.5 万行会被拖慢 | **装配点 >15 个 / 需要按作用域注入（Activity、Service、Worker）/ 引入 WorkManager 时**才换 Hilt（Dagger KSP 已稳定，不用 kapt）。**模块数不是判据**：切完 8 个 Gradle 工程后仍然只有一个 ~40 行的 `AppContainer` + 一个工厂，而 Hilt 会让每个模块都跑 KSP |
| 持久化 | Room + KSP | 2.8.4 | 现状 | Room 3 是大迁移（新坐标、KSP-only、强制协程），**后置** |
| 序列化 | kotlinx.serialization | 1.11.0 | 现状 | — |
| 并发 | kotlinx.coroutines | 1.11.0 | 现状 | — |
| 用户程序 | QuickJS（`run_code`） | 3.2.3 | PTC 语义要求进程内沙箱 | 后端可替换性由 `:seam` 的 `seam.ptc` 定义保证 |
| 格式 | **Spotless**（只做行尾空白 + 末尾换行 + LF，不启用 ktfmt / ktlint 的 Kotlin 重排） | Spotless 8.10.4 | 格式规矩只有一个家（`build-logic` 的约定插件）；**不引入 ktlint**（维护方已退出、2.0 仍 alpha、无官方 Gradle 插件） | — |
| 静态检查 | **Android Lint 进门禁** | 随 AGP | Android 专有正确性（含 Compose checks） | detekt 等 2.0 稳定后再评估（1.23.8 解析不了 Kotlin 2.4） |
| 测试 | JUnit4 + **Robolectric 4.17 + Compose UI test + Turbine 1.2.1** | — | 全部用例（含 Compose 界面测试）在 JVM 里跑，不需要模拟器；Robolectric 负责 Android 宿主环境 | 截图测试用 Roborazzi 1.76.0；官方截图测试仍 alpha，不押注 |
| 导航 | **暂不引入 Navigation 3** | — | ADSH 的「页面」是叠层与抽屉，不是返回栈；引入等于把叠层改成路由 = 行为改动 | **出现 ≥3 级返回栈时**上 Nav3 1.2.0（要求 compileSdk ≥36，已满足） |

## 先别装（以及为什么）

| 不装 | 原因 |
|---|---|
| ktlint | 维护方退出，2.0 仍 alpha，无官方 Gradle 插件 |
| Metro | MetroX Android 要求 minSdk 28，本项目 minSdk 26 |
| 官方 Compose 截图测试 | 仍是 alpha，且 API 正在迁到 test suites |
| Navigation 3 全量迁移 | 见上表触发条件 |
| Room 3 | 大迁移，与本阶段目标（不加功能）冲突 |
| detekt | 等 2.0 稳定；现有自写 Python 扫描器够用 |

## 不确定项（没查到就不编）

- **AGP 升级前提**：AGP 9.4.1 要求 Gradle ≥9.6，当前 wrapper 是 9.5.0 —— 不满足先别升 AGP。
- AGP 9.4.1 与 KSP 2.3.10 的精确配套关系。
- Hilt 在 AGP 9 内建 Kotlin 下是否有已知坑。
- detekt 1.23.8 能否解析 Kotlin 2.4。
