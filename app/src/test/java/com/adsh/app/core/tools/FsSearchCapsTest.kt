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

    @Test
    fun grepValueLineStaysCompleteWhileTheTextShowsTheTruncationMarker() {
        val long = "x".repeat(5_000)
        val result = grepResult(listOf(GrepMatch("a.kt", 7, long)))

        val valueLine = result.value["matches"]!!.jsonArray[0].jsonObject["line"]!!.jsonPrimitive.content
        assertEquals("单行截断只作用在正文上（值里保留完整行）", 5_000, valueLine.length)
        assertTrue("正文里要带 (line truncated)：\n" + result.text, result.text.endsWith("(line truncated)"))
        assertTrue(result.text.length < 2_100)
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
