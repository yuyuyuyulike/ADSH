#!/usr/bin/env bash
# 第 64 轮：写围栏 shim（app/src/main/cpp/fence.c）的本地行为自测。
# 把与真机同一份源码编成 Linux .so，LD_PRELOAD 挂进 bash，按模式分组验证判决：
#   A 没有任何围栏变量 = 不判决（第 64 轮的 bug 现场，完全权限就长这样）
#   B workspace-write = 只有白名单内可写（区外拒 + 逐字标记）  C read-only = 任何写都拒
#   D 显式 danger-full-access = 不判决                        E/F 有模式无根 = 失败关闭
#   G bionic 自传播（env -u LD_PRELOAD 摘不掉）  H 非 bionic 不塞 preload  I chmod 不误报
#   J shebang 自愈（第 67 轮）：脚本被拒时改 exec 解释器（自测旋钮 ADSH_SHEBANG_FORCE 复现）
# 用法：bash scripts/fence-selftest.sh     （40 项全过 = 门闸语义正确）
# 坑：测试树必须放在 /tmp **之外**（ADSH_TMP_REDIRECT 会把 /tmp 映射走，放 /tmp 下路径会被 shim 改写）
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T="$(mktemp -d /var/tmp/fence-selftest.XXXXXX)"
FENCE="$T/libadshfence.so"
TMPMAP="$T/tmpmap"
WS="$T/ws"
export T FENCE TMPMAP WS
mkdir -p "$WS" "$T/outside" "$TMPMAP"
cd "$T" || exit 1
gcc -shared -fPIC -O2 -o "$FENCE" "$ROOT/app/src/main/cpp/fence.c" -ldl -lpthread 2> "$T/gcc.log" || { echo "BUILD FAILED"; cat "$T/gcc.log"; exit 1; }
echo "build ok: $(stat -c%s "$FENCE") bytes, gcc warnings $(grep -c warning: "$T/gcc.log")"

unset ADSH_FENCE_MODE ADSH_FENCE_ROOTS ADSH_FENCE_MARK ADSH_SHIM_DIAG LD_PRELOAD ADSH_TMP_REDIRECT

# Linux 上 ELF 的解释器是 ld-linux（不是 bionic 的 linker64），所以要显式告诉 shim
# 「这台机器的 bionic 连接器长什么样」——否则每个子进程都会被判成外部 libc 而摘掉围栏（H 组用得上这条）。
INTERP_SELFTEST="$(readelf -l /bin/sh 2>/dev/null | sed -n 's/.*interpreter: \(.*\)\]/\1/p' | head -1)"
if [ -z "$INTERP_SELFTEST" ]; then INTERP_SELFTEST="/lib64/ld-linux-x86-64.so.2"; fi
export ADSH_BIONIC_LINKER="$INTERP_SELFTEST"

probe() { # probe <label> <expect allow|deny> <env-string> <script>
  local label="$1" expect="$2" envs="$3" body="$4" out rc verdict
  out=$(env $envs LD_PRELOAD="$FENCE" ADSH_TMP_REDIRECT="$TMPMAP" bash -c "$body" 2>&1); rc=$?
  if [ $rc -eq 0 ]; then verdict=allow
  elif printf '%s' "$out" | grep -q '\[sandbox: file access denied'; then verdict=deny
  else verdict=error; fi
  if [ "$verdict" = "$expect" ]; then printf 'PASS  %-44s (%s)\n' "$label" "$verdict"
  else printf 'FAIL  %-44s expected=%s got=%s rc=%s\n      %s\n' "$label" "$expect" "$verdict" "$rc" "$(printf '%s' "$out" | head -2 | tr '\n' '|')"; fi
}

echo "--- A 完全权限：shim 挂着、没有任何围栏变量（第 64 轮的 bug 现场）"
probe "A1 写工作区" allow "" 'echo a > "$WS/a1"'
probe "A2 写工作区外" allow "" 'echo a > "$T/outside/a2"'
probe "A3 删工作区外文件" allow "" 'rm -f "$T/outside/a2"'
probe "A4 mkdir 工作区外" allow "" 'mkdir -p "$T/outside/dir-a4"'
probe "A5 /tmp 映射仍生效" allow "" 'echo a > /tmp/a5'
[ -f "$TMPMAP/a5" ] && echo "PASS  A5b /tmp 落到 ADSH_TMP_REDIRECT" || echo "FAIL  A5b /tmp 没被映射"
probe "A6 诊断里 enabled=0" allow "ADSH_SHIM_DIAG=$T/a6.log" 'true'
grep -q 'enabled=0' "$T/a6.log" 2>/dev/null && echo "PASS  A6b shim 自述 enabled=0" || echo "FAIL  A6b shim 自述没有 enabled=0 ($(cat "$T/a6.log" 2>/dev/null | head -2))"

echo "--- B workspace-write：工作区 + 临时目录可写"
BENV="ADSH_FENCE_MODE=workspace-write ADSH_FENCE_ROOTS=$WS:$TMPMAP"
probe "B1 写工作区" allow "$BENV" 'echo b > "$WS/b1"'
probe "B2 写工作区外" deny "$BENV" 'echo b > "$T/outside/b2"'
probe "B3 删工作区外已有文件" deny "$BENV" 'echo x > "$T/outside/keep"; rm -f "$T/outside/keep"'
probe "B4 rm 工作区内文件" allow "$BENV" 'rm -f "$WS/b1"'
probe "B5 /tmp（映射到白名单内）" allow "$BENV" 'echo b > /tmp/b5'
probe "B6 读被拒目录（读一律放行）" allow "$BENV" 'cat "$T/outside/keep" > /dev/null; true'
probe "B7 拒绝标记 + 提示逐字" allow "$BENV" 'm=$( { echo b > "$T/outside/b7"; } 2>&1 ); case "$m" in *"[sandbox: file access denied under workspace-write mode]"*) ;; *) echo "no marker: $m"; exit 1;; esac; case "$m" in *"[sandbox: escalation available"*) exit 0;; *) echo "no hint: $m"; exit 1;; esac'
probe "B8 诊断里 enabled=1 roots=2" allow "$BENV ADSH_SHIM_DIAG=$T/b8.log" 'true'
grep -q 'enabled=1 roots=2' "$T/b8.log" 2>/dev/null && echo "PASS  B8b shim 自述 enabled=1 roots=2" || echo "FAIL  B8b shim 自述不对 ($(cat "$T/b8.log" 2>/dev/null | head -2))"

echo "--- C read-only：空白名单，命令照跑、写全拒"
CENV="ADSH_FENCE_MODE=read-only ADSH_FENCE_ROOTS="
probe "C1 写工作区" deny "$CENV" 'echo c > "$WS/c1"'
probe "C2 读工作区（先建一个）" allow "" 'echo seed > "$WS/c2"; cat "$WS/c2" > /dev/null'
probe "C3 mkdir" deny "$CENV" 'mkdir -p "$WS/c3"'
probe "C4 拒绝标记的模式名" allow "$CENV" 'm=$( { echo c > "$WS/c4"; } 2>&1 ); case "$m" in *"denied under read-only mode"*) exit 0;; *) echo "got: $m"; exit 1;; esac'

echo "--- D 显式 danger-full-access：不判决"
probe "D1 写工作区外" allow "ADSH_FENCE_MODE=danger-full-access ADSH_FENCE_ROOTS=" 'echo d > "$T/outside/d1"'

echo "--- E 坏配置：workspace-write + 空白名单（App 侧不会这么发，失败关闭）"
probe "E1 写工作区" deny "ADSH_FENCE_MODE=workspace-write ADSH_FENCE_ROOTS=" 'echo e > "$WS/e1"'

echo "--- F 未给白名单时的 read-only（fenceEnv 的形状就是 MODE + 空 ROOTS）"
probe "F1 写工作区" deny "ADSH_FENCE_MODE=read-only" 'echo f > "$WS/f1"'
echo "--- G bionic 自传播：env -u LD_PRELOAD 摘不掉围栏（第 66 轮）"
INTERP="$(readelf -l /bin/sh 2>/dev/null | sed -n 's/.*interpreter: \(.*\)\]/\1/p' | head -1)"
if [ -z "$INTERP" ]; then INTERP="/lib64/ld-linux-x86-64.so.2"; fi
g_env() { # g_env <额外env串> <shell 正文>
  env ADSH_BIONIC_LINKER="$INTERP" $1 LD_PRELOAD="$FENCE" ADSH_TMP_REDIRECT="$TMPMAP" bash -c "$2"
}
rm -f "$T/outside/g1" "$T/outside/g2" "$T/outside/g3"
g_env "ADSH_FENCE_MODE=workspace-write ADSH_FENCE_ROOTS=$WS" 'env -u LD_PRELOAD sh -c "echo x > \"$T/outside/g1\"" 2>/dev/null'
[ -f "$T/outside/g1" ] && echo "FAIL  G1 env -u LD_PRELOAD 的子进程写出去了（绕过成功）" || echo "PASS  G1 env -u LD_PRELOAD 的子进程仍然被拦住"
g_env "ADSH_FENCE_MODE=workspace-write ADSH_FENCE_ROOTS=$WS" 'sh -c "echo x > \"$T/outside/g2\"" 2>/dev/null'
[ -f "$T/outside/g2" ] && echo "FAIL  G2 普通子进程写出去了" || echo "PASS  G2 普通子进程被拦住"
g_env "" 'env -u LD_PRELOAD sh -c "echo x > \"$T/outside/g3\"" 2>/dev/null'
[ -f "$T/outside/g3" ] && echo "PASS  G3 对照：没有约束模式时（完全权限）照样写得出去" || echo "FAIL  G3 完全权限下 env -u 反而写不出去"

echo "--- H 非 bionic（glibc 前缀）不带 bionic preload，bionic 目标照旧"
mkdir -p "$T/glibc"
cp /bin/sh "$T/glibc/sh"
map_hits() { # map_hits <bionic-linker> <target>
  env ADSH_BIONIC_LINKER="$1" ADSH_FENCE_MODE=read-only ADSH_FENCE_ROOTS= LD_PRELOAD="$FENCE" ADSH_TMP_REDIRECT="$TMPMAP" "$2" -c 'grep -c libadshfence /proc/self/maps' 2>/dev/null
}
a=$(map_hits "/system/bin/linker64" "$T/glibc/sh")
b=$(map_hits "$INTERP" "$(command -v sh)")
echo "   /glibc/sh 命中=$a（应 0）  普通路径命中=$b（应 >0）"
if [ "$a" = "0" ]; then echo "PASS  H1 glibc 前缀下的目标不再被塞 bionic preload"; else echo "FAIL  H1 glibc 前缀下仍然挂了 shim（=$a）"; fi
if [ "$b" != "0" ]; then echo "PASS  H2 bionic 目标仍然挂着 shim（=$b）"; else echo "FAIL  H2 普通 bionic 目标的 shim 被摘掉了"; fi

# H3（第 72 轮按真机 diag 补的）：**脚本**的 shebang 里写的是**解释器程序**，不是加载器 ——
# 不能拿「不是 linker64 就算外部 libc」去判它。误判的后果很实：脚本子进程丢掉围栏 shim 与
# termux-exec（真机 diag：`[fixup] … foreign=1 … -> (empty)`），read-only 下脚本照样写得出去。
cat > "$T/h3.sh" <<'H3EOF'
#!/bin/sh
grep -c libadshfence /proc/self/maps
H3EOF
chmod 755 "$T/h3.sh"
c=$(g_env "ADSH_FENCE_MODE=read-only ADSH_FENCE_ROOTS=" "\"$T/h3.sh\"" 2>/dev/null)
echo "   脚本子进程里的 shim 命中=$c（应 >0）"
if [ "${c:-0}" != "0" ]; then echo "PASS  H3 shebang 脚本不再被误判成外部 libc"; else echo "FAIL  H3 脚本子进程丢了围栏 shim（shebang 被当成外部 libc）"; fi

echo "--- I chmod 正常时不许误报（静默改写提示只在真被忽略时出现）"
iout=$(g_env "ADSH_FENCE_MODE=workspace-write ADSH_FENCE_ROOTS=$WS" "touch \"$WS/i1\"; chmod 755 \"$WS/i1\"; stat -c %a \"$WS/i1\" 2>&1" 2>&1)
echo "   结果：$iout"
case "$iout" in *"[fs: chmod"*) echo "FAIL  I1 正常 chmod 也被判成静默改写";; *) echo "PASS  I1 正常 chmod 不报警";; esac
case "$iout" in *755*) echo "PASS  I2 权限位就是 755";; *) echo "FAIL  I2 权限位不是 755";; esac
echo "--- J shebang 自愈（第 67 轮）：内核拒绝脚本 exec 时改走解释器"
# 桌面 Linux 没有安卓那条 W^X（真机上 app 私有目录里的脚本一律 EACCES），所以用 shim 的自测旋钮
# ADSH_SHEBANG_FORCE=1：只对 #! 脚本假装「内核刚拒绝了这次 exec」，ELF 目标不受影响。
j_env() { # j_env <额外env串> <shell 正文>
  env ADSH_BIONIC_LINKER="$INTERP" $1 LD_PRELOAD="$FENCE" ADSH_TMP_REDIRECT="$TMPMAP" bash -c "$2"
}
# 一个只会回显自己 argv 的「解释器」：用来核对 argv 顺序与内核那个可选参数
cat > "$T/jinterp.c" <<'JEOF'
#include <stdio.h>
int main(int argc, char **argv) {
  printf("ARGV");
  for (int i = 0; i < argc; i++) printf(" [%s]", argv[i]);
  printf("\n");
  return 0;
}
JEOF
gcc -O2 -o "$T/jinterp" "$T/jinterp.c" 2>/dev/null

J1="$T/j1.sh"; printf '#!/bin/sh\necho J1-OK "$@"\n' > "$J1"; chmod 755 "$J1"
out=$(j_env "ADSH_SHEBANG_FORCE=1" "\"$J1\" a b" 2>&1)
case "$out" in *"J1-OK a b"*) echo "PASS  J1 被拒的脚本自愈成「解释器 + 脚本」";; *) echo "FAIL  J1 自愈没跑起来：$out";; esac

J2="$T/j2.sh"; printf '#!%s ARG1\nbody\n' "$T/jinterp" > "$J2"; chmod 755 "$J2"
out=$(j_env "ADSH_SHEBANG_FORCE=1" "\"$J2\" x" 2>&1)
case "$out" in *"[ARG1] [$J2] [x]"*) echo "PASS  J2 argv 与内核一致（解释器/可选参数/脚本/原参数）";; *) echo "FAIL  J2 argv 不对：$out";; esac

J3="$T/j3.sh"; printf '#!/bin/sh\necho J3-SHOULD-NOT-RUN\n' > "$J3"; chmod 644 "$J3"
out=$(j_env "ADSH_SHEBANG_FORCE=1" "\"$J3\"" 2>&1); rc=$?
case "$out" in *J3-SHOULD-NOT-RUN*) echo "FAIL  J3 没有可执行位的脚本也被自愈跑了";; *) [ "$rc" -ne 0 ] && echo "PASS  J3 没有可执行位仍然 EACCES（chmod 语义没被绕过）" || echo "FAIL  J3 居然成功（rc=0）";; esac

out=$(j_env "ADSH_SHEBANG_FORCE=1" "$T/jinterp e1" 2>&1)
case "$out" in *"[e1]"*) echo "PASS  J4 旋钮不影响 ELF 目标";; *) echo "FAIL  J4 ELF 目标被旋钮掐死了：$out";; esac

out=$(j_env "" "\"$J1\" z" 2>&1)
case "$out" in *"J1-OK z"*) echo "PASS  J5 不设旋钮时脚本照旧原生执行（无回归）";; *) echo "FAIL  J5 原生脚本执行坏了：$out";; esac

J6I="$T/j6-interp.sh"; printf '#!%s ARG1\n' "$T/jinterp" > "$J6I"; chmod 755 "$J6I"
J6="$T/j6.sh"; printf '#!%s nest\n' "$J6I" > "$J6"; chmod 755 "$J6"
out=$(j_env "ADSH_SHEBANG_FORCE=1" "\"$J6\" y" 2>&1)
case "$out" in *"nest"*) echo "PASS  J6 解释器本身也是脚本时继续兜底（两层）";; *) echo "FAIL  J6 嵌套兜底失败：$out";; esac

J7="$T/j7.sh"; printf '#!%s\n' "$J7" > "$J7"; chmod 755 "$J7"
out=$(timeout 10 env ADSH_BIONIC_LINKER="$INTERP" ADSH_SHEBANG_FORCE=1 LD_PRELOAD="$FENCE" ADSH_TMP_REDIRECT="$TMPMAP" "$J7" 2>&1); rc=$?
[ "$rc" -eq 124 ] && echo "FAIL  J7 自指脚本挂死了（timeout）" || echo "PASS  J7 自指脚本被深度上限挡住（rc=$rc）"

J8="$T/j8.sh"; printf '#!\necho J8-NATIVE-FALLBACK\n' > "$J8"; chmod 755 "$J8"
rm -f "$T/j8.log"
j_env "ADSH_SHEBANG_FORCE=1 ADSH_SHIM_DIAG=$T/j8.log" "\"$J8\"" >/dev/null 2>&1
grep -q '\[shebang\]' "$T/j8.log" 2>/dev/null && echo "FAIL  J8 空解释器也进了自愈" || echo "PASS  J8 空解释器不进自愈（留给内核的 ENOEXEC 路径）"

j_env "ADSH_SHEBANG_FORCE=1 ADSH_SHIM_DIAG=$T/j9.log" "\"$J1\"" >/dev/null 2>&1
grep -q '\[shebang\]' "$T/j9.log" 2>/dev/null && echo "PASS  J9 自愈留下诊断行" || echo "FAIL  J9 诊断里没有 [shebang] 行"

echo "workdir=$T"
