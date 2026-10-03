package com.adsh.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowCircleUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

/**
 * 会话流：正序 LazyColumn（dsh 的 Chat Node 列表）+ 右上角那三个快捷导航按钮。
 *
 * 这里只做「把 items 画出来」和读者的手势，状态与判定都在别处：
 *  - 列表内容由 [buildChatItems] 组装（items 是 ChatScreen 里的 derivedStateOf，每次重算换实例）；
 *  - 自动滚动的状态与判定在 [ChatScrollState]（本文件只读它的 following / towardTop，写它的 touch）；
 *  - 展开 / 收起、跳转这些**读者动作**一律先交出跟随（[readerAction]），口径见 AutoScroll.kt 的 ⑤。
 *
 * 手势这一段（按着不贴底 + 点空白收键盘）从 ChatScreen 原样搬来，判据仍是 [shouldClearComposerFocus]。
 */
@Composable
internal fun ColumnScope.ChatTranscript(
    state: ChatUiState,
    items: List<ChatItem>,
    listState: LazyListState,
    scroll: ChatScrollState,
    atTop: Boolean,
    previousUserId: Int?,
    lastTurnKey: Long,
    foldOpen: SnapshotStateMap<Long, Boolean>,
    /** 读者动作（展开 / 收起会话里的任何一行、打开任务列表）：先把跟随交出去，见 AutoScroll.kt ⑤ */
    readerAction: () -> Unit,
    /** dsh 的 `followTail()`：把视口钉到内容末端（本帧测量之前生效） */
    pinToBottom: () -> Unit,
    onBranch: (Long) -> Unit,
    onOpenDeliverable: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val hasConversation = items.isNotEmpty()
    val contentFontSize = state.contentFontSize

    // 手指按在消息区期间一下都不贴底（[touching]，= dsh 的 `pending`：读者输入还没采样完
    // 就不动视口）。**手势本身不改跟随意图** —— 意图由上面那次位置采样按「读者到底移动没移动」
    // 决定（dsh 的 `movedByReader`），所以点一下、按住不动都不会把跟随关掉。
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            // 键用 listState：换会话时 LazyListState 会重建，这里跟着重启
            .pointerInput(listState) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    val startY = down.position.y
                    // 这一次手势是不是真的把内容滑走了（超过 touch slop）
                    var dragged = false
                    scroll.touch(true)
                    try {
                        // 等这一套手势彻底结束（松手或取消）
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (!dragged && change != null &&
                                kotlin.math.abs(change.position.y - startY) > viewConfiguration.touchSlop
                            ) {
                                dragged = true
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        // finally 而不是顺序执行：手势被取消（节点回收）时手指状态也必须复位。
                        scroll.touch(false)
                        // 点在消息区（没滑动的那一下）= 离开输入状态：收起光标与键盘
                        // （用户要求：点其他空白地方，输入框里的光标就消失）。滑动不算 ——
                        // 滑历史的时候键盘不该自己收起来。长按也不算（第 85 轮修的真机 bug，
                        // 判据见 shouldClearComposerFocus）。
                        val heldMillis = android.os.SystemClock.uptimeMillis() - down.uptimeMillis
                        if (shouldClearComposerFocus(dragged, heldMillis, viewConfiguration.longPressTimeoutMillis)) {
                            focusManager.clearFocus()
                        }
                    }
                }
            },
    ) {
        // dsh 的 --dsh-content-font-size：会话内容区按设置里的字号缩放
        // （只影响会话内容，顶栏 / 输入栏不动，与 dsh 的「仅影响会话内容的字号」一致）
        val baseDensity = LocalDensity.current
        val contentDensity = remember(baseDensity, contentFontSize) {
            androidx.compose.ui.unit.Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * (contentFontSize / 14f),
            )
        }
        // Markdown 里的相对路径（图片 / 文件链接）按会话工作区解析
        androidx.compose.runtime.CompositionLocalProvider(
            LocalDensity provides contentDensity,
            LocalMarkdownRoot provides state.workspacePath,
        ) {
        if (!hasConversation) {
            Hero()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                // 正序（和 dsh 的 DOM 顺序一致）：展开/收起只影响被点那一行**下面**的内容，
                // 上面已经画好的行一动不动 —— 展开天然就是「向下展开」，不需要任何补偿滚动。
                // 对话比一屏短时按 dsh 的样子贴底（对齐方式交给 Arrangement）。
                // 行距由每一行自己带（一轮拆成了折叠行 / 过程行 / 轮尾三行）。
                // 不设 Arrangement.Bottom：dsh 的会话是普通块级流，内容比一屏短时
                // 从**顶部**开始排（之前贴底会让新会话空出一大截）；比一屏长时由
                // 下面的贴底跟随负责钉住底部。
            ) {
                items(items, key = { item -> transcriptKey(item) }) { item ->
                    // 只在 debug 里挂这个诊断副作用：release 里 `BuildConfig.DEBUG` 是常量 false，
                    // 整段 if 会被折掉 —— 否则每一行都会多出一个 DisposableEffect（本行进出组合时的日志）。
                    if (com.adsh.app.BuildConfig.DEBUG) {
                        val rowKey = transcriptKey(item)
                        androidx.compose.runtime.DisposableEffect(rowKey) {
                            trace("row+", rowKey)
                            onDispose { trace("row-", rowKey) }
                        }
                    }
                    when (item) {
                        // dsh 的 system-prompt 节点：系统提示词 / 系统提示词更新
                        // （会话里任何一行的展开都要停自动跟随，见 NOTES 的界面不变量）
                        is ChatItem.SystemPrompt ->
                            Box(Modifier.padding(top = item.gap)) {
                                SystemPromptRow(item.text, item.update, onReaderAction = readerAction)
                            }
                        // dsh 的 context 节点：上下文注入
                        is ChatItem.Context -> Box(Modifier.padding(top = item.gap)) {
                            ContextInjectionRow(
                                label = item.label,
                                form = item.form,
                                text = item.text,
                                onReaderAction = readerAction,
                            )
                        }
                        is ChatItem.User -> Box(Modifier.padding(top = item.gap)) {
                            UserMessage(item.text, item.time, item.attachments)
                        }
                        is ChatItem.Compact -> Box(Modifier.padding(top = item.gap)) {
                            CompactCard(item.text, onReaderAction = readerAction)
                        }
                        // 折叠行：展开 / 收起（状态在 foldOpen 里，items 是 derivedStateOf，
                        // 写它就会重算列表 —— 不需要额外的「修订号」
                        is ChatItem.TurnFold -> TurnFoldRow(
                            view = item.view,
                            open = item.open,
                            // 读者动作：停止自动跟随（否则这一展开会被流式贴底顶上去）。
                            // 展开天然向下长、上方不动，不再做补偿滚动（见上面 readerAction 附近的注释）
                            onToggle = { expanded ->
                                readerAction()
                                foldOpen[item.view.key] = expanded
                            },
                            modifier = Modifier.padding(top = item.gap),
                        )
                        // 过程里的一条：一行一个 item（展开时只组合看得见的那几行）
                        is ChatItem.TurnEntry -> TurnEntryRow(
                            view = item.view,
                            index = item.index,
                            // 行里的展开/收起是行内部状态：它一动就停止自动跟随（readerAction）；
                            // 展开向下长、上方不动，不替读者滚（见上面 readerAction 附近的注释）
                            onReaderAction = { readerAction() },
                            // 点工具行摘要里的文件路径 → 进文件预览（与轮尾交付物卡片同一条路径解析）
                            onOpenFile = onOpenDeliverable,
                            modifier = Modifier.padding(top = item.gap),
                        )
                        // 轮尾：只有最后一轮可以从轮尾分叉（dsh 的 branchUnavailable = 后面还有节点）
                        is ChatItem.TurnTail -> TurnTailRow(
                            view = item.view,
                            branchable = item.view.closed && item.view.key == lastTurnKey && !state.sending,
                            onBranch = onBranch,
                            modifier = Modifier.padding(top = item.gap),
                            workspacePath = state.workspacePath,
                            onOpenDeliverable = onOpenDeliverable,
                            // 交付文件卡的展开 / 收起与工具行同一条规矩（读者动作）：展开天然
                            // 向下长、上方一动不动，也不做补偿滚动 —— 见下面 readerAction 的注释
                            onCardToggle = { readerAction() },
                        )
                    }
                }
            }
        }
        }
        // dsh 的 toBottomSlot：**只要不在跟随就浮出来**（判据就是 `!scroll.followingTail`）——
        // 跟随意图与「现在离底部多少像素」无关，所以内容长高不会让它闪，也不需要旧版那套 64dp 迟滞。
        //
        // 第 95 轮：它上面再加两个**同形状**的快捷导航（本 App 的补充）。第三个导航按钮的位置与
        // 可见性口径是用户点名重排过的（第 96 轮）：
        //  - 从上到下 = 「回会话顶部」（向上的倒角，与底部那个向下倒角正好相反）
        //    → 「上一条用户消息」（放**二者中间**）→ 「回到底部」；
        //  - 上面两个只在**读者往会话开头方向滚**（往上翻）时出现：往下滚的那一下立刻收起来，
        //    停在半路也不会自己冒出来（用户点名：「其余时间均隐藏」）；
        //  - 到会话顶部时只收上面两个，「回到底部」留着（此刻正需要它）。
        if (hasConversation && !scroll.following) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = DshSpacing.Card, bottom = DshSpacing.Card),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
            ) {
                if (!atTop && scroll.towardTop) {
                    RoundRailButton(
                        // 倒角向上 = DshIcons.ChevronDown 转 180°（与「回到底部」那一个相反）
                        icon = DshIcons.ChevronDown,
                        iconRotation = 180f,
                        label = "回到会话顶部",
                    ) {
                        // 显式导航 = dsh 的 `pauseFollowing()`：先把跟随交出去，再跳
                        scroll.pause()
                        listState.requestScrollToItem(0, 0)
                    }
                    val target = previousUserId
                    if (target != null) {
                        RoundRailButton(
                            icon = Icons.Outlined.ArrowCircleUp,
                            label = "跳到上一条用户消息",
                        ) {
                            // 同上：读者动作优先，否则流式贴底会立刻把视口拽回底部
                            scroll.pause()
                            listState.requestScrollToItem(target, 0)
                        }
                    }
                }
                BackToBottomButton {
                    // dsh 的 `returnToBottom()`：无条件贴底（`toBottom()` 自己会把跟随打开）
                    scroll.resume()
                    pinToBottom()
                }
            }
        }
    }
}
