package com.adsh.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `@` 文件补全的规则层：token 语法、插入格式、目录 / 模糊两条查询、排序与有界索引。
 * 全部照 dsh 的 grammar.ts / WorkspaceFileSearch（见 .tmp/research-dsh-mention.md）。
 */
class MentionTest {

    // ---------------------------------------------------------------- token 语法

    @Test
    fun tokenStartsAtLineStartOrAfterWhitespace() {
        assertEquals(ActiveMention(0, 4, "src", quoted = false), activeAtToken("@src", 4))
        assertEquals(ActiveMention(3, 10, "src/tu", quoted = false), activeAtToken("hi @src/tu", 10))
        assertEquals(ActiveMention(0, 1, "", quoted = false), activeAtToken("@", 1))
        assertEquals(ActiveMention(3, 5, "b", quoted = false), activeAtToken("@a\n@b", 5))
    }

    @Test
    fun emailAndMidWordAtDoNotTrigger() {
        assertNull(activeAtToken("mail a@b.test", 13))
        assertNull(activeAtToken("foo@bar", 7))
        assertNull(activeAtToken("x(@y", 4))
    }

    @Test
    fun tokenMustEndAtTheCaretAndCannotCrossWhitespace() {
        assertNull("光标在 @ 之前", activeAtToken("x @a", 1))
        assertNull("@ 后打了空格", activeAtToken("@src ", 5))
        assertNull("不在 token 里", activeAtToken("hello", 5))
        assertNull("草稿 0", activeAtToken("", 0))
    }

    @Test
    fun openQuoteAllowsSpacesUntilTheQuoteCloses() {
        assertEquals(
            ActiveMention(5, 20, "docs/design n", quoted = true),
            activeAtToken("read @\"docs/design n", 20),
        )
    }

    // ---------------------------------------------------------------- 插入格式

    @Test
    fun pickFormatKeepsFileAndDirectoryTokens() {
        assertEquals("@src/index.ts", formatMentionPick("src/index.ts", directory = false))
        assertEquals("@src/", formatMentionPick("src", directory = true))
    }

    @Test
    fun whitespacePathsUseClosedQuotedTokens() {
        assertEquals("@\"docs/design notes.md\"", formatMentionPick("docs/design notes.md", directory = false))
        assertEquals("@\"docs/design notes/\"", formatMentionPick("docs/design notes", directory = true))
    }

    @Test
    fun drillKeepsTheQuoteOpenOnlyWhenItWasAlreadyOpen() {
        assertEquals("@src/", formatMention("src", directory = true, preserveQuote = false))
        assertEquals("@\"docs/design notes/", formatMention("docs/design notes", directory = true, preserveQuote = true))
        assertEquals("@\"docs/design notes/", formatMention("docs/design notes", directory = true, preserveQuote = false))
    }

    @Test
    fun unsafePathsAreRejected() {
        assertNull(formatMentionPick("bad\nname", directory = false))
        assertNull(formatMentionPick("bad \"name\".md", directory = false))
        assertNull(formatMentionPick("", directory = false))
    }

    // ---------------------------------------------------------------- 候选查询

    private fun entries(vararg paths: Pair<String, Boolean>) =
        paths.map { MentionEntry(it.first, it.second) }

    private val sample = entries(
        "src" to true,
        "src/main" to true,
        "src/main/App.kt" to false,
        "README.md" to false,
        ".gitignore" to false,
        ".github" to true,
        "docs/design notes.md" to false,
    )

    private fun pathsOf(entries: List<MentionEntry>) = entries.map { if (it.directory) it.path + "/" else it.path }

    @Test
    fun emptyQueryListsTheWorkspaceRootDirectoriesFirst() {
        assertEquals(listOf("src/", "README.md"), pathsOf(mentionEntriesFor(sample, "")))
    }

    @Test
    fun queryWithSlashListsThatDirectoryOnly() {
        assertEquals(listOf("src/main/"), pathsOf(mentionEntriesFor(sample, "src/")))
        assertEquals(
            listOf("src/main/App.kt"),
            pathsOf(mentionEntriesFor(sample, "src/main/")),
        )
    }

    @Test
    fun fuzzyQueryRanksNameMatchesBeforePathMatches() {
        val result = pathsOf(mentionEntriesFor(sample, "app"))
        assertEquals("src/main/App.kt", result.first())
        assertTrue("目录排在同分文件之前", mentionEntriesFor(sample, "src").first().directory)
    }

    @Test
    fun hiddenEntriesNeedAQueryThatStartsWithADot() {
        assertEquals(listOf(".github/", ".gitignore"), pathsOf(mentionEntriesFor(sample, ".git")))
        assertTrue(mentionEntriesFor(sample, "src").none { it.path.startsWith(".") })
    }

    @Test
    fun absoluteParentAndExcludedDirectoriesAreNotListed() {
        assertTrue(mentionEntriesFor(sample, "/etc").isEmpty())
        assertTrue(mentionEntriesFor(sample, "../secret").isEmpty())
        assertTrue(mentionEntriesFor(sample, "node_modules/").isEmpty())
    }

    @Test
    fun resultsAreCapped() {
        assertEquals(1, mentionEntriesFor(sample, "src", maxResults = 1).size)
        assertEquals(0, mentionEntriesFor(sample, "src", maxResults = 0).size)
    }

    // ---------------------------------------------------------------- 有界索引

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun indexWalksTheWorkspaceAndSkipsBuildAndDependencyDirectories() {
        val root = temp.newFolder("ws")
        File(root, "a.txt").writeText("a")
        File(root, "src").mkdirs()
        File(root, "src/b.kt").writeText("b")
        File(root, "build").mkdirs()
        File(root, "build/c.class").writeText("c")
        File(root, "node_modules").mkdirs()
        File(root, "node_modules/x.js").writeText("x")
        File(root, ".env").writeText("e")

        val index = WorkspaceFileIndex.build(root)
        val paths = index.map { it.path }.toSet()
        assertTrue(paths.contains("src"))
        assertTrue(paths.contains("src/b.kt"))
        assertTrue(paths.contains("a.txt"))
        assertTrue("隐藏文件进索引（查询时再过滤）", paths.contains(".env"))
        assertTrue("build 不遍历", paths.none { it.startsWith("build") })
        assertTrue("node_modules 不遍历", paths.none { it.startsWith("node_modules") })
    }

    @Test
    fun indexIsBoundedAndEmptyForAMissingRoot() {
        val root = temp.newFolder("ws2")
        File(root, "a.txt").writeText("a")
        File(root, "b.txt").writeText("b")
        File(root, "c.txt").writeText("c")
        assertEquals(2, WorkspaceFileIndex.build(root, maxEntries = 2).size)
        assertTrue(WorkspaceFileIndex.build(File(temp.root, "missing")).isEmpty())
    }
}
