package com.pixiv.downloader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.pixiv.downloader.model.DarkMode

// 根据种子颜色生成方案
private fun seedLightColorScheme(seed: Color) = lightColorScheme(
    primary = seed,
    onPrimary = Color.White,
    primaryContainer = seed.copy(alpha = 0.12f),
    onPrimaryContainer = seed,
    secondary = seed.copy(red = seed.red * 0.8f, green = seed.green * 0.8f, blue = seed.blue * 0.8f),
    tertiary = Color(
        red = (seed.red * 0.6f).coerceIn(0f, 1f),
        green = (seed.green * 1.2f).coerceIn(0f, 1f),
        blue = (seed.blue * 0.6f).coerceIn(0f, 1f)
    ),
)

private fun seedDarkColorScheme(seed: Color) = darkColorScheme(
    primary = seed.copy(alpha = 0.8f),
    onPrimary = Color(0xFF1A1A2E),
    primaryContainer = seed.copy(alpha = 0.15f),
    onPrimaryContainer = seed.copy(alpha = 0.9f),
    secondary = seed.copy(red = seed.red * 0.9f, green = seed.green * 0.9f, blue = seed.blue * 0.9f, alpha = 0.7f),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE1E1E1),
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFE1E1E1),
)

@Composable
fun PixivDownloaderTheme(
    darkMode: DarkMode = DarkMode.SYSTEM,
    dynamicColor: Boolean = true,
    customColorSeed: Color? = null,
    content: @Composable () -> Unit
) {
    val darkTheme = when (darkMode) {
        DarkMode.DARK -> true
        DarkMode.LIGHT -> false
        DarkMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context)
                else dynamicLightColorScheme(context)
            } else {
                if (darkTheme) darkColorScheme() else lightColorScheme()
            }
        }
        customColorSeed != null -> {
            if (darkTheme) seedDarkColorScheme(customColorSeed)
            else seedLightColorScheme(customColorSeed)
        }
        else -> {
            val defaultSeed = Color(0xFFE91E63) // Pixiv 风格粉色
            if (darkTheme) seedDarkColorScheme(defaultSeed)
            else seedLightColorScheme(defaultSeed)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
