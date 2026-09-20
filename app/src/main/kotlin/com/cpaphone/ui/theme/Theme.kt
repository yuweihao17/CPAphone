package com.cpaphone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 极简收敛色彩体系（收敛灰色 + 强调蓝紫）
val DarkBackground = Color(0xFF121316)
val DarkSurface = Color(0xFF1A1C20)
val DarkSurfaceVariant = Color(0xFF24272D)
val AccentPrimary = Color(0xFF4E75FF)
val AccentSecondary = Color(0xFF10B981) // 活跃绿
val AccentWarning = Color(0xFFF59E0B)   // 冷却黄
val AccentError = Color(0xFFEF4444)     // 故障红
val TextPrimaryDark = Color(0xFFF1F3F9)
val TextSecondaryDark = Color(0xFF9CA3AF)

private val DarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = Color.White,
    secondary = AccentSecondary,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    error = AccentError
)

private val LightColorScheme = lightColorScheme(
    primary = AccentPrimary,
    onPrimary = Color.White,
    secondary = AccentSecondary,
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    error = AccentError
)

@Composable
fun CPAphoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
