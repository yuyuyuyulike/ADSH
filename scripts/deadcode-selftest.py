#!/usr/bin/env python3
"""deadcode.py 的夹具自测：**扫描器自己得被测**（vulture 的 README 把这条写在 Features 里：
tested: it tests itself and has complete test coverage）。

为什么必须有：第 181 轮之前，扫描器的判据是「名字在整个仓库里出现几次」，它的**漏报**
（同名局部变量 / 字符串 / 注释 / import 行都算引用）从来没有人发现 —— 输出为空时分不清
「真没有死代码」还是「扫描器瞎了」。这里用一棵合成的小仓库把每一条判据钉住：
既钉「该报的必须报」（召回），也钉「不该报的必须不报」（精度）。

夹具覆盖的正是旧版栽过的坑：

| 夹具 | 钉住的判据 |
|---|---|
| shadowSource：B 的函数体里有同名局部变量 | 同名遮蔽不算引用（旧版漏报） |
| namedArgSource：B 里写成命名实参 whatever(namedArgSource = 1) | 命名实参的标签不算引用（旧版漏报） |
| deadPrivate / deadMember | private 零引用 = 100 分 |
| Box.touched() 经 this 调用、templateUser 里的 $templatedValue | 成员访问与字符串模板是**真引用**，不许误报 |
| @Suppress("unused") / override / @Test 类 | 豁免表生效 |
| testOnlyInternal（只有 test 调） | internal-test-only 分类 |
| neverUsed 被 A import 但没人用 | import 不是引用：同时报 unused-import 与 public-unused |

用法：python3 scripts/deadcode-selftest.py
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))

MAIN_A = '''package t

import t.B.neverUsed
import t.B.usedHelper

private const val THRESHOLD = 10

private fun deadPrivate() {}

private fun livePrivate() = THRESHOLD

internal fun deadInternal() {}

internal fun testOnlyInternal() {}

internal fun shadowSource() = 1

internal fun namedArgSource() = 2

internal val templatedValue = "x"

fun publicUnused() {}

fun publicUsed() {
    usedHelper()
    livePrivate()
    Box().touched()
    nameCollisions()
    templateUser()
    callWithNamedArgument()
}

class Box {
    private fun touched() {}

    private fun deadMember() {}

    override fun toString(): String = "box"

    @Suppress("unused")
    private fun silencedMember() {}
}
'''

MAIN_B = '''package t

import kotlin.math.abs

fun usedHelper() {}

fun neverUsed() {}

fun nameCollisions() {
    val shadowSource = 1
    val text = "shadowSource 只是字符串"
    // namedArgSource 只是注释
    println(shadowSource + text)
}

fun templateUser(): String = "值：" + "$templatedValue"

fun callWithNamedArgument() {
    whatever(namedArgSource = 1)
}

fun whatever(namedArgSource: Int) {}
'''

TEST_A = '''package t

class Sample {
    fun case() {
        testOnlyInternal()
        publicUsed()
    }
}
'''

EXPECTED = {
    ("unused-import", "neverUsed"),
    ("unused-import", "abs"),
    ("private-unused", "deadPrivate"),
    ("private-unused", "deadMember"),
    ("internal-unused", "deadInternal"),
    ("internal-unused", "shadowSource"),
    ("internal-unused", "namedArgSource"),
    ("internal-test-only", "testOnlyInternal"),
    ("public-unused", "publicUnused"),
    ("public-unused", "neverUsed"),
    ("public-test-only", "publicUsed"),
}


def build_tree(root):
    for sub, name, text in (
        ("main", "A.kt", MAIN_A),
        ("main", "B.kt", MAIN_B),
        ("test", "Sample.kt", TEST_A),
    ):
        path = os.path.join(root, "app", "src", sub, "java", "t")
        os.makedirs(path, exist_ok=True)
        open(os.path.join(path, name), "w", encoding="utf-8").write(text)


def main():
    root = tempfile.mkdtemp(prefix="deadcode-selftest-")
    try:
        build_tree(root)
        out = subprocess.run(
            [sys.executable, os.path.join(HERE, "deadcode.py"), "--root", root, "--json"],
            capture_output=True, text=True, encoding="utf-8",
        )
        if out.returncode != 0:
            print("扫描器退出码 " + str(out.returncode))
            print(out.stderr)
            return 1
        found = {(item["category"], item["name"]) for item in json.loads(out.stdout)}
        missing = sorted(EXPECTED - found)
        extra = sorted(found - EXPECTED)
        if missing or extra:
            print("自测失败")
            for category, name in missing:
                print("  漏报（该报没报）: " + category + " " + name)
            for category, name in extra:
                print("  误报（不该报却报了）: " + category + " " + name)
            return 1
        print("自测通过：" + str(len(EXPECTED)) + " 条判据全部命中，且字符串模板 / 成员访问 / "
              "注解豁免 / 命名实参 / 同名遮蔽都没有误报")
        return 0
    finally:
        shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
