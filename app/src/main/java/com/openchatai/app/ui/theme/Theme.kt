package com.openchatai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.openchai.core.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = LightSurface,
    secondary = BrandSecondary,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    outline = LightOutline,
    error = BrandError
)

private val DarkColors = darkColorScheme(
    primary = BrandPrimaryDark,
    onPrimary = DarkBackground,
    secondary = BrandSecondary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    outline = DarkOutline,
    error = BrandError
)

@Composable
fun OpenChatTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
