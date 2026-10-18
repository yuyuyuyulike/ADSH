#!/usr/bin/env python3
"""找出没被用到的 Kotlin import。

为什么要有它：scripts/deadcode.py 管的是**声明**，import 不在它的判据里 —— 删掉一段代码之后
留下的空 import 谁都不会拦（Kotlin 编译器只给 warning，R8 也不会因此失败）。第 216 轮清死代码时
一次翻出 3 处（`ChatDecisions.kt` 的 getValue/setValue、`PtcChannelTest.kt` 的 assertFalse）。

判据：用 kt_source 把**注释与字符串正文**都抹掉之后再搜这个名字。字符串不算引用 —— 否则一句
日志文案就能让空 import 永远躲过扫描；`$name` 模板引用保留，那是真引用。

运算符 / 委托名（getValue / setValue / invoke / plus / componentN …）是例外：它们的 import 是给
`by` 与运算符糖提供语义的，正文里本来就不会出现 —— 直接跳过，别报成没用。

用法：
  python scripts/find-unused-imports.py                  # 列出全部（所有模块的 src/main + src/test）
  python scripts/find-unused-imports.py --root app/src/test/java
  python scripts/find-unused-imports.py --fail-on-found  # 当门禁用（有就退出码 1）
"""
import argparse
import io
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kt_source import strip_comments_and_strings  # noqa: E402

IMPORT_RE = re.compile(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$", re.M)

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SKIP_DIRS = {".git", "build", ".gradle", ".idea", ".kotlin", ".cxx", "node_modules"}


def default_roots(repo_root):
    """所有模块的 src/main 与 src/test —— 切了模块之后不能再只看 :app/src。

    判据是目录结构（<模块>/src/<变体>），与 scripts/deadcode.py 的 source_dirs 同一口径。
    """
    out = []
    for base_dir, dirs, _files in os.walk(repo_root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith(".")]
        rel = os.path.relpath(base_dir, repo_root).replace("\\", "/")
        parts = rel.split("/")
        if len(parts) >= 2 and parts[-2] == "src" and parts[-1] in ("main", "test"):
            out.append(rel)
    return sorted(out)

# 运算符 / 委托名：import 它们是为了让 `by` 或运算符糖有语义，正文里不会出现名字
OPERATOR_NAMES = {
    "getValue", "setValue", "provideDelegate", "invoke", "contains", "iterator",
    "plus", "minus", "times", "div", "rem", "rangeTo", "compareTo", "unaryMinus",
    "unaryPlus", "inc", "dec", "get", "set",
}


def unused_imports(text):
    """返回 [(import 路径, 简单名)]：删掉 import 行之后正文里再也搜不到的那些。"""
    found = IMPORT_RE.findall(text)
    body = IMPORT_RE.sub("", text)
    code = strip_comments_and_strings(body)
    out = []
    for path, alias in found:
        name = alias or path.split(".")[-1]
        if name in OPERATOR_NAMES or name == "*":
            continue
        if not re.search(r"\b" + re.escape(name) + r"\b", code):
            out.append((path, name))
    return out


def main():
    parser = argparse.ArgumentParser(description="Kotlin 未使用 import 扫描")
    parser.add_argument("--root", action="append", default=None,
                        help="源码根，可重复；默认所有模块的 src/main 与 src/test")
    parser.add_argument("--fail-on-found", action="store_true", help="有未使用 import 时退出码 1")
    args = parser.parse_args()
    roots = args.root or default_roots(REPO)
    total = 0
    for root in roots:
        for base, dirs, files in os.walk(root):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith(".")]
            for name in sorted(files):
                if not name.endswith(".kt"):
                    continue
                path = os.path.join(base, name)
                for import_path, simple in unused_imports(io.open(path, encoding="utf-8").read()):
                    print("%s: %s (import %s)" % (path.replace(os.sep, "/"), simple, import_path))
                    total += 1
    print("未使用 import %d 处" % total)
    if args.fail_on_found and total:
        sys.exit(1)


if __name__ == "__main__":
    main()
