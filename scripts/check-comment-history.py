#!/usr/bin/env python3
"""注释门禁：代码里禁止出现「第 N 轮」与 R<NN> 轮次引用（历史归 CHANGELOG.md 与 commit message）。

用法：
    python scripts/check-comment-history.py                        # 报告：总数 + 注释/字符串/代码 三类分布 + 命中最多的文件
    python scripts/check-comment-history.py --baseline scripts/comment-history-baseline.json
    python scripts/check-comment-history.py --strict               # 注释/代码命中即失败（字符串只报告）
    python scripts/check-comment-history.py --long-blocks 5        # 额外列出连续注释超过 5 行的块（只报告）
    python scripts/check-comment-history.py --write-baseline       # 按当前状态重写 baseline
    python scripts/check-comment-history.py --selftest
    python scripts/check-comment-history.py --root <目录>           # 换扫描根

口径：
  - 匹配：阿拉伯数字（含全角）任意长度，或中文数字 ≥2 字 / 十开头 —— 第 183 轮、第八十三轮、第十轮；
    单字「第一轮 / 第二轮」是会话回合语义，不算（见 ROUND_RE 的注释）；
  - 用与 check-package-cycles.py 同一套状态机（可嵌套块注释、普通串、三引号原始串、
    字符字面量），但这里是**分类**而不是抹掉：命中落在注释里 = 注释，落在字符串里 = 字符串，
    其余 = 代码（标识符等）。后两类不在注释里、涉及可执行文本，单独报；
  - 扫描范围：.kt/.kts/.c/.h/.js/.pro/.sh（注释语法按扩展名分派，见 SUFFIX_TO_LANG）；
    文档（.md）**明确不扫** —— CHANGELOG.md / HANDOFF.md 是历史的家。
    跳过 build/.gradle/.kotlin/.tmp*/.git/docs/dist 这些非代码目录；
  - **注释命中与代码（标识符）命中判失败**；**字符串命中只报告不失败**（字符串是内容：UI 文案、
    测试数据、内嵌脚本注释都可能合法出现这种字样）。报告里会提示：若该字符串是内嵌脚本
    （JS / shell 前导码）里的注释，应当按注释对待并改掉；
  - 报告模式不失败；--baseline 只对「某个文件的注释/代码比基线多」失败；--strict 有注释或代码命中即失败。

退出码：0 通过 / 1 门禁失败 / 2 用法或文件错误。
"""
import json
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE_DEFAULT = "scripts/comment-history-baseline.json"

# 口径：阿拉伯数字（含全角）任意长度；中文数字要「≥2 字」或「十开头」（即数值 ≥10）。
# 单字「第一轮 / 第二轮 / 第九轮」按会话回合语义算，不进门禁 —— 那是正经中文，不是项目轮次史。
# 区间写法（第 108～113 轮）同样算一处；数字与「轮」之间夹「几 / 多」（第 60 几轮）也算。
_NUM = r"(?:[0-9０-９]+|[一二三四五六七八九十百零两]{2,}|十[一二三四五六七八九十百零两]*)"
ROUND_RE = re.compile(r"第\s*" + _NUM + r"(?:\s*[～~—-]\s*" + _NUM + r")?\s*[几多]?\s*轮")
# 轮次引用还有「R21 从 AppRoot 搬出来」这种写法。排除两类合法同名：
#   - R8：Android 的 R8 编译器（AGP 的 release 混淆器），不是项目轮次；
#   - DeepSeek-R1 这类模型名：R 前是连字符。
ROUND_TOKEN_RE = re.compile(r"(?<![A-Za-z0-9_-])R(?!8\b)\d{1,3}\b")
HISTORY_RES = (ROUND_RE, ROUND_TOKEN_RE)
LANG_KOTLIN, LANG_C, LANG_HASH = "kotlin", "c", "hash"
# 注释语法按扩展名分派：Kotlin/C/JS 是 // + /* */，shell/ProGuard 是 # 行注释。
SUFFIX_TO_LANG = {
    ".kt": LANG_KOTLIN,
    ".kts": LANG_KOTLIN,
    ".c": LANG_C,
    ".h": LANG_C,
    ".js": LANG_C,
    ".pro": LANG_HASH,
    ".sh": LANG_HASH,
}
SCAN_SUFFIXES = set(SUFFIX_TO_LANG)
# shell 里 # 只在词首起注释；echo "# 不是注释" 里的不算（那是字符串）。
HASH_COMMENT_START = set(" \t\n;&|()")
# 非代码目录：构建产物、文档（历史本来就归 CHANGELOG）、第三方资源；点目录（.git/.gradle/.tmp*）另外单独跳过。
SKIP_DIRS = {"build", "docs", "dist", "assets-src", "__pycache__"}

KIND_COMMENT, KIND_STRING, KIND_CODE = "comment", "string", "code"
KIND_LABEL = {KIND_COMMENT: "注释里", KIND_STRING: "字符串里", KIND_CODE: "代码里"}

CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, RAW_STRING, CHAR = range(6)


def classify(text, lang=LANG_KOTLIN):
    """逐字符分类：c 代码 / m 注释 / s 字符串（换行原样保留，长度与输入一致）。

    注释语法按语言分派：Kotlin/C/JS 走 // 与 /* */（Kotlin 的块注释可嵌套，C/JS 不可，
    后者按 C 语义在第一个 */ 收尾）；shell/ProGuard 走 # 行注释，且只在词首才算。
    """
    slash = lang in (LANG_KOTLIN, LANG_C)
    raw_strings = lang == LANG_KOTLIN
    nested_blocks = lang == LANG_KOTLIN
    hash_comments = lang == LANG_HASH
    out = []
    i = 0
    n = len(text)
    state = CODE
    depth = 0
    while i < n:
        c = text[i]
        if state == CODE:
            if slash and c == "/" and text.startswith("//", i):
                out.append("mm")
                i += 2
                state = LINE_COMMENT
            elif slash and c == "/" and text.startswith("/*", i):
                out.append("mm")
                i += 2
                state = BLOCK_COMMENT
                depth = 1
            elif raw_strings and text.startswith('"""', i):
                out.append("sss")
                i += 3
                state = RAW_STRING
            elif hash_comments and c == "#" and (i == 0 or text[i - 1] in HASH_COMMENT_START):
                out.append("m")
                i += 1
                state = LINE_COMMENT
            elif c == '"':
                out.append("s")
                i += 1
                state = STRING
            elif c == "'":
                out.append("s")
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
                out.append("m")
            i += 1
        elif state == BLOCK_COMMENT:
            if nested_blocks and text.startswith("/*", i):
                out.append("mm")
                i += 2
                depth += 1
            elif text.startswith("*/", i):
                out.append("mm")
                i += 2
                depth -= 1
                if depth == 0:
                    state = CODE
            else:
                out.append("\n" if c == "\n" else "m")
                i += 1
        elif state == RAW_STRING:
            if text.startswith('"""', i):
                out.append("sss")
                i += 3
                state = CODE
            else:
                out.append("\n" if c == "\n" else "s")
                i += 1
        elif state == STRING:
            if c == "\\":
                out.append("ss")
                i += 2
            elif c == '"':
                out.append("s")
                i += 1
                state = CODE
            elif c == "\n":
                out.append("\n")
                i += 1
                state = CODE
            else:
                out.append("s")
                i += 1
        else:  # CHAR
            if c == "\\":
                out.append("ss")
                i += 2
            elif c == "'":
                out.append("s")
                i += 1
                state = CODE
            elif c == "\n":
                out.append("\n")
                i += 1
                state = CODE
            else:
                out.append("s")
                i += 1
    return "".join(out)


def lang_of(name):
    """按扩展名决定注释语法；不在扫描范围内的返回 None。"""
    return SUFFIX_TO_LANG.get(Path(name).suffix)


def file_hits(text, lang=LANG_KOTLIN):
    """这个文件里所有轮次引用命中：{line, kind, text}（第 N 轮 + R<NN>）。"""
    classes = classify(text, lang)
    lines = text.split("\n")
    hits = []
    for regex in HISTORY_RES:
        lineno = 1
        pos = 0
        for m in regex.finditer(text):
            lineno += text.count("\n", pos, m.start())
            pos = m.start()
            cls = classes[m.start()] if m.start() < len(classes) else "c"
            kind = {"m": KIND_COMMENT, "s": KIND_STRING}.get(cls, KIND_CODE)
            body = lines[lineno - 1].strip() if lineno - 1 < len(lines) else ""
            hits.append({"line": lineno, "kind": kind, "text": body[:140], "pos": m.start()})
    hits.sort(key=lambda h: h["pos"])
    return hits
def long_comment_blocks(text, limit, lang=LANG_KOTLIN):
    """连续「整行都是注释」的行数超过 limit 的块（空行算块内，不打断）。"""
    classes = classify(text, lang)
    lines = text.split("\n")
    blocks = []
    offset = 0
    start = None
    count = 0
    for i, line in enumerate(lines, 1):
        seg = classes[offset:offset + len(line)]
        offset += len(line) + 1
        if not line.strip():
            continue
        pure_comment = all(ch == "m" for ch in seg if not ch.isspace())
        if pure_comment:
            if start is None:
                start = i
            count += 1
        else:
            if start is not None and count > limit:
                blocks.append((start, i - 1, count))
            start, count = None, 0
    if start is not None and count > limit:
        blocks.append((start, len(lines), count))
    return blocks


def collect_files(root):
    out = {}
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix not in SCAN_SUFFIXES:
            continue
        parts = path.relative_to(root).parts
        # 点目录一律跳过：.git/.gradle/.kotlin/.tmp* 以及别人放临时备份的目录。
        if any(part.startswith(".") for part in parts) or set(parts) & SKIP_DIRS:
            continue
        rel = str(path.relative_to(root)).replace("\\", "/")
        out[rel] = path.read_text(encoding="utf-8", errors="replace")
    return out


def scan(root):
    """-> (每文件分类计数, 非注释命中明细, 连续注释块)"""
    counts = {}
    foreign = []
    blocks = []
    for name, text in collect_files(root).items():
        lang = lang_of(name)
        if lang is None:
            continue
        hits = file_hits(text, lang)
        if hits:
            per_kind = {KIND_COMMENT: 0, KIND_STRING: 0, KIND_CODE: 0}
            for hit in hits:
                per_kind[hit["kind"]] += 1
                if hit["kind"] != KIND_COMMENT:
                    foreign.append({"file": name, **hit})
            counts[name] = per_kind
        for start, end, size in long_comment_blocks(text, 0, lang):
            blocks.append({"file": name, "start": start, "end": end, "size": size})
    return counts, foreign, blocks


def totals(counts):
    total = {KIND_COMMENT: 0, KIND_STRING: 0, KIND_CODE: 0}
    for per_kind in counts.values():
        for kind in total:
            total[kind] += per_kind.get(kind, 0)
    return total


def parse_path_arg(argv, flag, default):
    if flag not in argv:
        return None, False
    idx = argv.index(flag)
    nxt = argv[idx + 1] if idx + 1 < len(argv) else None
    if nxt is None or nxt.startswith("--"):
        return default, True
    return nxt, True


def print_report(counts, foreign, grand, blocks, long_limit):
    per_kind = totals(counts)
    print(f"注释门禁（禁止「第 N 轮」与 R<NN> 轮次引用）：共 {grand} 处 / {len(counts)} 个文件")
    for kind in (KIND_COMMENT, KIND_STRING, KIND_CODE):
        print(f"  {KIND_LABEL[kind]}{per_kind[kind]:>5} 处")
    if foreign:
        print(f"不在注释里的命中（涉及可执行文本，单独处理）：{len(foreign)} 处")
        for i, hit in enumerate(foreign[:30], 1):
            print(f"  [{i}] {hit['file']}:{hit['line']}  {KIND_LABEL[hit['kind']]}")
            print(f"      > {hit['text']}")
        if len(foreign) > 30:
            print(f"  ... 还有 {len(foreign) - 30} 处")
    if per_kind[KIND_STRING]:
        print("  提示：字符串里的命中只报告、不判失败；若它是内嵌脚本（JS / shell 前导码）里的注释，"
              "应当按注释对待并改掉。")
    top = sorted(counts.items(), key=lambda kv: (-sum(kv[1].values()), kv[0]))[:10]
    if top:
        print("命中最多的文件：")
        for name, kinds in top:
            print(f"  {sum(kinds.values()):>4}  {name}")
    if long_limit is not None:
        long_blocks = sorted((b for b in blocks if b["size"] > long_limit), key=lambda b: -b["size"])
        print(f"连续注释块 > {long_limit} 行：{len(long_blocks)} 处（只报告，给清理当线索）")
        for b in long_blocks[:30]:
            print(f"  {b['size']:>4} 行  {b['file']}:{b['start']}-{b['end']}")
        if len(long_blocks) > 30:
            print(f"  ... 还有 {len(long_blocks) - 30} 处")


def build_baseline(counts):
    return {
        "note": "「第 N 轮」清理的基线：按文件 + 分类记数，只卡「比基线多」。清完改用 --strict。",
        "by_kind": totals(counts),
        "files": {name: dict(kinds) for name, kinds in sorted(counts.items())},
    }


def blocking_hits(counts):
    """--strict / --baseline 只盯注释与代码；字符串是内容，只报告。"""
    return sum(
        kinds.get(KIND_COMMENT, 0) + kinds.get(KIND_CODE, 0)
        for kinds in counts.values()
    )


def new_hits(counts, baseline_files):
    """比基线多出来的**注释/代码**命中（字符串不参与判定）。"""
    fresh = []
    for name, kinds in counts.items():
        base = baseline_files.get(name, {})
        for kind in (KIND_COMMENT, KIND_CODE):
            delta = kinds.get(kind, 0) - int(base.get(kind, 0))
            if delta > 0:
                fresh.append((name, kind, delta))
    return sorted(fresh)


def selftest():
    cases = []

    def case(name, text, expected):
        actual = [(h["kind"], h["line"]) for h in file_hits(text)]
        cases.append((actual == expected, name, actual, expected))

    case(
        "注释里的算",
        "package a\n// 第 1 轮改的\nval x = 1\n",
        [(KIND_COMMENT, 2)],
    )
    case(
        "字符串里的单独算成字符串",
        'package a\nval s = "第 1 轮"\n',
        [(KIND_STRING, 2)],
    )
    case(
        "三引号原始串里的算字符串",
        'package a\nval js = """\n// 第 12 轮\n"""\n',
        [(KIND_STRING, 3)],
    )
    case(
        "第 10 轮与第 1 轮都算",
        "package a\n// 第 10 轮\n/* 第 1 轮 */\n",
        [(KIND_COMMENT, 2), (KIND_COMMENT, 3)],
    )
    case(
        "同一行既有字符串又有注释，各算一次不重复",
        'package a\nval s = "第 2 轮" // 第 3 轮\n',
        [(KIND_STRING, 2), (KIND_COMMENT, 2)],
    )
    case(
        "数字后夹「几」：第 60 几轮命中",
        "package a\n// 第 60 几轮改的\nval x = 1\n",
        [(KIND_COMMENT, 2)],
    )
    case(
        "代码里的（标识符）算代码类",
        "package a\nfun 第1轮() = 1\n",
        [(KIND_CODE, 2)],
    )
    case(
        "嵌套块注释里的算注释",
        "package a\n/* 外 /* 第 4 轮 */ 还是注释 */\nval x = 1\n",
        [(KIND_COMMENT, 2)],
    )
    case(
        "不带数字的「上一轮」不算",
        "package a\n// 上一轮改的\nval x = 1\n",
        [],
    )
    case(
        "阿拉伯数字：第 183 轮命中",
        "package a\n// 第 183 轮\n",
        [(KIND_COMMENT, 2)],
    )
    case(
        "全角阿拉伯数字：第８３轮命中",
        "package a\n// 第８３轮\n",
        [(KIND_COMMENT, 2)],
    )
    case(
        "中文数字 ≥2 字：第八十三轮命中",
        "package a\n// 第八十三轮\n",
        [(KIND_COMMENT, 2)],
    )
    case(
        "中文数字十开头：第十轮命中",
        "package a\n// 第十轮\n",
        [(KIND_COMMENT, 2)],
    )
    case(
        "中文数字单字第一轮不算（会话回合语义）",
        "package a\n// 第一轮\nval x = 1\n",
        [],
    )
    case(
        "中文数字单字第二轮不算",
        "package a\n// 第二轮\nval x = 1\n",
        [],
    )
    case(
        "上一轮 / 下一轮都不算",
        "package a\n// 上一轮\n// 下一轮\n",
        [],
    )

    def case_lang(name, text, lang, expected):
        actual = [(h["kind"], h["line"]) for h in file_hits(text, lang)]
        cases.append((actual == expected, name, actual, expected))

    case_lang("C 块注释算注释", "/* 第八十三轮 */\nint x;\n", LANG_C, [(KIND_COMMENT, 1)])
    case_lang("C 行注释算注释", "// 第八十三轮\nint x;\n", LANG_C, [(KIND_COMMENT, 1)])
    case_lang("shell 的 # 算注释", "#!/bin/sh\n# 第八十三轮\necho hi\n", LANG_HASH, [(KIND_COMMENT, 2)])
    case_lang(
        "shell 里 echo \"# …\" 不算注释（那是字符串）",
        'echo "# 第八十三轮"\n',
        LANG_HASH,
        [(KIND_STRING, 1)],
    )
    case_lang("ProGuard 的 # 也算注释", "# 第八十三轮\n-keep class X\n", LANG_HASH, [(KIND_COMMENT, 1)])
    case_lang("C 里 # 不是注释（NS_ 之类的标识符不误判）", "int a#b;\n", LANG_C, [])
    case("区间写法：第 108～113 轮命中", "package a\n// 第 108～113 轮\n", [(KIND_COMMENT, 2)])

    case("R 轮次引用：R21 命中", "package a\n// R21 从 AppRoot 搬出来\n", [(KIND_COMMENT, 2)])
    case("R8 是 Android 编译器，不算", "package a\n// R8 会删掉未用代码\n", [])
    case("DeepSeek-R1 模型名不算", 'package a\nval m = "DeepSeek-R1"\n', [])
    case("R80 两位以上轮次仍命中", "package a\n// R80 的改动\n", [(KIND_COMMENT, 2)])
    case("字符串里的 R2 算字符串", 'package a\nval s = "R2: 说明"\n', [(KIND_STRING, 2)])

    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / "A.c").write_text("int x;\n", encoding="utf-8")
        (Path(tmp) / "B.md").write_text("// 第八十三轮\n", encoding="utf-8")
        (Path(tmp) / ".tmp-x").mkdir()
        (Path(tmp) / ".tmp-x" / "C.c").write_text("// 第八十三轮\n", encoding="utf-8")
        collected = sorted(collect_files(Path(tmp)))
        cases.append((
            collected == ["A.c"],
            ".md 与点目录都不进扫描（历史归 CHANGELOG/HANDOFF）",
            collected,
            ["A.c"],
        ))

    blocks = long_comment_blocks("package a\n// 1\n// 2\n// 3\nval x = 1\n", 2)
    cases.append((blocks == [(2, 4, 3)], "连续注释块能数出行数", blocks, [(2, 4, 3)]))

    cases.append((
        blocking_hits({"a/A.kt": {KIND_COMMENT: 0, KIND_STRING: 3, KIND_CODE: 0}}) == 0
        and blocking_hits({"a/A.kt": {KIND_COMMENT: 1, KIND_STRING: 3, KIND_CODE: 1}}) == 2,
        "--strict 只算注释与代码，字符串不判失败",
        "见左",
        "见左",
    ))

    counts = {"a/A.kt": {KIND_COMMENT: 2, KIND_STRING: 1, KIND_CODE: 0}}
    cases.append((
        new_hits(counts, {}) == [("a/A.kt", KIND_COMMENT, 2)],
        "没有基线时注释全算新增（字符串不参与判定）",
        new_hits(counts, {}),
        "一条",
    ))
    cases.append((
        new_hits(counts, {"a/A.kt": {KIND_COMMENT: 2, KIND_STRING: 1, KIND_CODE: 0}}) == [],
        "与基线持平不算新增",
        new_hits(counts, {"a/A.kt": {KIND_COMMENT: 2, KIND_STRING: 1, KIND_CODE: 0}}),
        "空",
    ))
    cases.append((
        new_hits(counts, {"a/A.kt": {KIND_COMMENT: 1, KIND_STRING: 1, KIND_CODE: 0}})
        == [("a/A.kt", KIND_COMMENT, 1)],
        "比基线多一处就算新增",
        new_hits(counts, {"a/A.kt": {KIND_COMMENT: 1, KIND_STRING: 1, KIND_CODE: 0}}),
        "一条",
    ))

    failed = 0
    for ok, name, actual, expected in cases:
        print(f"{'ok  ' if ok else 'FAIL'}  {name}")
        if not ok:
            failed += 1
            print(f"      实际: {actual}")
            print(f"      期望: {expected}")
    print(f"自检：{len(cases) - failed}/{len(cases)} 通过")
    return 1 if failed else 0


def main():
    argv = sys.argv[1:]
    if "--selftest" in argv:
        return selftest()

    strict = "--strict" in argv
    baseline_path, has_baseline = parse_path_arg(argv, "--baseline", BASELINE_DEFAULT)
    write_path, has_write = parse_path_arg(argv, "--write-baseline", BASELINE_DEFAULT)
    long_arg, has_long = parse_path_arg(argv, "--long-blocks", None)

    scan_root = ROOT
    if "--root" in argv:
        idx = argv.index("--root")
        if idx + 1 < len(argv):
            scan_root = Path(argv[idx + 1]).resolve()

    long_limit = None
    if has_long:
        try:
            long_limit = int(long_arg)
        except (TypeError, ValueError):
            print("--long-blocks 需要一个整数，例如 --long-blocks 5")
            return 2

    counts, foreign, blocks = scan(scan_root)
    per_kind = totals(counts)
    grand = sum(per_kind.values())

    if has_write:
        target = ROOT / write_path
        body = json.dumps(build_baseline(counts), ensure_ascii=False, indent=2) + "\n"
        # 显式 LF：Windows 上 write_text 默认会把 \n 翻成 CRLF，仓库统一 LF。
        target.write_text(body, encoding="utf-8", newline="\n")
        print(f"已写入基线：{write_path}（共 {grand} 处 / {len(counts)} 个文件）")
        return 0

    print_report(counts, foreign, grand, blocks, long_limit)

    if strict:
        blocking = blocking_hits(counts)
        if blocking:
            print(f"门禁失败（--strict）：注释/代码里还有 {blocking} 处"
                  f"（注释 {per_kind[KIND_COMMENT]}、代码 {per_kind[KIND_CODE]}）；"
                  f"字符串另有 {per_kind[KIND_STRING]} 处，只报告不判失败。")
            return 1
        print(f"门禁通过（--strict）：注释/代码 0 处（字符串命中 {per_kind[KIND_STRING]} 处，只报告）。")
        return 0

    if not has_baseline:
        print("（报告模式：不失败。清理中用 --baseline；清完切 --strict）")
        return 0

    target = ROOT / baseline_path
    if not target.is_file():
        print(f"基线文件不存在：{baseline_path}")
        return 2
    data = json.loads(target.read_text(encoding="utf-8"))
    base_files = data.get("files", {})
    base_total = sum(sum(v.values()) for v in base_files.values())
    fresh = new_hits(counts, base_files)
    print(f"基线 {base_total} 处 / {len(base_files)} 个文件；现在 {grand} 处 / {len(counts)} 个文件"
          f"（净减 {base_total - grand}）")
    if fresh:
        print(f"新增 {sum(d for _n, _k, d in fresh)} 处，出自 {len({n for n, _k, _d in fresh})} 个文件：")
        for name, kind, delta in fresh[:30]:
            print(f"  +{delta}  {name}  （{KIND_LABEL[kind]}）")
        print("门禁失败：出现基线之外的新命中 —— 清理只能往下走。")
        return 1
    print("门禁通过：没有比基线多出来的命中。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
