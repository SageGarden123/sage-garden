@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.google.maps.android.compose.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ============================================================================
// WATERING SCHEDULE HELPERS
// ============================================================================

fun dateStringToMillis(s: String): Long? {
    if (s.isBlank()) return null
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.parse(s)?.time
    } catch (_: Exception) { null }
}

fun millisToDateString(millis: Long?): String {
    if (millis == null) return ""
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date(millis))
}

data class WateringStatus(val nextDueMillis: Long?, val label: String) {
    /** Sort key for priority ordering — never-watered plants sort first (most urgent). */
    fun sortKey(): Long = nextDueMillis ?: Long.MIN_VALUE
}

/**
 * Dec/Jan/Feb = summer + Jun/Jul/Aug = winter for a Southern-hemisphere garden, flipped for a
 * Northern-hemisphere one (Help → Weather-aware reminders); else base frequency. [hemisphere]
 * defaults to the live [HemisphereState] singleton, which is correct for any Compose call site —
 * a background caller with no composition (the reminder worker) should pass [getHemisphere]'s
 * result explicitly instead, since the singleton may not be synced yet in a cold-started process.
 */
fun effectiveWateringFrequencyDays(plant: PlantEntity, nowMillis: Long = System.currentTimeMillis(), hemisphere: Hemisphere = HemisphereState.value): Int? {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
    val isDecJanFeb = cal.get(java.util.Calendar.MONTH) in listOf(java.util.Calendar.DECEMBER, java.util.Calendar.JANUARY, java.util.Calendar.FEBRUARY)
    val isJunJulAug = cal.get(java.util.Calendar.MONTH) in listOf(java.util.Calendar.JUNE, java.util.Calendar.JULY, java.util.Calendar.AUGUST)
    val isSummer = if (hemisphere == Hemisphere.SOUTHERN) isDecJanFeb else isJunJulAug
    val isWinter = if (hemisphere == Hemisphere.SOUTHERN) isJunJulAug else isDecJanFeb
    return when {
        isSummer -> plant.summerWateringFrequencyDays ?: plant.wateringFrequencyDays
        isWinter -> plant.winterWateringFrequencyDays ?: plant.wateringFrequencyDays
        else -> plant.wateringFrequencyDays
    }
}

/** Generic due-date calculator, reused by watering, fertilising, and pruning. */
fun computeCareStatus(lastDate: Long?, frequencyDays: Int?, nowMillis: Long = System.currentTimeMillis()): WateringStatus? {
    val freq = frequencyDays ?: return null
    val last = lastDate ?: return WateringStatus(nextDueMillis = null, label = "Never — do now")
    val nextDue = last + freq * 86_400_000L
    val diffDays = ((nextDue - nowMillis) / 86_400_000L).toInt()
    val label = when {
        diffDays < 0 -> "Overdue by ${-diffDays} day(s)"
        diffDays == 0 -> "Due today"
        else -> "Due in $diffDays day(s)"
    }
    return WateringStatus(nextDueMillis = nextDue, label = label)
}

fun computeFertiliseStatus(plant: PlantEntity, nowMillis: Long = System.currentTimeMillis()): WateringStatus? =
    computeCareStatus(plant.lastFertilisedDate, plant.fertiliseFrequencyDays, nowMillis)

fun computePruneStatus(plant: PlantEntity, nowMillis: Long = System.currentTimeMillis()): WateringStatus? =
    computeCareStatus(plant.lastPrunedDate, plant.pruneFrequencyDays, nowMillis)

fun computeFeedStatus(plant: PlantEntity, nowMillis: Long = System.currentTimeMillis()): WateringStatus? =
    computeCareStatus(plant.lastFedDate, plant.feedFrequencyDays, nowMillis)

/** Returns null if no watering frequency is configured (nothing to schedule). See [effectiveWateringFrequencyDays] for the [hemisphere] default's caveat for background callers. */
fun computeWateringStatus(plant: PlantEntity, nowMillis: Long = System.currentTimeMillis(), hemisphere: Hemisphere = HemisphereState.value): WateringStatus? {
    val freq = effectiveWateringFrequencyDays(plant, nowMillis, hemisphere) ?: return null
    val last = plant.lastWateredDate
        ?: return WateringStatus(nextDueMillis = null, label = "Never watered — water now")

    val nextDue = last + freq * 86_400_000L
    val diffDays = ((nextDue - nowMillis) / 86_400_000L).toInt()
    val label = when {
        diffDays < 0 -> "Overdue by ${-diffDays} day(s)"
        diffDays == 0 -> "Due today"
        else -> "Due in $diffDays day(s)"
    }
    return WateringStatus(nextDueMillis = nextDue, label = label)
}

fun frostTenderOutdoorPlants(plants: List<PlantEntity>): List<PlantEntity> =
    plants.filter { (it.frost == "Tender" || it.frost == "Half-hardy") && !it.isIndoor }

/** Every zone (plant location) that's gone [PROGRESS_PHOTO_REMINDER_DAYS] or more since its last
 * progress photo, or never had one at all — [photos] should already be scoped to the same garden
 * as [plants] (see LocationPhotoDao.getAllOnceForGarden). */
fun dueProgressPhotoZones(plants: List<PlantEntity>, photos: List<LocationPhotoEntity>, now: Long): List<String> {
    val zones = plants.map { it.location }.filter { it.isNotBlank() }.distinct()
    val lastPhotoByZone = photos.groupBy { it.location }.mapValues { (_, entries) -> entries.maxOf { it.takenAt } }
    return zones.filter { zone ->
        val last = lastPhotoByZone[zone]
        last == null || (now - last) >= PROGRESS_PHOTO_REMINDER_DAYS * 86_400_000L
    }.sorted()
}

fun getFrostWarningsEnabled(context: Context): Boolean = gardenScopedBoolean(context, "frost_warnings_enabled", true)
fun setFrostWarningsEnabled(context: Context, value: Boolean) = setGardenScopedBoolean(context, "frost_warnings_enabled", value)
fun getFrostWarningsEnabledFor(context: Context, gardenId: String): Boolean =
    gardenScopedBoolean(context, "frost_warnings_enabled", true, gardenIdOverride = gardenId)
fun getFrostTempThreshold(context: Context): Double = gardenScopedFloat(context, "frost_temp_threshold", 2.0f).toDouble()
fun setFrostTempThreshold(context: Context, value: Double) = setGardenScopedFloat(context, "frost_temp_threshold", value.toFloat())
fun getFrostTempThresholdFor(context: Context, gardenId: String): Double =
    gardenScopedFloat(context, "frost_temp_threshold", 2.0f, gardenIdOverride = gardenId).toDouble()
