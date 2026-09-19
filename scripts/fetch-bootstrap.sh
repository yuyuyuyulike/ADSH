#!/usr/bin/env bash
# 下载 Termux bootstrap（构建期联网一次），校验 SHA-256 后作为 APK asset 存放。
# 运行期零下载：App 只从 asset 解压。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/assets-src/bootstrap"
ASSETS="$ROOT/app/src/main/assets/bootstrap"
TAG="bootstrap-2026.09.13-r1+apt.android-7"
FILE="bootstrap-aarch64.zip"
SHA256="dbf2805613ff2ace0b233c3b349e080bb0ff358f4ad64f4cca5966b27b93e7ee"
URL="https://github.com/termux/termux-packages/releases/download/${TAG}/${FILE}"

mkdir -p "$CACHE" "$ASSETS"

if [ ! -f "$CACHE/$FILE" ]; then
  echo "downloading $FILE ..."
  curl -fL --retry 3 -o "$CACHE/$FILE" "$URL"
fi

echo "$SHA256  $CACHE/$FILE" | sha256sum -c -

install -m 0644 "$CACHE/$FILE" "$ASSETS/usr.zip"

echo "bootstrap ready: $ASSETS/usr.zip ($(stat -c %s "$ASSETS/usr.zip") bytes)"
echo "$TAG" > "$CACHE/VERSION"
