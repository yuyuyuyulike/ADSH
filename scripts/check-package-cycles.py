#!/usr/bin/env python3
"""包循环与分层门禁：报出 com.adsh.app 各包之间的强连通分量（真环），并按 scripts/layering-rules.json
拒绝禁止的依赖方向。

用法：
    python scripts/check-package-cycles.py                 # 只报告当前有几个环、几处分层违规
    python scripts/check-package-cycles.py --strict        # 0 环 + 0 分层违规，否则失败（统一门禁用这个）
    python scripts/check-package-cycles.py --baseline scripts/package-cycles-baseline.json
    python scripts/check-package-cycles.py --layering scripts/layering-rules.json
    python scripts/check-package-cycles.py --selftest      # 自检（注释/内联/同包/通配前缀等反例）
    python scripts/check-package-cycles.py --root <目录>    # 换扫描根（对答案 / 测归档快照用）

口径：
  - 只扫 src/main 下的 .kt（测试代码不参与，测试里的 import 会造假边）；
  - 先剥注释与字符串（块注释可嵌套，普通串、原始串、字符字面量都要处理），再按行找
    com.adsh.app.* —— 不只看 import 行，内联全限定名同样算依赖；
  - 目标包用「源文件声明的包名」做最长前缀匹配；目标包等于来源包不成边（自环）；
  - R / BuildConfig / MainActivity / AdshApp 是入口与生成代码，忽略。

报告模式只报告、不失败。--strict 是门禁：有环、或有「不在 allow 清单里」的分层违规即失败。
--baseline 只对「新增环」失败，分层违规同样按 allow 判。--strict / --baseline 会自动加载默认规则文件。
allow 用来登记「已经存在、正在拆」的旧边（按文件收窄 + 写明理由）：同一条规则出现在新文件里照样失败。
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE_DEFAULT = "scripts/package-cycles-baseline.json"
LAYERING_DEFAULT = "scripts/layering-rules.json"

APP_PREFIX = "com.adsh.app"
IGNORED_SEGMENTS = {"R", "BuildConfig", "MainActivity", "AdshApp"}

REFERENCE_RE = re.compile(r"com\.adsh\.app(?:\.[A-Za-z_][A-Za-z0-9_]*)+")
PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)", re.MULTILINE)

CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, RAW_STRING, CHAR = range(6)


def strip_non_code(text):
    """把注释与字符串替换成空格（换行保留），只留下真正的代码供正则扫描。"""
    out = []
    i = 0
    n = len(text)
    state = CODE
    depth = 0
    while i < n:
        c = text[i]
        if state == CODE:
            if c == "/" and text.startswith("//", i):
                out.append("  ")
                i += 2
                state = LINE_COMMENT
            elif c == "/" and text.startswith("/*", i):
                out.append("  ")
                i += 2
                state = BLOCK_COMMENT
                depth = 1
            elif text.startswith('"""', i):
                out.append("   ")
                i += 3
                state = RAW_STRING
            elif c == '"':
                out.append(" ")
                i += 1
                state = STRING
            elif c == "'":
                out.append(" ")
                i += 1
                state = CHAR
            else:
                out.append(c)
                i += 1
        elif state == LINE_COMMENT:
            if c == "\n":
                out.append("\n")
                state = CODE
            else:
                out.append(" ")
            i += 1
        elif state == BLOCK_COMMENT:
            if text.startswith("/*", i):
                out.append("  ")
                i += 2
                depth += 1
            elif text.startswith("*/", i):
                out.append("  ")
                i += 2
                depth -= 1
                if depth == 0:
                    state = CODE
            else:
                out.append("\n" if c == "\n" else " ")
                i += 1
        elif state == RAW_STRING:
            if text.startswith('"""', i):
                out.append("   ")
                i += 3
                state = CODE
            else:
                out.append("\n" if c == "\n" else " ")
                i += 1
        elif state == STRING:
            if c == "\\":
                out.append("  ")
                i += 2
            elif c == '"':
                out.append(" ")
                i += 1
                state = CODE
            elif c == "\n":
                out.append("\n")
                i += 1
                state = CODE
            else:
                out.append(" ")
                i += 1
        else:  # CHAR
            if c == "\\":
                out.append("  ")
                i += 2
            elif c == "'":
                out.append(" ")
                i += 1
                state = CODE
            elif c == "\n":
                out.append("\n")
                i += 1
                state = CODE
            else:
                out.append(" ")
                i += 1
    return "".join(out)


def declared_package(stripped):
    m = PACKAGE_RE.search(stripped)
    return m.group(1) if m else None


def iter_references_with_lines(stripped):
    """(全限定名, 行号)。按行扫：com.adsh.app.* 不会跨行，行号直接来得。"""
    for lineno, line in enumerate(stripped.split("\n"), 1):
        for m in REFERENCE_RE.finditer(line):
            yield m.group(0), lineno


def resolve_package(token, packages_by_length):
    """最长前缀匹配：token 落在哪个已声明的包上。"""
    for pkg in packages_by_length:
        if token == pkg or token.startswith(pkg + "."):
            return pkg
    return None


def app_relative(pkg):
    """规则里的包名相对 com.adsh.app 写：core.agent 就是 "core.agent"。"""
    if pkg == APP_PREFIX:
        return ""
    if pkg.startswith(APP_PREFIX + "."):
        return pkg[len(APP_PREFIX) + 1:]
    return pkg


def pattern_matches(pattern, pkg):
    """包段前缀匹配："core.*" 命中 core 与 core.agent，不命中 corex。"""
    base = pattern[:-2] if pattern.endswith(".*") else pattern
    return pkg == base or pkg.startswith(base + ".")


def shared_scan(files):
    """共用一次剥注释：返回 (剥好的文本, 文件->包, 包按长度降序)。"""
    stripped = {name: strip_non_code(text) for name, text in files.items()}
    file_package = {}
    packages = set()
    for name, text in stripped.items():
        pkg = declared_package(text)
        file_package[name] = pkg
        if pkg:
            packages.add(pkg)
    return stripped, file_package, sorted(packages, key=len, reverse=True)


def edge_target(token, source, by_length):
    """一条引用算不算「到别的包的边」；不算就返回 None。"""
    target = resolve_package(token, by_length)
    if target is None or target == source:
        return None
    remainder = token[len(target):].lstrip(".").split(".")
    if any(seg in IGNORED_SEGMENTS for seg in remainder):
        return None
    return target


def build_graph(files):
    """files: {名字: 源码文本} -> {包: {包}}。"""
    stripped, file_package, by_length = shared_scan(files)
    graph = {pkg: set() for pkg in by_length}
    for name, text in stripped.items():
        source = file_package[name]
        if source is None:
            continue
        for token, _lineno in iter_references_with_lines(text):
            target = edge_target(token, source, by_length)
            if target is not None:
                graph[source].add(target)
    return graph


def load_layering(path):
    """规则文件 -> (forbid 规则, allow 已知豁免)。allow 只压「已经存在、正在拆」的旧边，
    且按文件收窄：同一条规则的**新文件**照样失败。"""
    data = json.loads(Path(path).read_text(encoding="utf-8"))

    def entries(key):
        out = []
        for i, raw in enumerate(data.get(key, []), 1):
            if "from" not in raw or "to" not in raw:
                raise ValueError(f"{path}: {key} 第 {i} 条缺 from / to")
            targets = raw["to"]
            if isinstance(targets, str):
                targets = [targets]
            out.append({
                "from": raw["from"],
                "to": list(targets),
                "why": raw.get("why", ""),
                "files": set(raw.get("files", [])),
            })
        return out

    return entries("forbid"), entries("allow")


def allowed_by(violation, allow):
    """这条违规是不是「已知豁免」：规则对得上、且文件在豁免清单里。"""
    source_rel = app_relative(violation["source"])
    target_rel = app_relative(violation["target"])
    for entry in allow:
        if not pattern_matches(entry["from"], source_rel):
            continue
        if not any(pattern_matches(t, target_rel) for t in entry["to"]):
            continue
        if entry["files"] and violation["file"] not in entry["files"]:
            continue
        return entry
    return None


def find_layering_violations(files, rules):
    """files + 规则 -> 违规列表（带规则、文件、行号、原始那行）。"""
    stripped, file_package, by_length = shared_scan(files)
    violations = []
    seen = set()
    for name, text in stripped.items():
        source = file_package[name]
        if source is None:
            continue
        source_rel = app_relative(source)
        raw_lines = files[name].split("\n")
        for token, lineno in iter_references_with_lines(text):
            target = edge_target(token, source, by_length)
            if target is None:
                continue
            target_rel = app_relative(target)
            for rule in rules:
                if not pattern_matches(rule["from"], source_rel):
                    continue
                hit = next((t for t in rule["to"] if pattern_matches(t, target_rel)), None)
                if hit is None:
                    continue
                key = (name, lineno, target, rule["from"], hit)
                if key in seen:
                    break
                seen.add(key)
                line_text = raw_lines[lineno - 1].strip() if lineno - 1 < len(raw_lines) else ""
                violations.append({
                    "rule_from": rule["from"],
                    "rule_to": hit,
                    "why": rule["why"],
                    "source": source,
                    "target": target,
                    "file": name,
                    "line": lineno,
                    "text": line_text[:140],
                })
                break
    return sorted(violations, key=lambda v: (v["rule_from"], v["file"], v["line"]))


def tarjan(graph):
    """返回所有 size > 1 的强连通分量；每个分量内的成员按字母序排。"""
    index = {}
    low = {}
    on_stack = {}
    stack = []
    counter = [0]
    components = []

    def strongconnect(node):
        index[node] = low[node] = counter[0]
        counter[0] += 1
        stack.append(node)
        on_stack[node] = True
        for nxt in graph.get(node, ()):
            if nxt not in index:
                strongconnect(nxt)
                low[node] = min(low[node], low[nxt])
            elif on_stack.get(nxt):
                low[node] = min(low[node], index[nxt])
        if low[node] == index[node]:
            component = []
            while True:
                popped = stack.pop()
                on_stack[popped] = False
                component.append(popped)
                if popped == node:
                    break
            if len(component) > 1:
                components.append(sorted(component))

    for node in sorted(graph):
        if node not in index:
            strongconnect(node)
    return sorted(components, key=lambda c: c[0])


# build-logic 的约定插件源码也在 src/main 下，但它不是应用代码，包名不参与依赖图；
# 产物目录（build）与 gradle 缓存同理。所以整棵子树直接跳过。
SKIP_DIRS = {"build", "build-logic", "gradle", ".gradle"}


def collect_files(root):
    files = {}
    for path in sorted(root.rglob("*.kt")):
        parts = path.relative_to(root).parts
        if SKIP_DIRS.intersection(parts) or "src" not in parts or "main" not in parts:
            continue
        files[str(path.relative_to(root)).replace("\\", "/")] = path.read_text(encoding="utf-8")
    return files


def report(cycles, cycles_in_baseline, has_baseline):
    print(f"包循环门禁：发现 {len(cycles)} 个环")
    for i, component in enumerate(cycles, 1):
        if has_baseline:
            mark = "已知" if frozenset(component) in cycles_in_baseline else "**新增**"
            print(f"  [{i}] {len(component)} 个包（{mark}）：")
        else:
            print(f"  [{i}] {len(component)} 个包：")
        for pkg in component:
            print(f"        {pkg}")


def print_violations(items, indent="  "):
    for i, v in enumerate(items[:20], 1):
        print(f"{indent}[{i}] 规则 {v['rule_from']} -> {v['rule_to']}：{v['why']}")
        print(f"{indent}    {v['file']}:{v['line']}")
        print(f"{indent}    {v['source']} -> {v['target']}")
        print(f"{indent}    > {v['text']}")
    if len(items) > 20:
        print(f"{indent}... 还有 {len(items) - 20} 处")


def report_layering(violations, rules, rules_path, allow):
    if not rules:
        print("分层门禁：没加载规则（用 --layering 指定，或让默认文件存在）。")
        return [], []
    known = [v for v in violations if allowed_by(v, allow)]
    fresh = [v for v in violations if not allowed_by(v, allow)]
    print(
        f"分层门禁：{len(rules)} 条规则（{rules_path}），"
        f"新违规 {len(fresh)} 处、已知豁免 {len(known)} 处"
    )
    if fresh:
        print_violations(fresh)
    if known:
        print("  以下是 allow 里已登记、待拆的旧边（不判失败）：")
        print_violations(known, indent="    ")
    return fresh, known


def parse_path_arg(argv, flag, default):
    """--flag <路径>；不给路径（或后面是另一个 --flag）就用 default。返回 (值, 是否出现)。"""
    if flag not in argv:
        return None, False
    idx = argv.index(flag)
    nxt = argv[idx + 1] if idx + 1 < len(argv) else None
    if nxt is None or nxt.startswith("--"):
        return default, True
    return nxt, True


def selftest():
    prefix = "com.adsh.app"
    cases = []

    def case(name, files, expected):
        graph = build_graph(files)
        actual = {f"{a} -> {b}" for a, targets in graph.items() for b in targets}
        cases.append((actual == expected, name, actual, expected))

    case(
        "注释里的全限定名不算依赖",
        {
            "a/A.kt": "package com.adsh.app.a\n"
            "// com.adsh.app.b.Foo 只是注释\n"
            "/*\n com.adsh.app.b.Bar\n*/\n"
            "val x = 1\n",
            "b/B.kt": "package com.adsh.app.b\n",
        },
        set(),
    )
    case(
        "嵌套块注释也不留假边",
        {
            "a/A.kt": "package com.adsh.app.a\n"
            "/* 外 /* com.adsh.app.b.X */ com.adsh.app.b.Y */\n"
            "val x = 1\n",
            "b/B.kt": "package com.adsh.app.b\n",
        },
        set(),
    )
    case(
        "普通串/原始串/字符字面量里的全限定名不算依赖",
        {
            "a/A.kt": 'package com.adsh.app.a\n'
            'val s = "com.adsh.app.b.Foo"\n'
            'val r = """com.adsh.app.b.Foo\n com.adsh.app.b.Bar"""\n'
            "val q = '\"'\n"
            "val real: com.adsh.app.b.Foo = TODO()\n",
            "b/B.kt": "package com.adsh.app.b\nclass Foo\n",
        },
        {f"{prefix}.a -> {prefix}.b"},
    )
    case(
        "内联全限定名算依赖（不只是 import 行）",
        {
            "a/A.kt": "package com.adsh.app.a\n"
            "val x: List<com.adsh.app.b.Foo> = emptyList()\n",
            "b/B.kt": "package com.adsh.app.b\n",
        },
        {f"{prefix}.a -> {prefix}.b"},
    )
    case(
        "同包（含同一文件）里的引用不算边",
        {
            "a/A.kt": "package com.adsh.app.a\n"
            "val x = com.adsh.app.a.Foo\n"
            "val y: com.adsh.app.a.Bar = x\n",
        },
        set(),
    )
    case(
        "忽略 R / BuildConfig / MainActivity / AdshApp",
        {
            "a/A.kt": "package com.adsh.app.a\n"
            "val a = com.adsh.app.R.string.app_name\n"
            "val b = com.adsh.app.BuildConfig.DEBUG\n"
            "val c = com.adsh.app.MainActivity\n"
            "val d = com.adsh.app.AdshApp\n",
            "root/Root.kt": "package com.adsh.app\n",
        },
        set(),
    )
    case(
        "跨包形成真环时能认出来",
        {
            "a/A.kt": "package com.adsh.app.a\n" "val x: com.adsh.app.b.Foo? = null\n",
            "b/B.kt": "package com.adsh.app.b\n" "val y: com.adsh.app.a.Foo? = null\n",
        },
        {f"{prefix}.a -> {prefix}.b", f"{prefix}.b -> {prefix}.a"},
    )

    rules = [
        {"from": "core.*", "to": ["runtime.*"], "why": "core 不认平台实现"},
        {"from": "seam.*", "to": ["core.*", "ui.*", "runtime.*", "island.*"], "why": "缝不许依赖应用代码"},
        {"from": "ui.kit", "to": ["core.*"], "why": "设计系统不许依赖业务层"},
    ]
    layering_cases = []

    def lcase(name, files, expected):
        actual = {
            f'{v["rule_from"]}: {v["source"]} -> {v["target"]} @{v["file"]}:{v["line"]}'
            for v in find_layering_violations(files, rules)
        }
        layering_cases.append((actual == expected, name, actual, expected))

    lcase(
        "分层：注释里的违规名不算",
        {
            "core/A.kt": "package com.adsh.app.core.agent\n"
            "// com.adsh.app.runtime.termux.TermuxRuntime\n"
            "/* com.adsh.app.runtime.termux.TermuxRuntime */\n"
            "val x = 1\n",
            "runtime/R.kt": "package com.adsh.app.runtime.termux\nclass TermuxRuntime\n",
        },
        set(),
    )
    lcase(
        "分层：内联全限定名算违规，且带文件与行号",
        {
            "core/A.kt": "package com.adsh.app.core.agent\n"
            "val x: com.adsh.app.runtime.termux.TermuxRuntime? = null\n",
            "runtime/R.kt": "package com.adsh.app.runtime.termux\nclass TermuxRuntime\n",
        },
        {"core.*: com.adsh.app.core.agent -> com.adsh.app.runtime.termux @core/A.kt:2"},
    )
    lcase(
        "分层：通配只吃包前缀，不认同前缀的兄弟包 corex",
        {
            "corex/A.kt": "package com.adsh.app.corex\n"
            "val x: com.adsh.app.runtime.termux.TermuxRuntime? = null\n",
            "runtime/R.kt": "package com.adsh.app.runtime.termux\nclass TermuxRuntime\n",
        },
        set(),
    )
    lcase(
        "分层：seam 不许依赖 core / ui / runtime / island",
        {
            "seam/A.kt": "package com.adsh.app.seam.ptc\n"
            "val a: com.adsh.app.core.tools.Tool? = null\n"
            "val b: com.adsh.app.ui.ChatItem? = null\n",
            "core/T.kt": "package com.adsh.app.core.tools\nclass Tool\n",
            "ui/U.kt": "package com.adsh.app.ui\nclass ChatItem\n",
        },
        {
            "seam.*: com.adsh.app.seam.ptc -> com.adsh.app.core.tools @seam/A.kt:2",
            "seam.*: com.adsh.app.seam.ptc -> com.adsh.app.ui @seam/A.kt:3",
        },
    )
    lcase(
        "分层：ui.kit 不许依赖 core，ui.panels 不在规则内",
        {
            "ui/kit/A.kt": "package com.adsh.app.ui.kit\n"
            "val a: com.adsh.app.core.data.SettingsStore? = null\n",
            "ui/panels/B.kt": "package com.adsh.app.ui.panels\n"
            "val b: com.adsh.app.core.data.SettingsStore? = null\n",
            "core/D.kt": "package com.adsh.app.core.data\nclass SettingsStore\n",
        },
        {"ui.kit: com.adsh.app.ui.kit -> com.adsh.app.core.data @ui/kit/A.kt:2"},
    )

    allow_entry = [{
        "from": "core.*",
        "to": ["runtime.*"],
        "why": "已知旧边",
        "files": {"core/Old.kt"},
    }]
    allow_cases = []

    def acase(name, files, expected_known, expected_fresh):
        found = find_layering_violations(files, rules)
        known = [v for v in found if allowed_by(v, allow_entry)]
        fresh = [v for v in found if not allowed_by(v, allow_entry)]
        ok = len(known) == expected_known and len(fresh) == expected_fresh
        allow_cases.append((
            ok,
            name,
            f"已知 {len(known)} / 新 {len(fresh)}",
            f"已知 {expected_known} / 新 {expected_fresh}",
        ))

    acase(
        "分层 allow：清单里的文件算已知，同规则的新文件照样算新违规",
        {
            "core/Old.kt": "package com.adsh.app.core.agent\n"
            "val a: com.adsh.app.runtime.termux.TermuxRuntime? = null\n",
            "core/New.kt": "package com.adsh.app.core.agent\n"
            "val b: com.adsh.app.runtime.termux.TermuxRuntime? = null\n",
            "runtime/R.kt": "package com.adsh.app.runtime.termux\nclass TermuxRuntime\n",
        },
        1,
        1,
    )

    failed = 0
    total = 0
    for group in (cases, layering_cases, allow_cases):
        for ok, name, actual, expected in group:
            total += 1
            print(f"{'ok  ' if ok else 'FAIL'}  {name}")
            if not ok:
                failed += 1
                print(f"      实际: {sorted(actual)}")
                print(f"      期望: {sorted(expected)}")
    print(f"自检：{total - failed}/{total} 通过")
    return 1 if failed else 0


def main():
    argv = sys.argv[1:]
    if "--selftest" in argv:
        return selftest()

    strict = "--strict" in argv
    baseline_path, has_baseline = parse_path_arg(argv, "--baseline", BASELINE_DEFAULT)
    layering_path, has_layering = parse_path_arg(argv, "--layering", LAYERING_DEFAULT)

    scan_root = ROOT
    if "--root" in argv:
        idx = argv.index("--root")
        if idx + 1 < len(argv):
            scan_root = Path(argv[idx + 1]).resolve()

    files = collect_files(scan_root)
    graph = build_graph(files)
    cycles = tarjan(graph)
    edges = sum(len(v) for v in graph.values())
    print(f"扫描 {len(files)} 个文件 / {len(graph)} 个包 / {edges} 条包依赖")

    cycles_in_baseline = set()
    if has_baseline:
        data = json.loads((ROOT / baseline_path).read_text(encoding="utf-8"))
        cycles_in_baseline = {frozenset(c) for c in data.get("cycles", [])}

    report(cycles, cycles_in_baseline, has_baseline)

    # 分层规则：显式 --layering 就必须存在；否则门禁模式（--strict / --baseline）自动挂默认文件。
    rules = []
    allow = []
    loaded_path = None
    if has_layering:
        target = ROOT / layering_path
        if not target.is_file():
            print(f"分层规则文件不存在：{layering_path}")
            return 2
        rules, allow = load_layering(target)
        loaded_path = layering_path
    else:
        # 没显式指定就用默认规则文件；没有就跳过（报告模式不因此失败）。
        target = ROOT / LAYERING_DEFAULT
        if target.is_file():
            rules, allow = load_layering(target)
            loaded_path = LAYERING_DEFAULT

    violations = find_layering_violations(files, rules) if rules else []
    fresh, _known = report_layering(violations, rules, loaded_path if loaded_path else "(未加载)", allow)

    if strict:
        problems = []
        if cycles:
            problems.append(f"{len(cycles)} 个环")
        if fresh:
            problems.append(f"{len(fresh)} 处分层新违规")
        if problems:
            print("门禁失败（--strict）：" + "、".join(problems) + "。")
            return 1
        detail = "0 个环、0 处分层新违规" if rules else "0 个环（未加载分层规则）"
        print(f"门禁通过（--strict）：{detail}。")
        return 0

    if not has_baseline:
        print("（报告模式：不失败。门禁用 --strict；--baseline 只卡新增环）")
        return 0

    new_cycles = [c for c in cycles if frozenset(c) not in cycles_in_baseline]
    gone = [c for c in cycles_in_baseline if c not in {frozenset(x) for x in cycles}]
    print(f"基线 {len(cycles_in_baseline)} 个环；新增 {len(new_cycles)} 个；已消失 {len(gone)} 个")
    if gone:
        print("已消失：" + "；".join(",".join(sorted(c)) for c in gone))
    if new_cycles:
        print("门禁失败：出现基线之外的新环 —— 不许新增。")
    if fresh:
        print(f"门禁失败：{len(fresh)} 处分层新违规（allow 里登记的旧边不算）。")
    return 1 if (new_cycles or fresh) else 0


if __name__ == "__main__":
    sys.exit(main())
