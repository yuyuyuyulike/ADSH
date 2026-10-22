#!/usr/bin/env bash
# 构建期把 Termux glibc 运行时（官方 deb）并进 assets/bootstrap/usr.zip（运行期零下载）。
#
# 来源：termux-pacman 的 glibc 2.44 aarch64 与 glibc-runner 2.0-3（packages-cf.termux.dev
# 的 termux-glibc 源），按需精选运行期文件：加载器 / libc 族 / C.utf8 & en_US.utf8 /
# 几个排障工具（getent、ldd、locale…）。不搬 include、.a、gconv、share（体积大且运行期不需要）。
#
# 调用方：[prepare-assets.sh] 在 fetch-bootstrap.sh 之后调用（usr.zip 必须先存在）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/assets-src/glibc"
SRC_ZIP="$ROOT/assets-src/bootstrap/bootstrap-aarch64.zip"
OUT_ZIP="$ROOT/app/src/main/assets/bootstrap/usr.zip"
BASE="https://packages-cf.termux.dev/apt/termux-glibc"

GLIBC_FILE="glibc_2.44_aarch64.deb"
GLIBC_SHA="5392d254e84c436e96783bcb6ffffe429d6b24c7ad00490f23f06f66c4769fb6"
RUNNER_FILE="glibc-runner_2.0-3_all.deb"
RUNNER_SHA="0ce36481955702c0f57cb8293bfa2dd8a043e91ae2d6f325c2b53cb6b9268544"

mkdir -p "$CACHE" "$(dirname "$OUT_ZIP")"

dl() { # url file sha256
  [ -f "$CACHE/$2" ] || { echo "downloading $2 ..."; curl -fL --retry 3 -o "$CACHE/$2" "$1"; }
  echo "$3  $CACHE/$2" | sha256sum -c -
}
dl "$BASE/pool/stable/g/glibc/$GLIBC_FILE" "$GLIBC_FILE" "$GLIBC_SHA"
dl "$BASE/pool/stable/g/glibc-runner/$RUNNER_FILE" "$RUNNER_FILE" "$RUNNER_SHA"

python - "$SRC_ZIP" "$OUT_ZIP" "$CACHE/$GLIBC_FILE" "$CACHE/$RUNNER_FILE" <<'PY'
import io, os, sys, tarfile, zipfile

src_zip, out_zip, glibc_deb, runner_deb = sys.argv[1:5]
NAME = "usr"
NL = chr(10)

def deb_data_tar(deb):
    raw = open(deb, "rb").read()
    assert raw[:8] == b"!<arch>" + bytes([10]), deb
    pos, members = 8, {}
    while pos + 60 <= len(raw):
        head = raw[pos:pos + 60]
        name = head[:16].decode().strip().rstrip("/")
        size = int(head[48:58].decode().strip())
        members[name] = raw[pos + 60:pos + 60 + size]
        pos += 60 + size + (size & 1)
    return tarfile.open(fileobj=io.BytesIO(members["data.tar.xz"]), mode="r:xz")

def iter_prefix(tf):
    prefix = "./data/data/com.termux/files/" + NAME + "/"
    for member in tf.getmembers():
        if not member.isfile() or member.issym() or not member.name.startswith(prefix):
            continue
        yield member.name[len(prefix):], tf.extractfile(member).read()

# glibc-runner 的默认入口是 exec ld.so "$@"，但 ADSH 全局导出了指向 bionic 库的
# LD_LIBRARY_PATH（会把 libz/libssl 之类抢先塞给 glibc），上游脚本没有清它 —— 所以这里用
# 等价的 12 行 grun：unset 两个 bionic 变量 + 显式 --library-path。
GRUN = '''#!/data/data/com.termux/files/usr/bin/sh
# grun - run a glibc dynamic binary with the bundled Termux glibc loader.
# External Linux binaries point PT_INTERP at /lib/ld-linux-aarch64.so.1, which does not
# exist on Android; exec them through the bundled loader instead. Both LD_PRELOAD (fence
# shim + termux-exec) and LD_LIBRARY_PATH (ADSH exports bionic libs) must be cleared or
# glibc links against the wrong runtime.
GLIBC_PREFIX=/data/data/com.termux/files/usr/glibc
loader="$GLIBC_PREFIX/lib/ld-linux-aarch64.so.1"
[ -x "$loader" ] || { echo "grun: glibc loader missing: $loader" >&2; exit 1; }
[ $# -ge 1 ] || { echo "usage: grun PROGRAM [ARGS...]" >&2; exit 2; }
libpath="$GLIBC_PREFIX/lib"
if [ -n "$ADSH_GLIBC_LIBS" ]; then libpath="$libpath:$ADSH_GLIBC_LIBS"; fi
unset LD_PRELOAD
unset LD_LIBRARY_PATH
exec "$loader" --library-path "$libpath" "$@"
'''

RUNTIME_BIN = {"getconf", "getent", "iconv", "ldd", "locale"}
ADD = []
# deb 里的路径已经是 usr/glibc/...（glibc 的编译期前缀），zip 里原样保留这一层。
for path, data in iter_prefix(deb_data_tar(glibc_deb)):
    if path.startswith("glibc/lib/") and "/" not in path[len("glibc/lib/"):] and ".so" in os.path.basename(path):
        ADD.append((path, data))
    elif path.startswith("glibc/lib/locale/"):
        ADD.append((path, data))
    elif path.startswith("glibc/etc/"):
        ADD.append((path, data))
    elif path.startswith("glibc/bin/") and os.path.basename(path) in RUNTIME_BIN:
        ADD.append((path, data))

# glibc-runner 的许可证与文档（脚本本体已由 ADSH 等价的 grun 取代，这里只取 NOTICE 素材）。
for path, data in iter_prefix(deb_data_tar(runner_deb)):
    if path in ("share/doc/glibc-runner/LICENSE",):
        ADD.append((path, data))

ADD.append(("bin/grun", GRUN.encode()))

# SYMLINKS.txt 格式："<目标>←./<链接相对路径>"（BootstrapInstaller.createSymlinks 逐行读）。
EXTRA_LINKS = [
    "../lib/ld-linux-aarch64.so.1←./glibc/bin/ld.so",
    "/data/data/com.termux/files/usr/etc/resolv.conf←./glibc/etc/resolv.conf",
    "/data/data/com.termux/files/usr/etc/hosts←./glibc/etc/hosts",
]

with zipfile.ZipFile(src_zip) as zin, zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED, 9) as zout:
    names = set(zin.namelist())
    assert not (names & {n for n, _ in ADD}), "glibc entries collide with bootstrap entries"
    for info in zin.infolist():
        data = zin.read(info.filename)
        if info.filename == "SYMLINKS.txt":
            text = data.decode("utf-8").rstrip(NL) + NL + "".join(line + NL for line in EXTRA_LINKS)
            data = text.encode("utf-8")
        zi = zipfile.ZipInfo(info.filename, date_time=(2026, 1, 1, 0, 0, 0))
        zi.external_attr = info.external_attr
        zi.compress_type = zipfile.ZIP_DEFLATED
        zout.writestr(zi, data)
    for name, data in ADD:
        zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
        zi.external_attr = 0o644 << 16
        zi.compress_type = zipfile.ZIP_DEFLATED
        zout.writestr(zi, data)

print("glibc asset: +%d files, usr.zip -> %d bytes" % (len(ADD), os.path.getsize(out_zip)))
PY

echo "glibc runtime merged: $OUT_ZIP ($(stat -c %s "$OUT_ZIP") bytes)"
