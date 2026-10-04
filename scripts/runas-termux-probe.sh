#!/data/data/com.termux/files/usr/bin/bash
# ADSH 内嵌 Termux 的 run-as 深度探针（第 183 轮，R94）。每个判定都打 ok / FAIL / skip，末尾给合计。
# 配套的 runas-termux-runner.sh 负责把环境摆成 App 的样子（LD_PRELOAD 必须在 bash 启动前就在）。
#
# 覆盖：身份/内核 → 环境事实（前缀/家目录/TMPDIR/预载库）→ bash 本体与链接 → 工具可达性
# （pkg/apt/dpkg/git/python/node/clang/ssh…）→ shell 基本功（管道/重定向/glob/子 shell 退出码/
# 链接/chmod）→ 三种 shebang 脚本直接执行（termux-exec 的关键路径）→ dpkg/apt 数据库 →
# 工作区与 scratch → 写围栏（workspace-write：白名单外必须被拒，标记逐字是 dsh 那两行）→
# App 自己的 adsh-env-check。
MODE="$1"
pass=0; fail=0
ok(){ printf '  ok    %s\n' "$1"; pass=$((pass+1)); }
bad(){ printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
skip(){ printf '  skip  %s\n' "$1"; skipn=$((skipn+1)); }
skipn=0
chk(){ if [ "$1" = 0 ]; then ok "$2"; else bad "$2（rc=$1）"; fi; }

echo "===== A. 身份 / 内核 ====="
id
uname -a
echo "sdk=$(getprop ro.build.version.sdk) model=$(getprop ro.product.model) abi=$(getprop ro.product.cpu.abi)"
echo "selinux=$(cat /proc/self/attr/current 2>/dev/null)"

echo
echo "===== B. 环境事实 ====="
for v in PREFIX HOME TMPDIR TERMUX__PREFIX TERMUX_APP__DATA_DIR TERMUX_APP__LEGACY_DATA_DIR TERMUX_VERSION ANDROID__BUILD_VERSION_SDK DPKG_ADMINDIR ADSH_FENCE_MODE ADSH_FENCE_ACTIVE ADSH_FENCE_ROOTS ADSH_WORKSPACE ADSH_SCRATCH PATH; do
  printf '  %-30s %s\n' "$v" "$(printenv "$v" 2>/dev/null || echo '（未设置）')"
done
echo "  LD_PRELOAD:"
echo "$LD_PRELOAD" | tr ' ' '\n' | sed 's/^/    /'
echo "  blibc/termux-exec 加载情况（/proc/self/maps）:"
grep -oE '(libadshfence|libtermux-exec[a-z-]*).so' /proc/self/maps 2>/dev/null | sort -u | sed 's/^/    /'

echo
echo "===== C. bash 本体 ====="
echo "  bash        $(bash --version | head -1)"
echo "  \$BASH       $BASH"
echo "  \$0          $0"
echo "  sh 链接     $(ls -l "$PREFIX/bin/sh" | awk '{print $9,$10,$11}')"
ls -l "$PREFIX/bin/bash" | sed 's/^/  bash 链接   /'
[ -x /system/bin/linker64 ] && ok "linker64 在位" || bad "linker64 不在位"

echo
echo "===== D. 工具可达性 ====="
for c in pkg apt apt-get dpkg git python3 node npm rg curl wget jq tar clang make cmake ssh termux-info termux-open login; do
  p=$(command -v "$c" 2>/dev/null)
  printf '  %-12s %s\n' "$c" "${p:-（没有）}"
done
for c in bash rg git python3 dpkg; do
  if command -v "$c" >/dev/null 2>&1; then
    v=$("$c" --version 2>&1 | head -1)
    printf '  %-12s %s\n' "$c --version" "$v"
  fi
done

echo
echo "===== E. shell 基本功 ====="
n=$(printf 'a\nb\nc\n' | grep b | wc -l); [ "$n" = 1 ] && ok "管道 grep|wc = 1" || bad "管道结果 $n"
cd "$TMPDIR" || bad "cd TMPDIR 失败"
rm -rf adshprobe && mkdir -p adshprobe/sub && ok "mkdir -p 递归建目录"
echo hello > adshprobe/a.txt; echo world > adshprobe/sub/b.txt
s=$(cat adshprobe/a.txt adshprobe/sub/b.txt | tr '\n' ' ')
[ "$s" = "hello world " ] && ok "重定向 + cat 多文件 = '$s'" || bad "重定向结果 '$s'"
glob=$(for f in adshprobe/*.txt; do echo "$f"; done | wc -l)
[ "$glob" = 1 ] && ok "glob 展开 1 个" || bad "glob 展开 $glob 个"
( exit 7 ); rc=$?; [ "$rc" = 7 ] && ok "子 shell 退出码透传 = 7" || bad "退出码 $rc"
if false; then bad "if/else 反了"; else ok "if/else 正常"; fi
ln -s a.txt adshprobe/link.txt && [ "$(cat adshprobe/link.txt)" = hello ] && ok "符号链接可用" || bad "符号链接"
if ln adshprobe/a.txt adshprobe/hard.txt 2>/dev/null; then ok "硬链接可用"; else bad "硬链接失败（FUSE？）"; fi
chmod +x adshprobe/a.txt && ok "chmod +x 生效" || bad "chmod 失败"
touch adshprobe/empty.txt && [ -f adshprobe/empty.txt ] && ok "touch 建空文件" || bad "touch"
echo "  变量/算术: $((6*7)) $( echo "$((2+2))" )"
printf '  printf: %s|%d|%05.1f\n' "字" 42 3.5

echo
echo "===== F. shebang 脚本直接执行（termux-exec 的关键路径）====="
printf '#!/usr/bin/env bash\necho "    bash shebang ok argv0=$0 参数=$*"\n' > adshprobe/s.sh
chmod +x adshprobe/s.sh
./adshprobe/s.sh 甲 乙; chk $? "#!/usr/bin/env bash 脚本直接执行"
printf '#!/bin/sh\necho "    sh shebang ok"\n' > adshprobe/s2.sh; chmod +x adshprobe/s2.sh
./adshprobe/s2.sh; chk $? "#!/bin/sh 脚本直接执行"
printf '#!/data/data/com.termux/files/usr/bin/bash\necho "    abs shebang ok"\n' > adshprobe/s3.sh
chmod +x adshprobe/s3.sh; ./adshprobe/s3.sh; chk $? "绝对路径 shebang 直接执行"

echo
echo "===== G. dpkg / apt（只读检查）====="
if command -v dpkg >/dev/null 2>&1; then
  n=$(dpkg -l 2>/dev/null | wc -l); [ "$n" -gt 5 ] && ok "dpkg -l 列出 $n 行（数据库可读）" || bad "dpkg -l 只有 $n 行"
  dpkg -l 2>/dev/null | awk 'NR>5{print $2}' | head -8 | sed 's/^/    /'
else
  bad "dpkg 不可达"
fi
if command -v apt-get >/dev/null 2>&1; then
  out=$(apt-get -s install --reinstall bash 2>&1 | tail -2); rc=$?
  [ $rc = 0 ] && ok "apt-get -s（模拟安装）能跑：$(echo "$out" | head -1)" || bad "apt-get -s rc=$rc：$out"
  ls "$PREFIX/var/lib/apt/lists" >/dev/null 2>&1 && echo "    apt lists 目录在位"
fi

echo
echo "===== H. 工作区（外部存储）====="
W="$ADSH_WORKSPACE"
if [ -d "$W" ]; then
  ok "工作区可进入：$W"
  ls "$W" | head -5 | sed 's/^/    /'
  if echo probe > "$W/.adsh-runas-probe" 2>/dev/null; then
    ok "工作区内写文件成功"; rm -f "$W/.adsh-runas-probe"
  else
    skip "工作区写：run-as 的 SELinux 域（runas_app）没有外部存储授权 —— App 自身不受限（同一分钟 logcat 里有它的导入记录）"
  fi
else
  skip "工作区不可见：$W（runas_app 域没有 /storage 访问；App 自身可写）"
fi
if [ -n "$ADSH_SCRATCH" ]; then
  mkdir -p "$ADSH_SCRATCH" 2>/dev/null && echo x > "$ADSH_SCRATCH/.probe" 2>/dev/null && { ok "scratch 可写（f2fs）"; rm -f "$ADSH_SCRATCH/.probe"; } || bad "scratch 不可写"
fi

echo
echo "===== I. 围栏（模式 $MODE）====="
echo "  ADSH_FENCE_MODE=$ADSH_FENCE_MODE ACTIVE=${ADSH_FENCE_ACTIVE:-（空）} ROOTS=${ADSH_FENCE_ROOTS:-（空）}"
if [ "$MODE" = "workspace-write" ]; then
  if echo x > "$W/.fence-probe" 2>/dev/null; then ok "白名单内（工作区）写成功"; rm -f "$W/.fence-probe"; else skip "白名单内（工作区）写：runas_app 域进不去 /storage（不是围栏判决）"; fi
  out=$( ( echo nope > "$HOME/fence-outside.txt" ) 2>&1 ); rc=$?
  if [ $rc -ne 0 ]; then ok "白名单外（$HOME）写被拒 rc=$rc"; echo "$out" | head -3 | sed 's/^/    /'; else bad "白名单外写竟然成功"; rm -f "$HOME/fence-outside.txt"; fi
  head -1 /system/etc/hosts >/dev/null 2>&1 && ok "读 /system/etc/hosts 不受影响" || bad "读 /system/etc/hosts 被拒"
  head -c 2 "$PREFIX/bin/bash" >/dev/null 2>&1 && ok "读前缀里的文件不受影响" || bad "读前缀被拒"
  ls "$HOME" >/dev/null 2>&1 && ok "列 HOME 目录不受影响" || bad "列 HOME 被拒"
  if echo tmp > /tmp/adsh-tmp-probe 2>/dev/null; then
    [ -f "$TMPDIR/adsh-tmp-probe" ] && ok "/tmp 映射到 $TMPDIR" || bad "/tmp 写了但没落到 TMPDIR"
    rm -f /tmp/adsh-tmp-probe
  else
    bad "/tmp 写入失败"
  fi
else
  echo x > "$HOME/fence-outside.txt" 2>/dev/null && { ok "完全权限：工作区外（$HOME）可写（预期，围栏不判决）"; rm -f "$HOME/fence-outside.txt"; } || bad "完全权限下 $HOME 仍写不了"
fi

echo
echo "===== J. App 自己的自检脚本 ====="
if [ -x "$PREFIX/bin/adsh-env-check" ]; then
  adsh-env-check 2>&1 | head -26 | sed 's/^/  /'
  chk $? "adsh-env-check 跑通"
else
  echo "  （$PREFIX/bin/adsh-env-check 不在或不可执行）"
fi

echo
echo "===== 结果：ok=$pass fail=$fail skip=$skipn（模式 $MODE）====="
[ "$fail" = 0 ] && echo "全部通过" || echo "有 $fail 条失败，见上面 FAIL 行"
