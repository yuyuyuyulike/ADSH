#!/usr/bin/env python3
"""搬运式重构的等价性检查：把「旧文件（某个提交里）」与「新文件集合（工作区）」做代码行多重集比对。

为什么要有它：R24–R33 每一刀都用同一条口径验证「只搬不改」——把两边的**代码行**（去注释、去空白、
逐行 trim 后计数）做多重集比较，然后把差异逐条解释掉。口径固定了，验证就不该每次手写一遍
（审计 §5 的那条教训：改完的等价性要能用一条命令重放）。

怎么读结果：
  * **只在旧侧** = 被改写或删掉的写法（例如 \`it.\` → \`current.\`、\`_state.update { it.copy(\` 包裹消失、
    局部量改名、\`private fun\` → \`internal fun\`）。纯搬运时这里应该只剩"函数头 / 返回 / 调用点"。
  * **只在新侧** = 新函数签名、新调用点、新 import、以及上面那些改写的另一半。
  * 两边都没出现的差异 = 一行不差；出现**第三种**东西（逻辑行凭空多出或少掉）说明搬运改了语义。

用法：
  python scripts/check-move.py --old app/src/main/java/.../AgentLoop.kt \
      --new app/src/main/java/.../AgentLoop.kt app/src/main/java/.../TurnMeter.kt
  # 也可以用一条命令当门禁用（超出允许条数就非零退出）：
  python scripts/check-move.py --old A.kt --new A.kt B.kt --allow-old 1 --allow-new 20

选项：
  --rev REV     旧文件取自哪个提交（默认 HEAD）
  --allow-old N 允许「只在旧侧」的行数上限（默认不限制）
  --allow-new N 允许「只在新侧」的行数上限（默认不限制）
  --quiet       只打印汇总行
"""
import argparse
import collections
import re
import subprocess
import sys


def code_lines(text):
    """代码行多重集：去掉空行与注释行，行内空白折叠成一个空格。"""
    out = []
    for line in text.split(chr(10)):
        t = line.strip()
        if not t or t.startswith("//") or t.startswith("*") or t.startswith("/*") or t.startswith("*/"):
            continue
        out.append(re.sub(r"\s+", " ", t))
    return collections.Counter(out)


def comment_lines(text):
    out = []
    for line in text.split(chr(10)):
        t = line.strip()
        if t.startswith("//") or t.startswith("*") or t.startswith("/*") or t.startswith("*/"):
            out.append(re.sub(r"\s+", " ", t))
    return collections.Counter(out)


def read_old(path, rev):
    r = subprocess.run(["git", "show", rev + ":" + path], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("读不到 " + rev + ":" + path + " —— " + r.stderr.strip())
    return r.stdout


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--old", required=True, help="旧文件（工作区相对路径）")
    ap.add_argument("--new", required=True, nargs="+", help="新文件集合（可以多个）")
    ap.add_argument("--rev", default="HEAD")
    ap.add_argument("--allow-old", type=int, default=None)
    ap.add_argument("--allow-new", type=int, default=None)
    ap.add_argument("--quiet", action="store_true")
    a = ap.parse_args()

    old_text = read_old(a.old, a.rev)
    new_text = ""
    for p in a.new:
        with open(p, encoding="utf-8") as f:
            new_text += f.read() + chr(10)
    old, new = code_lines(old_text), code_lines(new_text)
    only_old, only_new = old - new, new - old

    if not a.quiet:
        print("旧（%s:%s）代码行 %d 行；新（%s）代码行 %d 行"
              % (a.rev, a.old, sum(old.values()), " + ".join(a.new), sum(new.values())))
        print("== 只在旧侧（%d 种 / %d 行）" % (len(only_old), sum(only_old.values())))
        for k, v in sorted(only_old.items()):
            print("  -%dx %s" % (v, k[:120]))
        print("== 只在新侧（%d 种 / %d 行）" % (len(only_new), sum(only_new.values())))
        for k, v in sorted(only_new.items()):
            print("  +%dx %s" % (v, k[:120]))
        oc, nc = comment_lines(old_text), comment_lines(new_text)
        print("（注释行：旧 %d → 新 %d）" % (sum(oc.values()), sum(nc.values())))
    print("SUMMARY only_old=%d only_new=%d" % (sum(only_old.values()), sum(only_new.values())))

    bad = False
    if a.allow_old is not None and sum(only_old.values()) > a.allow_old:
        print("!! 只在旧侧超出允许的 %d 行" % a.allow_old); bad = True
    if a.allow_new is not None and sum(only_new.values()) > a.allow_new:
        print("!! 只在新侧超出允许的 %d 行" % a.allow_new); bad = True
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
