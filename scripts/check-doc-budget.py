#!/usr/bin/env python3
"""文档行数门禁：一个事实只有一个家，超预算即失败。

用法：python scripts/check-doc-budget.py [--list]
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 文件 -> 行数上限。改上限要在这里改，不要在文档里写「约」。
BUDGETS = {
    "AGENTS.md": 80,
    "HANDOFF.md": 100,
    "docs/ARCHITECTURE.md": 150,
    "docs/TECH-STACK.md": 120,
    "docs/MODULES.md": 120,
    "docs/LESSONS.md": 340,
}


def main() -> int:
    if "--list" in sys.argv:
        for path, limit in BUDGETS.items():
            print(f"{limit:>4}  {path}")
        return 0

    failures = []
    print(f"{'lines':>5} {'max':>5}  {'':<4} file")
    for path, limit in BUDGETS.items():
        target = ROOT / path
        if not target.is_file():
            failures.append(f"{path}: 文件不存在（预算表里列了就必须有）")
            print(f"{'-':>5} {limit:>5}  MISS {path}")
            continue
        count = len(target.read_text(encoding="utf-8").splitlines())
        ok = count <= limit
        print(f"{count:>5} {limit:>5}  {'ok' if ok else 'OVER'} {path}")
        if not ok:
            failures.append(f"{path}: {count} 行 > 上限 {limit}")

    if failures:
        print()
        print("超预算：")
        for f in failures:
            print("  - " + f)
        return 1
    print()
    print("全部在预算内。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
