#!/usr/bin/env bash
# 随包静态工具的获取与校验（构建期使用，运行期零下载）。
# 目前只需要 ripgrep；后续可扩展 fd / jq。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/execLibs/arm64-v8a"
CACHE="$ROOT/assets-src/tools"
RG_VERSION="15.2.0"
RG_TARBALL="ripgrep-${RG_VERSION}-aarch64-unknown-linux-musl.tar.gz"
RG_URL="https://github.com/BurntSushi/ripgrep/releases/download/${RG_VERSION}/${RG_TARBALL}"
RG_SHA256="800b1e7206afe799dfb5a6901f23147cfaabe0e52210538100f61e86e1740915"

mkdir -p "$DEST" "$CACHE"

if [ ! -f "$CACHE/$RG_TARBALL" ]; then
  echo "downloading $RG_TARBALL ..."
  curl -fsSL -o "$CACHE/$RG_TARBALL" "$RG_URL"
fi

echo "$RG_SHA256  $CACHE/$RG_TARBALL" | sha256sum -c -

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
tar -xzf "$CACHE/$RG_TARBALL" -C "$TMP"
RG_BIN="$(find "$TMP" -type f -name rg | head -1)"
[ -n "$RG_BIN" ] || { echo "rg not found in tarball" >&2; exit 1; }

install -m 0644 "$RG_BIN" "$DEST/librg.so"

echo "installed: $DEST/librg.so ($(stat -c %s "$DEST/librg.so") bytes)"
if command -v readelf >/dev/null 2>&1; then
  echo "--- ELF check (期望：无 INTERP / 无 RUNPATH / 无 NEEDED) ---"
  readelf --program-headers --dynamic "$DEST/librg.so" | grep -E "INTERP|RUNPATH|NEEDED" || echo "(static: none)"
fi
