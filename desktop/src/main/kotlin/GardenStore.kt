import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reads and writes the same JSON schema as the Android app's "Export to device" / "Restore"
 * backup file (see BackupHelper.kt). This app only understands and edits "plants" and "careLog",
 * but keeps every other top-level key it doesn't touch (irrigationPaths, sunZones, growthPhotos,
 * settings, etc.) exactly as loaded, so a file saved here and later restored on the phone never
 * silently drops anything the phone app cares about.
 */
class GardenStore(private val file: File) {
    var plants: MutableList<Plant> = mutableListOf()
        private set
    var careLog: MutableList<CareLogEntry> = mutableListOf()
        private set
    private var passthrough: JSONObject = JSONObject()

    fun load() {
        if (!file.exists()) {
            plants = mutableListOf()
            careLog = mutableListOf()
            passthrough = JSONObject()
            return
        }
        val root = JSONObject(file.readText())
        plants = parsePlants(root.optJSONArray("plants") ?: JSONArray())
        careLog = parseCareLog(root.optJSONArray("careLog") ?: JSONArray())
        passthrough = root
    }

    fun save() {
        val root = JSONObject(passthrough.toString()) // clone so repeated saves don't accumulate stale state
        root.put("backupVersion", 2)
        root.put("createdAt", System.currentTimeMillis())
        root.put("plants", plantsToJson(plants))
        root.put("careLog", careLogToJson(careLog))
        file.parentFile?.mkdirs()
        file.writeText(root.toString(2))
    }

    private fun parsePlants(arr: JSONArray): MutableList<Plant> {
        val result = mutableListOf<Plant>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            result += Plant(
                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                name = o.optString("name", ""),
                sci = o.optString("sci", ""),
                location = o.optString("location", ""),
                native = o.optString("native", ""),
                pollinator = o.optString("pollinator", ""),
                frost = o.optString("frost", ""),
                qty = o.optInt("qty", 1),
                notes = o.optString("notes", ""),
                isIndoor = o.optBoolean("isIndoor", false),
                manualWateringOnly = o.optBoolean("manualWateringOnly", false),
                lastWateredDate = o.optLongOrNull("lastWateredDate"),
                wateringFrequencyDays = o.optIntOrNull("wateringFrequencyDays"),
                lastFertilisedDate = o.optLongOrNull("lastFertilisedDate"),
                fertiliseFrequencyDays = o.optIntOrNull("fertiliseFrequencyDays"),
                lastPrunedDate = o.optLongOrNull("lastPrunedDate"),
                pruneFrequencyDays = o.optIntOrNull("pruneFrequencyDays"),
                lastFedDate = o.optLongOrNull("lastFedDate"),
                feedFrequencyDays = o.optIntOrNull("feedFrequencyDays"),
                sun = o.optString("sun", ""),
                water = o.optString("water", ""),
                soil = o.optString("soil", ""),
                source = o.optString("source", ""),
                date = o.optString("date", ""),
                wateringSystem = o.optString("wateringSystem", ""),
                lat = o.optDoubleOrNull("lat"),
                lng = o.optDoubleOrNull("lng"),
                photoUri = if (o.isNull("photoUri")) null else o.optString("photoUri", null),
                photoUris = o.optJSONArray("photoUris")?.let { p -> (0 until p.length()).map { p.getString(it) } } ?: emptyList(),
                mapX = o.optDoubleOrNull("mapX"),
                mapY = o.optDoubleOrNull("mapY"),
                summerWateringFrequencyDays = o.optIntOrNull("summerWateringFrequencyDays"),
                winterWateringFrequencyDays = o.optIntOrNull("winterWateringFrequencyDays")
            )
        }
        return result
    }

    private fun plantsToJson(list: List<Plant>): JSONArray {
        val arr = JSONArray()
        list.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id); o.put("name", p.name); o.put("sci", p.sci); o.put("location", p.location)
            o.put("sun", p.sun); o.put("water", p.water); o.put("soil", p.soil); o.put("frost", p.frost)
            o.put("native", p.native); o.put("pollinator", p.pollinator); o.put("source", p.source)
            o.put("date", p.date); o.put("qty", p.qty); o.put("notes", p.notes)
            o.put("wateringSystem", p.wateringSystem)
            o.put("lat", p.lat ?: JSONObject.NULL); o.put("lng", p.lng ?: JSONObject.NULL)
            o.put("photoUri", p.photoUri ?: JSONObject.NULL)
            o.put("photoUris", JSONArray(p.photoUris))
            o.put("mapX", p.mapX ?: JSONObject.NULL); o.put("mapY", p.mapY ?: JSONObject.NULL)
            o.put("lastWateredDate", p.lastWateredDate ?: JSONObject.NULL)
            o.put("wateringFrequencyDays", p.wateringFrequencyDays ?: JSONObject.NULL)
            o.put("manualWateringOnly", p.manualWateringOnly)
            o.put("isIndoor", p.isIndoor)
            o.put("summerWateringFrequencyDays", p.summerWateringFrequencyDays ?: JSONObject.NULL)
            o.put("winterWateringFrequencyDays", p.winterWateringFrequencyDays ?: JSONObject.NULL)
            o.put("lastFertilisedDate", p.lastFertilisedDate ?: JSONObject.NULL)
            o.put("fertiliseFrequencyDays", p.fertiliseFrequencyDays ?: JSONObject.NULL)
            o.put("lastPrunedDate", p.lastPrunedDate ?: JSONObject.NULL)
            o.put("pruneFrequencyDays", p.pruneFrequencyDays ?: JSONObject.NULL)
            o.put("lastFedDate", p.lastFedDate ?: JSONObject.NULL)
            o.put("feedFrequencyDays", p.feedFrequencyDays ?: JSONObject.NULL)
            arr.put(o)
        }
        return arr
    }

    private fun parseCareLog(arr: JSONArray): MutableList<CareLogEntry> {
        val result = mutableListOf<CareLogEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            result += CareLogEntry(
                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                plantId = o.optString("plantId", ""),
                type = o.optString("type", "watering"),
                date = o.optLong("date", System.currentTimeMillis()),
                notes = o.optString("notes", "")
            )
        }
        return result
    }

    private fun careLogToJson(list: List<CareLogEntry>): JSONArray {
        val arr = JSONArray()
        list.forEach { c ->
            val o = JSONObject()
            o.put("id", c.id); o.put("plantId", c.plantId); o.put("type", c.type)
            o.put("date", c.date); o.put("notes", c.notes)
            arr.put(o)
        }
        return arr
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else optLong(key)
private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)
private fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key)

/** Default save location for this app's own data — a Sage Garden phone backup can also be opened directly via "Open backup file...". */
fun defaultGardenFile(): File =
    File(System.getProperty("user.home"), "SageGardenDesktop/garden_data.json")
