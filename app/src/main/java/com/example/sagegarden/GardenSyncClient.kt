package com.example.sagegarden

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class GardenSyncResult {
    data class Success(val plantCount: Int, val careLogCount: Int, val permission: String = "write") : GardenSyncResult()
    data object NetworkError : GardenSyncResult()
    data object ServerError : GardenSyncResult()
    data object NotAuthorized : GardenSyncResult()
}

/**
 * Syncs this device's plants/care-log against the shared Firestore doc for [gardenId] (defaulting
 * to [deviceId] — this device's own default garden — when not given a shared garden to sync
 * against instead) — see syncGarden.ts for the merge logic and its membership/token check. Both
 * this and the desktop app's equivalent client send their full local state every call and simply
 * overwrite local state with whatever comes back; neither client does any merging itself. Pass a
 * phone's own install ID as [deviceId] to sync "as itself", or another device's install ID (e.g.
 * entered once on desktop) as [gardenId] to join that same garden — see GardenMembershipClient for
 * the newer, explicit-invite version of joining someone else's garden.
 */
object GardenSyncClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private const val BASE_URL = BuildConfig.SAGE_API_BASE_URL

    private fun jsonBody(json: JSONObject) =
        json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun plantToJson(p: PlantEntity): JSONObject = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("sci", p.sci); put("location", p.location)
        put("sun", p.sun); put("water", p.water); put("soil", p.soil); put("soilPh", p.soilPh); put("category", p.category); put("frost", p.frost)
        put("native", p.native); put("pollinator", p.pollinator); put("source", p.source)
        put("date", p.date); put("qty", p.qty); put("notes", p.notes)
        put("wateringSystem", p.wateringSystem)
        put("lat", p.lat ?: JSONObject.NULL); put("lng", p.lng ?: JSONObject.NULL)
        put("photoUri", p.photoUri ?: JSONObject.NULL)
        put("photoUris", JSONArray(p.photoUris))
        put("photoThumbnail", p.photoThumbnailBase64 ?: JSONObject.NULL)
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

    private fun jsonToPlant(o: JSONObject): PlantEntity = PlantEntity(
        id = o.getString("id"),
        name = o.optString("name", ""),
        sci = o.optString("sci", ""),
        location = o.optString("location", ""),
        sun = o.optString("sun", ""),
        water = o.optString("water", ""),
        soil = o.optString("soil", ""),
        soilPh = o.optString("soilPh", ""),
        category = o.optString("category", ""),
        frost = o.optString("frost", ""),
        native = o.optString("native", ""),
        pollinator = o.optString("pollinator", ""),
        source = o.optString("source", ""),
        date = o.optString("date", ""),
        qty = o.optInt("qty", 1),
        notes = o.optString("notes", ""),
        wateringSystem = o.optString("wateringSystem", ""),
        lat = if (o.isNull("lat")) null else o.optDouble("lat"),
        lng = if (o.isNull("lng")) null else o.optDouble("lng"),
        photoUri = if (o.isNull("photoUri")) null else o.optString("photoUri"),
        photoUris = o.optJSONArray("photoUris")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
        photoThumbnailBase64 = if (o.isNull("photoThumbnail")) null else o.optString("photoThumbnail"),
        mapX = if (o.isNull("mapX")) null else o.optDouble("mapX"),
        mapY = if (o.isNull("mapY")) null else o.optDouble("mapY"),
        lastWateredDate = if (o.isNull("lastWateredDate")) null else o.optLong("lastWateredDate"),
        wateringFrequencyDays = if (o.isNull("wateringFrequencyDays")) null else o.optInt("wateringFrequencyDays"),
        manualWateringOnly = o.optBoolean("manualWateringOnly", false),
        isIndoor = o.optBoolean("isIndoor", false),
        summerWateringFrequencyDays = if (o.isNull("summerWateringFrequencyDays")) null else o.optInt("summerWateringFrequencyDays"),
        winterWateringFrequencyDays = if (o.isNull("winterWateringFrequencyDays")) null else o.optInt("winterWateringFrequencyDays"),
        lastFertilisedDate = if (o.isNull("lastFertilisedDate")) null else o.optLong("lastFertilisedDate"),
        fertiliseFrequencyDays = if (o.isNull("fertiliseFrequencyDays")) null else o.optInt("fertiliseFrequencyDays"),
        lastPrunedDate = if (o.isNull("lastPrunedDate")) null else o.optLong("lastPrunedDate"),
        pruneFrequencyDays = if (o.isNull("pruneFrequencyDays")) null else o.optInt("pruneFrequencyDays"),
        lastFedDate = if (o.isNull("lastFedDate")) null else o.optLong("lastFedDate"),
        feedFrequencyDays = if (o.isNull("feedFrequencyDays")) null else o.optInt("feedFrequencyDays"),
        updatedAt = o.optLong("updatedAt", 0L)
    )

    private fun careLogToJson(c: CareLogEntity): JSONObject = JSONObject().apply {
        put("id", c.id); put("plantId", c.plantId); put("type", c.type)
        put("date", c.date); put("notes", c.notes); put("updatedAt", c.updatedAt)
    }

    private fun jsonToCareLog(o: JSONObject): CareLogEntity = CareLogEntity(
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

    suspend fun sync(context: Context, deviceId: String, gardenId: String = deviceId): GardenSyncResult = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val plantDao = db.plantDao()
            val careLogDao = db.careLogDao()

            // Backfill: a plant saved before the thumbnail feature existed (or on a version that
            // predates it) has a local photoUri but no cached photoThumbnailBase64 yet — generation
            // otherwise only happens when FormScreen sees photoUri actually change (see
            // PhotoThumbnail.kt), which a plant saved long ago will never trigger again on its own.
            // Filling the gap here means it converges within one sync pass instead of requiring the
            // owner to reopen and re-save every existing plant. Persisted via the DAO directly (not
            // viewModel.save()) so this doesn't bump updatedAt — it's not a genuine edit, matching
            // the same convention BackupHelper.kt uses for a passive/derived write.
            val localPlants = plantDao.getAllOnceForGarden(gardenId).map { plant ->
                val uri = plant.photoUri
                if (plant.photoThumbnailBase64 == null && uri != null) {
                    val parsed = Uri.parse(uri)
                    if (parsed.scheme != "http" && parsed.scheme != "https") {
                        val thumbnail = generatePhotoThumbnailBase64(context, parsed)
                        if (thumbnail != null) {
                            val updated = plant.copy(photoThumbnailBase64 = thumbnail)
                            plantDao.upsert(updated)
                            updated
                        } else plant
                    } else plant
                } else plant
            }

            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("gardenId", gardenId)
                GardenMembershipStore.getMemberToken(context, gardenId)?.let { put("memberToken", it) }
                put("plants", JSONArray(localPlants.map { plantToJson(it) }))
                put("plantTombstones", tombstonesToJson(GardenSyncStore.getPlantTombstones(context, gardenId)))
                put("careLog", JSONArray(careLogDao.getAllOnceForGarden(gardenId).map { careLogToJson(it) }))
                put("careLogTombstones", tombstonesToJson(GardenSyncStore.getCareLogTombstones(context, gardenId)))
                // The garden address/coordinates/zones are basic shared context (unlike the custom map
                // image or irrigation setup, which stay device-local) — pushed here so a view-only
                // member who never set their own address for this garden still sees where it actually
                // is instead of the map's hardcoded fallback location. Only the OWNER'S device ever
                // sends these: the server only accepts them from the owner anyway (a non-owner editor's
                // own locally-cached values from an unrelated garden must never overwrite the real
                // shared ones — see syncGarden.ts), so a non-owner simply omits them and relies on
                // whatever the server echoes back. getGardenAddress/getGardenLatLng/getGardenLocations
                // resolve via effectiveGardenId(context), which callers of sync() always pass as gardenId.
                if (isOwnerOfGarden(context, gardenId)) {
                    getGardenAddress(context).takeIf { it.isNotBlank() }?.let { put("gardenAddress", it) }
                    getGardenLatLng(context)?.let { (lat, lng) -> put("gardenLat", lat); put("gardenLng", lng) }
                    getGardenLocations(context)?.let { locs -> put("gardenLocations", JSONArray(locs)) }
                }
            }
            val request = Request.Builder().url("$BASE_URL/syncGarden").post(jsonBody(body)).build()

            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: return@withContext GardenSyncResult.NetworkError
                if (response.code == 403) return@withContext GardenSyncResult.NotAuthorized
                if (!response.isSuccessful) return@withContext GardenSyncResult.ServerError
                val json = JSONObject(text)

                // The server may auto-provision membership (a brand-new garden, or a legacy pre-sharing
                // device) and hand back a freshly-issued token — persist it so the next sync already
                // has it. Existing known-garden metadata (name, role) is preserved; only the token is
                // ever missing for a freshly-provisioned membership.
                val returnedToken = json.optString("memberToken", "")
                if (returnedToken.isNotBlank()) {
                    val permission = json.optString("permission", "write")
                    val existing = GardenMembershipStore.getKnownGardens(context).firstOrNull { it.gardenId == gardenId }
                    val role = existing?.role ?: if (gardenId == deviceId) "owner" else "member"
                    val name = existing?.name ?: "My Garden"
                    GardenMembershipStore.upsertKnownGarden(context, KnownGarden(gardenId, name, role, permission, returnedToken))
                }

                val mergedPlantsArr = json.getJSONArray("plants")
                val mergedPlantIds = mutableSetOf<String>()
                for (i in 0 until mergedPlantsArr.length()) {
                    val plant = jsonToPlant(mergedPlantsArr.getJSONObject(i)).copy(gardenId = gardenId)
                    plantDao.upsert(plant)
                    mergedPlantIds += plant.id
                }
                val plantTombstones = jsonToTombstones(json.getJSONArray("plantTombstones"))
                plantTombstones.forEach { if (it.id !in mergedPlantIds) plantDao.deleteById(it.id) }
                GardenSyncStore.setPlantTombstones(context, gardenId, plantTombstones)

                val mergedCareLogArr = json.getJSONArray("careLog")
                val mergedCareLogIds = mutableSetOf<String>()
                for (i in 0 until mergedCareLogArr.length()) {
                    val entry = jsonToCareLog(mergedCareLogArr.getJSONObject(i)).copy(gardenId = gardenId)
                    careLogDao.upsert(entry)
                    mergedCareLogIds += entry.id
                }
                val careLogTombstones = jsonToTombstones(json.getJSONArray("careLogTombstones"))
                careLogTombstones.forEach { if (it.id !in mergedCareLogIds) careLogDao.deleteById(it.id) }
                GardenSyncStore.setCareLogTombstones(context, gardenId, careLogTombstones)

                json.optString("gardenAddress", "").takeIf { it.isNotBlank() }?.let { setGardenAddress(context, it) }
                if (!json.isNull("gardenLat") && !json.isNull("gardenLng")) {
                    setGardenLatLng(context, json.getDouble("gardenLat"), json.getDouble("gardenLng"))
                }
                // null (vs an empty array) means no garden member has ever explicitly set zones yet —
                // leave this device's own getOrSeedGardenLocations fallback alone in that case, rather
                // than locking in a premature empty list.
                if (!json.isNull("gardenLocations")) {
                    val arr = json.getJSONArray("gardenLocations")
                    setGardenLocations(context, (0 until arr.length()).map { arr.getString(it) })
                }

                GardenSyncStore.setLastSyncedAt(context, System.currentTimeMillis())
                refreshWateringWidgets(context)
                GardenSyncResult.Success(mergedPlantsArr.length(), mergedCareLogArr.length(), json.optString("permission", "write"))
            }
        } catch (_: Exception) {
            GardenSyncResult.NetworkError
        }
    }
}
