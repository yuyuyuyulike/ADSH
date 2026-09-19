package com.adsh.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** DeepSeek 品牌蓝：主按钮 / 用户气泡 / 光标统一用它 */
val DshBlue = Color(0xFF4D6BFE)

private val Light = lightColorScheme(
    primary = DshBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E9FF),
    onPrimaryContainer = Color(0xFF11215C),
    inversePrimary = Color(0xFFB8C4FF),
    secondary = Color(0xFF5B6172),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EBF2),
    onSecondaryContainer = Color(0xFF1A1D26),
    tertiary = Color(0xFF6B5BE0),
    onTertiary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF14161C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14161C),
    surfaceVariant = Color(0xFFF2F3F7),
    onSurfaceVariant = Color(0xFF6B7280),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFC),
    surfaceContainer = Color(0xFFF5F6FA),
    surfaceContainerHigh = Color(0xFFEFF0F6),
    surfaceContainerHighest = Color(0xFFE9EAF1),
    surfaceDim = Color(0xFFE6E7EE),
    surfaceBright = Color(0xFFFFFFFF),
    outline = Color(0xFFC7CAD4),
    outlineVariant = Color(0xFFE7E8EF),
    inverseSurface = Color(0xFF2C2F38),
    inverseOnSurface = Color(0xFFF3F4F8),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0xFF000000),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF91A4FF),
    onPrimary = Color(0xFF0A1740),
    primaryContainer = Color(0xFF23346F),
    onPrimaryContainer = Color(0xFFDDE3FF),
    inversePrimary = Color(0xFF3550C8),
    secondary = Color(0xFFA9AEBE),
    onSecondary = Color(0xFF1A1D26),
    secondaryContainer = Color(0xFF2A2D38),
    onSecondaryContainer = Color(0xFFE4E6EF),
    tertiary = Color(0xFFC3BCFF),
    onTertiary = Color(0xFF231A5C),
    background = Color(0xFF101116),
    onBackground = Color(0xFFE6E7EC),
    surface = Color(0xFF101116),
    onSurface = Color(0xFFE6E7EC),
    surfaceVariant = Color(0xFF23252E),
    onSurfaceVariant = Color(0xFF9BA1AE),
    surfaceContainerLowest = Color(0xFF0B0C10),
    surfaceContainerLow = Color(0xFF15161B),
    surfaceContainer = Color(0xFF1A1B21),
    surfaceContainerHigh = Color(0xFF22232A),
    surfaceContainerHighest = Color(0xFF2B2C34),
    surfaceDim = Color(0xFF101116),
    surfaceBright = Color(0xFF363841),
    outline = Color(0xFF4A4D58),
    outlineVariant = Color(0xFF2E3038),
    inverseSurface = Color(0xFFE6E7EC),
    inverseOnSurface = Color(0xFF2C2F38),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

/**
 * 主题。preference 是 dsh 的 ui-theme.preference（light / dark / system，默认 system）：
 * 设置页的「外观」三个立方写的就是它。
 */
@Composable
fun AdshTheme(
    preference: String = com.adsh.app.core.data.SettingsStore.THEME_SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (preference) {
        com.adsh.app.core.data.SettingsStore.THEME_LIGHT -> false
        com.adsh.app.core.data.SettingsStore.THEME_DARK -> true
        else -> systemDark
    }
    val palette = if (dark) DarkDshPalette else LightDshPalette
    androidx.compose.runtime.CompositionLocalProvider(
        LocalDshPalette provides palette,
        // Material 的 LocalContentColor 默认值是纯黑，且 MaterialTheme 不会覆盖它：
        // 深色模式下凡是「没写颜色」的图标/文字都会黑成一片（顶栏两枚图标就是这样消失的）
        androidx.compose.material3.LocalContentColor provides palette.labelPrimary,
    ) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            content = content,
        )
    }
}
