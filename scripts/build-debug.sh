#!/usr/bin/env bash
# WSL / Git-Bash 下调用 Windows 侧 Gradle（Android SDK 是 Windows 路径，必须用 Windows JVM 构建）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WIN_ROOT="$(wslpath -w "$ROOT")"
exec /mnt/c/Windows/System32/cmd.exe /c "${WIN_ROOT}\\scripts\\build-debug.bat" "$@"
