package com.lanmedia.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ✅ 炫酷渐变色定义
val PurpleGradient = Brush.verticalGradient(
    colors = listOf(Color(0xFF6C63FF), Color(0xFF4A47A3))
)

val BlueGradient = Brush.verticalGradient(
    colors = listOf(Color(0xFF00D9FF), Color(0xFF00B8D4))
)

val PinkGradient = Brush.verticalGradient(
    colors = listOf(Color(0xFFFF6584), Color(0xFFFF4757))
)

val DarkBackgroundGradient = Brush.verticalGradient(
    colors = listOf(Color(0xFF0F0F1E), Color(0xFF1A1A2E))
)

// ✅ 炫酷深色主题 - 现代科技感配色
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6C63FF),        // 紫色主色调
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A47A3),
    onPrimaryContainer = Color.White,
    
    secondary = Color(0xFF00D9FF),      // 青色强调色
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF00B8D4),
    onSecondaryContainer = Color.White,
    
    tertiary = Color(0xFFFF6584),       // 粉色点缀
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFF4757),
    onTertiaryContainer = Color.White,
    
    background = Color(0xFF0F0F1E),     // 深蓝黑背景
    onBackground = Color(0xFFE0E0E0),
    
    surface = Color(0xFF1A1A2E),        // 卡片表面
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF252542),
    onSurfaceVariant = Color(0xFFB0B0C0),
    
    error = Color(0xFFFF4757),
    onError = Color.White
)

// ✅ 清新浅色主题
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6C63FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF8B85FF),
    onPrimaryContainer = Color.White,
    
    secondary = Color(0xFF00B8D4),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF00D9FF),
    onSecondaryContainer = Color.Black,
    
    tertiary = Color(0xFFFF6584),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFF8FA3),
    onTertiaryContainer = Color.White,
    
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF2D3436),
    
    surface = Color.White,
    onSurface = Color(0xFF2D3436),
    surfaceVariant = Color(0xFFE8EAF6),
    onSurfaceVariant = Color(0xFF5C5C7A),
    
    error = Color(0xFFFF4757),
    onError = Color.White
)

@Composable
fun LanMediaPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
