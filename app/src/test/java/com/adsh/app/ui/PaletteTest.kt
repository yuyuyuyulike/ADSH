package com.adsh.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触发菜单的三条 dsh 规则（见 Palette.kt 的注释与 dsh 的引用）：
 * 触发词的活性、查询命中、以及「打全命令就让位」。
 *
 * 菜单行的 UI 是 Compose 的，规则层只认 [PaletteEntry]，所以这里用一个最小替身，
 * 不碰 ImageVector / 组合。
 */
class PaletteTest {

    /** 菜单行的替身：只带规则层用得到的三样东西 */
    private data class Row(
        override val name: String,
        override val label: String,
        override val usableWithDraft: Boolean = true,
    ) : PaletteEntry

    private val commands = listOf(
        Row("file", "文件"),
        Row("plan", "计划", usableWithDraft = false),
        Row("compact", "压缩"),
        Row("permission", "权限", usableWithDraft = false),
        Row("export", "下载日志"),
    )

    // ------------------------------------------------------------ 触发词的活性

    @Test
    fun `刚键入斜杠时查询是空串`() {
        assertEquals("", slashQueryOf("/"))
    }

    @Test
    fun `斜杠后面还没有空白时是活触发词`() {
        assertEquals("p", slashQueryOf("/p"))
        assertEquals("plan", slashQueryOf("/plan"))
    }

    @Test
    fun `斜杠后面出现空白就没有活触发词`() {
        // dsh 的 detectTrigger 从光标往回扫，碰到空白直接返回 null（core/detect.ts:63）
        assertNull(slashQueryOf("/plan "))
        assertNull(slashQueryOf("/plan 写一个页面"))
        assertNull(slashQueryOf("/compact 现在"))
    }

    @Test
    fun `斜杠不在行首就不算触发词`() {
        assertNull(slashQueryOf(""))
        assertNull(slashQueryOf("你好"))
        assertNull(slashQueryOf("你好 /plan"))
        assertNull(slashQueryOf(" /plan"))
    }

    @Test
    fun `双斜杠不是触发词`() {
        // dsh 的 boundaryOk：紧跟在另一个斜杠后面的斜杠是 URL 里的那种，不算触发词（detect.ts:26-28）
        assertNull(slashQueryOf("//"))
        assertNull(slashQueryOf("//plan"))
    }

    // ------------------------------------------------------------ 查询命中

    @Test
    fun `空查询命中所有行`() {
        assertTrue(commands.all { paletteMatches("", it) })
    }

    @Test
    fun `命令名与本地化标题都参与匹配且不区分大小写`() {
        assertTrue(paletteMatches("pl", commands[1]))
        assertTrue(paletteMatches("PLAN", commands[1]))
        assertTrue(paletteMatches("计划", commands[1]))
        assertFalse(paletteMatches("压缩", commands[1]))
    }

    @Test
    fun `一条都不命中时菜单应当关闭`() {
        // dsh 的 menuReduce：所有分组都 ready 且为空 -> closed（core/menu.ts:118）
        assertTrue(commands.none { paletteMatches("zzz", it) })
    }

    // ------------------------------------------------------------ 打全了就完整

    @Test
    fun `打全命令名算完整`() {
        // dsh 的 resolveCommand：descriptor.name === token（ui-commands/.../resolution.ts:50-58）
        assertTrue(paletteQueryComplete("plan", commands))
        assertTrue(paletteQueryComplete("PLAN", commands))
        assertTrue(paletteQueryComplete("compact", commands))
    }

    @Test
    fun `半截命令与未知命令都不算完整`() {
        assertFalse(paletteQueryComplete("", commands))
        assertFalse(paletteQueryComplete("pla", commands))
        assertFalse(paletteQueryComplete("zzz", commands))
    }

    @Test
    fun `中文标题不算完整命令`() {
        // ADSH 的发送分支只认命令名（没有 dsh 的别名表），所以标题不能把菜单关掉
        assertFalse(paletteQueryComplete("计划", commands))
    }

    // ------------------------------------------------------------ 与草稿的相容性

    @Test
    fun `草稿为空时所有行都可用`() {
        assertTrue(commands.all { paletteUsableWith("", it) })
        assertTrue(commands.all { paletteUsableWith("   ", it) })
    }

    @Test
    fun `草稿非空时只留能与它共存的命令`() {
        // dsh：position 不是 leading 时滤掉带 hint 的行（ui-commands/src/client/service.ts:247）
        val names = commands.filter { paletteUsableWith("帮我看看这个 bug", it) }.map { it.name }
        assertEquals(listOf("file", "compact", "export"), names)
    }
}
