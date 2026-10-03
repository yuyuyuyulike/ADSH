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

    // ---------- 回车那一下的意图（R14 第四步：从 ChatScreen 的 onSend lambda 里搬出来）----------

    @Test
    fun `plan 后面带任务时先切模式再把任务当消息发`() {
        // claim token：token 之后的内容是**参数**，不是模式开关
        assertEquals(CommandIntent.Plan(on = true, rest = "写一个页面"), commandIntentOf("/plan 写一个页面"))
        assertEquals(CommandIntent.Plan(on = true, rest = ""), commandIntentOf("/plan"))
        assertEquals(CommandIntent.Plan(on = true, rest = ""), commandIntentOf("  /plan   "))
    }

    @Test
    fun `plan off 是退出计划模式且不发消息`() {
        assertEquals(CommandIntent.Plan(on = false, rest = ""), commandIntentOf("/plan off"))
        assertEquals(CommandIntent.Plan(on = false, rest = ""), commandIntentOf("/plan   off  "))
    }

    @Test
    fun `三条精确命令各自成一条意图`() {
        assertEquals(CommandIntent.Compact, commandIntentOf("/compact"))
        assertEquals(CommandIntent.Permission, commandIntentOf("/permission"))
        assertEquals(CommandIntent.Export, commandIntentOf("/export"))
        // 前后空白不算内容（先 trim 再判）
        assertEquals(CommandIntent.Compact, commandIntentOf("  /compact "))
    }

    @Test
    fun `命令名后面还有内容就不是命令`() {
        // dsh 的 resolveCommand 要求 descriptor.name === token：多一个字就退回普通消息
        assertEquals(CommandIntent.Send("/compact 一下"), commandIntentOf("/compact 一下"))
        assertEquals(CommandIntent.Send("/planning"), commandIntentOf("/planning"))
        assertEquals(CommandIntent.Send("/未知命令"), commandIntentOf("/未知命令"))
    }

    @Test
    fun `普通草稿 trim 之后原样发出去`() {
        assertEquals(CommandIntent.Send("帮我看看这个 bug"), commandIntentOf("  帮我看看这个 bug  "))
        // 空草稿也发一条空消息 —— 与搬出来之前一致（拦空发送是输入框那边的事）
        assertEquals(CommandIntent.Send(""), commandIntentOf("   "))
    }

    // ------------------------------------------------------------ 可见性算式（paletteViewOf）

    private fun view(overlays: ChatOverlays, draft: String) = paletteViewOf(overlays, draft, commands)

    @Test
    fun `没有触发词时菜单不可见`() {
        val v = view(ChatOverlays(), "你好")
        assertEquals(emptyList<Row>(), v.rows)
        assertFalse(v.visible)
    }

    @Test
    fun `刚敲斜杠时五行全在`() {
        val v = view(ChatOverlays(), "/")
        assertEquals(commands, v.rows)
        assertTrue(v.visible)
    }

    @Test
    fun `键入查询只留命中的行`() {
        assertEquals(listOf("plan"), view(ChatOverlays(), "/pl").rows.map { it.name })
        assertEquals(listOf("compact"), view(ChatOverlays(), "/comp").rows.map { it.name })
    }

    @Test
    fun `打全命令之后菜单让位`() {
        val v = view(ChatOverlays(), "/plan")
        assertEquals(emptyList<Row>(), v.rows)
        assertFalse(v.visible)
    }

    @Test
    fun `查询后面有空白就没有活触发词`() {
        assertFalse(view(ChatOverlays(), "/plan 写一个页面").visible)
    }

    @Test
    fun `一条都不命中时不可见`() {
        assertFalse(view(ChatOverlays(), "/zzz").visible)
    }

    @Test
    fun `关掉过的那份草稿不再自动召回`() {
        assertFalse(view(ChatOverlays(paletteMuted = "/pl"), "/pl").visible)
        assertTrue("改了草稿就重新武装", view(ChatOverlays(paletteMuted = "/pl"), "/pla").visible)
    }

    @Test
    fun `加号打开的全量菜单按能不能与草稿共存过滤`() {
        val v = view(ChatOverlays(launcherDraft = "/pl"), "/pl")
        assertEquals(listOf("file", "compact", "export"), v.rows.map { it.name })
        assertTrue(v.visible)
    }

    @Test
    fun `两条路径同时成立时加号赢`() {
        // 草稿 /pl 既能被「键入 /」命中（只该剩 plan），也挂着「+」的全量菜单（plan 与草稿共存不了）
        val launcher = view(ChatOverlays(launcherDraft = "/pl"), "/pl")
        assertEquals(listOf("file", "compact", "export"), launcher.rows.map { it.name })
    }
}
