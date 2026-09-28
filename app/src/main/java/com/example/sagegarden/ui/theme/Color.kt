package com.example.sagegarden.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Sage Garden's brand colours: sage green, deep green, cream, brick red. Every palette shares the
// same warm neutral surfaces so the app keeps its character; only the accent family changes.

private val lightNeutrals = lightColorScheme(
    background = Color(0xFFFBFAF6), onBackground = Color(0xFF1B1C18),
    surface = Color(0xFFFBFAF6), onSurface = Color(0xFF1B1C18),
    surfaceVariant = Color(0xFFE3DDCF), onSurfaceVariant = Color(0xFF4A4739),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF6F4EE),
    surfaceContainer = Color(0xFFF1EEE6), surfaceContainerHigh = Color(0xFFEBE8DF),
    surfaceContainerHighest = Color(0xFFE5E2D9), surfaceBright = Color(0xFFFBFAF6), surfaceDim = Color(0xFFDCD9D0),
    outline = Color(0xFF7A7667), outlineVariant = Color(0xFFCBC6B5),
    error = Color(0xFFB23B3B), onError = Color.White,
    errorContainer = Color(0xFFFBE9E7), onErrorContainer = Color(0xFF5F1412),
    inverseSurface = Color(0xFF30312C), inverseOnSurface = Color(0xFFF2F1EA),
    scrim = Color.Black,
)

private val darkNeutrals = darkColorScheme(
    background = Color(0xFF121410), onBackground = Color(0xFFE3E3DC),
    surface = Color(0xFF121410), onSurface = Color(0xFFE3E3DC),
    surfaceVariant = Color(0xFF45463A), onSurfaceVariant = Color(0xFFC7C7B8),
    surfaceContainerLowest = Color(0xFF0D0F0B), surfaceContainerLow = Color(0xFF1A1C18),
    surfaceContainer = Color(0xFF1E201C), surfaceContainerHigh = Color(0xFF282B26),
    surfaceContainerHighest = Color(0xFF333531), surfaceBright = Color(0xFF383A35), surfaceDim = Color(0xFF121410),
    outline = Color(0xFF919283), outlineVariant = Color(0xFF45463A),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE3E3DC), inverseOnSurface = Color(0xFF30312C),
    scrim = Color.Black,
)

/** One accent family (primary/secondary/tertiary, each with container variants) for light and dark. */
private data class Accents(
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val secondary: Color, val onSecondary: Color, val secondaryContainer: Color, val onSecondaryContainer: Color,
    val tertiary: Color, val onTertiary: Color, val tertiaryContainer: Color, val onTertiaryContainer: Color,
)

private fun ColorScheme.withAccents(a: Accents) = copy(
    primary = a.primary, onPrimary = a.onPrimary, primaryContainer = a.primaryContainer, onPrimaryContainer = a.onPrimaryContainer,
    inversePrimary = a.primaryContainer, surfaceTint = a.primary,
    secondary = a.secondary, onSecondary = a.onSecondary, secondaryContainer = a.secondaryContainer, onSecondaryContainer = a.onSecondaryContainer,
    tertiary = a.tertiary, onTertiary = a.onTertiary, tertiaryContainer = a.tertiaryContainer, onTertiaryContainer = a.onTertiaryContainer,
)

// Shared earthy secondary (cream/khaki) and warm-orange tertiary, used by every palette.
private val earthLight = listOf(Color(0xFF6B5E45), Color.White, Color(0xFFE3DDCF), Color(0xFF2B2418))
private val earthDark = listOf(Color(0xFFCFC5AF), Color(0xFF362F1F), Color(0xFF4D4533), Color(0xFFEDE2CB))
private val emberLight = listOf(Color(0xFFA8481F), Color.White, Color(0xFFFFDBCE), Color(0xFF380D00))
private val emberDark = listOf(Color(0xFFFFB59A), Color(0xFF5A1C00), Color(0xFF7A3014), Color(0xFFFFDBCE))
private val sageLight = listOf(Color(0xFF3A5A40), Color.White, Color(0xFFD4E8D1), Color(0xFF233821))
private val sageDark = listOf(Color(0xFFA5D0A8), Color(0xFF0E3818), Color(0xFF2A4A30), Color(0xFFC8E8C9))

private fun accents(p: List<Color>, s: List<Color>, t: List<Color>) =
    Accents(p[0], p[1], p[2], p[3], s[0], s[1], s[2], s[3], t[0], t[1], t[2], t[3])

internal fun paletteScheme(palette: AppPalette, dark: Boolean): ColorScheme {
    val base = if (dark) darkNeutrals else lightNeutrals
    val earth = if (dark) earthDark else earthLight
    val ember = if (dark) emberDark else emberLight
    val sage = if (dark) sageDark else sageLight
    val a = when (palette) {
        AppPalette.SAGE, AppPalette.DYNAMIC -> accents(sage, earth, ember)
        AppPalette.TERRACOTTA -> accents(
            if (dark) listOf(Color(0xFFFFB693), Color(0xFF571E00), Color(0xFF7A2F0C), Color(0xFFFFDBCC))
            else listOf(Color(0xFF9A4521), Color.White, Color(0xFFFFDBCC), Color(0xFF3A0B00)),
            earth, sage
        )
        AppPalette.OCEAN -> accents(
            if (dark) listOf(Color(0xFF8ECFF0), Color(0xFF003549), Color(0xFF0B4D66), Color(0xFFC4E7FF))
            else listOf(Color(0xFF2C6A85), Color.White, Color(0xFFC4E7FF), Color(0xFF001E2C)),
            earth, ember
        )
        AppPalette.LAVENDER -> accents(
            if (dark) listOf(Color(0xFFD6BBFF), Color(0xFF3B2659), Color(0xFF523E75), Color(0xFFEEDCFF))
            else listOf(Color(0xFF6B568F), Color.White, Color(0xFFEEDCFF), Color(0xFF251140)),
            earth, sage
        )
    }
    return base.withAccents(a)
}

/**
 * Colours Material's scheme has no slot for: the "water" accent used for watering actions, a
 * warning banner, and the due-status trio. The status colours come in a colour-blind-friendly
 * variant (blue / amber / vermillion instead of green / amber / red, which deuteranopes and
 * protanopes can't tell apart). Status is always also spelled out in text, never colour alone.
 */
data class AppColors(
    val water: Color,
    val onWater: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** Overdue / at risk. */
    val statusUrgent: Color,
    /** Due today or soon. */
    val statusSoon: Color,
    /** On track / done. */
    val statusGood: Color,
)

internal fun appColors(dark: Boolean, colourBlindSafe: Boolean): AppColors = when {
    !dark && !colourBlindSafe -> AppColors(
        water = Color(0xFF2F7A99), onWater = Color.White,
        warningContainer = Color(0xFFFFF3CD), onWarningContainer = Color(0xFF6B5300),
        statusUrgent = Color(0xFFB23B3B), statusSoon = Color(0xFF8A5A00), statusGood = Color(0xFF2E7D32),
    )
    dark && !colourBlindSafe -> AppColors(
        water = Color(0xFF7CC4E4), onWater = Color(0xFF00344A),
        warningContainer = Color(0xFF4A3B00), onWarningContainer = Color(0xFFFFE08A),
        statusUrgent = Color(0xFFFFB4AB), statusSoon = Color(0xFFFFB951), statusGood = Color(0xFF8BD18F),
    )
    !dark -> AppColors(
        water = Color(0xFF2F7A99), onWater = Color.White,
        warningContainer = Color(0xFFFFF3CD), onWarningContainer = Color(0xFF6B5300),
        statusUrgent = Color(0xFFB34700), statusSoon = Color(0xFF7A6300), statusGood = Color(0xFF0060A8),
    )
    else -> AppColors(
        water = Color(0xFF7CC4E4), onWater = Color(0xFF00344A),
        warningContainer = Color(0xFF4A3B00), onWarningContainer = Color(0xFFFFE08A),
        statusUrgent = Color(0xFFFFB870), statusSoon = Color(0xFFE8D26A), statusGood = Color(0xFF9CCAFF),
    )
}
