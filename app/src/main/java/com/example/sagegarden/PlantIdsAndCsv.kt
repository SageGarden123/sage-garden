@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.google.maps.android.compose.*
import java.util.Date

val CSV_HEADERS = listOf(
    "Plant ID", "Plant", "Amount", "Scientific name", "Location", "Date planted", "Source",
    "Sun", "Soil", "Soil pH", "Category", "Water", "Frost", "Native/Exotic", "Pollinator-Friendly", "Notes",
    "Latitude", "Longitude", "Watering System", "Manual Watering Only", "Indoor Plant"
)

fun generateNextPlantId(existingPlants: List<PlantEntity>, prefix: String = "P"): String =
    nextPlantId(existingPlants.map { it.id }, prefix)

/** Callers that already have a device-wide id set on hand (e.g. CSV import, which needs to check
 * every garden's ids for uniqueness while separately tracking active-garden plants for update
 * matching) can call this directly instead of building a throwaway PlantEntity list. [prefix]
 * should be this garden's own letter from [plantIdPrefixForGarden] — distinct per-garden prefixes
 * (rather than every garden using "P") mean two gardens' auto-generated ids can never collide in
 * the first place, on top of the existing device-wide uniqueness scan as a backstop. */
fun nextPlantId(existingIds: Collection<String>, prefix: String = "P"): String {
    val maxNum = existingIds.mapNotNull { id ->
        Regex("^${Regex.escape(prefix)}(\\d+)$").find(id.trim())?.groupValues?.get(1)?.toIntOrNull()
    }.maxOrNull() ?: 0
    return "$prefix%04d".format(maxNum + 1)
}

// Letters available to additional (non-default) gardens for their plant-id prefix — "P" is
// reserved for the device's own original default garden (gardenId == installId) so existing
// ids/backups/exports need no migration. Extremely unlikely to ever be exhausted for a personal
// gardening app, but falls back to two-letter codes ("QA", "QB", ...) rather than erroring if it
// somehow is.
internal const val PLANT_ID_PREFIX_LETTERS = "QRSTUVWXYZ"

internal fun plantIdPrefixAtIndex(index: Int): String {
    if (index < PLANT_ID_PREFIX_LETTERS.length) return PLANT_ID_PREFIX_LETTERS[index].toString()
    val overflow = index - PLANT_ID_PREFIX_LETTERS.length
    val first = PLANT_ID_PREFIX_LETTERS[(overflow / 26) % PLANT_ID_PREFIX_LETTERS.length]
    val second = 'A' + (overflow % 26)
    return "$first$second"
}

/**
 * The plant-id letter prefix ("P", "Q", "R", ...) this garden uses on THIS device — stable once
 * assigned (persisted), so a garden's plants always keep the same prefix rather than shifting
 * around. The device's own original default garden always gets "P"; any other garden (created or
 * joined) gets the next never-yet-used letter the first time this is called for it. Two different
 * devices sharing the same garden may assign it different letters locally — that's fine, since the
 * point is only to guarantee THIS device's own locally-generated ids never collide with each
 * other, not to keep prefixes consistent across devices. See feedback_plant_id_cross_garden_collision.
 */
fun plantIdPrefixForGarden(context: Context, gardenId: String): String {
    if (gardenId.isBlank() || gardenId == getOrCreateInstallId(context)) return "P"
    val prefs = gardenPrefs(context)
    val key = "plant_id_prefix.$gardenId"
    prefs.getString(key, null)?.let { return it }
    val nextIndex = prefs.getInt("plant_id_prefix_next_index", 0)
    val assigned = plantIdPrefixAtIndex(nextIndex)
    prefs.edit().putString(key, assigned).putInt("plant_id_prefix_next_index", nextIndex + 1).apply()
    return assigned
}

fun detectCsvDelimiter(line: String): Char {
    val commaCount = line.count { it == ',' }
    val tabCount = line.count { it == '\t' }
    return if (tabCount > commaCount) '\t' else ','
}

fun parseCsvLine(line: String, delimiter: Char = ','): List<String> {
    val result = mutableListOf<String>()
    var current = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
            c == '"' -> inQuotes = !inQuotes
            c == delimiter && !inQuotes -> { result.add(current.toString()); current = StringBuilder() }
            else -> current.append(c)
        }
        i++
    }
    result.add(current.toString())
    return result
}
