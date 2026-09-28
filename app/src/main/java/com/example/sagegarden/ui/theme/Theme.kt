package com.example.sagegarden.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat

val LocalAppColors = staticCompositionLocalOf { appColors(dark = false, colourBlindSafe = false) }

/** Sage Garden's extra semantic colours (water accent, warning banner, due-status trio) — see [AppColors]. */
val MaterialTheme.appColors: AppColors
    @Composable get() = LocalAppColors.current

/** Pushes text/outline colours to full-strength ink and deepens (or, in dark mode, brightens) the accent colours. */
private fun ColorScheme.highContrast(dark: Boolean): ColorScheme {
    val ink = if (dark) Color.White else Color.Black
    fun strengthen(c: Color) = lerp(c, ink, 0.35f)
    return copy(
        primary = strengthen(primary), secondary = strengthen(secondary), tertiary = strengthen(tertiary), error = strengthen(error),
        onSurface = ink, onBackground = ink, onSurfaceVariant = lerp(onSurfaceVariant, ink, 0.6f),
        outline = lerp(outline, ink, 0.6f), outlineVariant = lerp(outlineVariant, ink, 0.4f),
    )
}

@Composable
fun SageGardenTheme(content: @Composable () -> Unit) {
    val dark = when (AppearanceState.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    var scheme = if (AppearanceState.palette == AppPalette.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else paletteScheme(AppearanceState.palette, dark)
    if (AppearanceState.highContrast) scheme = scheme.highContrast(dark)

    // Status-bar/navigation-bar icons must flip with the theme or they vanish against the background.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalAppColors provides appColors(dark, AppearanceState.colourBlindSafe),
        // Multiplies (never replaces) the user's system font scale — see AppearanceState.
        LocalDensity provides Density(density.density, density.fontScale * AppearanceState.textSize.scale),
    ) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}
