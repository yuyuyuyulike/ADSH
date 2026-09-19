echo "--- poc4 start ---"
D=/storage/emulated/0/adsh-poc4
echo "list-Download:"; "$PREFIX/bin/ls" /storage/emulated/0/Download 2>&1 | "$PREFIX/bin/head" -3
"$PREFIX/bin/mkdir" -p "$D" && echo created
echo "hello-adsh" > "$D/hello.txt" && echo wrote
"$PREFIX/bin/cat" "$D/hello.txt"
"$PREFIX/bin/mv" "$D/hello.txt" "$D/hello2.txt" && echo moved
"$PREFIX/bin/cp" "$D/hello2.txt" "$D/hello3.txt" && echo copied
"$PREFIX/bin/ls" -la "$D" 2>&1 | "$PREFIX/bin/head" -5
"$PREFIX/bin/rm" -rf "$D" && echo removed
echo "--- poc4 done ---"
