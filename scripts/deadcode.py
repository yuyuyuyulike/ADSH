#!/usr/bin/env python3
"""Kotlin 死代码扫描器（第 182 轮重写；取代 find-unused-declarations.py 与 find-file-local-dead-code.py）。

## 为什么重写

旧版只有一条判据：**名字在整个仓库的文本里出现几次**，1 次就是候选。这条判据两个方向都错：

* **漏报**：名字在别处撞上就永远躲过扫描 —— 另一个文件里的同名局部变量、日志字符串、import 行、
  注释都算「引用」（第 181 轮之前的「熵减」全靠人肉盯输出，就是因为这个）；
* **误报**：真正被反射 / 框架按名字调用的东西会被报出来，只能靠一长串 if 特判兜。

## 借来的三套判据（都来自开源实现）

* **vulture**（Python，7k★）：按**代码类型给置信度**（参数 / 不可达 100%、import 90%、
  属性 / 类 / 函数 / 方法 / 变量 60%），用 --min-confidence 过滤；假阳性写进 whitelist 文件，
  支持 --make-whitelist 自动生成。这里照搬：每条候选带 confidence，--min-confidence 过滤，
  --baseline 就是同一件事的白名单（带 reason 字段，要求写清为什么它不能删）。
* **detekt**（Kotlin，7k★）：UnusedPrivateMember / UnusedPrivateProperty / UnusedPrivateClass 的
  判据是「**private** 声明在本文件内没有任何引用」，另有 allowedNames 正则
  （默认 _|ignored|expected|serialVersionUID）与 @Suppress 豁免。这里把「private = 100 置信」
  当核心，再往 internal / public 外推时降置信度。
* **Android Lint**（UnusedResources / MissingClass）与 **R8 -printusage**：真正的语义级判据
  （lint 跑得慢、R8 要 release 构建）。所以本脚本定位是**快**的那一半：lint 管
  「资源 / 清单 / API 级别」，本脚本管「Kotlin 声明」。

## 判据

对每个 main 源码里的声明（顶层或类成员）数**词法级引用**（scripts/kt_source.py + 本文件的作用域）：

* 注释与字符串正文不算引用（字符串模板里的 $name 算，那是真引用）；
* import 行不算引用（那只说明有人在另一处 import 了同名符号；导入本身会被单独报 unused-import）；
* 命名实参的标签不算引用（foo(name = 1) 里的 name 是参数名）；
* **任何声明处都不算引用**（旧版最大的漏报源：另一个文件里也有个叫 lines 的 val，旧版就算「有人用」）；
* **同名遮蔽**：函数体内的局部变量 / 参数与外部声明同名时，这个函数体里对该名字的引用
  算在局部头上，不喂给外部声明（旧版的另一个漏报源）；
* 作用域：private 只数**本文件**，internal / public 数整个 main，test 单算一栏。

判定与置信度：

| 类别 | 判据 | 置信度 |
|---|---|---|
| unused-import | import 进来的名字在本文件正文里没出现 | 100 |
| private-unused | private 声明在本文件内零引用 | 100 |
| internal-unused | internal 声明在 main 里零引用 | 70 |
| internal-test-only | internal 声明只在 test 里被引用 | 65 |
| public-unused | public 顶层声明在 main 里零引用 | 60 |
| public-test-only | public 顶层声明只在 test 里被引用 | 55 |

豁免（跳过，不报）：@Test / @Before / @After 等 JUnit 回调、@SerialName / @Serializable /
@Entity / @Dao / @Query / @TypeConverter / @Embedded / @Relation、@Keep、@JvmStatic / @JvmField、
@Provides / @Inject、@Preview、@Suppress("unused")、override / actual / expect、
名字匹配 allowedNames 正则（_ / ignored / expected / serialVersionUID）、函数体内的局部声明
（那是编译器 warning 的活）、protected（子类可能在别处）。

**@Composable 不在豁免表里**：组合函数就是普通函数调用（编译器补 composer 参数而已），
没人调用的 private @Composable 就是死代码。

已知边界（宁可漏报，不可误报）：构造函数参数里声明的属性（class Foo(private val bar: Int)）、
反射 / 字符串查找（Class.forName("Foo")、@Suppress 之外的按名查找）、KSP/Room 生成的代码。
这些靠 --baseline 记，不靠瞎报。

用法：
  python3 scripts/deadcode.py                     # 全量（含 60 分档）
  python3 scripts/deadcode.py --min-confidence 90 # 只看高置信度（可以直接删的）
  python3 scripts/deadcode.py --json
  python3 scripts/deadcode.py --update-baseline   # 把当前候选写进 baseline（逐个补 reason）
  python3 scripts/deadcode.py --fail-on-new       # 有 baseline 之外的新候选就以 2 退出
  python3 scripts/deadcode-selftest.py            # 扫描器自己的夹具自测（精度 + 召回）
"""
import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kt_source import identifiers, strip_comments, strip_comments_and_strings  # noqa: E402

# 注解文本要从**未抹字符串**的那一份里取：@Suppress("unused") 里的参数抹掉之后就成了
# @Suppress("")，豁免判据（只豁免抑制 unused 的）就没法判了。
ANNO_RE = re.compile(r"@[\w.]+(?:\s*\([^()]*\))?")

# ----- 词法/声明层 ----------------------------------------------------------------

MODIFIER = (
    "public|private|protected|internal|expect|actual|final|open|abstract|sealed|const|lateinit|"
    "inline|noinline|crossinline|reified|suspend|operator|infix|external|override|tailrec|vararg|"
    "annotation|data|enum|value|companion|inner"
)
DECL_RE = re.compile(
    r"^\s*(?P<annos>(?:@[\w.]+(?:\s*\([^()]*\))?\s*)*)"
    r"(?P<mods>(?:(?:" + MODIFIER + r")\s+)*)"
    r"(?P<kind>fun|val|var|class|object|interface|typealias)\s+"
    r"(?:<[^>]*>\s*)?"
    r"(?P<recv>[A-Za-z_][A-Za-z0-9_.<>,?\[\]]*\.)?"
    r"(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
)
OBJECT_NONAME_RE = re.compile(r"^\s*(?:(?:" + MODIFIER + r")\s+)*object\s*\{")
LOCAL_DECL_RE = re.compile(r"^\s*(?:val|var)\s+(?:<[^>]*>\s*)?(?P<name>[A-Za-z_][A-Za-z0-9_]*)")
IMPORT_RE = re.compile(r"^\s*import\s+(?P<fq>[\w.]+)(?:\s+as\s+(?P<alias>\w+))?\s*$")

# 框架按名字/反射调用的注解：声明「没人直接提到」是正常的，跳过（≈ detekt 的 ignoreAnnotated）
EXEMPT_ANNOTATIONS = (
    "Test", "Before", "After", "BeforeClass", "AfterClass", "Ignore", "ParameterizedTest",
    "SerialName", "Serializable", "Embedded", "Relation", "PrimaryKey", "Entity", "Dao",
    "Database", "Query", "Insert", "Update", "Delete", "TypeConverter", "Keep", "JvmStatic",
    "JvmField", "JvmOverloads", "Provides", "Inject", "Singleton", "Preview", "Suppress",
    "SuppressLint",
)
# @Suppress 只在抑制的是 unused 时豁免（@Suppress("UNCHECKED_CAST") 的声明照样要查）
UNUSED_SUPPRESS = re.compile(r"unused", re.IGNORECASE)
# detekt 的 allowedNames：这些名字是「故意不用」的约定写法
ALLOWED_NAMES = re.compile(r"^(_|ignored|expected|serialVersionUID)")

SKIP_DIRS = {".git", "build", ".gradle", ".idea", ".kotlin", ".cxx", "node_modules"}


class Decl:
    __slots__ = ("path", "line", "kind", "name", "visibility", "is_member", "container_kind",
                 "annos", "mods")

    def __init__(self, path, line, kind, name, visibility, is_member, container_kind, annos, mods):
        self.path = path
        self.line = line
        self.kind = kind
        self.name = name
        self.visibility = visibility
        self.is_member = is_member
        self.container_kind = container_kind
        self.annos = annos
        self.mods = mods


class Scope:
    """一个函数体：体里出现的局部声明 / 参数都在 names 里（名字 -> 声明行）。"""

    __slots__ = ("start", "end", "names")

    def __init__(self, start):
        self.start = start
        self.end = start
        self.names = {}


def parse_file(path, rel, collect_decls):
    text = open(path, encoding="utf-8", errors="replace").read()
    masked = strip_comments_and_strings(text)
    lines = masked.split("\n")
    raw_lines = strip_comments(text).split("\n")
    decls = []
    decl_sites = set()
    imports = []
    scopes = []
    depth = 0
    paren_open = 0
    pending_class = None
    stack = []  # [(depth_at_open, kind, name, scope)]
    pending_annos = []

    for number, line in enumerate(lines, start=1):
        while stack and stack[-1][0] >= depth:
            _, kind_closed, _name_closed, scope = stack.pop()
            if scope is not None:
                scope.end = number - 1
                scopes.append(scope)
        container = stack[-1] if stack else None
        import_match = IMPORT_RE.match(line)
        if import_match:
            imports.append((rel, number, import_match.group("fq"),
                            import_match.group("alias")))
        match = DECL_RE.match(line)
        inline_annos = ANNO_RE.findall(raw_lines[number - 1]) if match else []
        annos = pending_annos + inline_annos
        if match:
            kind, name = match.group("kind"), match.group("name")
            mods = match.group("mods") or ""
            visibility = "public"
            for candidate in ("private", "protected", "internal", "public"):
                if re.search(r"\b" + candidate + r"\b", mods):
                    visibility = candidate
                    break
            in_function = container is not None and container[1] == "fun"
            decl_sites.add((rel, number, name))
            # 构造参数里声明的属性（class Foo(private val bar: Int)）：算这个类的**成员**
            in_signature = paren_open > 0 and pending_class is not None
            if collect_decls and not in_function:
                container_kind = container[1] if container else None
                if in_signature:
                    container_kind = "class"
                decls.append(Decl(rel, number, kind, name, visibility,
                                  container is not None or in_signature, container_kind,
                                  annos, mods))
            if kind in ("class", "object", "interface"):
                if "{" in line:
                    stack.append((depth, kind, name, None))
                else:
                    pending_class = name
                if paren_open == 0 and "(" in line and line.count("(") > line.count(")"):
                    pending_class = name
            elif kind == "fun":
                scope = Scope(number)
                # 参数也算局部名（体内对参数的引用不该喂给外部同名声明）
                signature = line
                probe = number
                while "(" in signature and signature.count("(") > signature.count(")") and probe < len(lines):
                    probe += 1
                    signature += " " + lines[probe - 1]
                for param in re.findall(r"(\w+)\s*:", signature):
                    scope.names.setdefault(param, number)
                stack.append((depth, kind, name, scope))
        elif OBJECT_NONAME_RE.match(line):
            if "{" in line:
                stack.append((depth, "object", "Companion", None))
        # 函数体里的局部声明：**不能**用 not match 挡掉 —— 局部 val 同样会被 DECL_RE 匹配到
        # （旧版就是这样漏掉同名遮蔽的），这里按「谁在作用域里」独立收一遍。
        if stack and stack[-1][1] == "fun" and stack[-1][3] is not None:
            local = LOCAL_DECL_RE.match(line)
            if local:
                stack[-1][3].names.setdefault(local.group("name"), number)
        # 注解从**未抹字符串**的那一行取：@Suppress("unused") 的参数抹掉后就判不出「抑制的是 unused」
        stripped = raw_lines[number - 1].strip()
        if stripped.startswith("@"):
            pending_annos = (pending_annos + ANNO_RE.findall(stripped))[-4:]
        elif stripped == "" or stripped.startswith(")") or stripped.endswith(","):
            pass
        else:
            pending_annos = []
        depth += line.count("{") - line.count("}")
        paren_open = max(0, paren_open + line.count("(") - line.count(")"))
        if paren_open == 0 and "{" in line:
            pending_class = None
    for _depth, _kind, _name, scope in stack:
        if scope is not None:
            scope.end = len(lines)
            scopes.append(scope)
    return masked, decls, decl_sites, imports, scopes


MANIFEST_RE = re.compile(r'android:name="([^"]+)"')


def manifest_references(root):
    """AndroidManifest 里 android:name 点到的类：系统按名字实例化，源码里当然搜不到引用。

    这正是 Android Lint 的 MissingClass 检查的反方向（那边查「清单点了但类不存在」，
    这边要保证「清单点到的类不许被当成死代码」）。
    """
    names = {}
    for rel in ("app/src/main/AndroidManifest.xml", "app/src/debug/AndroidManifest.xml"):
        path = os.path.join(root, rel)
        if not os.path.exists(path):
            continue
        text = open(path, encoding="utf-8", errors="replace").read()
        for fq in MANIFEST_RE.findall(text):
            simple = fq.rsplit(".", 1)[-1]
            if simple:
                names.setdefault(simple, []).append((rel, 0))
    return names


def load_sources(root):
    sources = {}
    for bucket, subdir in (("main", "main"), ("test", "test")):
        base = os.path.join(root, "app", "src", subdir)
        for base_dir, dirs, files in os.walk(base):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
            for name in files:
                if name.endswith(".kt"):
                    sources[os.path.join(base_dir, name)] = bucket
    return sources


def scan(root, with_tests=True):
    decls = []
    decl_sites = set()
    imports = []
    scopes = {}
    raw_refs = {"main": {}, "test": {}}
    sources = load_sources(root)
    for path, bucket in sources.items():
        if bucket == "test" and not with_tests:
            continue
        rel = os.path.relpath(path, root).replace("\\", "/")
        masked, file_decls, file_sites, file_imports, file_scopes = parse_file(
            path, rel, collect_decls=(bucket == "main"))
        decls.extend(file_decls)
        decl_sites |= file_sites
        scopes[rel] = file_scopes
        if bucket == "main":
            imports.extend(file_imports)
        for number, name, context in identifiers(masked):
            if context in ("import", "named_arg"):
                continue
            if (rel, number, name) in file_sites:
                continue
            raw_refs[bucket].setdefault(name, []).append((rel, number, context))

    # 同名遮蔽：落在「声明了同名局部/参数」的函数体里、且在声明之后 → 算局部的，不喂给外部声明
    refs = {"main": {}, "test": {}}
    for bucket, table in raw_refs.items():
        for name, hits in table.items():
            kept = []
            for rel, number, context in hits:
                shadowed = False
                # 只有**裸名**可能被局部遮蔽：X.size(...) 这种成员访问走接收者类型，
                # 函数里有个叫 size 的局部变量不影响它（第 182 轮 FileTypeIcon 的假阳性就是这条）
                if context == "plain":
                    for scope in scopes.get(rel, ()):
                        if scope.start <= number <= scope.end:
                            declared = scope.names.get(name)
                            if declared is not None and declared <= number:
                                shadowed = True
                                break
                if not shadowed:
                    kept.append((rel, number))
            if kept:
                refs[bucket][name] = kept
    for name, hits in manifest_references(root).items():
        refs["main"].setdefault(name, []).extend(hits)
    return decls, refs, imports


def is_exempt(decl):
    if ALLOWED_NAMES.match(decl.name):
        return True
    if re.search(r"\b(override|actual|expect)\b", decl.mods):
        return True
    if re.search(r"\boperator\b", decl.mods):
        return True  # 运算符重载按符号调用（a + b 里没有 plus 这个名字），与 override 同理
    for anno in decl.annos:
        simple = anno.lstrip("@").split("(")[0]
        if simple in EXEMPT_ANNOTATIONS:
            if simple == "Suppress" and not UNUSED_SUPPRESS.search(anno):
                continue
            return True
    return False


def candidates(root, with_tests=True):
    decls, refs, imports = scan(root, with_tests)
    out = []

    for path, line, fq, alias in imports:
        if fq.endswith(".*"):
            continue
        name = alias or fq.split(".")[-1]
        if name in ("getValue", "setValue", "provideDelegate"):
            continue  # 属性委托约定：名字不出现在正文里也必须保留
        hits = [hit for hit in refs["main"].get(name, []) if hit[0] == path]
        if not hits:
            hits = [hit for hit in refs["test"].get(name, []) if hit[0] == path]
        if not hits:
            out.append({"category": "unused-import", "confidence": 100, "file": path,
                        "line": line, "kind": "import", "name": name})

    for decl in decls:
        if is_exempt(decl):
            continue
        main_hits = refs["main"].get(decl.name, [])
        test_hits = refs["test"].get(decl.name, [])
        same_file = [hit for hit in main_hits if hit[0] == decl.path]
        if decl.visibility == "private":
            if not same_file:
                out.append({"category": "private-unused", "confidence": 100, "file": decl.path,
                            "line": decl.line, "kind": decl.kind, "name": decl.name})
            continue
        if decl.visibility == "protected":
            continue  # 子类可能在别处：静态判不了，按「宁可漏报」跳过
        if decl.visibility == "internal":
            if not main_hits:
                out.append({
                    "category": "internal-test-only" if test_hits else "internal-unused",
                    "confidence": 65 if test_hits else 70, "file": decl.path,
                    "line": decl.line, "kind": decl.kind, "name": decl.name})
            continue
        # public（默认可见性）：顶层 + **object / companion 的成员**。
        # object 是 Kotlin 单例（不能被继承），它的公开成员与顶层声明同性质，只能按名字访问；
        # class 的成员不报（子类 / 反射 / 生成代码都可能用，静态判不了）。
        reportable = (not decl.is_member) or decl.container_kind == "object"
        if reportable and not main_hits:
            out.append({
                "category": "public-test-only" if test_hits else "public-unused",
                "confidence": 55 if test_hits else 60, "file": decl.path,
                "line": decl.line, "kind": decl.kind, "name": decl.name})
    return out


def load_baseline(path):
    if not path or not os.path.exists(path):
        return {}
    try:
        data = json.load(open(path, encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return {(e.get("file"), e.get("name"), e.get("kind")): e for e in data.get("entries", [])}


def main():
    parser = argparse.ArgumentParser(description="Kotlin 死代码扫描器")
    parser.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser.add_argument("--min-confidence", type=int, default=0)
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--no-tests", action="store_true")
    parser.add_argument("--baseline", default=None)
    parser.add_argument("--update-baseline", action="store_true")
    parser.add_argument("--fail-on-new", action="store_true")
    args = parser.parse_args()

    baseline_path = args.baseline or os.path.join(args.root, "scripts", "deadcode-baseline.json")
    baseline = load_baseline(baseline_path)
    found = candidates(args.root, with_tests=not args.no_tests)

    kept, excused = [], []
    for item in found:
        key = (item["file"], item["name"], item["kind"])
        (excused if key in baseline else kept).append(item)

    if args.update_baseline:
        entries = []
        for item in found:
            key = (item["file"], item["name"], item["kind"])
            entry = dict(item)
            entry["reason"] = baseline.get(key, {}).get("reason", "")
            entries.append(entry)
        json.dump({"entries": entries}, open(baseline_path, "w", encoding="utf-8"),
                  ensure_ascii=False, indent=1)
        print("已写入 baseline：" + baseline_path + "（" + str(len(entries)) + " 条，逐条补 reason）")
        return 0

    shown = [item for item in kept if item["confidence"] >= args.min_confidence]
    if args.json:
        print(json.dumps(shown, ensure_ascii=False, indent=1))
    else:
        by_category = {}
        for item in shown:
            by_category.setdefault(item["category"], []).append(item)
        print("候选 " + str(len(shown)) + " 条（baseline 豁免 " + str(len(excused)) + " 条）")
        for category in sorted(by_category, key=lambda c: -by_category[c][0]["confidence"]):
            items = by_category[category]
            print("")
            print("== " + category + " (" + str(items[0]["confidence"]) + " 分, " + str(len(items)) + ") ==")
            for item in sorted(items, key=lambda i: (i["file"], i["line"])):
                print("  %s:%d  %s %s" % (item["file"], item["line"], item["kind"], item["name"]))
    if args.fail_on_new and kept:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
