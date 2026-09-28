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
 * a background caller with no composition (the reminder worker) should pass GardenSettings.of(context, gardenId).hemisphere's
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

/**
 * Care dates are compared as CALENDAR DAYS in the phone's own time zone, never as 24-hour periods.
 * A date picked in the app is stored as UTC midnight of the chosen day; counting 24-hour periods from
 * that made a plant watered on the 28th with a 2-day frequency "due today" from 8pm on the 29th in
 * Australia (its due moment, 10am on the 30th local, was under 24 hours away). Anything that isn't
 * exactly UTC midnight (a "Done" tap, a care-log entry) is a real moment, read in local time.
 */
fun careDateToLocalDate(millis: Long): java.time.LocalDate =
    if (millis % 86_400_000L == 0L) java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    else java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

private fun localDateOf(millis: Long): java.time.LocalDate =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

private fun startOfLocalDay(date: java.time.LocalDate): Long =
    date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Whole calendar days from today until [dueMillis]'s day — 0 = due today, negative = overdue. */
fun daysUntil(dueMillis: Long, nowMillis: Long = System.currentTimeMillis()): Int =
    java.time.temporal.ChronoUnit.DAYS.between(localDateOf(nowMillis), localDateOf(dueMillis)).toInt()

/** Next due moment = the start (local midnight) of the day [frequencyDays] after [lastDate]'s day. */
private fun nextDueMillis(lastDate: Long, frequencyDays: Int): Long =
    startOfLocalDay(careDateToLocalDate(lastDate).plusDays(frequencyDays.toLong()))

private fun dueLabel(diffDays: Int) = when {
    diffDays < 0 -> "Overdue by ${-diffDays} day(s)"
    diffDays == 0 -> "Due today"
    else -> "Due in $diffDays day(s)"
}

/** Generic due-date calculator, reused by watering, fertilising, and pruning. */
fun computeCareStatus(lastDate: Long?, frequencyDays: Int?, nowMillis: Long = System.currentTimeMillis()): WateringStatus? {
    val freq = frequencyDays ?: return null
    val last = lastDate ?: return WateringStatus(nextDueMillis = null, label = "Never — do now")
    val nextDue = nextDueMillis(last, freq)
    return WateringStatus(nextDueMillis = nextDue, label = dueLabel(daysUntil(nextDue, nowMillis)))
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

    val nextDue = nextDueMillis(last, freq)
    return WateringStatus(nextDueMillis = nextDue, label = dueLabel(daysUntil(nextDue, nowMillis)))
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

/**
 * Which due zones should actually trigger a notification TODAY — as opposed to [dueProgressPhotoZones],
 * which lists everything currently due (for the details screen). Without this, a due zone notified
 * every single day until a photo was taken. Now each zone notifies on the day it becomes due, then
 * again only on the garden's own overdue-repeat cadence ("remind me again every N days"), or never
 * again if overdue repeats are off — the same rule watering reminders follow.
 *
 * A zone that has never had a progress photo becomes due on [enabledAt] (when reminders were switched
 * on), so enabling the feature gives one prompt per zone rather than a daily one. [enabledAt] of 0
 * means it was enabled before that was recorded; the caller stamps it on first use.
 */
fun progressPhotoZonesToNotify(
    plants: List<PlantEntity>,
    photos: List<LocationPhotoEntity>,
    now: Long,
    enabledAt: Long,
    overdueRepeatEnabled: Boolean,
    overdueRepeatDays: Int
): List<String> {
    val dayMs = 86_400_000L
    val zones = plants.map { it.location }.filter { it.isNotBlank() }.distinct()
    val lastPhotoByZone = photos.groupBy { it.location }.mapValues { (_, entries) -> entries.maxOf { it.takenAt } }
    return zones.filter { zone ->
        val dueAt = lastPhotoByZone[zone]?.let { it + PROGRESS_PHOTO_REMINDER_DAYS * dayMs } ?: enabledAt
        if (now < dueAt) return@filter false
        val daysOverdue = ((now - dueAt) / dayMs).toInt()
        daysOverdue == 0 || (overdueRepeatEnabled && daysOverdue % overdueRepeatDays.coerceAtLeast(1) == 0)
    }.sorted()
}

