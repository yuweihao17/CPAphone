package com.cpaphone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// 极简收敛暗色调（沉浸式深炭黑，低视觉疲劳）
val DarkBackground = Color(0xFF0F1012)
val DarkSurface = Color(0xFF16181D)
val DarkSurfaceVariant = Color(0xFF1E2128)
val DarkSurfaceContainer = Color(0xFF262A33)
val DarkBorder = Color(0xFF2E333D)

val AccentPrimary = Color(0xFF3B82F6)        // 标志蓝
val AccentPrimaryContainer = Color(0xFF1E3A8A)
val AccentSecondary = Color(0xFF10B981)      // 活跃绿
val AccentWarning = Color(0xFFF59E0B)        // 冷却黄
val AccentError = Color(0xFFEF4444)          // 故障红
val AccentPurple = Color(0xFF8B5CF6)         // 思考/Claude 蓝紫
val TextPrimaryDark = Color(0xFFF8FAFC)
val TextSecondaryDark = Color(0xFF94A3B8)
val TextTertiaryDark = Color(0xFF64748B)

// 极简明色调（柔和米白纸面质感，高雅不刺眼）
val LightBackground = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF1F5F9)
val LightSurfaceContainer = Color(0xFFE2E8F0)
val LightBorder = Color(0xFFCBD5E1)
val TextPrimaryLight = Color(0xFF0F172A)
val TextSecondaryLight = Color(0xFF475569)

private val DarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = Color.White,
    primaryContainer = AccentPrimaryContainer,
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = AccentSecondary,
    onSecondary = Color.White,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    outline = DarkBorder,
    error = AccentError
)

private val LightColorScheme = lightColorScheme(
    primary = AccentPrimary,
    onPrimary = Color.White,
    secondary = AccentSecondary,
    onSecondary = Color.White,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight,
    outline = LightBorder,
    error = AccentError
)

val CompactShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun CPAphoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = CompactShapes,
        content = content
    )
}
