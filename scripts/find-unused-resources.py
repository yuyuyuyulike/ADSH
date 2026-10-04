#!/usr/bin/env python3
"""没人引用的 Android 资源（第 182 轮重写；旧版只认 strings.xml 与 drawable/*.xml）。

## 旧版的问题

判据是「整个仓库的文本里有没有 R.string.X / @string/X」——两个字面串搜一遍。于是：

* **覆盖面**：values 里除 string 以外的条目（color / dimen / style / bool / array / plurals /
  integer）与 mipmap / xml / layout / raw 下的文件**从来没扫过**（真机上一眼可见的
  ic_launcher 那些就是这么「安全」的）；
* **语料**：把 README / HANDOFF / 扫描器自己的源码也算进语料 —— 文档里提一句资源名，
  这个资源就永远躲过扫描（漏报）；
* **注释**：Kotlin 注释里的 R.string.X 同样算引用（与 Kotlin 侧旧扫描器同款毛病）。

## 现在的判据

* 候选 = app/src/**/res 下的每个资源（values* 的条目 + drawable*/mipmap*/xml/layout/menu/anim/
  raw/font 下的每个文件）；
* 引用 = 语料里出现 R.类型.名字 或 @类型/名字（类型按资源种类映射：
  string-array/array 归 array，plurals 归 plurals，其余同名）；
* 语料 = 仓库里的 .kt（先剥注释，见 kt_source.py）、.xml、.kts、.toml、.json、.properties、
  .sh、.js、.c、.h、.py —— **不含 .md 与 scripts/**（文档与扫描器自己提一句不算引用）；
* 豁免：tools:keep 点名的资源（那是显式的「别删」声明）、android: 命名空间的引用不算我们的。

与 Android Lint 的分工：gradlew 的 :app:lintDebug 里 UnusedResources 是**语义级**交叉验证
（它看得懂 variants / tools:keep / 资源合并，但慢、且要跑 Gradle）。本脚本是快的那一半。

用法：python3 scripts/find-unused-resources.py [--json]
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kt_source import strip_comments  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# values 里的条目 → 引用时用的类型名
VALUE_TAGS = {
    "string": "string", "string-array": "array", "array": "array", "integer-array": "array",
    "plurals": "plurals", "integer": "integer", "bool": "bool", "color": "color",
    "dimen": "dimen", "style": "style", "fraction": "fraction", "attr": "attr",
}
# 目录 → 引用时用的类型名
FILE_DIRS = {
    "drawable": "drawable", "mipmap": "mipmap", "xml": "xml", "layout": "layout",
    "menu": "menu", "anim": "anim", "animator": "animator", "raw": "raw", "font": "font",
    "transition": "transition", "interpolator": "interpolator", "navigation": "navigation",
}
CORPUS_EXT = (".kt", ".xml", ".kts", ".toml", ".json", ".properties", ".sh", ".js", ".c", ".h",
              ".py", ".gradle")
SKIP_DIRS = {".git", "build", ".gradle", ".idea", ".kotlin", ".cxx", "scripts", "dist",
             "node_modules"}
ENTRY_RE = re.compile(r"<(?P<tag>[a-z-]+)\s+[^>]*name=\"(?P<name>[^\"]+)\"")
KEEP_RE = re.compile(r"tools:keep=\"([^\"]+)\"")
REF_TYPE_ALIASES = {"array": ("array", "string-array", "integer-array")}


def resource_dirs():
    for base in (os.path.join(ROOT, "app", "src", "main", "res"),
                 os.path.join(ROOT, "app", "src", "debug", "res")):
        if os.path.isdir(base):
            yield base


def collect_candidates():
    candidates = []
    for res in resource_dirs():
        for name in sorted(os.listdir(res)):
            path = os.path.join(res, name)
            rel = os.path.relpath(path, ROOT).replace("\\", "/")
            if name.startswith("values"):
                if not os.path.isdir(path):
                    continue
                for xml in sorted(os.listdir(path)):
                    if not xml.endswith(".xml"):
                        continue
                    body = open(os.path.join(path, xml), encoding="utf-8", errors="replace").read()
                    for match in ENTRY_RE.finditer(body):
                        tag, key = match.group("tag"), match.group("name")
                        kind = VALUE_TAGS.get(tag)
                        if kind:
                            candidates.append((rel + "/" + xml, kind, key))
                continue
            kind = next((k for d, k in FILE_DIRS.items()
                         if name == d or name.startswith(d + "-")), None)
            if kind and os.path.isdir(path):
                for entry in sorted(os.listdir(path)):
                    if os.path.isfile(os.path.join(path, entry)):
                        candidates.append((rel + "/" + entry, kind, os.path.splitext(entry)[0]))
    # 同一个资源在 values 与 values-night 各写一份：按「类型 + 名字」去重，只报一次
    unique, seen = [], set()
    for item in candidates:
        key = (item[1], item[2])
        if key in seen:
            continue
        seen.add(key)
        unique.append(item)
    return unique


def corpus_text():
    chunks = []
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in files:
            if not name.endswith(CORPUS_EXT):
                continue
            path = os.path.join(base, name)
            try:
                text = open(path, encoding="utf-8", errors="ignore").read()
            except OSError:
                continue
            # Kotlin 先剥注释：注释里提一句 R.string.X 不算引用（旧版的漏报源之一）
            chunks.append(strip_comments(text) if name.endswith(".kt") else text)
    return "\n".join(chunks)


def main():
    text = corpus_text()
    kept = set()
    for group in KEEP_RE.findall(text):
        for part in group.split(","):
            kept.add(part.strip().lstrip("@").split("/")[-1])
    candidates = []
    for path, kind, key in collect_candidates():
        if key in kept:
            continue
        names = REF_TYPE_ALIASES.get(kind, (kind,))
        referenced = any(
            ("R." + alias + "." + key) in text or ("@" + alias + "/" + key) in text
            for alias in names
        )
        if not referenced:
            candidates.append({"file": path, "kind": kind, "name": key, "confidence": 90})
    if "--json" in sys.argv:
        print(json.dumps(candidates, ensure_ascii=False, indent=1))
    else:
        print("候选（没人引用的资源）:", len(candidates))
        for item in candidates:
            print("  %-62s %-9s %s" % (item["file"], item["kind"], item["name"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
