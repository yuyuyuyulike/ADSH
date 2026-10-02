#!/usr/bin/env bash
# 构建前一次性准备随包资源：execLibs 里的可执行文件 + bootstrap asset + 映射表。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
"$HERE/fetch-tools.sh"          # 静态工具（ripgrep）→ execLibs/librg.so
"$HERE/fetch-bash.sh"           # bash（只有它允许 exec）→ execLibs/libbash.so
"$HERE/fetch-bootstrap.sh"      # Termux bootstrap → assets/bootstrap/usr.zip
"$HERE/prepare-execlibs.py"     # bootstrap bin/* → execLibs/lib*.so + assets/execlibs.map
