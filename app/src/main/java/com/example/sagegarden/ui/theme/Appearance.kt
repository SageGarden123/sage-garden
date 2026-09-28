package com.example.sagegarden.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode(val label: String) { SYSTEM("Match device"), LIGHT("Light"), DARK("Dark") }

enum class AppPalette(val label: String) {
    SAGE("Sage"), TERRACOTTA("Terracotta"), OCEAN("Ocean"), LAVENDER("Lavender"),
    /** Android 12+ wallpaper-based colours; falls back to Sage on older devices. */
    DYNAMIC("Match wallpaper"),
}

enum class TextSize(val label: String, val scale: Float) {
    DEFAULT("Default", 1.0f), LARGE("Large", 1.15f), LARGEST("Largest", 1.3f)
}

/**
 * Device-wide look & accessibility preferences, applied at the root by SageGardenTheme. A
 * Compose-observable singleton (like this codebase's other app-wide toggles) so a change in
 * Settings restyles the whole app immediately. [TextSize] multiplies Android's own font-size
 * setting rather than replacing it, so someone who already enlarged text system-wide gets
 * larger still, never smaller.
 *
 * Colour filters (colour correction, inversion, greyscale) are deliberately NOT duplicated here —
 * Android's own Accessibility settings already apply them system-wide, and better. Settings links
 * there instead. [colourBlindSafe] covers the one thing the system can't: which colours the app
 * itself uses to mean "overdue" vs "on track".
 */
object AppearanceState {
    private const val PREFS = "app_appearance"

    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set
    var palette by mutableStateOf(AppPalette.SAGE)
        private set
    var textSize by mutableStateOf(TextSize.DEFAULT)
        private set
    var highContrast by mutableStateOf(false)
        private set
    var colourBlindSafe by mutableStateOf(false)
        private set

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = prefs(context)
        themeMode = ThemeMode.entries.firstOrNull { it.name == p.getString("theme_mode", null) } ?: ThemeMode.SYSTEM
        palette = AppPalette.entries.firstOrNull { it.name == p.getString("palette", null) } ?: AppPalette.SAGE
        textSize = TextSize.entries.firstOrNull { it.name == p.getString("text_size", null) } ?: TextSize.DEFAULT
        highContrast = p.getBoolean("high_contrast", false)
        colourBlindSafe = p.getBoolean("colour_blind_safe", false)
    }

    fun setThemeMode(context: Context, value: ThemeMode) { themeMode = value; prefs(context).edit().putString("theme_mode", value.name).apply() }
    fun setPalette(context: Context, value: AppPalette) { palette = value; prefs(context).edit().putString("palette", value.name).apply() }
    fun setTextSize(context: Context, value: TextSize) { textSize = value; prefs(context).edit().putString("text_size", value.name).apply() }
    fun setHighContrast(context: Context, value: Boolean) { highContrast = value; prefs(context).edit().putBoolean("high_contrast", value).apply() }
    fun setColourBlindSafe(context: Context, value: Boolean) { colourBlindSafe = value; prefs(context).edit().putBoolean("colour_blind_safe", value).apply() }
}
