package com.adsh.app.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsh.app.core.data.SettingsStore
import com.adsh.app.ui.theme.LocalDshPalette

/**
 * 输入框上那两个弹层菜单：**模型 / 推理等级**（两级）与**权限预设**。
 *
 * R15 从 `DshComposer`（495 行）里搬出来 —— 它们在输入框那一行里各占二三十行，
 * 却不碰输入框的任何状态：模型菜单只要当前模型 / 等级 / 余额与两个选择回调，
 * 权限菜单只要当前档位与一个「要不要弹风险确认」的回调。两处**原文逐行搬运**，
 * 只有 `onMenuChange(null)` → `onOpenChange(false)`、`modelPane` → `pane`（本地状态）这类改写。
 *
 * 两个菜单的窗口几何口径（高度夹在锚点上方、卡片贴窗口底边、切子页不改窗口尺寸）见
 * [popupPanelMaxHeight] 的注释 —— 那里是「点推理等级时上滑一下」那个 bug 的结论。
 */

/** 模型窗口根页的固定内容高度：两行按 weight 均分（54dp/行），切换子页时窗口不会改变尺寸 */
private val MODEL_MENU_HEIGHT = 108.dp

/** 子页（模型清单 / 推理等级）的高度上限（dsh 的菜单是 min(360px, 100vh-96px)） */
private val MODEL_MENU_MAX_HEIGHT = 360.dp
/** 模型窗口宽度：与上下文占用窗口一致（dsh 的 .JObwrW_panel 也是 264px） */
private val MODEL_MENU_WIDTH = 264.dp
/** 卡片底边与触发按钮之间的间距（dsh 的 `bottom: calc(100% + 8px)`）：底边恒定锚在这里 */
private val MODEL_MENU_GAP = 8.dp
/** 卡片顶边至少要避开的距离（状态栏高度拿不到时用它）：再挤也不让卡片顶到屏幕最上沿 */
private val MODEL_MENU_TOP_MARGIN = 8.dp
/** 子页的最小高度：横屏 / 输入框长得很高时锚点上方空间有限，也不能把菜单压成一条 */
private val MODEL_MENU_MIN_HEIGHT = 160.dp

/**
 * 权限预设弹层的宽度下限（内容兜底）：三行里最长的是「工作区内修改」，
 * 图标 14 + 间距 + 6 个汉字 + 勾选 16 + 左右内边距，约 150dp。
 */
private val PERMISSION_MENU_MIN_WIDTH = 160.dp

/**
 * 权限预设：只显示当前档位的图标 + 可转动倒角（dsh 在窄容器下就是隐藏文字）。
 *
 * [cardWidthPx] 是输入卡片的宽度 —— 弹层宽度按用户第 80 轮的口径取**卡片的一半**。
 */
@Composable
internal fun PermissionMenuTrigger(
    permission: String,
    open: Boolean,
    cardWidthPx: Int,
    onOpenChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    /** 选「完全访问」时要先弹风险确认，由调用方弹那个对话框（状态也在调用方） */
    onSelectNeedsConfirm: () -> Unit,
) {
    val palette = LocalDshPalette.current
    // 权限预设：只显示当前档位的图标 + 可转动倒角（dsh 在窄容器下就是隐藏文字）
    val preset = permissionPreset(permission)
    Box {
        TriggerPill(icon = preset.icon, contentDescription = "权限预设：" + preset.label, open = open) {
            onOpenChange(!open)
        }
        if (open) {
            DshPopup(onDismiss = { onOpenChange(false) }, alignStart = true) {
                // 宽度：**输入卡片的一半**（用户第 80 轮的真机口径，首版照搬 dsh 的
                // `max-width: 100%` 直接顶满，被截图打回）。
                // dsh 的同类弹层是内容定宽、`min-width: min(220px, 100%)`、
                // `max-width: 100%`（ui-commands/src/client/PopupSelectView.module.css:13-16），
                // 那是桌面宽度下的取值；手机上没有那个宽度，按用户口径折半，
                // 再给内容兜一个下限（行是 fillMaxWidth 的，宽度必须自己定死，
                // 否则它会一路涨到父约束的上限）。
                val permissionMenuWidth = with(LocalDensity.current) {
                    (cardWidthPx / 2).toDp()
                }.coerceAtLeast(PERMISSION_MENU_MIN_WIDTH)
                DshMenuCard(Modifier.width(permissionMenuWidth)) {
                    PERMISSION_PRESETS.forEach { item ->
                        DshMenuRow(
                            label = item.label,
                            icon = item.icon,
                            selected = item.id == permission,
                            onClick = {
                                onOpenChange(false)
                                if (item.id == SettingsStore.PERMISSION_FULL_ACCESS && item.id != permission) {
                                    onSelectNeedsConfirm()
                                } else {
                                    onSelect(item.id)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「模型 / 推理等级」两级菜单（dsh 的 ModelSelect + effort 列表）。
 *
 * 触发按钮的坐标与当前子页是**本组件自己的状态**：它们只服务这一个菜单。
 * [open] 由调用方持有（它还要管与触发菜单 / 权限弹层的互斥）。
 */
@Composable
internal fun ModelMenuTrigger(
    open: Boolean,
    modelGroups: List<ChatViewModel.ModelGroup>,
    balances: Map<String, ChatViewModel.BalanceState>,
    currentModel: String,
    currentProviderId: String,
    efforts: List<Pair<String, String>>,
    currentEffort: String,
    onOpenChange: (Boolean) -> Unit,
    onSelectModel: (String, String) -> Unit,
    onSelectEffort: (String) -> Unit,
) {
    /** 触发按钮顶边在 root 里的 y（px）：定位要用的量，见 [popupPanelMaxHeight] */
    var triggerTop by remember { mutableFloatStateOf(0f) }
    /** 当前子页：root（两行）/ model / effort（见 MODEL_MENU_HEIGHT 的注释） */
    var pane by remember { mutableStateOf("root") }
    val palette = LocalDshPalette.current
    // 模型：与设置页「模型」分节同一枚图标（IconDataOutline16）+ 可转动倒角
    // -> 「模型 / 推理等级」两级菜单
    Box(Modifier.onGloballyPositioned { triggerTop = it.positionInRoot().y }) {
        TriggerPill(icon = DshSettingIcons.Data, contentDescription = "模型与推理等级", open = open) {
            pane = "root"
            onOpenChange(!open)
        }
        if (open) {
            // 位置与最初版本一致：菜单底边贴在触发按钮上方 8dp（不再往上抬到输入框上沿）。
            // 定位要用的两个量在 Popup **外面**取：Popup 是独立窗口，它自己的 root 坐标与
            // insets 不能代表主窗口；放在这里读，输入框长高（触发按钮上移）时也会跟着重算。
            val density = LocalDensity.current
            val statusBarTopPx = WindowInsets.statusBars.getTop(density)
            val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
            // 窗口高度在**整个菜单生命周期里恒定**，这是「不要动效」的关键：
            // Popup 是独立窗口，只要窗口几何在打开期间发生变化（换子页时窗口变高变矮），
            // 系统就会先把旧尺寸那一帧画出来、再跳到新位置 —— 用户看到的就是
            // 「点推理等级时上滑一下」。窗口尺寸定死之后，切子页只是卡片自己在窗口里
            // 长高/缩矮（纯组合层布局，即时生效、没有动画），底边始终钉在触发按钮上方 8dp。
            val panelHeight = popupPanelMaxHeight(
                density = density,
                anchorTopPx = triggerTop,
                safeTopPx = statusBarTopPx,
                screenHeight = screenHeight,
                maxHeight = MODEL_MENU_MAX_HEIGHT,
                minHeight = MODEL_MENU_MIN_HEIGHT,
                gap = MODEL_MENU_GAP,
                topMargin = MODEL_MENU_TOP_MARGIN,
            )
            DshPopup(onDismiss = { onOpenChange(false) }) {
                Box(
                    Modifier.width(MODEL_MENU_WIDTH).height(panelHeight),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    // 卡片上方那一片空白也在弹窗窗口里（不抢焦点的 Popup 照样收得到触摸），
                    // 落在窗口内、卡片外的点击必须自己接住：否则会被窗口吃掉 ——
                    // 既不关菜单、也点不到下面的消息。行为与「点空白关闭」一致。
                    Box(
                        Modifier.matchParentSize().pointerInput(Unit) {
                            detectTapGestures { onOpenChange(false) }
                        },
                    )
                    // 卡片贴底：内容多高就往上长多高（dsh 的 bottom: calc(100% + 8px)）。
                    // 根页两行用 weight 均分固定高度；子页高度上限就是这个窗口高度，
                    // 超过则在卡片内部滚动。
                    DshMenuCard(Modifier.fillMaxWidth()) {
                        Column(
                            if (pane == "root") {
                                Modifier.height(MODEL_MENU_HEIGHT)
                            } else {
                                Modifier.heightIn(max = panelHeight).verticalScroll(rememberScrollState())
                            },
                        ) {
                                when (pane) {
                                    // 根页是固定高度（MODEL_MENU_HEIGHT），两行按 weight 均分；
                                    // 注意 weight 只能在固定高度的 Column 里用 ——
                                    // 子页那层是 heightIn + verticalScroll（滚动 → 高度无界），
                                    // 里面再放 weight 的行会被量成 0 高（菜单看着像没打开）。
                                    "root" -> {
                                        DshMenuRow(
                                            label = "模型",
                                            value = currentModel,
                                            chevronRight = true,
                                            modifier = Modifier.weight(1f),
                                            onClick = { pane = "model" },
                                        )
                                        DshMenuRow(
                                            label = "推理等级",
                                            // 该模型一个等级都没有时不显示值（dsh 的 effortLabel === undefined）
                                            value = efforts.firstOrNull { it.first == currentEffort }?.second,
                                            chevronRight = true,
                                            modifier = Modifier.weight(1f),
                                            onClick = { pane = "effort" },
                                        )
                                    }
                                    "model" -> {
                                        // dsh 的 ModelSelect：按提供方分组，组标题 12/18 三级色，
                                        // 列表超过菜单高度时自己滚（dsh 的 .groups{overflow-y:auto}）。
                                        // 组标题这一行最右边是余额（DeepSeek 的 /user/balance，
                                        // 每次打开菜单刷新一次）—— 拿不到就不占位。
                                        Column(Modifier.fillMaxWidth()) {
                                            modelGroups.forEach { group ->
                                                val balance = balances[group.providerId]
                                                Row(
                                                    modifier = Modifier.fillMaxWidth()
                                                        .padding(start = DshSpacing.Xl, end = DshSpacing.Xl, top = 5.dp, bottom = DshSpacing.Sm),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        text = group.providerName,
                                                        fontSize = 12.sp,
                                                        lineHeight = 18.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = palette.labelTertiary,
                                                    )
                                                    val balanceText = balance?.text.orEmpty()
                                                    if (balanceText.isNotEmpty()) {
                                                        Spacer(Modifier.weight(1f))
                                                        Text(
                                                            // 刷新中在数字后面跟一个省略号，位置不动
                                                            text = if (balance?.loading == true) balanceText + " …" else balanceText,
                                                            fontSize = 12.sp,
                                                            lineHeight = 18.sp,
                                                            color = palette.labelTertiary,
                                                            maxLines = 1,
                                                        )
                                                    }
                                                }
                                                group.models.forEach { model ->
                                                    DshMenuRow(
                                                        label = model,
                                                        selected = model == currentModel &&
                                                            group.providerId == currentProviderId,
                                                        onClick = {
                                                            onOpenChange(false)
                                                            onSelectModel(model, group.providerId)
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    else -> Column(Modifier.fillMaxWidth()) {
                                        if (efforts.isEmpty()) {
                                            // dsh 的 empty.efforts：「当前模型未提供推理等级。」
                                            Text(
                                                text = "当前模型未提供推理等级。",
                                                modifier = Modifier.padding(horizontal = DshSpacing.Xxxl, vertical = DshSpacing.Xxl),
                                                fontSize = 13.sp,
                                                lineHeight = 20.sp,
                                                color = palette.labelTertiary,
                                            )
                                        }
                                        efforts.forEach { (id, label) ->
                                            DshMenuRow(
                                                label = label,
                                                selected = id == currentEffort,
                                                onClick = {
                                                    onOpenChange(false)
                                                    onSelectEffort(id)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        }
    }
}
