echo "--- e2e start ---"
echo "PREFIX=$PREFIX"
"$PREFIX/bin/ls" -la "$PREFIX/bin" 2>&1 | "$PREFIX/bin/head" -4
echo "hello-world" | "$PREFIX/bin/sed" "s/world/ADSH/" 2>&1
echo "grep-lines=$("$PREFIX/bin/grep" -c . "$PREFIX/etc/profile" 2>&1)"
"$PREFIX/bin/find" "$PREFIX/etc" -maxdepth 1 -type f 2>&1 | "$PREFIX/bin/head" -3
"$PREFIX/bin/mkdir" -p "$PREFIX/tmp/t1" && echo data > "$PREFIX/tmp/t1/f.txt" && "$PREFIX/bin/cat" "$PREFIX/tmp/t1/f.txt"
"$PREFIX/bin/rm" -rf "$PREFIX/tmp/t1" && echo "rw-remove-ok"
echo "--- e2e done ---"
