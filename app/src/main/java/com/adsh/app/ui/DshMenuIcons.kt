package com.adsh.app.ui

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 触发菜单（`+` / `/`）用到的图标：path 逐字取自 dsh-client-ui-primitives 的 index.js
 * （IconCompactOutlineArtwork / IconDownloadOutlineArtwork），描边宽度 1（dsh 的 Regular 档）。
 *
 * 其余几枚直接复用已有实现，出处见各自注释：
 *  - 文件 = [DshIcons.Paperclip]（IconPaperclipOutline16）
 *  - 计划 = [DshSettingIcons.ListPen]（dsh 的 IconPlanOutlineArtwork 就是 IconListPenOutlineArtwork）
 *  - 权限 = [DshIcons.ShieldAlert]（PermissionIconFullAccessArtwork：盾牌 + 感叹号）
 *  - 模型 = [DshSettingIcons.Data]（IconDataOutlineRegular）
 */
object DshMenuIcons {

    /** IconCompactOutline16：压缩（圆弧轨道 + 缺口弧） */
    val Compact: ImageVector by lazy {
        dshIcon(
            "DshCompact",
            16f,
            // dsh 的轨道弧 opacity:0.35
            dshPart(
                "M8 14.5C11.5899 14.5 14.5 11.5899 14.5 8C14.5 4.41015 11.5899 1.5 8 1.5C4.41015 " +
                    "1.5 1.5 4.41015 1.5 8C1.5 11.5899 4.41015 14.5 8 14.5Z",
                stroke = true,
                strokeWidth = 1f,
                strokeAlpha = 0.35f,
            ),
            dshPart(
                "M8 1.5C8.85359 1.5 9.69883 1.66813 10.4874 1.99478C11.2761 2.32144 11.9926 " +
                    "2.80022 12.5962 3.40381C13.1998 4.00739 13.6786 4.72394 14.0052 5.51256C14.3319 " +
                    "6.30117 14.5 7.14641 14.5 8",
                stroke = true,
                strokeWidth = 1f,
            ),
        )
    }

    /** IconDownloadOutline16：下载日志（向下箭头 + 盘子） */
    val Download: ImageVector by lazy {
        dshIcon(
            "DshDownload",
            16f,
            dshPart("M8 1.95317V10.0469", stroke = true, strokeWidth = 1f),
            dshPart("M4.25 6.29688L8 10.0469L11.75 6.29688", stroke = true, strokeWidth = 1f),
            dshPart(
                "M1.5 10.0469V13.158C1.5 13.3937 1.60536 13.6198 1.79289 13.7865C1.98043 13.9532 " +
                    "2.23478 14.0469 2.5 14.0469H13.5C13.7652 14.0469 14.0196 13.9532 14.2071 " +
                    "13.7865C14.3946 13.6198 14.5 13.3937 14.5 13.158V10.0469",
                stroke = true,
                strokeWidth = 1f,
            ),
        )
    }
}
