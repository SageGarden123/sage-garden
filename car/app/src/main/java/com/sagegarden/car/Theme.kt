package com.sagegarden.car

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** A small preset palette rather than a full colour wheel — quick to test with, and every option is
 * already tuned to look good against the dark surfaces below (a fully custom picker is easy to add
 * later if the presets aren't enough). */
data class AccentPreset(val label: String, val color: Color)

val accentPresets = listOf(
    AccentPreset("Sage", Color(0xFF3A5A40)),
    AccentPreset("Ocean", Color(0xFF2C6E8F)),
    AccentPreset("Sunset", Color(0xFFC9622B)),
    AccentPreset("Berry", Color(0xFF8E3B6B)),
    AccentPreset("Amber", Color(0xFFB98A1E)),
    AccentPreset("Slate", Color(0xFF556070))
)

private const val PREFS = "sage_garden_car_prefs"
private const val KEY_ACCENT = "accent_color_argb"
private const val KEY_INSTALL_ID = "linked_install_id"

fun getSavedAccentColor(context: Context): Color {
    val argb = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_ACCENT, accentPresets[0].color.toArgbInt())
    return Color(argb)
}
fun setSavedAccentColor(context: Context, color: Color) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putInt(KEY_ACCENT, color.toArgbInt()).apply()
    ThemeState.accent = color
}
private fun Color.toArgbInt(): Int =
    (alpha * 255).toInt().shl(24) or (red * 255).toInt().shl(16) or (green * 255).toInt().shl(8) or (blue * 255).toInt()

fun getLinkedInstallId(context: Context): String =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_INSTALL_ID, "") ?: ""
fun setLinkedInstallId(context: Context, id: String) {
    // A different garden means a different membership — drop the old token.
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_INSTALL_ID, id).putString("active_garden_id", id).apply()
}

private const val KEY_OWN_DEVICE_ID = "own_device_id"
private const val KEY_MEMBER_TOKEN = "member_token"

/**
 * This car display's OWN identity, separate from the phone's Install ID it's linked to. It used to
 * sync AS the phone (sending the phone's Install ID as its device id, with no token), which the
 * server treated as the phone having lost its token — so every refresh here silently issued the
 * phone a new token and broke the phone's owner-only actions until the phone next synced. Now it
 * joins the phone's garden as its own member and keeps its own token.
 */
fun getOwnDeviceId(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.getString(KEY_OWN_DEVICE_ID, null)?.let { return it }
    val id = "car-" + java.util.UUID.randomUUID().toString()
    prefs.edit().putString(KEY_OWN_DEVICE_ID, id).apply()
    return id
}
/** Member token for [gardenId]. Migrates the single token 0.4 kept for the linked phone's garden. */
fun getMemberToken(context: Context, gardenId: String): String? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.getString(KEY_MEMBER_TOKEN, null)?.let { legacy ->
        prefs.edit().remove(KEY_MEMBER_TOKEN).putString("token.${getLinkedInstallId(context)}", legacy).apply()
    }
    return prefs.getString("token.$gardenId", null)
}
fun setMemberToken(context: Context, gardenId: String, token: String?) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("token.$gardenId", token).apply()
}

/** Which garden is shown — defaults to the linked phone's own garden. */
fun getActiveGardenId(context: Context): String =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("active_garden_id", null) ?: getLinkedInstallId(context)
fun setActiveGardenId(context: Context, gardenId: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("active_garden_id", gardenId).apply()
}

private const val KEY_GARDEN_LAT = "garden_lat"
private const val KEY_GARDEN_LNG = "garden_lng"

/** Cached from the last successful sync so the Map tab has a sensible camera position immediately
 * on launch, same reasoning as the plant list cache. */
fun getSavedGardenLatLng(context: Context): Pair<Double, Double>? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (!prefs.contains(KEY_GARDEN_LAT) || !prefs.contains(KEY_GARDEN_LNG)) return null
    return Pair(
        Double.fromBits(prefs.getLong(KEY_GARDEN_LAT, 0L)),
        Double.fromBits(prefs.getLong(KEY_GARDEN_LNG, 0L))
    )
}
fun setSavedGardenLatLng(context: Context, lat: Double?, lng: Double?) {
    val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
    if (lat != null && lng != null) {
        editor.putLong(KEY_GARDEN_LAT, lat.toRawBits()).putLong(KEY_GARDEN_LNG, lng.toRawBits())
    } else {
        editor.remove(KEY_GARDEN_LAT).remove(KEY_GARDEN_LNG)
    }
    editor.apply()
}

/** Compose-observable accent colour — changing it in Settings recolours every screen immediately,
 * following this codebase's established singleton pattern (see the phone app's HemisphereState etc.
 * for the same idea) rather than needing a full navigation restart to pick it up. */
object ThemeState {
    var accent by mutableStateOf(accentPresets[0].color)
}

@Composable
fun SageGardenCarTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = ThemeState.accent,
        secondary = ThemeState.accent,
        surface = Color(0xFF1B1F1C),
        background = Color(0xFF121412),
        surfaceVariant = Color(0xFF23271F)
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
