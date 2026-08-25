import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

sealed class GardenSyncResult {
    data class Success(
        val plants: List<Plant>,
        val plantTombstones: List<SyncTombstone>,
        val careLog: List<CareLogEntry>,
        val careLogTombstones: List<SyncTombstone>
    ) : GardenSyncResult()
    data object NetworkError : GardenSyncResult()
    data object ServerError : GardenSyncResult()
}

/**
 * Same Cloud Functions backend as the Android app (see SageClient.kt there) — this is the plain
 * HTTPS `syncGarden` endpoint, no Firebase SDK needed on this side either. Sends this device's
 * full plant/care-log state plus its tombstones, and replaces local state entirely with whatever
 * comes back — all merge logic lives server-side (see gardenSync.ts), this client does no merging.
 */
object GardenSyncClient {
    private const val BASE_URL = "https://us-central1-gardenmapper-a68ec.cloudfunctions.net"

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    private fun plantToJson(p: Plant): JSONObject = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("sci", p.sci); put("location", p.location)
        put("sun", p.sun); put("water", p.water); put("soil", p.soil); put("frost", p.frost)
        put("native", p.native); put("pollinator", p.pollinator); put("source", p.source)
        put("date", p.date); put("qty", p.qty); put("notes", p.notes)
        put("wateringSystem", p.wateringSystem)
        put("lat", p.lat ?: JSONObject.NULL); put("lng", p.lng ?: JSONObject.NULL)
        put("photoUri", p.photoUri ?: JSONObject.NULL)
        put("photoUris", JSONArray(p.photoUris))
        put("mapX", p.mapX ?: JSONObject.NULL); put("mapY", p.mapY ?: JSONObject.NULL)
        put("lastWateredDate", p.lastWateredDate ?: JSONObject.NULL)
        put("wateringFrequencyDays", p.wateringFrequencyDays ?: JSONObject.NULL)
        put("manualWateringOnly", p.manualWateringOnly)
        put("isIndoor", p.isIndoor)
        put("summerWateringFrequencyDays", p.summerWateringFrequencyDays ?: JSONObject.NULL)
        put("winterWateringFrequencyDays", p.winterWateringFrequencyDays ?: JSONObject.NULL)
        put("lastFertilisedDate", p.lastFertilisedDate ?: JSONObject.NULL)
        put("fertiliseFrequencyDays", p.fertiliseFrequencyDays ?: JSONObject.NULL)
        put("lastPrunedDate", p.lastPrunedDate ?: JSONObject.NULL)
        put("pruneFrequencyDays", p.pruneFrequencyDays ?: JSONObject.NULL)
        put("lastFedDate", p.lastFedDate ?: JSONObject.NULL)
        put("feedFrequencyDays", p.feedFrequencyDays ?: JSONObject.NULL)
        put("updatedAt", p.updatedAt)
    }

    private fun jsonToPlant(o: JSONObject): Plant = Plant(
        id = o.getString("id"),
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
        lastWateredDate = if (o.isNull("lastWateredDate")) null else o.optLong("lastWateredDate"),
        wateringFrequencyDays = if (o.isNull("wateringFrequencyDays")) null else o.optInt("wateringFrequencyDays"),
        lastFertilisedDate = if (o.isNull("lastFertilisedDate")) null else o.optLong("lastFertilisedDate"),
        fertiliseFrequencyDays = if (o.isNull("fertiliseFrequencyDays")) null else o.optInt("fertiliseFrequencyDays"),
        lastPrunedDate = if (o.isNull("lastPrunedDate")) null else o.optLong("lastPrunedDate"),
        pruneFrequencyDays = if (o.isNull("pruneFrequencyDays")) null else o.optInt("pruneFrequencyDays"),
        lastFedDate = if (o.isNull("lastFedDate")) null else o.optLong("lastFedDate"),
        feedFrequencyDays = if (o.isNull("feedFrequencyDays")) null else o.optInt("feedFrequencyDays"),
        sun = o.optString("sun", ""),
        water = o.optString("water", ""),
        soil = o.optString("soil", ""),
        source = o.optString("source", ""),
        date = o.optString("date", ""),
        wateringSystem = o.optString("wateringSystem", ""),
        lat = if (o.isNull("lat")) null else o.optDouble("lat"),
        lng = if (o.isNull("lng")) null else o.optDouble("lng"),
        photoUri = if (o.isNull("photoUri")) null else o.optString("photoUri"),
        photoUris = o.optJSONArray("photoUris")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
        mapX = if (o.isNull("mapX")) null else o.optDouble("mapX"),
        mapY = if (o.isNull("mapY")) null else o.optDouble("mapY"),
        summerWateringFrequencyDays = if (o.isNull("summerWateringFrequencyDays")) null else o.optInt("summerWateringFrequencyDays"),
        winterWateringFrequencyDays = if (o.isNull("winterWateringFrequencyDays")) null else o.optInt("winterWateringFrequencyDays"),
        updatedAt = o.optLong("updatedAt", 0L)
    )

    private fun careLogToJson(c: CareLogEntry): JSONObject = JSONObject().apply {
        put("id", c.id); put("plantId", c.plantId); put("type", c.type)
        put("date", c.date); put("notes", c.notes); put("updatedAt", c.updatedAt)
    }

    private fun jsonToCareLog(o: JSONObject): CareLogEntry = CareLogEntry(
        id = o.getString("id"),
        plantId = o.optString("plantId", ""),
        type = o.optString("type", "watering"),
        date = o.optLong("date", System.currentTimeMillis()),
        notes = o.optString("notes", ""),
        updatedAt = o.optLong("updatedAt", 0L)
    )

    private fun tombstonesToJson(tombstones: List<SyncTombstone>): JSONArray {
        val arr = JSONArray()
        tombstones.forEach { arr.put(JSONObject().put("id", it.id).put("deletedAt", it.deletedAt)) }
        return arr
    }

    private fun jsonToTombstones(arr: JSONArray): List<SyncTombstone> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SyncTombstone(o.getString("id"), o.getLong("deletedAt"))
        }

    /** Blocking call — run off the UI thread (see App.kt's use of a coroutine scope / background thread). */
    fun sync(
        deviceId: String,
        plants: List<Plant>,
        careLog: List<CareLogEntry>,
        plantTombstones: List<SyncTombstone>,
        careLogTombstones: List<SyncTombstone>
    ): GardenSyncResult {
        return try {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("plants", JSONArray(plants.map { plantToJson(it) }))
                put("plantTombstones", tombstonesToJson(plantTombstones))
                put("careLog", JSONArray(careLog.map { careLogToJson(it) }))
                put("careLogTombstones", tombstonesToJson(careLogTombstones))
            }
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$BASE_URL/syncGarden"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return GardenSyncResult.ServerError

            val json = JSONObject(response.body())
            val mergedPlantsArr = json.getJSONArray("plants")
            val mergedPlants = (0 until mergedPlantsArr.length()).map { jsonToPlant(mergedPlantsArr.getJSONObject(it)) }
            val mergedPlantTombstones = jsonToTombstones(json.getJSONArray("plantTombstones"))

            val mergedCareLogArr = json.getJSONArray("careLog")
            val mergedCareLog = (0 until mergedCareLogArr.length()).map { jsonToCareLog(mergedCareLogArr.getJSONObject(it)) }
            val mergedCareLogTombstones = jsonToTombstones(json.getJSONArray("careLogTombstones"))

            GardenSyncResult.Success(mergedPlants, mergedPlantTombstones, mergedCareLog, mergedCareLogTombstones)
        } catch (_: Exception) {
            GardenSyncResult.NetworkError
        }
    }
}
