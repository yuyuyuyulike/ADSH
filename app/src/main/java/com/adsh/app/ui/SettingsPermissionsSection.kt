package com.adsh.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.adsh.app.ui.theme.LocalDshPalette

/**
 * 设置-功能页的「权限」卡：标题 + 一句小字（用户口径：「快捷授予权限。」），展开后三项，每项是
 * **卡体内的一行**（标签 + 状态 + 倒角，行与行之间一条 .5px 分线 —— 与终端 / 智能体循环那些卡里的
 * ValueField 同一套），点一下跳到 adsh 在这个系统上的对应权限页。
 *
 * 第 181 轮用户口径：「方框不要有气泡，风格和其他卡片一致」—— 以前每一行套了一个
 * bgModulePlatform、圆角 12 的方框，看着像一排药丸（同页别的卡的行都是分线分隔、没有底）。
 * 现在去掉底色与圆角，只留分线；行内字号也换成卡体内的那一套（13/20 Medium + 12/18）。
 *
 * 「授予后卡片里也要显示已授权」这条靠**回前台重算**实现：跳出去时本页只是 stop，用户回来会走
 * ON_RESUME（[LifecycleEventObserver]），那一刻把三项状态重新读一遍（[permissionStatus] 每次都读
 * 系统真值，没有本地缓存）。
 */
@Composable
internal fun PermissionsCard() {
    val context = LocalContext.current
    val palette = LocalDshPalette.current
    var open by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // 从系统设置页回来：权限可能刚被授掉，重算
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 展开时也重算一次：收起着的时候用户可能刚从别处改过权限
    val status = remember(tick, open) {
        AppPermission.entries.associateWith { permissionStatus(context, it) }
    }
    SettingsCard(background = if (open) palette.bgLayer2 else palette.bgLayer3) {
        CardFrame(
            icon = DshIcons.ShieldCheck,
            title = "权限",
            description = "快捷授予权限。",
            open = open,
            dirty = false,
        ) { open = !open }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.Card)) {
                DshHairline()
                AppPermission.entries.forEachIndexed { index, permission ->
                    PermissionRow(
                        label = permission.label,
                        granted = status[permission],
                        first = index == 0,
                        onClick = { openPermissionSettings(context, permission) },
                    )
                }
                Spacer(Modifier.height(DshSpacing.Xxxl))
            }
        }
    }
}

/**
 * 一行权限：卡片体内的一行（分线分隔、没有底），整行可点。
 *
 * [granted] 为 null = 系统没给读取口，那一行写「去设置」—— 文案与状态读法都在 [PermissionActions.kt]。
 */
@Composable
private fun PermissionRow(label: String, granted: Boolean?, first: Boolean, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Column(Modifier.fillMaxWidth()) {
        if (!first) DshHairline()
        Row(
            Modifier
                .fillMaxWidth()
                .dshClickable(interactionSource = dshInteraction(), onClick = onClick)
                .padding(vertical = DshSpacing.Xxxl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.Xl),
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = palette.labelPrimary,
            )
            Text(
                text = permissionStatusLabel(granted),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = palette.labelTertiary,
            )
            Icon(
                imageVector = DshIcons.ChevronRight,
                contentDescription = null,
                tint = palette.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
