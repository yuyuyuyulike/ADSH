package com.adsh.app.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话页浮层的互斥与 dismissed 规则（[ChatOverlays]）—— R12 从 ChatScreen 的四个 remember 里
 * 提出来的。这里每一条都对应一类只能靠真机目视、出问题又很难复述的现场：
 * 统计浮窗与模型弹层同时开着、关掉触发菜单之后它又自己弹回来、发完消息菜单还挂着……
 */
class ChatOverlaysTest {

    // ---------- 触发菜单的可见性（dsh 的 input.commands 三条规则）----------

    @Test
    fun launcherStaysOpenOnlyWhileTheDraftIsUntouched() {
        val open = ChatOverlays(launcherDraft = "/pl")
        assertTrue(open.launcherOpen("/pl"))
        // 用户接着打字：交回「键入 /」那条路径（launcher 这一份不再算数）
        assertFalse(open.launcherOpen("/plan "))
        assertFalse(ChatOverlays().launcherOpen(""))
    }

    @Test
    fun typedStaysOpenUntilTheQueryIsDismissedOrComplete() {
        val idle = ChatOverlays()
        assertTrue(idle.typedOpen("/pl", complete = false, draft = "/pl"))
        // 没有活触发词（Palette.slashQueryOf 已经算好传进来）
        assertFalse(idle.typedOpen(null, complete = false, draft = "/plan 写一页"))
        // 精确命中一条命令：菜单让位，回车直接执行
        assertFalse(idle.typedOpen("/plan", complete = true, draft = "/plan"))
        // 这一份草稿刚被用户关掉（dsh 的 dismissed）：不再自动召回
        assertFalse(ChatOverlays(paletteMuted = "/pl").typedOpen("/pl", complete = false, draft = "/pl"))
        // 输入新的查询就重新武装
        assertTrue(ChatOverlays(paletteMuted = "/pl").typedOpen("/pla", complete = false, draft = "/pla"))
    }

    // ---------- 互斥 ----------

    @Test
    fun statsToggleClosesTheComposerMenu() {
        val both = ChatOverlays(composerMenu = "model")
        assertEquals(ChatOverlays(statsOpen = true), both.statsToggled())
        // 再点一次：关掉自己，弹层仍然是关的
        assertEquals(ChatOverlays(), ChatOverlays(statsOpen = true).statsToggled())
    }

    @Test
    fun openingAComposerMenuClosesStatsAndDismissesThePalette() {
        val before = ChatOverlays(launcherDraft = "/pl", statsOpen = true)
        val after = before.menuChanged("model", draft = "/pl")
        assertEquals("model", after.composerMenu)
        assertFalse(after.statsOpen)
        // 触发菜单按「这一份草稿已被忽略」收掉（与 paletteDismissed 同一套语义）
        assertNull(after.launcherDraft)
        assertEquals("/pl", after.paletteMuted)
    }

    @Test
    fun closingAComposerMenuTouchesNothingElse() {
        val open = ChatOverlays(composerMenu = "context", paletteMuted = "/x", statsOpen = false)
        assertEquals(open.copy(composerMenu = null), open.menuChanged(null, draft = "/x"))
    }

    // ---------- 触发菜单的两条状态转移 ----------

    @Test
    fun paletteVisibilityOnArmsTheLauncherAndClearsTheMute() {
        val after = ChatOverlays(paletteMuted = "/pl").paletteVisibility(want = true, draft = "/pl")
        assertEquals("/pl", after.launcherDraft)
        assertNull(after.paletteMuted)
    }

    @Test
    fun paletteVisibilityOffRecordsTheDismissedDraft() {
        val after = ChatOverlays(launcherDraft = "/pl").paletteVisibility(want = false, draft = "/pl")
        assertNull(after.launcherDraft)
        assertEquals("/pl", after.paletteMuted)
    }

    @Test
    fun dismissingThePaletteAlsoDropsTheLauncher() {
        val after = ChatOverlays(launcherDraft = "/pl", paletteMuted = "old").paletteDismissed("/pl")
        assertNull(after.launcherDraft)
        assertEquals("/pl", after.paletteMuted)
    }

    @Test
    fun sendingClearsBothPaletteStatesButKeepsThePopups() {
        val before = ChatOverlays(launcherDraft = "/pl", paletteMuted = "/pl", composerMenu = "model", statsOpen = true)
        val after = before.sent()
        assertNull(after.launcherDraft)
        assertNull(after.paletteMuted)
        // 发消息不关弹层与统计 —— 与改动前一致
        assertEquals("model", after.composerMenu)
        assertTrue(after.statsOpen)
    }

    // ---------- 跨进程重建只存统计那一个开关 ----------

    @Test
    fun onlyTheStatsFlagSurvivesProcessRecreation() {
        val scope = SaverScope { true }
        val before = ChatOverlays(launcherDraft = "/pl", paletteMuted = "/pl", composerMenu = "model", statsOpen = true)
        val saved = with(ChatOverlaysSaver) { scope.save(before) }
        assertEquals(ChatOverlays(statsOpen = true), ChatOverlaysSaver.restore(saved!!))
    }
}
