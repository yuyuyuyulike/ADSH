#!/system/bin/sh
# 用 App 自己的那份环境（逐字照抄 TermuxEnv.shellEnvironment + fenceEnv）跑探针。
#
# 用法（第 183 轮建立，R94）：
#   adb shell "run-as com.termux sh -c 'cat > /data/data/com.termux/files/home/adsh-runner.sh'" < scripts/runas-termux-runner.sh
#   adb shell "run-as com.termux sh -c 'cat > /data/data/com.termux/files/home/adsh-probe.sh'"  < scripts/runas-termux-probe.sh
#   adb shell run-as com.termux sh /data/data/com.termux/files/home/adsh-runner.sh danger-full-access
#   adb shell run-as com.termux sh /data/data/com.termux/files/home/adsh-runner.sh workspace-write
#   # 跑完删掉设备上的两个副本（App 私有目录里不留垃圾）
#
# **run-as 的 SELinux 域是 runas_app**（不是 App 自己的 untrusted_app）：它没有 /storage 访问，
# 所以凡是「写工作区」的判定在这里会失败 —— 那是域限制，不是 App 的缺陷。探针把这类记成 skip。
# 为什么要有这个壳：LD_PRELOAD 必须在 **bash 启动之前**就在环境里 —— termux-exec 是
# 动态链接器在进程启动时加载的，脚本里再 export 对当前这个 bash 进程已经晚了。
MODE="$1"
P=/data/data/com.termux/files/usr
H=/data/data/com.termux/files/home
export PREFIX="$P"
export HOME="$H"
export PATH="$P/bin:/system/bin"
export LD_LIBRARY_PATH="$P/lib"
export TERM=xterm-256color COLORTERM=truecolor LANG=C.UTF-8
export TMPDIR="$P/tmp" TMP="$P/tmp" TEMP="$P/tmp" ADSH_TMP_REDIRECT="$P/tmp"
export ADSH_SCRATCH="$H/scratch"
export SHELL="$P/bin/bash"
export TERMUX_VERSION=0.119.0 TERMUX_MAIN_PACKAGE_FORMAT=debian TERMUX_APP_PACKAGE_MANAGER=apt
export TERMUX_APP__PACKAGE_NAME=com.termux TERMUX_APP__VERSION_NAME=0.119.0 TERMUX_APP__VERSION_CODE=13
export TERMUX_APP__TARGET_SDK=28 TERMUX_APP__UID=10394 TERMUX_APP__PID=$$
export TERMUX_APP__FILES_DIR=/data/user/0/com.termux/files
export TERMUX_APP__DATA_DIR=/data/user/0/com.termux
export TERMUX_APP__LEGACY_DATA_DIR=/data/data/com.termux
export TERMUX_APP__APK_RELEASE=GITHUB TERMUX_APP__IS_DEBUGGABLE_BUILD=1
export ANDROID__BUILD_VERSION_SDK=36
export TERMUX_APP_PID=$$ TERMUX_APK_RELEASE=GITHUB TERMUX_IS_DEBUGGABLE_BUILD=1
export TERMUX__PREFIX="$P" TERMUX__HOME="$H" TERMUX__ROOTFS=/data/data/com.termux/files
export DPKG_ADMINDIR="$P/var/lib/dpkg"
export PWD=/storage/emulated/0/1/App ADSH_WORKSPACE=/storage/emulated/0/1/App
NL=/data/app/~~0Fxzh2fwK4JL5YvpwxS4DQ==/com.termux-UK7saVuZK0xhN3SPs8GOSg==/lib/arm64
export LD_PRELOAD="$NL/libadshfence.so $P/lib/libtermux-exec-ld-preload.so"
export ADSH_FENCE_MODE="$MODE"
if [ "$MODE" = "workspace-write" ]; then
  export ADSH_FENCE_ACTIVE=1
  export ADSH_FENCE_ROOTS="/storage/emulated/0/1/App:$P/tmp:$H/scratch:/data/user/0/com.termux/cache:/data/data/com.termux/cache"
fi
exec "$P/bin/bash" "$H/adsh-probe.sh" "$MODE"
