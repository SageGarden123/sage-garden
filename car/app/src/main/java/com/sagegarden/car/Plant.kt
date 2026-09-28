package com.sagegarden.car

import org.json.JSONObject

/**
 * Deliberately a small subset of the phone app's PlantEntity — this is a read-only viewer, so it
 * only carries the fields the Dashboard/list/detail screens actually display. Parsed straight out
 * of syncGarden's response JSON (same shape GardenSyncClient.kt on the phone sends/receives).
 */
data class Plant(
    val id: String,
    val name: String,
    val sci: String,
    val location: String,
    val category: String,
    val sun: String,
    val water: String,
    val soil: String,
    val frost: String,
    val native: String,
    val pollinator: String,
    val notes: String,
    val qty: Int,
    val photoUri: String?,
    val photoThumbnailBase64: String?,
    val lastWateredDate: Long?,
    val wateringFrequencyDays: Int?,
    val lastFertilisedDate: Long?,
    val fertiliseFrequencyDays: Int?,
    val lastPrunedDate: Long?,
    val pruneFrequencyDays: Int?,
    val lastFedDate: Long?,
    val feedFrequencyDays: Int?,
    val lat: Double?,
    val lng: Double?
)

fun jsonToPlant(o: JSONObject): Plant = Plant(
    id = o.getString("id"),
    name = o.optString("name", ""),
    sci = o.optString("sci", ""),
    location = o.optString("location", ""),
    category = o.optString("category", ""),
    sun = o.optString("sun", ""),
    water = o.optString("water", ""),
    soil = o.optString("soil", ""),
    frost = o.optString("frost", ""),
    native = o.optString("native", ""),
    pollinator = o.optString("pollinator", ""),
    notes = o.optString("notes", ""),
    qty = o.optInt("qty", 1),
    photoUri = if (o.isNull("photoUri")) null else o.optString("photoUri"),
    photoThumbnailBase64 = if (o.isNull("photoThumbnail")) null else o.optString("photoThumbnail"),
    lastWateredDate = if (o.isNull("lastWateredDate")) null else o.optLong("lastWateredDate"),
    wateringFrequencyDays = if (o.isNull("wateringFrequencyDays")) null else o.optInt("wateringFrequencyDays"),
    lastFertilisedDate = if (o.isNull("lastFertilisedDate")) null else o.optLong("lastFertilisedDate"),
    fertiliseFrequencyDays = if (o.isNull("fertiliseFrequencyDays")) null else o.optInt("fertiliseFrequencyDays"),
    lastPrunedDate = if (o.isNull("lastPrunedDate")) null else o.optLong("lastPrunedDate"),
    pruneFrequencyDays = if (o.isNull("pruneFrequencyDays")) null else o.optInt("pruneFrequencyDays"),
    lastFedDate = if (o.isNull("lastFedDate")) null else o.optLong("lastFedDate"),
    feedFrequencyDays = if (o.isNull("feedFrequencyDays")) null else o.optInt("feedFrequencyDays"),
    lat = if (o.isNull("lat")) null else o.optDouble("lat"),
    lng = if (o.isNull("lng")) null else o.optDouble("lng")
)

/** Emoji used as this category's map marker — mirrors the phone app's categoryMarkerEmoji exactly,
 * falling back to a plain pin for "Other"/unset (same convention). */
fun categoryMarkerEmoji(category: String): String = when (category) {
    "Trees" -> "🌳"
    "Shrubs" -> "🪴"
    "Ground Cover" -> "🍀"
    "Climbers/Vines" -> "🍃"
    "Grasses" -> "🌾"
    "Ferns" -> "🌿"
    "Perennials" -> "🌸"
    "Annuals" -> "🌻"
    "Bulbs" -> "🌷"
    "Succulents" -> "🌵"
    "Palms/Cycads" -> "🌴"
    "Aquatic" -> "🪷"
    "Herbs" -> "🌱"
    else -> "📍"
}

/** Simplified from the phone app's computeWateringStatus — flat frequency only, no
 * summer/winter override or hemisphere awareness. Fine for a read-only glance view; if this ever
 * needs to match the phone exactly, port effectiveWateringFrequencyDays over too. */
data class CareStatus(val label: String, val overdue: Boolean, val dueToday: Boolean)

fun careStatus(last: Long?, frequencyDays: Int?, now: Long): CareStatus? {
    val freq = frequencyDays ?: return null
    if (last == null) return CareStatus("Never — do now", overdue = true, dueToday = false)
    // Calendar days in the local time zone, same as the phone: picked dates are stored as UTC
    // midnight, and counting 24-hour periods showed plants as due a day early east of UTC.
    val zone = java.time.ZoneId.systemDefault()
    val lastDay = if (last % 86_400_000L == 0L) java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        else java.time.Instant.ofEpochMilli(last).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val diffDays = java.time.temporal.ChronoUnit.DAYS.between(today, lastDay.plusDays(freq.toLong())).toInt()
    return when {
        diffDays < 0 -> CareStatus("Overdue by ${-diffDays} day(s)", overdue = true, dueToday = false)
        diffDays == 0 -> CareStatus("Due today", overdue = false, dueToday = true)
        else -> CareStatus("Due in $diffDays day(s)", overdue = false, dueToday = false)
    }
}

fun Plant.wateringStatus(now: Long) = careStatus(lastWateredDate, wateringFrequencyDays, now)
