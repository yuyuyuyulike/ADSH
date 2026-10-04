package com.adsh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.adsh.app.ui.theme.LocalDshPalette

/**
 * 设置-功能页的「权限」卡（用户点名要的那一张）。
 *
 * 形态就是设置页里别的卡：标题 + 一句小字（用户口径：「快捷授予权限。」），展开后四项，每项是一个
 * **同款的圆角长方框**（底色 bgModulePlatform、圆角 12 —— 与字号步进器 / 主题立方同一套），
 * 框里是「名称 + 授权状态 + 倒角」，点一下跳到 adsh 在这个系统上的对应权限页。
 *
 * 「授予后卡片里也要显示已授权」这条靠**回前台重算**实现：跳出去时本页只是 stop，用户回来会走
 * ON_RESUME（[LifecycleEventObserver]），那一刻把四项状态重新读一遍（[isGranted] 每次都读系统
 * 真值，没有本地缓存，所以不会出现「显示已授权其实没给」）。
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
    val granted = remember(tick, open) {
        AppPermission.entries.associateWith { isGranted(context, it) }
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
                Spacer(Modifier.height(DshSpacing.Xxxl))
                AppPermission.entries.forEachIndexed { index, permission ->
                    if (index > 0) Spacer(Modifier.height(DshSpacing.Xl))
                    PermissionRow(
                        label = permission.label,
                        granted = granted[permission] == true,
                        onClick = { openPermissionSettings(context, permission) },
                    )
                }
                Spacer(Modifier.height(DshSpacing.Xxxl))
            }
        }
    }
}

/** 一行权限：一个圆角长方框（同字号步进器 / 主题立方的底），整框可点 */
@Composable
private fun PermissionRow(label: String, granted: Boolean, onClick: () -> Unit) {
    val palette = LocalDshPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.bgModulePlatform)
            .dshClickable(interactionSource = dshInteraction(), onClick = onClick)
            .padding(horizontal = DshSpacing.Section, vertical = DshSpacing.Xxxl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.Lg),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = palette.labelPrimary,
        )
        Text(
            text = if (granted) "已授权" else "未授权",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = if (granted) palette.success else palette.labelTertiary,
        )
        Spacer(Modifier.size(DshSpacing.Md))
        Icon(
            imageVector = DshIcons.ChevronRight,
            contentDescription = null,
            tint = palette.labelTertiary,
            modifier = Modifier.size(14.dp),
        )
    }
}
