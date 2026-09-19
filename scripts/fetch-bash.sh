#!/usr/bin/env bash
# 从 Termux 官方仓库取 bash 的 .deb，只抽出 bin/bash，改名为 libbash.so 放进 execLibs。
# 为什么必须这样：files/usr/bin/bash 在 app 私有目录，SELinux 禁止 exec（见 docs/exec-escape-spike.md）；
# 只有 nativeLibraryDir 下的文件才允许 exec。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/assets-src/termux"
DEST="$ROOT/app/src/main/execLibs/arm64-v8a"
INDEX_URL="https://packages-cf.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages"
BASE_URL="https://packages-cf.termux.dev/apt/termux-main"

mkdir -p "$CACHE" "$DEST"

if [ ! -f "$CACHE/Packages" ]; then
  echo "fetching Packages index ..."
  curl -fsSL --retry 3 -o "$CACHE/Packages" "$INDEX_URL"
fi

python3 - "$CACHE/Packages" bash > "$CACHE/bash.fields" <<'PY'
import re, sys
path, want = sys.argv[1], sys.argv[2]
text = open(path, encoding='utf-8', errors='replace').read()
for block in text.split('\n\n'):
    if not re.search(r'^Package: ' + re.escape(want) + r'$', block, re.M):
        continue
    fn = re.search(r'^Filename: (.+)$', block, re.M)
    sha = re.search(r'^SHA256: (.+)$', block, re.M)
    if fn and sha:
        print(fn.group(1).strip())
        print(sha.group(1).strip())
        break
PY

test -s "$CACHE/bash.fields" || { echo "bash not found in index" >&2; exit 1; }
REL="$(head -1 "$CACHE/bash.fields")"
SHA="$(tail -1 "$CACHE/bash.fields")"
DEB="$(basename "$REL")"

echo "bash deb: $REL"
echo "expected sha256: $SHA"

if [ ! -f "$CACHE/$DEB" ]; then
  curl -fsSL --retry 3 -o "$CACHE/$DEB" "$BASE_URL/$REL"
fi
echo "$SHA  $CACHE/$DEB" | sha256sum -c -

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
dpkg-deb -x "$CACHE/$DEB" "$TMP"

BIN="$TMP/data/data/com.termux/files/usr/bin/bash"
test -x "$BIN" || { echo "bash binary missing in deb" >&2; exit 1; }
install -m 0644 "$BIN" "$DEST/libbash.so"

echo "installed: $DEST/libbash.so ($(stat -c %s "$DEST/libbash.so") bytes)"
echo "--- NEEDED / RUNPATH ---"
readelf --dynamic "$DEST/libbash.so" | grep -E "NEEDED|RUNPATH|RPATH" || true
echo "--- INTERP ---"
readelf --program-headers "$DEST/libbash.so" | grep -i interp || true
