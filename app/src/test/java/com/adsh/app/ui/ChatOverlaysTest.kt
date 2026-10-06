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

    // ---------- 互斥：一个槽，写进去谁就是唯一开着的 ----------

    @Test
    fun togglingOneTriggerClosesWhateverElseWasOpen() {
        // 模型弹层开着时点统计胶囊：模型弹层让位（同一个槽被覆盖）
        assertEquals(ChatOverlays(open = OPEN_STATS), ChatOverlays(open = OPEN_MODEL).toggled(OPEN_STATS))
        // 再点一次统计：关掉自己
        assertEquals(ChatOverlays(), ChatOverlays(open = OPEN_STATS).toggled(OPEN_STATS))
        // 上下文与统计之间同理
        assertEquals(ChatOverlays(open = OPEN_CONTEXT), ChatOverlays(open = OPEN_STATS).toggled(OPEN_CONTEXT))
    }

    @Test
    fun openingAComposerMenuClosesTheDockAndDismissesThePalette() {
        val before = ChatOverlays(launcherDraft = "/pl", open = OPEN_STATS)
        val after = before.menuChanged(OPEN_MODEL, draft = "/pl")
        assertEquals(OPEN_MODEL, after.open)
        // 触发菜单按「这一份草稿已被忽略」收掉（与 paletteDismissed 同一套语义）
        assertNull(after.launcherDraft)
        assertEquals("/pl", after.paletteMuted)
    }

    @Test
    fun closingAComposerMenuTouchesNothingElse() {
        val open = ChatOverlays(open = OPEN_CONTEXT, paletteMuted = "/x")
        assertEquals(open.copy(open = null), open.menuChanged(null, draft = "/x"))
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
    fun sendingClearsBothPaletteStatesButKeepsThePopup() {
        val before = ChatOverlays(launcherDraft = "/pl", paletteMuted = "/pl", open = OPEN_MODEL)
        val after = before.sent()
        assertNull(after.launcherDraft)
        assertNull(after.paletteMuted)
        // 发消息不关浮层 —— 与改动前一致
        assertEquals(OPEN_MODEL, after.open)
    }

    // ---------- 跨进程重建只存「哪个浮层开着」 ----------

    @Test
    fun onlyTheOpenPopupSurvivesProcessRecreation() {
        val scope = SaverScope { true }
        val before = ChatOverlays(launcherDraft = "/pl", paletteMuted = "/pl", open = OPEN_USAGE)
        val saved = with(ChatOverlaysSaver) { scope.save(before) }
        assertEquals(ChatOverlays(open = OPEN_USAGE), ChatOverlaysSaver.restore(saved!!))
        assertEquals(ChatOverlays(), ChatOverlaysSaver.restore(listOf("")))
    }
}
