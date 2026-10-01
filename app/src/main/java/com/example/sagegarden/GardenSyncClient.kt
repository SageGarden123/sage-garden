package com.example.sagegarden

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
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

    // One sync in flight per garden at a time. With several independent triggers (widget refresh,
    // widget-config save, the daily notification worker, the foreground auto-sync loop) all able to
    // call sync() for the same gardenId, two calls can genuinely overlap — e.g. tapping "Save" again
    // while an earlier, slower save for the same garden is still finishing its network round trip.
    // Each call reads local Room state at its OWN start and reflects a merge from whenever ITS
    // response happened to arrive; if an older, slower call's response lands and commits AFTER a
    // newer, faster call already committed correct data, its stale merge (and stale tombstone list)
    // can delete rows the newer call just correctly wrote — confirmed by a report where the sync
    // summary reported the right plant count for a garden every time, yet the widget's actual due
    // list sometimes excluded that garden entirely. Serializing per gardenId means a second call for
    // the same garden always starts AFTER the first has fully committed, so it only ever sees (and
    // can only ever produce) the latest state — no response can ever race another for the same garden.
    private val gardenMutexes = ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(gardenId: String): Mutex = gardenMutexes.getOrPut(gardenId) { Mutex() }

    // Local plants/care-log fingerprint (see PlantDao.syncFingerprint) as of each garden's last
    // successful sync, captured inside the merge transaction. MainActivity pushes local edits by
    // watching the live fingerprint and syncing only when it differs from this — which is how a
    // sync's own merge writes (which change the fingerprint too) avoid triggering another sync.
    private val lastSyncedFingerprints = ConcurrentHashMap<String, String>()
    fun lastSyncedFingerprint(gardenId: String): String? = lastSyncedFingerprints[gardenId]

    /** Everything that syncs for [gardenId] — plants, care log and all three photo tables — so an edit to any of them triggers a sync. */
    fun localFingerprint(db: AppDatabase, gardenId: String): Flow<String> = combine(
        db.plantDao().syncFingerprint(gardenId), db.careLogDao().syncFingerprint(gardenId),
        db.extraPhotoDao().syncFingerprint(gardenId), db.locationPhotoDao().syncFingerprint(gardenId), db.growthPhotoDao().syncFingerprint(gardenId)
    ) { parts -> parts.joinToString("|") }

    suspend fun localFingerprintOnce(db: AppDatabase, gardenId: String): String = listOf(
        db.plantDao().syncFingerprintOnce(gardenId), db.careLogDao().syncFingerprintOnce(gardenId),
        db.extraPhotoDao().syncFingerprintOnce(gardenId), db.locationPhotoDao().syncFingerprintOnce(gardenId), db.growthPhotoDao().syncFingerprintOnce(gardenId)
    ).joinToString("|")

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

    // ---- Photos -------------------------------------------------------------------------------
    // Extra, progress (zone) and growth-timeline photos sync as ONE "photos" collection, told apart
    // by "kind". Only http(s) — i.e. Dropbox-uploaded — photos are ever sent: a content:// URI points
    // at this phone's own storage and is useless to any other device. A phone-local photo starts
    // syncing once it's uploaded (updateUri bumps its updatedAt).

    private fun isShareableUri(uri: String) = uri.startsWith("http://") || uri.startsWith("https://")

    private fun extraPhotoToJson(p: ExtraPhotoEntity) = JSONObject().apply {
        put("id", p.id); put("kind", "extra"); put("plantId", p.plantId); put("uri", p.uri)
        put("label", p.label); put("takenAt", p.addedAt); put("updatedAt", p.updatedAt)
    }
    private fun progressPhotoToJson(p: LocationPhotoEntity) = JSONObject().apply {
        put("id", p.id); put("kind", "progress"); put("location", p.location); put("uri", p.uri)
        put("label", p.label); put("takenAt", p.takenAt); put("updatedAt", p.updatedAt)
    }
    private fun growthPhotoToJson(p: GrowthPhotoEntity) = JSONObject().apply {
        put("id", p.id); put("kind", "growth"); put("plantId", p.plantId); put("uri", p.uri)
        put("label", p.label); put("takenAt", p.takenAt); put("updatedAt", p.updatedAt)
    }

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

    /** [notifyWidgets] is false only for syncAllKnownGardens' per-garden calls below — it coalesces
     * everyone's individual widget refresh into one, fired after ALL gardens have finished, instead of
     * each garden's completion independently repainting the widget from a still-partially-synced
     * state (visible as due items from another garden briefly appearing then disappearing again as
     * each subsequent garden's sync landed in whatever order they happened to finish). */
    suspend fun sync(context: Context, deviceId: String, gardenId: String = deviceId, notifyWidgets: Boolean = true): GardenSyncResult = withContext(Dispatchers.IO) {
        mutexFor(gardenId).withLock {
        try {
            val db = AppDatabase.getInstance(context)
            val plantDao = db.plantDao()
            val careLogDao = db.careLogDao()

            // Backfill: a plant saved before the thumbnail feature existed (or on a version that
            // predates it) has a local photoUri but no cached photoThumbnailBase64 yet — generation
            // otherwise only happens when FormScreen sees photoUri actually change (see
            // PhotoThumbnail.kt), which a plant saved long ago will never trigger again on its own.
            // Filling the gap here means it converges within one sync pass instead of requiring the
            // owner to reopen and re-save every existing plant.
            //
            // Must bump updatedAt: the server's merge is strict last-write-wins on updatedAt
            // (mergeCollection in gardenSync.ts only accepts incoming when strictly newer than
            // stored). An earlier version of this backfill deliberately left updatedAt unchanged —
            // matching BackupHelper.kt's convention for a passive/derived write — but that was wrong
            // here: with an unchanged (tied) timestamp the backfilled thumbnail could never win the
            // merge against the already-synced thumbnail-less record, so it silently never reached
            // Firestore no matter how many times the owner re-synced. This IS a genuine local change
            // (new thumbnail data that didn't exist before), so it needs a fresh timestamp to
            // actually propagate.
            val localPlants = plantDao.getAllOnceForGarden(gardenId).map { plant ->
                val uri = plant.photoUri
                if (plant.photoThumbnailBase64 == null && uri != null) {
                    val parsed = Uri.parse(uri)
                    if (parsed.scheme != "http" && parsed.scheme != "https") {
                        val thumbnail = generatePhotoThumbnailBase64(context, parsed)
                        if (thumbnail != null) {
                            val updated = plant.copy(photoThumbnailBase64 = thumbnail, updatedAt = System.currentTimeMillis())
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
                put("photos", JSONArray().apply {
                    db.extraPhotoDao().getAllOnceForGarden(gardenId).filter { isShareableUri(it.uri) }.forEach { put(extraPhotoToJson(it)) }
                    db.locationPhotoDao().getAllOnceForGarden(gardenId).filter { isShareableUri(it.uri) }.forEach { put(progressPhotoToJson(it)) }
                    db.growthPhotoDao().getAllOnceForGarden(gardenId).filter { isShareableUri(it.uri) }.forEach { put(growthPhotoToJson(it)) }
                })
                put("photoTombstones", tombstonesToJson(GardenSyncStore.getPhotoTombstones(context, gardenId)))
                // The garden address/coordinates/zones are basic shared context (unlike the custom map
                // image or irrigation setup, which stay device-local) — pushed here so a view-only
                // member who never set their own address for this garden still sees where it actually
                // is instead of the map's hardcoded fallback location. Only the OWNER'S device ever
                // sends these: the server only accepts them from the owner anyway (a non-owner editor's
                // own locally-cached values from an unrelated garden must never overwrite the real
                // shared ones — see syncGarden.ts), so a non-owner simply omits them and relies on
                // whatever the server echoes back. Must read [gardenId]'s OWN values via the *For
                // getters, never the active-garden ones: sync() is routinely called for a garden that
                // isn't the active one (syncAllKnownGardens, widget/notification deep links), and
                // reading the active garden's address here pushed a shared garden's address up as the
                // owner's own garden's address whenever the shared garden happened to be on screen.
                if (isOwnerOfGarden(context, gardenId)) {
                    GardenSettings.of(context, gardenId).address.takeIf { it.isNotBlank() }?.let { put("gardenAddress", it) }
                    GardenSettings.of(context, gardenId).latLng?.let { (lat, lng) -> put("gardenLat", lat); put("gardenLng", lng) }
                    GardenSettings.of(context, gardenId).locations?.let { locs -> put("gardenLocations", JSONArray(locs)) }
                }
            }
            // Lets the server grant this device realtime change signals for the garden — see RealtimeGardenSync.
            val idToken = RealtimeGardenSync.idTokenOrNull()
            val request = Request.Builder().url("$BASE_URL/syncGarden").post(jsonBody(body))
                .apply { if (idToken != null) header("X-Firebase-Id-Token", idToken) }
                .build()

            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: run {
                    Log.w("GardenSyncClient", "sync($gardenId) failed: empty response body")
                    return@withContext GardenSyncResult.NetworkError
                }
                if (response.code == 403) {
                    Log.w("GardenSyncClient", "sync($gardenId) failed: 403 not authorized")
                    return@withContext GardenSyncResult.NotAuthorized
                }
                if (!response.isSuccessful) {
                    Log.w("GardenSyncClient", "sync($gardenId) failed: HTTP ${response.code}")
                    return@withContext GardenSyncResult.ServerError
                }
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
                val mergedCareLogArr = json.getJSONArray("careLog")
                val plantTombstones = jsonToTombstones(json.getJSONArray("plantTombstones"))
                val careLogTombstones = jsonToTombstones(json.getJSONArray("careLogTombstones"))
                val mergedPhotosArr = json.optJSONArray("photos")
                val photoTombstones = json.optJSONArray("photoTombstones")?.let { jsonToTombstones(it) }
                // One transaction for the whole merge, not one commit per row — Room's live Flow
                // (e.g. the Dashboard's plant count) re-queries and emits on every individual
                // upsert/delete, so a large garden's merge was visibly observable mid-flight as a
                // transient undercount (only the rows written so far) that "jumped" to the real
                // total once the loop finished. Wrapping it means the Flow only ever sees the
                // fully-merged before/after states, never a partial one.
                //
                // Never let the merge overwrite a row edited locally while the request was in flight:
                // the payload was snapshotted before the network round-trip, so the server's copy
                // can't include that edit. Blindly upserting it silently reverted e.g. a Home-tab
                // "Done" tap (lastWateredDate snapped back and the plant reappeared as due), which only
                // stuck on a second tap made outside a sync. Such a row is kept, and the fingerprint is
                // left unrecorded so the next sync pushes it.
                var keptNewerLocal = false
                db.withTransaction {
                    val mergedPlantIds = mutableSetOf<String>()
                    for (i in 0 until mergedPlantsArr.length()) {
                        val plant = jsonToPlant(mergedPlantsArr.getJSONObject(i)).copy(gardenId = gardenId)
                        mergedPlantIds += plant.id
                        val local = plantDao.getByIdForGarden(gardenId, plant.id)
                        if (local != null && local.updatedAt > plant.updatedAt) { keptNewerLocal = true; continue }
                        plantDao.upsert(plant)
                    }
                    plantTombstones.forEach { if (it.id !in mergedPlantIds) plantDao.deleteById(gardenId, it.id) }

                    val mergedCareLogIds = mutableSetOf<String>()
                    for (i in 0 until mergedCareLogArr.length()) {
                        val entry = jsonToCareLog(mergedCareLogArr.getJSONObject(i)).copy(gardenId = gardenId)
                        mergedCareLogIds += entry.id
                        val local = careLogDao.getById(entry.id)
                        if (local != null && local.updatedAt > entry.updatedAt) { keptNewerLocal = true; continue }
                        careLogDao.upsert(entry)
                    }
                    careLogTombstones.forEach { if (it.id !in mergedCareLogIds) careLogDao.deleteById(it.id) }

                    // Absent from a server that predates photo sync — leave local photos (and their
                    // tombstones) entirely alone rather than treating that as "everything deleted".
                    if (mergedPhotosArr != null && photoTombstones != null) {
                        val extraDao = db.extraPhotoDao(); val progressDao = db.locationPhotoDao(); val growthDao = db.growthPhotoDao()
                        val mergedPhotoIds = mutableSetOf<String>()
                        for (i in 0 until mergedPhotosArr.length()) {
                            val o = mergedPhotosArr.getJSONObject(i)
                            val id = o.getString("id"); val updatedAt = o.optLong("updatedAt", 0L)
                            val takenAt = o.optLong("takenAt", updatedAt); val uri = o.optString("uri", ""); val label = o.optString("label", "")
                            mergedPhotoIds += id
                            when (o.optString("kind")) {
                                "extra" -> {
                                    val local = extraDao.getById(id)
                                    if (local != null && local.updatedAt > updatedAt) { keptNewerLocal = true; continue }
                                    extraDao.upsert(ExtraPhotoEntity(id, o.optString("plantId"), uri, label, takenAt, gardenId, updatedAt))
                                }
                                "progress" -> {
                                    val local = progressDao.getById(id)
                                    if (local != null && local.updatedAt > updatedAt) { keptNewerLocal = true; continue }
                                    progressDao.upsert(LocationPhotoEntity(id, o.optString("location"), uri, label, takenAt, gardenId, updatedAt))
                                }
                                "growth" -> {
                                    val local = growthDao.getById(id)
                                    if (local != null && local.updatedAt > updatedAt) { keptNewerLocal = true; continue }
                                    growthDao.upsert(GrowthPhotoEntity(id, o.optString("plantId"), uri, takenAt, label, gardenId, updatedAt))
                                }
                            }
                        }
                        photoTombstones.forEach {
                            if (it.id !in mergedPhotoIds) { extraDao.deleteById(it.id); progressDao.deleteById(it.id); growthDao.deleteById(it.id) }
                        }
                    }

                    if (keptNewerLocal) lastSyncedFingerprints.remove(gardenId)
                    else lastSyncedFingerprints[gardenId] = localFingerprintOnce(db, gardenId)
                }
                if (photoTombstones != null) GardenSyncStore.setPhotoTombstones(context, gardenId, photoTombstones)
                GardenSyncStore.setPlantTombstones(context, gardenId, plantTombstones)
                GardenSyncStore.setCareLogTombstones(context, gardenId, careLogTombstones)

                // Written to [gardenId]'s own keys, NOT the active garden's — see GardenSettings' doc comment.
                json.optString("gardenAddress", "").takeIf { it.isNotBlank() }?.let { GardenSettings.of(context, gardenId).address = it }
                if (!json.isNull("gardenLat") && !json.isNull("gardenLng")) {
                    GardenSettings.of(context, gardenId).setLatLng(json.getDouble("gardenLat"), json.getDouble("gardenLng"))
                }
                // null (vs an empty array) means no garden member has ever explicitly set zones yet —
                // leave this device's own GardenSettings.getOrSeedLocations fallback alone in that case, rather
                // than locking in a premature empty list.
                if (!json.isNull("gardenLocations")) {
                    val arr = json.getJSONArray("gardenLocations")
                    GardenSettings.of(context, gardenId).locations = (0 until arr.length()).map { arr.getString(it) }
                }

                // Absent from older server versions — only trust the grant once the server confirms it understands signals.
                val serverSupportsSignals = json.has("signalRev")
                if (serverSupportsSignals) GardenSyncStore.setSignalRev(context, gardenId, json.getLong("signalRev"))
                RealtimeGardenSync.onSynced(context, gardenId, granted = idToken != null && serverSupportsSignals)

                GardenSyncStore.setLastSyncedAt(context, System.currentTimeMillis())
                if (notifyWidgets) refreshWateringWidgets(context)
                Log.d("GardenSyncClient", "sync($gardenId) succeeded: ${mergedPlantsArr.length()} plants, ${mergedCareLogArr.length()} care log entries")
                GardenSyncResult.Success(mergedPlantsArr.length(), mergedCareLogArr.length(), json.optString("permission", "write"))
            }
        } catch (e: Exception) {
            Log.w("GardenSyncClient", "sync($gardenId) threw", e)
            GardenSyncResult.NetworkError
        }
        }
    }

    /**
     * Syncs every garden this device has access to, not just whichever one is active in the UI.
     * MainActivity's own auto-sync loop only keeps the ACTIVE garden's local Room data fresh (see
     * its comment) — a garden you're a member of but haven't had open recently otherwise never gets
     * its plants pulled down at all, so background work that checks every known garden (the
     * watering-reminder worker, the home-screen widget's scheduled/manual refresh) would silently
     * see stale or entirely empty local data for it. Failures are per-garden and swallowed (sync()
     * itself never throws) so one flaky network call or revoked membership doesn't stop the rest
     * from refreshing.
     *
     * Refreshes the known-gardens list itself first (none of this method's background callers ever
     * did — only the foreground UI does, on its own 60s loop or when the sharing/widget-config screens
     * open), so a garden joined/created since the app was last opened isn't silently skipped here.
     * Then syncs every garden CONCURRENTLY rather than one at a time — a background job (WorkManager)
     * only gets a limited window of guaranteed network access, and two-plus sequential HTTP round
     * trips (one per garden) risk the later ones getting cut off before they complete; sync() logs
     * its own per-garden outcome, so a logcat capture around a "some gardens didn't refresh" report
     * shows exactly which garden failed and why (network/auth/server error) instead of guessing.
     *
     * Each individual sync() call suppresses its own widget refresh (notifyWidgets = false) — with
     * several gardens finishing concurrently in unpredictable order, letting each one repaint the
     * widget independently meant the widget would show whatever partial state Room happened to be in
     * after JUST that one garden's sync landed, then repaint again as the next garden finished — a
     * garden's due plants visibly appearing and then disappearing again before the final, fully-synced
     * state settled. One refresh, fired here after every garden has finished, replaces all of those.
     */
    suspend fun syncAllKnownGardens(context: Context) = coroutineScope {
        GardenMembershipClient.refreshKnownGardens(context)
        val deviceId = getOrCreateInstallId(context)
        val gardenIds = allKnownGardenIds(context)
        gardenIds.map { gardenId -> async { sync(context, deviceId, gardenId, notifyWidgets = false) } }.awaitAll()
        refreshWateringWidgets(context)
    }
}
