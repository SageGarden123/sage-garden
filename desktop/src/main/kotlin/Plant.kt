import java.util.UUID

/**
 * Mirrors the subset of the Android app's PlantEntity that this desktop app actually edits, plus
 * every other field found in the Android backup JSON schema (see BackupHelper.kt in the Android
 * app) so a file round-trips losslessly even though this app doesn't understand every field —
 * this is what lets "Export to device" on Android and "Open backup file..." here read each
 * other's files directly, with no real sync system needed for a single person's two devices.
 */
data class Plant(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var sci: String = "",
    var location: String = "",
    var native: String = "",
    var pollinator: String = "",
    var frost: String = "",
    var qty: Int = 1,
    var notes: String = "",
    var isIndoor: Boolean = false,
    var manualWateringOnly: Boolean = false,
    var lastWateredDate: Long? = null,
    var wateringFrequencyDays: Int? = null,
    var lastFertilisedDate: Long? = null,
    var fertiliseFrequencyDays: Int? = null,
    var lastPrunedDate: Long? = null,
    var pruneFrequencyDays: Int? = null,
    var lastFedDate: Long? = null,
    var feedFrequencyDays: Int? = null,
    // Not edited by this app, but preserved so exporting from here never drops what the phone app cares about.
    var sun: String = "",
    var water: String = "",
    var soil: String = "",
    var source: String = "",
    var date: String = "",
    var wateringSystem: String = "",
    var lat: Double? = null,
    var lng: Double? = null,
    var photoUri: String? = null,
    var photoUris: List<String> = emptyList(),
    var mapX: Double? = null,
    var mapY: Double? = null,
    var summerWateringFrequencyDays: Int? = null,
    var winterWateringFrequencyDays: Int? = null
)

data class CareLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val plantId: String,
    val type: String, // "watering", "fertilise", "feed", "prune" — matches the Android app's careLog schema
    val date: Long,
    val notes: String = ""
)

data class WateringStatus(val nextDueMillis: Long?, val label: String)

fun computeWateringStatus(plant: Plant, nowMillis: Long = System.currentTimeMillis()): WateringStatus? {
    val freq = plant.wateringFrequencyDays ?: return null
    val last = plant.lastWateredDate ?: return WateringStatus(null, "Never watered — water now")
    val nextDue = last + freq * 86_400_000L
    val diffDays = ((nextDue - nowMillis) / 86_400_000L).toInt()
    val label = when {
        diffDays < 0 -> "Overdue by ${-diffDays} day(s)"
        diffDays == 0 -> "Due today"
        else -> "Due in $diffDays day(s)"
    }
    return WateringStatus(nextDue, label)
}

fun careTypeLabel(type: String) = when (type) {
    "watering" -> "Watered"
    "fertilise" -> "Fertilised"
    "feed" -> "Fed"
    else -> "Pruned"
}

fun careTypeIcon(type: String) = when (type) {
    "watering" -> "💧"
    "fertilise" -> "🌱"
    "feed" -> "🍽️"
    else -> "✂️"
}
