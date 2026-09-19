# PoC-3 探针：只用 bash 内建，避免误用 app 私有目录里的外部命令
echo "preload-first=${LD_PRELOAD%%:*}"
IFS= read -r c < /proc/self/attr/current
echo "ctx=$c"
n=0
while IFS= read -r l; do case "$l" in *termux-exec*) n=$((n+1)) ;; esac; done < /proc/self/maps
echo "maps-termux=$n"
"$PREFIX/bin/ls" -d "$PREFIX/bin" 2>&1; echo "ls-exit=$?"
"$PREFIX/bin/sed" --version 2>&1; echo "sed-exit=$?"
