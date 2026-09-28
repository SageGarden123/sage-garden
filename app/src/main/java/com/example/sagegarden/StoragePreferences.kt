@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.google.maps.android.compose.*

// ============================================================================
// SIMPLE PREFERENCES (photo storage mode: "local" or "cloud")
// ============================================================================

fun getPhotoStorageMode(context: Context): String {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("photo_storage_mode", "local") ?: "local"
}

fun setPhotoStorageMode(context: Context, mode: String) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("photo_storage_mode", mode).apply()
}

/**
 * Which map image/rotation/on-off-toggle is in effect — per-garden, via the same
 * gardenScopedString/Boolean/Int helpers (defined below) used for hemisphere/notification settings,
 * so switching gardens in Help swaps to that garden's own drawing instead of showing garden A's
 * custom map while looking at garden B's plants.
 */
fun getCustomMapUri(context: Context): Uri? {
    val raw = gardenScopedString(context, "custom_map_uri", "")
    return raw.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
}
fun setCustomMapUri(context: Context, uri: Uri?) {
    setGardenScopedString(context, "custom_map_uri", uri?.toString() ?: "")
}
fun isUsingCustomMap(context: Context): Boolean = gardenScopedBoolean(context, "use_custom_map", false)
fun getCustomMapRotation(context: Context): Int = gardenScopedInt(context, "custom_map_rotation", 0)
fun setCustomMapRotation(context: Context, degrees: Int) {
    setGardenScopedInt(context, "custom_map_rotation", ((degrees % 360) + 360) % 360)
}
fun setUsingCustomMap(context: Context, value: Boolean) {
    setGardenScopedBoolean(context, "use_custom_map", value)
}
