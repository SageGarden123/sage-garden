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
    var category: String = "",
    var sun: String = "",
    var water: String = "",
    var soil: String = "",
    var soilPh: String = "",
    var source: String = "",
    var date: String = "",
    var wateringSystem: String = "",
    var lat: Double? = null,
    var lng: Double? = null,
    var photoUri: String? = null,
    var photoUris: List<String> = emptyList(),
    // Small base64 JPEG cached alongside a phone-local (content://) photoUri — see PlantEntity in
    // the Android app. Not generated here (desktop never captures photos), only preserved so a
    // sync/backup round-trip doesn't drop what the Android client needs to show a fallback thumbnail.
    var photoThumbnailBase64: String? = null,
    var mapX: Double? = null,
    var mapY: Double? = null,
    var summerWateringFrequencyDays: Int? = null,
    var winterWateringFrequencyDays: Int? = null,
    var updatedAt: Long = 0L // last local write time — used by GardenSyncClient's last-write-wins merge
)

data class CareLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val plantId: String,
    val type: String, // "watering", "fertilise", "feed", "prune" — matches the Android app's careLog schema
    val date: Long,
    val notes: String = "",
    val updatedAt: Long = 0L
)

// Mirrors the Android app's dropdown option lists (MainActivity.kt) exactly, so a value picked
// here round-trips as one Android already recognises.
val categoryOptions = listOf(
    "Trees", "Shrubs", "Ground Cover", "Climbers/Vines", "Grasses", "Ferns", "Perennials",
    "Annuals", "Bulbs", "Succulents", "Palms/Cycads", "Aquatic", "Herbs", "Other"
)
val sunOptions = listOf("Full", "Full-Partial", "Partial", "Partial-Shade", "Shade", "Unknown")
val waterOptions = listOf("Low", "Moderate", "High", "Unknown")
val soilOptions = listOf(
    "Sandy", "Loamy", "Clay", "Silty", "Peaty", "Chalky", "Rocky/Stony", "Potting Mix", "Other", "Unknown"
)
val soilPhOptions = listOf("Acidic", "Acidic–Neutral", "Neutral", "Neutral–Alkaline", "Alkaline", "Acidic–Alkaline", "Unknown")
val frostOptions = listOf("Hardy", "Half-hardy", "Tender", "Tender (indoor only)", "Unknown")
val nativeOptions = listOf("Native (Aus)", "Exotic")
val pollinatorOptions = listOf(
    "Yes - bees", "Yes - butterflies", "Yes - bees & butterflies",
    "Yes - birds", "No", "Other"
)

data class SyncTombstone(val id: String, val deletedAt: Long)

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

private fun computeCareStatus(lastDate: Long?, frequencyDays: Int?, nowMillis: Long): WateringStatus? {
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

fun computeFertiliseStatus(plant: Plant, nowMillis: Long = System.currentTimeMillis()): WateringStatus? =
    computeCareStatus(plant.lastFertilisedDate, plant.fertiliseFrequencyDays, nowMillis)

fun computePruneStatus(plant: Plant, nowMillis: Long = System.currentTimeMillis()): WateringStatus? =
    computeCareStatus(plant.lastPrunedDate, plant.pruneFrequencyDays, nowMillis)

fun computeFeedStatus(plant: Plant, nowMillis: Long = System.currentTimeMillis()): WateringStatus? =
    computeCareStatus(plant.lastFedDate, plant.feedFrequencyDays, nowMillis)

fun frostTenderOutdoorPlants(plants: List<Plant>): List<Plant> =
    plants.filter { (it.frost == "Tender" || it.frost == "Half-hardy") && !it.isIndoor }

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
