# 第三方组件声明（THIRD_PARTY_NOTICES）

本仓库自身代码以 MIT 发布（见 `LICENSE`）。APK 内**以聚合方式**随包分发了下列第三方二进制；
它们与项目代码是独立进程关系（fork/exec），不构成衍生作品。

所有 GPL 组件均**未经修改**地来自官方发布物，对应源码即同版本上游源码，获取方式见每行链接。

## 一、Termux bootstrap（整体基线）

- 版本：`bootstrap-2026.09.13-r1+apt.android-7`
- 文件：`bootstrap-aarch64.zip`，SHA-256 `dbf2805613ff2ace0b233c3b349e080bb0ff358f4ad64f4cca5966b27b93e7ee`
- 上游：https://github.com/termux/termux-packages/releases
- 说明：`app/src/main/assets/bootstrap/usr.zip` 即该文件（构建期由 `scripts/fetch-bootstrap.sh` 拉取并校验）；
  `app/src/main/execLibs/` 下的 186 个 ELF 全部从该 zip 的 `bin/` 复制而来，仅重命名以便随 APK 打包。

## 二、GPL 组件（需提供源码获取途径）

| 组件 | 版本 | 许可 | 上游 |
|---|---|---|---|
| bash | 5.3.15 | GPL-3.0-or-later | https://ftp.gnu.org/gnu/bash/ |
| coreutils | 9.11-1 | GPL-3.0-or-later | https://ftp.gnu.org/gnu/coreutils/ |
| grep | 3.12-3 | GPL-3.0-or-later | https://ftp.gnu.org/gnu/grep/ |
| sed | 4.10 | GPL-3.0-or-later | https://ftp.gnu.org/gnu/sed/ |
| gawk | 5.x | GPL-3.0-or-later | https://ftp.gnu.org/gnu/gawk/ |
| findutils | 4.10.0-1 | GPL-3.0-or-later | https://ftp.gnu.org/gnu/findutils/ |
| tar | 1.35-3 | GPL-3.0-or-later | https://ftp.gnu.org/gnu/tar/ |
| diffutils | 3.x | GPL-3.0-or-later | https://ftp.gnu.org/gnu/diffutils/ |
| gzip | 1.x | GPL-3.0-or-later | https://ftp.gnu.org/gnu/gzip/ |

> 获取对应源码：按上表版本号从 GNU 官方 FTP 下载同版本 tarball；
> 或使用 Termux 的构建系统复现（https://github.com/termux/termux-packages）。
> 本项目的 `scripts/fetch-bootstrap.sh` 固定了 release tag 与 SHA-256，任何人可复现同一份二进制。

## 三、宽松许可组件

| 组件 | 版本 | 许可 | 上游 |
|---|---|---|---|
| ripgrep | 15.2.0（aarch64-unknown-linux-musl，静态） | MIT 或 Unlicense | https://github.com/BurntSushi/ripgrep |
| QuickJS wrapper | wang.harlon.quickjs 3.2.3 | MIT | https://github.com/HarlonWang/quickjs-android |
| Jetpack Compose / AndroidX / Room / Lifecycle | 见 `gradle/libs.versions.toml` | Apache-2.0 | https://developer.android.com/jetpack |
| kotlinx.serialization / coroutines | 见 `gradle/libs.versions.toml` | Apache-2.0 | https://github.com/Kotlin |
| haze | 2.0.0-beta02 | Apache-2.0 | https://github.com/chrisbanes/haze |
| termux terminal-view | 0.118.0 | Apache-2.0 / GPL-3.0（Termux 项目） | https://github.com/termux/termux-app |

## 四、合规说明

- 项目自身代码与上述 GPL 二进制是**独立进程**关系（App 通过 exec 调用它们），属于聚合分发，故项目许可采用 MIT。
- 若将来需要分发修改过的 GPL 二进制，必须一并提供修改后的源码。
