# ADSH：/tmp 重定向未实现

- 报告时间：2026-09-19
- 环境：Android 16 / Kernel 6.6.118，Termux 0.119.0（DSH 定制构建，targetSdk=37）
- 相关变量：ADSH_TMP_REDIRECT=/data/data/com.termux/files/usr/tmp
- 相关提示：系统提示中向 agent 声明「/tmp/... 会被重定向到 $TMPDIR」

---

## 1. 结论

**/tmp 的重定向没有生效，读写全都失败。**

真实 /tmp 是 Android 系统目录（属主 shell:shell，权限 drwxrwx--x），当前 app 用户无写权限。ADSH_TMP_REDIRECT 虽已导出，但路径映射层并未把 /tmp 下的访问映射到 $TMPDIR，因此任何走 /tmp 的操作都直接撞上系统权限。

---

## 2. 复现与证据

### 2.1 Shell 层（写）

    touch /tmp/x                    -> touch: cannot touch '/tmp/x': Permission denied
    echo hi > /tmp/adsh_probe.txt   -> bash: /tmp/adsh_probe.txt: Permission denied
    mktemp /tmp/adsh.XXXX           -> mktemp: failed to create file via template: Permission denied

### 2.2 各类运行时（写）

    python3 -c "open('/tmp/x','w')"      -> PermissionError: [Errno 13] Permission denied
    node -e "fs.writeFileSync('/tmp/x')" -> Error: EACCES: permission denied, open '/tmp/x'
    curl -s -o /tmp/x https://...        -> 失败
    pip download --dest /tmp/pipprobe six -> PermissionError: [Errno 13] '/tmp/pipprobe'

### 2.3 读路径同样不映射

先把文件真实写入 $TMPDIR，再通过 /tmp 去读：

    echo hi > "$TMPDIR/redir_probe.txt"    # 成功
    cat /tmp/redir_probe.txt               -> No such file or directory
    ls  /tmp/redir_probe.txt               -> No such file or directory

说明不是「只挡写」，而是 /tmp 这条路径根本没有被映射。

### 2.4 文件工具层

通过文件工具写 /tmp 也被沙箱拒绝：

    [sandbox: file access denied under workspace-write mode]

---

## 3. 影响

1. **与提示文案矛盾，会主动诱导 agent 犯错。** 系统提示明确说 /tmp 会重定向到 $TMPDIR，agent 会据此放心地把临时文件写到 /tmp，然后在运行期失败。本次会话中我本人已在两处踩坑（pip 的 --dest、dev server 日志重定向）。
2. **硬编码 /tmp 的第三方工具全线失效。** 大量工具、构建脚本、测试框架不读 TMPDIR 而直接拼 /tmp，这类失败往往报错信息含糊、排查成本高。
3. 当前唯一可用路径是 $TMPDIR（= $PREFIX/tmp，f2fs，正常可读写）；TMPDIR/TMP/TEMP 也已正确导出。

---

## 4. 期望行为

ADSH_TMP_REDIRECT 生效后，以下两组路径应等价：

    /tmp/<name>                          
    $TMPDIR/<name>

即 /tmp 下的访问被透明映射到 $TMPDIR，且覆盖读、写、创建、删除、stat、列目录等常见操作。

---

## 5. 建议

**方案 A（推荐）：把映射做完整**

- 覆盖 open / openat / creat / unlink / mkdir / rename / stat / lstat / access / opendir 等入口，而不只是写路径。
- 一并覆盖文件工具层（当前 write 到 /tmp 被沙箱直接拒绝），使工具层与 shell 层行为一致。
- 注意符号链接与相对路径场景，避免映射后路径穿越。

**方案 B（成本最低）：修正提示文案**

- 若短期内不打算实现映射，请把系统提示里「/tmp 会被重定向到 $TMPDIR」改为「/tmp 不可写，请一律使用 $TMPDIR」。至少消除对 agent 的错误引导。

---

## 6. 最小复现脚本

    #!/data/data/com.termux/files/usr/bin/bash
    echo "ADSH_TMP_REDIRECT=$ADSH_TMP_REDIRECT"
    echo "TMPDIR=$TMPDIR"

    echo hi > "$TMPDIR/probe.txt" && echo "[基线] 写 TMPDIR 成功"

    touch /tmp/probe.txt 2>&1         && echo "[失败] touch /tmp 竟然成功" || echo "[复现] touch /tmp 被拒"
    cat   /tmp/probe.txt 2>&1         && echo "[失败] cat /tmp 竟然成功"   || echo "[复现] cat /tmp 读不到"
