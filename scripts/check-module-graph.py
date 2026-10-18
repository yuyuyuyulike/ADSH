#!/usr/bin/env python3
"""模块依赖图：算 com.adsh.app.* 的包级邻接表、core.* 的安全切割顺序、每个模块要声明的依赖。

用法：
    python scripts/check-module-graph.py            # 打印事实（不写文件、不失败）
    python scripts/check-module-graph.py --write    # 重新生成 docs/module-graph.md
    python scripts/check-module-graph.py --check    # 比对 docs/module-graph.md 新鲜度（过期退 1）
    python scripts/check-module-graph.py --selftest # 反例自检

口径复用 scripts/check-package-cycles.py（同一个剥注释/剥字符串的词法器、同一套最长前缀解析、
同一份忽略名单 R/BuildConfig/MainActivity/AdshApp）—— 直接 import，保证两个门禁不会各扫各的。
core.* 的单元按包的第一段归并（core.agent.worker 与 core.agent 同属一个单元）。

包名不再能推出模块名（:core:tools 收 core.jobs/core.workspace，:core:data 收 core.llm/core.session，
:platform:termux 收 platform.ptc，:seam 收五个
seam 包），所以「包 -> 模块」是下面 PACKAGE_MODULES 里的一张显式表：新包出现时在那里补一行，
不要回退到按名字猜。
"""
import importlib.util
import sys
from pathlib import Path

NL = chr(10)
HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
DOC_PATH = "docs/module-graph.md"

_spec = importlib.util.spec_from_file_location("cpc", str(HERE / "check-package-cycles.py"))
_cpc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_cpc)
strip_non_code = _cpc.strip_non_code
declared_package = _cpc.declared_package
edge_target = _cpc.edge_target
iter_references_with_lines = _cpc.iter_references_with_lines
collect_files = _cpc.collect_files

APP_PREFIX = "com.adsh.app"
CORE_ROOT = "com.adsh.app.core"
CORE_NAMES = ["agent", "data", "jobs", "llm", "phone", "ptc", "session", "tools", "workspace"]
ROOM_MARKERS = ["@Entity", "@Dao", "@Database", "@TypeConverter", "@PrimaryKey", "@ColumnInfo", "@Embedded", "androidx.room"]
# 根包符号：它们是 :app 的入口与生成代码，别的模块引用它就是反向依赖
ROOT_SYMBOLS = ("BuildConfig", "R", "MainActivity", "AdshApp")
APP_MODULE = ":app"

# 包前缀 -> Gradle 模块。合并之后包名不再能推出模块名，这张表是唯一来源。
PACKAGE_MODULES = [
    ("com.adsh.app.core.agent", ":core:agent"),
    ("com.adsh.app.core.data", ":core:data"),
    ("com.adsh.app.core.jobs", ":core:tools"),
    ("com.adsh.app.core.llm", ":core:data"),
    ("com.adsh.app.core.session", ":core:data"),
    ("com.adsh.app.core.tools", ":core:tools"),
    ("com.adsh.app.core.workspace", ":core:tools"),
    ("com.adsh.app.platform.ptc", ":platform:termux"),
    ("com.adsh.app.runtime.termux", ":platform:termux"),
    ("com.adsh.app.seam", ":seam"),
    ("com.adsh.app.ui.blocks", ":ui:blocks"),
    ("com.adsh.app.ui.chat", ":feature:chat"),
    ("com.adsh.app.ui.kit", ":ui:blocks"),
    ("com.adsh.app.ui.theme", ":ui:blocks"),
]


def unit_of(pkg):
    """包名 -> 单元（core 下按第一段归并；其它包原样）。"""
    if pkg == CORE_ROOT or pkg.startswith(CORE_ROOT + "."):
        rest = pkg[len(CORE_ROOT):].strip(".")
        return CORE_ROOT + "." + rest.split(".")[0] if rest else CORE_ROOT
    return pkg


def module_of(unit):
    """单元 -> Gradle 模块（PACKAGE_MODULES 显式表，最长前缀优先）。"""
    for prefix, module in sorted(PACKAGE_MODULES, key=lambda item: len(item[0]), reverse=True):
        if unit == prefix or unit.startswith(prefix + "."):
            return module
    return None



def registered_modules():
    """settings.gradle.kts 里注册的模块路径集合，例如 {':app', ':seam', ':core:base'}。"""
    path = ROOT / "settings.gradle.kts"
    found = set()
    if not path.is_file():
        return found
    for line in path.read_text(encoding="utf-8").split(NL):
        row = line.strip()
        if not row.startswith("include("):
            continue
        start = row.find(chr(34))
        end = row.find(chr(34), start + 1)
        if start >= 0 and end > start:
            found.add(row[start + 1:end])
    return found


def core_module_status():
    """每个 core 包的单元 -> 状态（已注册 / 有模块目录但未注册 / 待建）。"""
    registered = registered_modules()
    status = {}
    for name in CORE_NAMES:
        module = module_of(CORE_ROOT + "." + name)
        if module is None:
            status[name] = "无模块映射"
            continue
        target = ROOT.joinpath(*module.lstrip(":").split(":"))
        if module in registered:
            status[name] = "已建（" + module + " 已注册）"
        elif (target / "build.gradle.kts").is_file():
            status[name] = "目录已建（" + module + " 未注册）"
        else:
            status[name] = "待建（" + module + "）"
    return status


def module_of_path(rel, is_module=None):
    """源文件相对路径 -> 模块路径（靠该目录里有 build.gradle.kts 判定）。"""
    if is_module is None:
        def is_module(segs):
            return (ROOT.joinpath(*segs) / "build.gradle.kts").is_file()
    parts = rel.split("/")
    if len(parts) >= 2 and is_module(parts[:2]):
        return ":" + parts[0] + ":" + parts[1]
    if parts and is_module(parts[:1]):
        return ":" + parts[0]
    return None


def root_symbol_of(token):
    """token 是不是直接落在根包上的入口/生成符号（com.adsh.app.BuildConfig 等）；是就返回符号名。"""
    if not token.startswith(APP_PREFIX + "."):
        return None
    head = token[len(APP_PREFIX) + 1:].split(".")[0]
    return head if head in ROOT_SYMBOLS else None


def scan_graph(files, is_module=None):
    """-> (edges, imports, facts, root_hits)

    edges: {(源单元, 目标单元): [(文件, 行, 那行源码)]}
    imports: {单元: {import 的两段前缀: 次数}}
    facts: {单元: {files, lines, room, android}}
    root_hits: 非 :app 模块里引用根包符号的地方 [{module, symbol, file, line, text}]
    """
    stripped = {}
    file_pkg = {}
    packages = set()
    for name, text in files.items():
        clean = strip_non_code(text)
        stripped[name] = clean
        pkg = declared_package(clean)
        file_pkg[name] = pkg
        if pkg:
            packages.add(pkg)
    by_length = sorted(packages, key=len, reverse=True)

    edges = {}
    imports = {}
    facts = {}
    root_hits = []
    for name in sorted(files):
        source_pkg = file_pkg.get(name)
        if source_pkg is None:
            continue
        module = module_of_path(name, is_module)
        source = unit_of(source_pkg)
        rows = files[name].split(NL)
        info = facts.setdefault(source, {"files": 0, "lines": 0, "room": 0, "android": 0})
        info["files"] += 1
        info["lines"] += len(rows)
        bucket_imports = imports.setdefault(source, {})
        for row in stripped[name].split(NL):
            row = row.strip()
            if not row.startswith("import "):
                continue
            target = row[len("import "):].strip()
            head = ".".join(target.split(".")[:2])
            bucket_imports[head] = bucket_imports.get(head, 0) + 1
        body = stripped[name]
        if any(marker in body for marker in ROOM_MARKERS):
            info["room"] += 1
        if any(head.startswith(("android.", "androidx.")) for head in bucket_imports):
            info["android"] += 1

        for token, lineno in iter_references_with_lines(body):
            # 根包符号（BuildConfig / R / MainActivity / AdshApp）：:app 自己引用不算，
            # 别的模块引用就是反向依赖。这里与 edge_target 走同一个词法器，不另开一套扫描。
            symbol = root_symbol_of(token)
            if symbol is not None and module is not None and module != APP_MODULE:
                body_line = rows[lineno - 1].strip()[:100] if lineno - 1 < len(rows) else ""
                root_hits.append({
                    "module": module,
                    "symbol": symbol,
                    "file": name,
                    "line": lineno,
                    "text": body_line,
                })
            target_pkg = edge_target(token, source_pkg, by_length)
            if target_pkg is None:
                continue
            target = unit_of(target_pkg)
            if target == source:
                continue
            bucket = edges.setdefault((source, target), [])
            if len(bucket) < 200:
                sample = rows[lineno - 1].strip()[:100] if lineno - 1 < len(rows) else ""
                bucket.append((name, lineno, sample))
    return edges, imports, facts, root_hits


def tarjan(nodes, adjacency):
    """所有强连通分量（含单点；调用方挑 size > 1）。"""
    index, low, on_stack, stack, counter, comps = {}, {}, {}, [], [0], []

    def strongconnect(node):
        index[node] = low[node] = counter[0]
        counter[0] += 1
        stack.append(node)
        on_stack[node] = True
        for nxt in adjacency.get(node, ()):
            if nxt not in index:
                strongconnect(nxt)
                low[node] = min(low[node], low[nxt])
            elif on_stack.get(nxt):
                low[node] = min(low[node], index[nxt])
        if low[node] == index[node]:
            comp = []
            while True:
                popped = stack.pop()
                on_stack[popped] = False
                comp.append(popped)
                if popped == node:
                    break
            comps.append(sorted(comp))

    for node in sorted(nodes):
        if node not in index:
            strongconnect(node)
    return comps


def topo_waves(nodes, adjacency):
    """依赖在前：边 A -> B（A 依赖 B）则 B 先就绪。返回 (批次列表, 卡在环里的节点)。

    同一批里的单元互不依赖，可以任意顺序（甚至同一批里一起切）。
    """
    pending = {n: set(x for x in adjacency.get(n, ()) if x in nodes and x != n) for n in nodes}
    waves = []
    while pending:
        ready = sorted(n for n, deps in pending.items() if not deps)
        if not ready:
            break
        waves.append(ready)
        for n in ready:
            del pending[n]
        for deps in pending.values():
            deps.difference_update(ready)
    return waves, sorted(pending)


def topo_order(nodes, adjacency):
    """批次拍平成一个顺序（只给自检用）。"""
    waves, stuck = topo_waves(nodes, adjacency)
    return [n for wave in waves for n in wave], stuck


def core_view(edges):
    core_units = [CORE_ROOT + "." + n for n in CORE_NAMES]
    core_set = set(core_units)
    adjacency = {u: set() for u in core_units}
    for (a, b) in edges:
        if a in core_set and b in core_set:
            adjacency[a].add(b)
    return core_units, core_set, adjacency


def short(unit):
    return unit.replace(APP_PREFIX + ".", "")


def samples_of(edges, key, count):
    return "<br>".join(f + ":" + str(l) for f, l, _ in edges.get(key, [])[:count])


def render(edges, imports, facts, root_hits):
    core_units, core_set, adjacency = core_view(edges)
    waves, stuck = topo_waves(core_units, adjacency)
    status = core_module_status()
    comps = [c for c in tarjan(core_units, adjacency) if len(c) > 1]

    out = []
    add = out.append
    add("# 模块依赖图（包依赖 + 包到模块的映射）")
    add("")
    add("由 python scripts/check-module-graph.py --write 生成；--check 比对新鲜度（CI 用）。")
    add("")
    add("口径：各模块 src/main 下的 .kt，剥掉注释与字符串后逐行找 com.adsh.app.*"
        "（内联全限定名与 import 同等看待），目标包按「已声明的包名」最长前缀解析；"
        "core.* 按包的第一段归并成单元。包名不再能推出模块名 —— 包 -> 模块是脚本里的显式表（见 §六）。"
        "词法器直接复用 scripts/check-package-cycles.py。")
    add("")
    add("## 一、九个核心包的体量")
    add("")
    add("| 单元 | 文件 | 行 | 模块 |")
    add("|---|---|---|---|")
    for unit in core_units:
        info = facts.get(unit, {"files": 0, "lines": 0})
        add("| " + short(unit) + " | " + str(info["files"]) + " | " + str(info["lines"]) + " | " + str(module_of(unit)) + " |")
    add("")
    add("## 二、core.* 出边（谁依赖谁）")
    add("")
    add("| 来源单元 | 目标 | 次数 | 样本（文件:行） |")
    add("|---|---|---|---|")
    for (a, b) in sorted(edges):
        if a not in core_set:
            continue
        add("| " + short(a) + " | " + short(b) + " | " + str(len(edges[(a, b)])) + " | " + samples_of(edges, (a, b), 3) + " |")
    add("")
    add("## 三、core.* 入边（谁依赖它；core→core 的入边把上一节反过来读）")
    add("")
    add("| 目标单元 | 来源 | 次数 | 样本（文件:行） |")
    add("|---|---|---|---|")
    for (a, b) in sorted(edges):
        if b not in core_set or a in core_set:
            continue
        add("| " + short(b) + " | " + short(a) + " | " + str(len(edges[(a, b)])) + " | " + samples_of(edges, (a, b), 3) + " |")
    add("")
    runtime_like = sorted({short(b) for (a, b) in edges if a in core_set and not b.startswith(APP_PREFIX + ".core.")})
    add("事实：core.* 对外的出边只到 " + "、".join(runtime_like) + "（没有 runtime.* / ui.* / island.* 的边）。")
    add("")
    add("## 四、切割顺序（依赖在前）")
    add("")
    add("规则：边 A → B 表示 A 依赖 B，所以 B 必须先就绪。同一批里的单元互不依赖，顺序可任意。")
    add("")
    for i, wave in enumerate(waves, 1):
        add("第 " + str(i) + " 批：" + "、".join(str(module_of(u)) + "（" + short(u) + "，" + status[u.split(".")[-1]] + "）" for u in wave))
    if stuck:
        add("")
        add("卡在环里、不能单独切的单元：" + "、".join(short(u) for u in stuck))
    add("")
    add("## 五、环（强连通分量，size > 1）")
    add("")
    if not comps:
        add("- 无：core.* 之间是 DAG。")
    for comp in comps:
        add("- " + str(len(comp)) + " 个单元：" + "、".join(short(u) for u in comp))
        for a in sorted(comp):
            backs = sorted(b for b in adjacency.get(a, ()) if b in comp)
            if backs:
                add("    - " + short(a) + " → " + "、".join(short(b) for b in backs)
                    + "（例：" + samples_of(edges, (a, backs[0]), 2) + "）")
    add("")
    add("## 六、包到模块的映射（脚本里的显式表）")
    add("")
    add("包名前缀匹配、最长前缀优先 —— 一条包依赖落到哪个模块只看这张表。")
    add("")
    add("| 包前缀 | 模块 |")
    add("|---|---|")
    for prefix, module in PACKAGE_MODULES:
        add("| " + short(prefix) + " | " + module + " |")
    add("")
    add("| 单元 | 模块 | 状态 | 三方库 import 前缀 | Room/KSP |")
    add("|---|---|---|---|---|")
    for unit in core_units:
        info = facts.get(unit, {"room": 0})
        heads = sorted(imports.get(unit, {}))
        androidish = [h for h in heads if h.startswith(("android.", "androidx."))]
        others = [h for h in heads if not h.startswith(("android.", "androidx.", "java.", "javax.", "kotlin.", "com.adsh"))]
        listed = others + androidish
        if not listed:
            listed = ["（无三方依赖）"]
        add("| " + short(unit) + " | " + str(module_of(unit)) + " | " + status[unit.split(".")[-1]] + " | "
            + "<br>".join(listed[:8]) + " | " + ("是" if info["room"] else "否") + " |")
    add("")
    add("## 七、settings.gradle.kts 里已注册的模块")
    add("")
    for m in sorted(registered_modules()):
        add("- " + m)
    add("")
    add("core.* 的九个包落在这些模块里：" + "、".join(sorted({str(module_of(u)) for u in core_units})))
    add("")
    add("## 八、非 :app 模块引用根包符号")
    add("")
    add("规则：:seam / :ui:blocks / :feature:chat / :core:* 里不许出现 com.adsh.app.{BuildConfig,R,MainActivity,AdshApp}"
        "（它们是 :app 的入口与生成代码，被下方模块引用就是反向依赖）；:app 自己引用不算。"
        "命中即 --strict 失败。")
    add("")
    add("| 模块 | 符号 | 文件:行 | 那行 |")
    add("|---|---|---|---|")
    if root_hits:
        for hit in sorted(root_hits, key=lambda h: (h["module"], h["file"], h["line"])):
            add("| " + hit["module"] + " | " + hit["symbol"] + " | " + hit["file"] + ":" + str(hit["line"]) + " | " + hit["text"] + " |")
    else:
        add("| — | — | — | 0 处 |")
    add("")
    return NL.join(out) + NL


def selftest():
    cases = []

    def case(name, ok, actual, expected):
        cases.append((ok, name, actual, expected))

    case("core 子包归并到第一段单元",
         unit_of("com.adsh.app.core.agent.worker") == "com.adsh.app.core.agent",
         unit_of("com.adsh.app.core.agent.worker"), "com.adsh.app.core.agent")
    case("非 core 包不归并",
         unit_of("com.adsh.app.seam.tool") == "com.adsh.app.seam.tool",
         unit_of("com.adsh.app.seam.tool"), "com.adsh.app.seam.tool")
    case("合并后 seam.* 全落 :seam",
         module_of("com.adsh.app.seam.tool") == ":seam" and module_of("com.adsh.app.seam.ptc") == ":seam",
         (module_of("com.adsh.app.seam.tool"), module_of("com.adsh.app.seam.ptc")), (":seam", ":seam"))
    case("ui 包按显式表映射（ui.theme/ui.kit/ui.blocks 都落 :ui:blocks，不按名字猜）",
         module_of("com.adsh.app.ui.kit") == ":ui:blocks" and module_of("com.adsh.app.ui.theme") == ":ui:blocks"
             and module_of("com.adsh.app.ui.blocks") == ":ui:blocks",
         (module_of("com.adsh.app.ui.kit"), module_of("com.adsh.app.ui.theme"), module_of("com.adsh.app.ui.blocks")),
         (":ui:blocks", ":ui:blocks", ":ui:blocks"))
    case("jobs 落 :core:tools；电话线协议在 seam.phone（旧 core.phone 已不存在）",
         module_of("com.adsh.app.core.jobs") == ":core:tools"
         and module_of("com.adsh.app.seam.phone") == ":seam"
         and module_of("com.adsh.app.core.phone") is None,
         (module_of("com.adsh.app.core.jobs"), module_of("com.adsh.app.seam.phone"), module_of("com.adsh.app.core.phone")),
         (":core:tools", ":seam", None))
    case("llm 与 session 都在 :core:data；ptc 在 :platform:termux",
         module_of("com.adsh.app.core.llm") == ":core:data"
         and module_of("com.adsh.app.core.session") == ":core:data"
         and module_of("com.adsh.app.platform.ptc") == ":platform:termux",
         (module_of("com.adsh.app.core.llm"), module_of("com.adsh.app.core.session"), module_of("com.adsh.app.platform.ptc")),
         (":core:data", ":core:data", ":platform:termux"))
    case("模块映射不按名字猜",
         module_of("com.adsh.app.core.tools") == ":core:tools",
         module_of("com.adsh.app.core.tools"), ":core:tools")
    case("runtime.termux 落 :platform:termux；runtime.phone / island 不属于独立模块",
         module_of("com.adsh.app.runtime.termux") == ":platform:termux"
         and module_of("com.adsh.app.runtime.phone") is None and module_of("com.adsh.app.island") is None,
         (module_of("com.adsh.app.runtime.termux"), module_of("com.adsh.app.runtime.phone"), module_of("com.adsh.app.island")),
         (":platform:termux", None, None))

    a, b = "com.adsh.app.core.a", "com.adsh.app.core.b"
    order, stuck = topo_order([a, b], {a: {b}})
    case("依赖在前：被依赖者排前面", order == [b, a] and not stuck, order, [b, a])
    order2, stuck2 = topo_order([a, b], {a: {b}, b: {a}})
    case("成环：两个都判为卡住", order2 == [] and stuck2 == [a, b], (order2, stuck2), ([], [a, b]))
    comps = [c for c in tarjan([a, b], {a: {b}, b: {a}}) if len(c) > 1]
    case("Tarjan 认出环", comps == [[a, b]], comps, [[a, b]])
    case("自环不影响拓扑序", topo_order([a], {a: {a}})[0] == [a], topo_order([a], {a: {a}})[0], [a])

    edges = {("com.adsh.app.core.agent", "com.adsh.app.core.tools"): [("x.kt", 3, "val x: com.adsh.app.core.tools.T")]}
    units, _core_set, adjacency = core_view(edges)
    case("core_view 只留 core 内部边",
         adjacency["com.adsh.app.core.agent"] == {"com.adsh.app.core.tools"} and len(units) == 9,
         (sorted(adjacency["com.adsh.app.core.agent"]), len(units)), (["com.adsh.app.core.tools"], 9))

    def fake_is_module(segs):
        return tuple(segs) in (("app",), ("core", "agent"), ("core", "base"), ("seam",), ("ui", "kit"))

    def root_hits_of(files):
        _e, _i, _f, hits = scan_graph(files, is_module=fake_is_module)
        return sorted((h["module"], h["symbol"]) for h in hits)

    case("core 里 import com.adsh.app.BuildConfig 要报",
         root_hits_of({"core/agent/src/main/kotlin/A.kt": "package com.adsh.app.core.agent" + NL + "import com.adsh.app.BuildConfig" + NL})
         == [(":core:agent", "BuildConfig")],
         root_hits_of({"core/agent/src/main/kotlin/A.kt": "package com.adsh.app.core.agent" + NL + "import com.adsh.app.BuildConfig" + NL}),
         [(":core:agent", "BuildConfig")])
    case(":app 里 import com.adsh.app.BuildConfig 不报",
         root_hits_of({"app/src/main/java/A.kt": "package com.adsh.app" + NL + "import com.adsh.app.BuildConfig" + NL}) == [],
         root_hits_of({"app/src/main/java/A.kt": "package com.adsh.app" + NL + "import com.adsh.app.BuildConfig" + NL}),
         [])
    case(":seam 里内联全限定名也算（不只是 import 行）",
         root_hits_of({"seam/src/main/java/A.kt": "package com.adsh.app.seam.tool" + NL + "val d = com.adsh.app.BuildConfig.DEBUG" + NL})
         == [(":seam", "BuildConfig")],
         root_hits_of({"seam/src/main/java/A.kt": "package com.adsh.app.seam.tool" + NL + "val d = com.adsh.app.BuildConfig.DEBUG" + NL}),
         [(":seam", "BuildConfig")])
    case("注释与字符串里的根符号不算",
         root_hits_of({"ui/kit/src/main/java/A.kt": "package com.adsh.app.ui.kit" + NL + "// com.adsh.app.R" + NL + "val s = \"com.adsh.app.AdshApp\"" + NL}) == [],
         root_hits_of({"ui/kit/src/main/java/A.kt": "package com.adsh.app.ui.kit" + NL + "// com.adsh.app.R" + NL + "val s = \"com.adsh.app.AdshApp\"" + NL}),
         [])
    case("core 自己的符号（com.adsh.app.core.*）不算根符号",
         root_hits_of({"core/agent/src/main/kotlin/A.kt": "package com.adsh.app.core.agent" + NL + "val x: com.adsh.app.core.tools.Tool? = null" + NL}) == [],
         root_hits_of({"core/agent/src/main/kotlin/A.kt": "package com.adsh.app.core.agent" + NL + "val x: com.adsh.app.core.tools.Tool? = null" + NL}),
         [])
    case("R / MainActivity / AdshApp 都认",
         root_hits_of({"core/base/src/main/kotlin/A.kt": "package com.adsh.app.core.jobs" + NL + "import com.adsh.app.R" + NL + "import com.adsh.app.MainActivity" + NL + "import com.adsh.app.AdshApp" + NL})
         == [(":core:base", "AdshApp"), (":core:base", "MainActivity"), (":core:base", "R")],
         root_hits_of({"core/base/src/main/kotlin/A.kt": "package com.adsh.app.core.jobs" + NL + "import com.adsh.app.R" + NL + "import com.adsh.app.MainActivity" + NL + "import com.adsh.app.AdshApp" + NL}),
         [(":core:base", "AdshApp"), (":core:base", "MainActivity"), (":core:base", "R")])

    failed = 0
    for ok, name, actual, expected in cases:
        print(("ok  " if ok else "FAIL") + "  " + name)
        if not ok:
            failed += 1
            print("      实际: " + str(actual))
            print("      期望: " + str(expected))
    print("自检：" + str(len(cases) - failed) + "/" + str(len(cases)) + " 通过")
    return 1 if failed else 0


def main():
    argv = sys.argv[1:]
    if "--selftest" in argv:
        return selftest()

    files = collect_files(ROOT)
    edges, imports, facts, root_hits = scan_graph(files)
    doc = render(edges, imports, facts, root_hits)

    if "--write" in argv:
        (ROOT / DOC_PATH).write_text(doc, encoding="utf-8", newline=NL)
        print("已写入 " + DOC_PATH + "（" + str(len(files)) + " 个文件 / " + str(len(edges)) + " 条包级边）")
        return 0

    if "--check" in argv:
        target = ROOT / DOC_PATH
        if not target.is_file():
            print(DOC_PATH + " 不存在：先跑 --write")
            return 1
        if target.read_text(encoding="utf-8") != doc:
            print(DOC_PATH + " 已过期：重跑 python scripts/check-module-graph.py --write")
            return 1
        print(DOC_PATH + " 是最新的。")
        return 0

    if "--strict" in argv:
        if root_hits:
            print("门禁失败（--strict）：非 :app 模块引用了根包符号，共 " + str(len(root_hits)) + " 处：")
            for hit in sorted(root_hits, key=lambda h: (h["module"], h["file"], h["line"])):
                print("  " + hit["module"] + "  " + hit["file"] + ":" + str(hit["line"]) + "  " + hit["symbol"])
            return 1
        print("门禁通过（--strict）：非 :app 模块 0 处引用根包符号（BuildConfig / R / MainActivity / AdshApp）。")
        return 0

    core_units, core_set, adjacency = core_view(edges)
    waves, stuck = topo_waves(core_units, adjacency)
    print("扫描 " + str(len(files)) + " 个文件 / " + str(len(edges)) + " 条包级边")
    print("非 :app 模块引用根包符号：" + str(len(root_hits)) + " 处")
    for hit in sorted(root_hits, key=lambda h: (h["module"], h["file"], h["line"])):
        print("  " + hit["module"] + "  " + hit["file"] + ":" + str(hit["line"]) + "  " + hit["symbol"] + "  | " + hit["text"])
    print("")
    print("core.* 出边：")
    for key in sorted(edges):
        if key[0] in core_set:
            print("  " + short(key[0]) + " -> " + short(key[1]) + "  (" + str(len(edges[key])) + ")  "
                  + edges[key][0][0] + ":" + str(edges[key][0][1]))
    print("")
    print("外部 -> core.* 入边：")
    for key in sorted(edges):
        if key[1] in core_set and key[0] not in core_set:
            print("  " + short(key[0]) + " -> " + short(key[1]) + "  (" + str(len(edges[key])) + ")  "
                  + edges[key][0][0] + ":" + str(edges[key][0][1]))
    print("")
    for i, wave in enumerate(waves, 1):
        print("第 " + str(i) + " 批（可并列）：" + "、".join(short(u) for u in wave))
    if stuck:
        print("卡在环里：" + ", ".join(short(u) for u in stuck))
    for comp in [c for c in tarjan(core_units, adjacency) if len(c) > 1]:
        print("环：" + " / ".join(short(u) for u in comp))
    return 0


if __name__ == "__main__":
    sys.exit(main())
