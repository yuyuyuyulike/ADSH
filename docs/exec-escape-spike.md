# PoC-1：exec 通道真机验证（结论：通过）

日期：2026-09-14　对应方案书 §5.3 / §6 M0-PoC1

## 目的

验证在 **无 root、无 proot、现代 targetSdk（37）** 下：
1. APK 内以 `lib*.so` 命名的可执行文件，安装后落在 `nativeLibraryDir`，**可以 exec**；
2. 同一个二进制放到 app 私有目录（`filesDir`）后 **不能 exec**（Android 10+ W^X 的 neverallow）；
3. 从而确认"可执行文件必须走 nativeLibraryDir"这一设计前提在本机成立。

## 构建侧证据（APK 内容）

```
$ unzip -v app-debug.apk | grep lib/
  Length   Method    Size  Cmpr    Name
 4466000  Defl:N  2014864  55%  lib/arm64-v8a/librg.so
```

- `Defl:N` = 以 deflate 压缩存放 → 安装期会被**解压成真实文件**。
- 这是 `packaging { jniLibs { useLegacyPackaging = true } }` 的直接效果；
  AGP 对 minSdk > 23 的默认是"不压缩 + page-aligned"（`Stored`），那样 `nativeLibraryDir` 下**不会有真实文件**，exec 会失败（errno 13）。
- 二进制：ripgrep 15.2.0 `aarch64-unknown-linux-musl`，静态（readelf 确认无 INTERP / RUNPATH / NEEDED），**4,466,000 字节**（比方案书早先记的"1.89 MB"大——1.89 MB 是 .tar.gz 压缩包，解压后是 4.27 MiB）。

## 设备侧证据（真机 logcat）

设备：aarch64，内核 `6.6.118-android15`（Android 15），SELinux 域 `u:r:untrusted_app:s0:c119,...`。

```
packageName       = com.adsh.app.debug
nativeLibraryDir  = /data/app/~~FZ0naSf2-07e77IOHUm94A==/com.adsh.app.debug-P0th15N4qeDvHgFXK5xieQ==/lib/arm64
extractNativeLibs = true
librg.so exists   = true   size = 4466000
canRead/canExec   = true / true

-- [1] exec nativeLibraryDir/librg.so (期望成功) --
result: finished=true exit=0 in 109ms
ripgrep 15.2.0 (rev e89fff89ac)
features:+pcre2
simd(compile):+NEON
simd(runtime):+NEON

-- [2] exec filesDir/librg-copy (期望失败) --
copy exists=true size=4466000 canExec=true
FAILED: java.io.IOException: Cannot run program "/data/user/0/com.adsh.app.debug/files/librg-copy.so": error=13, Permission denied
```

## 结论

| 假设 | 结果 |
|---|---|
| `nativeLibraryDir` 下的 `lib*.so` 可 exec | ✅ 成功，exit=0，ripgrep 正常输出版本 |
| app 私有目录下同二进制不可 exec | ✅ `error=13, Permission denied`，**即使 `File.canExecute()` 返回 true** |
| `useLegacyPackaging=true` 让 .so 以 `Defl` 入包 | ✅ 55% 压缩率 |
| 需要 proot / root / 降 targetSdk | ❌ 不需要 |

**注意**：`canExec=true` 但 exec 仍被拒绝——所以"能不能执行"**不能**用 `File.canExecute()` 判断，必须以真实 exec 为准。这条也解释了为什么工作区校验必须用 `bash -lc 'test -w P'`（方案书 §3.3）。

## 复现方式

```bash
./scripts/fetch-tools.sh                 # 下载 ripgrep（带 SHA-256 校验）→ app/src/main/execLibs/arm64-v8a/librg.so
./scripts/build-debug.sh                 # Windows 侧 Gradle 构建（JDK: Android Studio JBR）
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.adsh.app.debug/com.adsh.app.MainActivity
adb logcat -d -s ADSH_POC1:I
```

## 对后续的影响

- §5.3 的"方案 A"从**纸面推演**升级为**已验证**；M0 的兜底（方案 B/C）暂不需要。
- 共享库仍可留在 `files/usr/lib`（dlopen/mmap 只需 `execute`，不需要 `execute_no_trans`）——PoC-3 会验证这一点（Termux bash 是动态链接的）。
