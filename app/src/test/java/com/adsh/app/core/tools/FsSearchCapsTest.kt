package com.adsh.app.core.tools

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * glob / grep 的「上限、顺序、目录」三件事。
 *
 * 这一组契约被实测反馈改过两轮，所以每条都钉住：
 *  - **glob 的 100 上限**：正文与结构化值是**同一页**；
 *  - **grep 的 250 上限**：adsh 刻意让结构化值也封顶（dsh 的值是全量，上限只在正文）——
 *    理由见 Tools.kt 的 grepResult；
 *  - **glob 连目录一起返回**、顺序是**字典序**（dsh 只给文件、按修改时间）。
 */
class FsSearchCapsTest {

    // ---------------------------------------------------------------- 上限

    @Test
    fun globValueIsTheSamePageAsTheFooterSays() {
        val all = (1..150).map { "f" + it + ".kt" }
        val result = globResult(all, ".")

        val paths = result.value["paths"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("结构化值必须只剩第一页", 100, paths.size)
        assertEquals(all.take(100), paths)

        assertTrue(result.text, result.text.contains("(Showing 100 of 150 paths"))
        assertTrue(result.text, result.text.trim().startsWith("f1.kt"))
    }

    @Test
    fun globUnderTheCapKeepsEverythingAndNoFooter() {
        val result = globResult(listOf("a.kt", "b.kt"), ".")
        assertEquals(listOf("a.kt", "b.kt"), result.value["paths"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("a.kt\nb.kt", result.text)
        // 没被截断时也要把标记写出来（false + 真实总数），程序才分得清「正好 100 条」与「被砍到 100」
        assertFalse(result.value["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(2, result.value["totalPaths"]!!.jsonPrimitive.content.toInt())
    }

    /**
     * 截断必须**在结构化值里说得出来**（第 98 轮，测试报告 2.4）：正文里那句
     * "(Showing 100 of 150 paths…)" 在 PTC 模式下进不了模型上下文，程序只拿到值 ——
     * 实测反馈是「调用方无法从返回值判断是否被截断，只能自己记住 100 / 250 这两个上限」。
     */
    @Test
    fun truncationIsVisibleInTheGlobValue() {
        val result = globResult((1..150).map { "f$it.kt" }, ".")
        assertTrue(result.value["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(150, result.value["totalPaths"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun truncationIsVisibleInTheGrepValue() {
        val result = grepResult((1..1000).map { GrepMatch("a.kt", it, "line $it") })
        assertTrue(result.value["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1000, result.value["totalMatches"]!!.jsonPrimitive.content.toInt())

        val small = grepResult((1..3).map { GrepMatch("a.kt", it, "line $it") })
        assertFalse(small.value["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(3, small.value["totalMatches"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun grepValueIsAlsoCappedAtTheDocumentedLimit() {
        val matches = (1..1000).map { GrepMatch("a.kt", it, "line " + it) }
        val result = grepResult(matches)

        assertEquals(
            "值是给程序用的，但上限两边都生效（ADSH 与 dsh 的有意差异）",
            250,
            result.value["matches"]!!.jsonArray.size,
        )
        assertTrue(result.text, result.text.startsWith("Found 250 of 1000 matches"))
        assertTrue(
            result.text,
            result.text.contains("(The complete result could not be saved; narrow pattern, path, or include to see more.)"),
        )
    }

    /**
     * 单行截断**值与正文一起过**（第 197 轮改的，与 dsh 有意不同）：值原来保留完整行，
     * 但那条完整行要跨进程交给 :ptc 里的程序，手机主进程 256MB 的 Java 堆扛不住 ——
     * 真机实测一个 5MB 单行文件的 grep 就能把 app 推到 OOM 闪退。
     * 被截过的命中补 truncated 标记，程序据此知道「这一行没看全」。
     */
    @Test
    fun grepValueLineIsCappedTooAndFlagged() {
        val long = "x".repeat(5_000)
        val result = grepResult(listOf(GrepMatch("a.kt", 7, long)))

        val match = result.value["matches"]!!.jsonArray[0].jsonObject
        val valueLine = match["line"]!!.jsonPrimitive.content
        assertTrue("值也截到 2000 字节 + 标注：" + valueLine.length, valueLine.length < 2_100)
        assertTrue(valueLine.endsWith("(line truncated)"))
        assertEquals("被截过的命中要带 truncated 标记", "true", match["truncated"]!!.jsonPrimitive.content)
        assertTrue("正文里也要带 (line truncated)：\n" + result.text, result.text.endsWith("(line truncated)"))
        assertTrue(result.text.length < 2_100)

        // 短行两边都不动、也不打标记
        val shortMatch = grepResult(listOf(GrepMatch("a.kt", 7, "short line"))).value["matches"]!!.jsonArray[0].jsonObject
        assertEquals("short line", shortMatch["line"]!!.jsonPrimitive.content)
        assertTrue("不该有 truncated", shortMatch["truncated"] == null)
    }

    @Test
    fun emptyResultsSaySo() {
        assertEquals("No matches found", grepResult(emptyList()).text)
        assertEquals(0, grepResult(emptyList()).value["matches"]!!.jsonArray.size)
        assertEquals("No files found", globResult(emptyList(), ".").text)
    }

    // ---------------------------------------------------------------- glob 模式

    @Test
    fun basenamePatternMatchesAtAnyDepth() {
        assertTrue(matchesGlob("*.kt", "c.kt"))
        assertTrue(matchesGlob("*.kt", "a/b/c.kt"))
        assertFalse(matchesGlob("*.kt", "a/b/c.java"))
    }

    @Test
    fun patternWithASeparatorIsAnchored() {
        assertTrue(matchesGlob("src/*", "src/a.kt"))
        assertFalse(matchesGlob("src/*", "other/src/a.kt"))
        assertFalse(matchesGlob("src/*", "src/sub/a.kt"))
    }

    @Test
    fun doubleStarCrossesDirectoriesAndCanVanish() {
        assertTrue(matchesGlob("**/*.kt", "a.kt"))
        assertTrue(matchesGlob("**/*.kt", "x/y/a.kt"))
        assertTrue(matchesGlob("src/**/a.kt", "src/a.kt"))
        assertTrue(matchesGlob("src/**/a.kt", "src/x/y/a.kt"))
    }

    @Test
    fun classesAndAlternationWork() {
        assertTrue(matchesGlob("{a,b}.kt", "b.kt"))
        assertFalse(matchesGlob("{a,b}.kt", "c.kt"))
        assertTrue(matchesGlob("[ab].kt", "a.kt"))
        assertFalse(matchesGlob("[ab].kt", "c.kt"))
        assertTrue(matchesGlob("?.kt", "a.kt"))
    }

    // ---------------------------------------------------------------- glob 目录

    private fun tree(root: File) {
        File(root, "a.kt").writeText("a")
        File(root, "src").mkdirs()
        File(root, "src/b.kt").writeText("b")
        File(root, "src/sub").mkdirs()
        File(root, "src/sub/c.kt").writeText("c")
        File(root, ".git").mkdirs()
        File(root, ".git/config").writeText("x")
    }

    private fun tempTree(): File {
        val dir = File.createTempFile("globtree", "").let { it.delete(); it.mkdirs(); it }
        tree(dir)
        return dir
    }

    @Test
    fun directoriesAreCollectedRelativeToTheWorkspaceRootNotTheSearchRoot() {
        val root = tempTree()
        try {
            // 搜索根是 root/src，但返回的路径必须是**工作区根相对**的（喂给 read 才解析得到）
            val dirs = collectMatchingDirectories(File(root, "src"), root, "*")
            assertEquals(listOf("src/sub"), dirs)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun directoriesMatchThePatternAndSkipVcsMetadata() {
        val root = tempTree()
        try {
            // 双星号（无斜杠）⇒ 基名匹配：每个目录都算命中；.git 不看
            assertEquals(listOf("src", "src/sub"), collectMatchingDirectories(root, root, "**"))
            // 有斜杠 ⇒ 锚定
            assertEquals(listOf("src/sub"), collectMatchingDirectories(root, root, "src/*"))
            assertEquals(emptyList<String>(), collectMatchingDirectories(root, root, "nope/*"))
        } finally {
            root.deleteRecursively()
        }
    }
}
