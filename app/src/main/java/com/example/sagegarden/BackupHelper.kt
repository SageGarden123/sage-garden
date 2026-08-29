package com.example.sagegarden

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.WriteMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val BACKUP_JSON_FILENAME = "garden_mapper_backup.json"
private const val BACKUP_MAP_FILENAME_PREFIX = "garden_mapper_backup_map"

data class BackupResult(val success: Boolean, val message: String)
data class RestoreResult(val success: Boolean, val message: String)

private data class BackupCounts(
    val plants: Int, val paths: Int, val events: Int, val sunZones: Int, val growthPhotos: Int,
    val extraPhotos: Int = 0, val locationPhotos: Int = 0
) {
    fun summary() = "$plants plant(s), $paths irrigation path(s), $events watering event(s), $sunZones sun zone(s), $growthPhotos growth photo(s), $extraPhotos extra photo(s), $locationPhotos progress photo(s)"
}

private data class BackupPayload(val root: JSONObject, val mapBytes: ByteArray?, val mapFileName: String?, val counts: BackupCounts)

object BackupHelper {

    private suspend fun buildBackupPayload(
        context: Context,
        plants: List<PlantEntity>,
        paths: List<IrrigationPathEntity>,
        events: List<WateringEvent>,
        mapFileNameBase: String = BACKUP_MAP_FILENAME_PREFIX
    ): BackupPayload {
        val root = JSONObject()
        root.put("backupVersion", 2)
        root.put("createdAt", System.currentTimeMillis())

        val plantsArr = JSONArray()
        plants.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id); o.put("name", p.name); o.put("sci", p.sci); o.put("location", p.location)
            o.put("sun", p.sun); o.put("water", p.water); o.put("soil", p.soil); o.put("soilPh", p.soilPh); o.put("category", p.category); o.put("frost", p.frost)
            o.put("gardenId", p.gardenId)
            o.put("native", p.native); o.put("pollinator", p.pollinator); o.put("source", p.source)
            o.put("date", p.date); o.put("qty", p.qty); o.put("notes", p.notes)
            o.put("wateringSystem", p.wateringSystem)
            o.put("lat", p.lat ?: JSONObject.NULL); o.put("lng", p.lng ?: JSONObject.NULL)
            o.put("photoUri", p.photoUri ?: JSONObject.NULL)
            o.put("photoUris", JSONArray(p.photoUris))
            o.put("photoThumbnail", p.photoThumbnailBase64 ?: JSONObject.NULL)
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
            o.put("updatedAt", p.updatedAt)
            plantsArr.put(o)
        }
        root.put("plants", plantsArr)

        val pathsArr = JSONArray()
        paths.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id); o.put("zone", p.zone)
            o.put("outletX", p.outletX); o.put("outletY", p.outletY)
            o.put("segmentsJson", p.segmentsJson); o.put("gardenId", p.gardenId)
            pathsArr.put(o)
        }
        root.put("irrigationPaths", pathsArr)

        val eventsArr = JSONArray()
        events.forEach { e ->
            val o = JSONObject()
            o.put("id", e.id); o.put("zone", e.zone); o.put("outlet", e.outlet)
            o.put("startTime", e.startTime); o.put("durationMinutes", e.durationMinutes)
            o.put("source", e.source); o.put("gardenId", e.gardenId)
            eventsArr.put(o)
        }
        root.put("wateringEvents", eventsArr)

        val db = AppDatabase.getInstance(context)

        val sunZonesArr = JSONArray()
        db.sunZoneDao().getAllOnceForGarden(effectiveGardenId(context)).forEach { z ->
            val o = JSONObject()
            o.put("id", z.id); o.put("category", z.category); o.put("pointsJson", z.pointsJson); o.put("mapType", z.mapType)
            o.put("gardenId", z.gardenId)
            sunZonesArr.put(o)
        }
        root.put("sunZones", sunZonesArr)

        // All three scoped to the active garden — these were previously read with getAllOnce()
        // (every garden on the device combined), which meant a near-empty garden's backup falsely
        // reported growth/progress photo counts that actually belonged to a different garden.
        val backupGardenId = effectiveGardenId(context)

        val growthPhotosArr = JSONArray()
        db.growthPhotoDao().getAllOnceForGarden(backupGardenId).forEach { g ->
            val o = JSONObject()
            o.put("id", g.id); o.put("plantId", g.plantId); o.put("uri", g.uri)
            o.put("takenAt", g.takenAt); o.put("label", g.label); o.put("gardenId", g.gardenId)
            growthPhotosArr.put(o)
        }
        root.put("growthPhotos", growthPhotosArr)

        val extraPhotosArr = JSONArray()
        db.extraPhotoDao().getAllOnceForGarden(backupGardenId).forEach { e ->
            val o = JSONObject()
            o.put("id", e.id); o.put("plantId", e.plantId); o.put("uri", e.uri)
            o.put("label", e.label); o.put("addedAt", e.addedAt); o.put("gardenId", e.gardenId)
            extraPhotosArr.put(o)
        }
        root.put("extraPhotos", extraPhotosArr)

        val locationPhotosArr = JSONArray()
        db.locationPhotoDao().getAllOnceForGarden(backupGardenId).forEach { l ->
            val o = JSONObject()
            o.put("id", l.id); o.put("location", l.location); o.put("uri", l.uri)
            o.put("label", l.label); o.put("takenAt", l.takenAt); o.put("gardenId", l.gardenId)
            locationPhotosArr.put(o)
        }
        root.put("locationPhotos", locationPhotosArr)

        val careLogArr = JSONArray()
        db.careLogDao().getAllOnce().forEach { c ->
            val o = JSONObject()
            o.put("id", c.id); o.put("plantId", c.plantId); o.put("type", c.type)
            o.put("date", c.date); o.put("notes", c.notes); o.put("updatedAt", c.updatedAt); o.put("gardenId", c.gardenId)
            careLogArr.put(o)
        }
        root.put("careLog", careLogArr)

        val flowRatesArr = JSONArray()
        db.waterFlowRateDao().getAllOnceForGarden(effectiveGardenId(context)).forEach { f ->
            val o = JSONObject()
            o.put("zone", f.zone); o.put("outlet", f.outlet); o.put("litersPerMinute", f.litersPerMinute)
            o.put("gardenId", f.gardenId)
            flowRatesArr.put(o)
        }
        root.put("waterFlowRates", flowRatesArr)

        val tuyaArr = JSONArray()
        getTuyaZoneMappings(context).forEach { m ->
            val o = JSONObject()
            o.put("zone", m.zone); o.put("deviceId", m.deviceId); o.put("outlet", m.outlet)
            tuyaArr.put(o)
        }
        root.put("tuyaZoneMappings", tuyaArr)

        val rachioArr = JSONArray()
        getRachioZoneMappings(context).forEach { m ->
            val o = JSONObject()
            o.put("zone", m.zone); o.put("deviceId", m.deviceId); o.put("zoneId", m.zoneId)
            rachioArr.put(o)
        }
        root.put("rachioZoneMappings", rachioArr)

        val settings = JSONObject()
        settings.put("irrigationSystem", getIrrigationSystem(context).name)
        settings.put("photoStorageMode", getPhotoStorageMode(context))
        settings.put("dropboxPhotoFolderPath", getDropboxPhotoFolderPath(context) ?: "")
        settings.put("localPhotoFolderUri", getLocalPhotoFolderUri(context)?.toString() ?: "")
        settings.put("usingCustomMap", isUsingCustomMap(context))
        settings.put("customMapRotation", getCustomMapRotation(context))
        settings.put("dashboardStatKeys", getDashboardStatKeys(context).joinToString(","))
        settings.put("dashboardChartEnabled", getDashboardChartEnabled(context))
        settings.put("dashboardChartGroupBy", getDashboardChartGroupBy(context))
        settings.put("listFieldKeys", getListFieldKeys(context).joinToString(","))
        settings.put("listGroupBy", getListGroupBy(context))
        settings.put("listSortBy", getListSortBy(context))
        settings.put("defaultLandingTab", getDefaultLandingTab(context))
        settings.put("notificationsEnabled", getNotificationsEnabled(context))
        settings.put("notificationStyle", getNotificationStyle(context))
        settings.put("notificationOffsets", getNotificationOffsets(context).joinToString(","))
        settings.put("notificationHour", getNotificationHour(context))
        settings.put("notificationMinute", getNotificationMinute(context))
        settings.put("overdueRepeatEnabled", getOverdueRepeatEnabled(context))
        settings.put("overdueRepeatDays", getOverdueRepeatDays(context))
        settings.put("fertiliseRemindersEnabled", getFertiliseRemindersEnabled(context))
        settings.put("pruneRemindersEnabled", getPruneRemindersEnabled(context))
        settings.put("feedRemindersEnabled", getFeedRemindersEnabled(context))
        settings.put("weatherSkipEnabled", getWeatherSkipEnabled(context))
        settings.put("rainProbabilityThreshold", getRainProbabilityThreshold(context))
        settings.put("rainAmountThresholdMm", getRainAmountThreshold(context).toDouble())
        settings.put("frostWarningsEnabled", getFrostWarningsEnabled(context))
        settings.put("frostTempThreshold", getFrostTempThreshold(context))
        settings.put("waterRatePerKiloliter", getWaterRatePerKiloliter(context))
        val gardenLatLng = getGardenLatLng(context)
        settings.put("gardenLat", gardenLatLng?.first ?: JSONObject.NULL)
        settings.put("gardenLng", gardenLatLng?.second ?: JSONObject.NULL)
        settings.put("gardenAddress", getGardenAddress(context))
        val gardenLocations = getGardenLocations(context)
        settings.put("gardenLocations", if (gardenLocations != null) JSONArray(gardenLocations) else JSONObject.NULL)
        settings.put("irrigationLogFolderUri", getIrrigationLogFolderUri(context)?.toString() ?: "")
        settings.put("irrigationLogDropboxFolderPath", getIrrigationLogDropboxFolderPath(context) ?: "")
        // Per-zone "last Dropbox folder browsed to" memory for progress photos — dynamically keyed
        // (one entry per zone name), so enumerated from the raw prefs rather than a fixed getter.
        val progressPhotoFolders = JSONObject()
        context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE).all
            .filterKeys { it.startsWith("progress_photo_dropbox_folder.") }
            .forEach { (k, v) -> progressPhotoFolders.put(k.removePrefix("progress_photo_dropbox_folder."), v as? String ?: "") }
        settings.put("progressPhotoDropboxFolders", progressPhotoFolders)
        root.put("settings", settings)

        val mapUri = getCustomMapUri(context)
        var mapBytes: ByteArray? = null
        var mapFileName: String? = null
        if (mapUri != null) {
            val bytes = context.contentResolver.openInputStream(mapUri)?.use { it.readBytes() }
            if (bytes != null) {
                val ext = guessImageExtension(context, mapUri)
                mapFileName = "$mapFileNameBase.$ext"
                mapBytes = bytes
            }
        }
        root.put("customMapFileName", mapFileName ?: JSONObject.NULL)

        val counts = BackupCounts(
            plantsArr.length(), pathsArr.length(), eventsArr.length(), sunZonesArr.length(), growthPhotosArr.length(),
            extraPhotosArr.length(), locationPhotosArr.length()
        )
        return BackupPayload(root, mapBytes, mapFileName, counts)
    }

    private suspend fun applyBackupRoot(
        context: Context,
        root: JSONObject,
        viewModel: PlantViewModel,
        pathViewModel: IrrigationPathViewModel,
        wateringViewModel: WateringZoneViewModel,
        forceFresh: Boolean = false
    ): BackupCounts {
        val plantsArr = root.optJSONArray("plants") ?: JSONArray()
        for (i in 0 until plantsArr.length()) {
            val o = plantsArr.getJSONObject(i)
            val photoUrisArr = o.optJSONArray("photoUris") ?: JSONArray()
            val photoUris = (0 until photoUrisArr.length()).map { photoUrisArr.getString(it) }
            // Writes directly via the DAO (not viewModel.save()) so the backup's own updatedAt
            // survives the restore by default — PlantViewModel.saveSync always stamps "now", which
            // is right for a genuine edit but wrong here: it would make a restored-from-old-backup
            // device look like it has the freshest data and incorrectly win the next multi-device
            // sync. [forceFresh] deliberately opts INTO that "freshest wins" behavior instead — for
            // when the user's actual intent is "roll back to this backup and make it the truth
            // everywhere", not a passive local restore that should defer to genuinely newer edits
            // from other devices.
            AppDatabase.getInstance(context).plantDao().upsert(
                PlantEntity(
                    id = o.getString("id"), name = o.getString("name"), sci = o.optString("sci", ""),
                    location = o.optString("location", ""), sun = o.optString("sun", ""),
                    water = o.optString("water", ""), soil = o.optString("soil", ""), soilPh = o.optString("soilPh", ""), category = o.optString("category", ""), frost = o.optString("frost", ""),
                    native = o.optString("native", "Native (Aus)"), pollinator = o.optString("pollinator", ""),
                    source = o.optString("source", ""), date = o.optString("date", ""), qty = o.optInt("qty", 1),
                    notes = o.optString("notes", ""), wateringSystem = o.optString("wateringSystem", ""),
                    lat = if (o.isNull("lat")) null else o.optDouble("lat"),
                    lng = if (o.isNull("lng")) null else o.optDouble("lng"),
                    photoUri = if (o.isNull("photoUri")) null else o.optString("photoUri"),
                    photoUris = photoUris,
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
                    updatedAt = if (forceFresh) System.currentTimeMillis() else o.optLong("updatedAt", 0L),
                    gardenId = o.optString("gardenId", "").ifBlank { effectiveGardenId(context) }
                )
            )
        }
        refreshWateringWidgets(context)

        val pathsArr = root.optJSONArray("irrigationPaths") ?: JSONArray()
        for (i in 0 until pathsArr.length()) {
            val o = pathsArr.getJSONObject(i)
            pathViewModel.save(
                IrrigationPathEntity(
                    id = o.getString("id"), zone = o.getString("zone"),
                    outletX = o.getDouble("outletX"), outletY = o.getDouble("outletY"),
                    segmentsJson = o.getString("segmentsJson"),
                    gardenId = o.optString("gardenId", "").ifBlank { effectiveGardenId(context) }
                )
            )
        }

        val eventsArr = root.optJSONArray("wateringEvents") ?: JSONArray()
        val restoredEvents = (0 until eventsArr.length()).map { i ->
            val o = eventsArr.getJSONObject(i)
            WateringEvent(
                id = o.getString("id"), zone = o.getString("zone"), outlet = o.optString("outlet", "1"),
                startTime = o.getLong("startTime"), durationMinutes = o.getInt("durationMinutes"),
                source = o.optString("source", "Tuya"),
                gardenId = o.optString("gardenId", "").ifBlank { effectiveGardenId(context) }
            )
        }
        if (restoredEvents.isNotEmpty()) wateringViewModel.importEvents(restoredEvents)

        val db = AppDatabase.getInstance(context)

        val sunZonesArr = root.optJSONArray("sunZones") ?: JSONArray()
        for (i in 0 until sunZonesArr.length()) {
            val o = sunZonesArr.getJSONObject(i)
            db.sunZoneDao().upsert(
                SunZoneEntity(
                    id = o.getString("id"), category = o.getString("category"), pointsJson = o.getString("pointsJson"),
                    mapType = o.optString("mapType", "custom"), // older backups predate real-map zones
                    gardenId = o.optString("gardenId", "").ifBlank { effectiveGardenId(context) }
                )
            )
        }

        val growthPhotosArr = root.optJSONArray("growthPhotos") ?: JSONArray()
        for (i in 0 until growthPhotosArr.length()) {
            val o = growthPhotosArr.getJSONObject(i)
            db.growthPhotoDao().upsert(
                GrowthPhotoEntity(
                    id = o.getString("id"), plantId = o.getString("plantId"), uri = o.getString("uri"),
                    takenAt = o.getLong("takenAt"), label = o.optString("label", ""),
                    gardenId = o.optString("gardenId", "").ifBlank { effectiveGardenId(context) }
                )
            )
        }

        val extraPhotosArr = root.optJSONArray("extraPhotos") ?: JSONArray()
        for (i in 0 until extraPhotosArr.length()) {
            val o = extraPhotosArr.getJSONObject(i)
            db.extraPhotoDao().upsert(
                ExtraPhotoEntity(
                    id = o.getString("id"), plantId = o.getString("plantId"), uri = o.getString("uri"),
                    label = o.optString("label", ""), addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                    gardenId = o.optString("gardenId", "").ifBlank { effectiveGardenId(context) }
                )
            )
        }

        val locationPhotosArr = root.optJSONArray("locationPhotos") ?: JSONArray()
        for (i in 0 until locationPhotosArr.length()) {
            val o = locationPhotosArr.getJSONObject(i)
            db.locationPhotoDao().upsert(
                LocationPhotoEntity(
                    id = o.getString("id"), location = o.getString("location"), uri = o.getString("uri"),
                    label = o.optString("label", ""), takenAt = o.optLong("takenAt", System.currentTimeMillis()),
                    gardenId = o.optString("gardenId", "").ifBlank { effectiveGardenId(context) }
                )
            )
        }

        val careLogArr = root.optJSONArray("careLog") ?: JSONArray()
        for (i in 0 until careLogArr.length()) {
            val o = careLogArr.getJSONObject(i)
            db.careLogDao().upsert(
                CareLogEntity(
                    id = o.getString("id"), plantId = o.getString("plantId"), type = o.getString("type"),
                    date = o.getLong("date"), notes = o.optString("notes", ""),
                    updatedAt = if (forceFresh) System.currentTimeMillis() else o.optLong("updatedAt", 0L),
                    gardenId = o.optString("gardenId", "").ifBlank { effectiveGardenId(context) }
                )
            )
        }

        val flowRatesArr = root.optJSONArray("waterFlowRates") ?: JSONArray()
        for (i in 0 until flowRatesArr.length()) {
            val o = flowRatesArr.getJSONObject(i)
            db.waterFlowRateDao().upsert(
                WaterFlowRateEntity(
                    zone = o.getString("zone"), outlet = o.optString("outlet", "1"), litersPerMinute = o.getDouble("litersPerMinute"),
                    gardenId = o.optString("gardenId", "").ifBlank { effectiveGardenId(context) }
                )
            )
        }

        val tuyaArr = root.optJSONArray("tuyaZoneMappings") ?: JSONArray()
        val tuyaMappings = (0 until tuyaArr.length()).map { i ->
            val o = tuyaArr.getJSONObject(i)
            TuyaZoneMapping(o.getString("zone"), o.getString("deviceId"), o.optString("outlet", "1"))
        }
        setTuyaZoneMappings(context, tuyaMappings)

        val rachioArr = root.optJSONArray("rachioZoneMappings") ?: JSONArray()
        val rachioMappings = (0 until rachioArr.length()).map { i ->
            val o = rachioArr.getJSONObject(i)
            RachioZoneMapping(o.getString("zone"), o.getString("deviceId"), o.getString("zoneId"))
        }
        setRachioZoneMappings(context, rachioMappings)

        root.optJSONObject("settings")?.let { s ->
            // Restored before Tuya/Rachio credentials (deliberately excluded from backup for
            // security) so the zone-mapping panel just above shows under the right vendor instead
            // of silently defaulting to "None" once those wiped credentials fail the fallback
            // heuristic in getIrrigationSystem() — which made restored zone mappings look lost.
            IrrigationSystem.entries.firstOrNull { it.name == s.optString("irrigationSystem", "") }
                ?.let { setIrrigationSystem(context, it) }
            setPhotoStorageMode(context, s.optString("photoStorageMode", "local"))
            s.optString("localPhotoFolderUri", "").takeIf { it.isNotBlank() }
                ?.let { setLocalPhotoFolderUri(context, Uri.parse(it)) }
            setUsingCustomMap(context, s.optBoolean("usingCustomMap", false))
            setCustomMapRotation(context, s.optInt("customMapRotation", 0))
            s.optString("dashboardStatKeys", "").split(",").filter { it.isNotBlank() }
                .let { if (it.isNotEmpty()) setDashboardStatKeys(context, it) }
            setDashboardChartEnabled(context, s.optBoolean("dashboardChartEnabled", true))
            setDashboardChartGroupBy(context, s.optString("dashboardChartGroupBy", "location"))
            s.optString("listFieldKeys", "").split(",").filter { it.isNotBlank() }
                .let { if (it.isNotEmpty()) setListFieldKeys(context, it) }
            setListGroupBy(context, s.optString("listGroupBy", "location"))
            setListSortBy(context, s.optString("listSortBy", "name"))
            setDefaultLandingTab(context, s.optString("defaultLandingTab", "map"))
            // Deliberately not restored: the OS permission is never granted on a fresh install/restore,
            // so restoring "enabled" would show a red permission warning the user didn't ask for.
            // Leave it off; they can flip it back on (which re-prompts for permission) if they want it.
            setNotificationStyle(context, s.optString("notificationStyle", "lockscreen"))
            s.optString("notificationOffsets", "0").split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
                .let { if (it.isNotEmpty()) setNotificationOffsets(context, it) }
            setNotificationTime(context, s.optInt("notificationHour", 8), s.optInt("notificationMinute", 0))
            setOverdueRepeatEnabled(context, s.optBoolean("overdueRepeatEnabled", true))
            setOverdueRepeatDays(context, s.optInt("overdueRepeatDays", 3))
            setFertiliseRemindersEnabled(context, s.optBoolean("fertiliseRemindersEnabled", false))
            setPruneRemindersEnabled(context, s.optBoolean("pruneRemindersEnabled", false))
            setFeedRemindersEnabled(context, s.optBoolean("feedRemindersEnabled", false))
            setWeatherSkipEnabled(context, s.optBoolean("weatherSkipEnabled", false))
            setRainProbabilityThreshold(context, s.optInt("rainProbabilityThreshold", 60))
            setRainAmountThreshold(context, s.optDouble("rainAmountThresholdMm", 1.0).toFloat())
            setFrostWarningsEnabled(context, s.optBoolean("frostWarningsEnabled", true))
            setFrostTempThreshold(context, s.optDouble("frostTempThreshold", 2.0))
            setWaterRatePerKiloliter(context, s.optDouble("waterRatePerKiloliter", getWaterRatePerKiloliter(context)))
            if (!s.isNull("gardenLat") && !s.isNull("gardenLng")) {
                setGardenLatLng(context, s.optDouble("gardenLat"), s.optDouble("gardenLng"))
            }
            setGardenAddress(context, s.optString("gardenAddress", ""))
            if (!s.isNull("gardenLocations")) {
                val arr = s.optJSONArray("gardenLocations") ?: JSONArray()
                setGardenLocations(context, (0 until arr.length()).map { arr.getString(it) })
            }
            s.optString("irrigationLogFolderUri", "").takeIf { it.isNotBlank() }
                ?.let { setIrrigationLogFolderUri(context, Uri.parse(it)) }
            s.optString("irrigationLogDropboxFolderPath", "").takeIf { it.isNotBlank() }
                ?.let { setIrrigationLogDropboxFolderPath(context, it) }
            s.optJSONObject("progressPhotoDropboxFolders")?.let { obj ->
                obj.keys().forEach { zone -> setProgressPhotoDropboxFolder(context, zone, obj.getString(zone)) }
            }
        }

        return BackupCounts(
            plantsArr.length(), pathsArr.length(), eventsArr.length(), sunZonesArr.length(), growthPhotosArr.length(),
            extraPhotosArr.length(), locationPhotosArr.length()
        )
    }

    /** Null if no backup exists yet at [jsonFileName] in the configured Dropbox path — otherwise
     * when the existing one was last modified, for a "replace existing backup?" confirmation
     * prompt before overwriting it. */
    suspend fun existingBackupModifiedAt(context: Context, jsonFileName: String = BACKUP_JSON_FILENAME): Date? = withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext null
            val folderPath = getDropboxBackupFolderPath(context) ?: getDropboxPhotoFolderPath(context) ?: ""
            val jsonPath = "$folderPath/$jsonFileName".replace("//", "/")
            (client.files().getMetadata(jsonPath) as? FileMetadata)?.serverModified
        } catch (_: Exception) {
            null // not found (or unreachable) — either way, nothing to confirm replacing
        }
    }

    /** The single rolling-snapshot filename ("Replace" always writes here, and it's the default for
     * a plain restore) — exposed so callers outside this file don't need their own copy of the
     * private constant. */
    fun defaultBackupFileName(): String = BACKUP_JSON_FILENAME

    /**
     * The rolling-snapshot filename to use for [gardenId] specifically — the device's own original
     * default garden keeps the plain, unchanged [BACKUP_JSON_FILENAME] (no migration needed for
     * existing users), but any OTHER garden gets its own name-derived filename
     * ("garden_mapper_backup_<name>.json"). Without this, two gardens sharing the same Dropbox
     * backup folder would silently overwrite each other's backup under the identical fixed
     * filename — backing up Garden B after Garden A would destroy Garden A's only backup, the same
     * device-wide-state-clobbering class of bug as the plant-id collision this feature exists
     * alongside (see feedback_plant_id_cross_garden_collision). Recomputed fresh from whatever the
     * garden is CURRENTLY named — renaming a garden starts a new backup file going forward rather
     * than trying to track renames, so an old name's backup is left behind (harmless clutter, not
     * silently lost).
     */
    fun defaultBackupFileNameForGarden(context: Context, gardenId: String): String {
        if (gardenId.isBlank() || gardenId == getOrCreateInstallId(context)) return BACKUP_JSON_FILENAME
        val name = GardenMembershipStore.getKnownGardens(context).firstOrNull { it.gardenId == gardenId }?.name ?: "garden"
        return "garden_mapper_backup_${sanitizeForDropboxFilename(name)}.json"
    }

    /** A fresh, never-yet-used dated filename for a "Create new" backup — distinguishable from (and
     * never overwrites) the default rolling snapshot or any other dated backup already taken today. */
    fun newDatedBackupFileName(): String =
        "garden_mapper_backup_${SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())}.json"

    data class DropboxBackupInfo(val fileName: String, val modifiedAt: Date)

    /** Every backup JSON file sitting in the configured Dropbox backup folder — the default rolling
     * snapshot plus any dated ones from "Create new" — newest first, for a "choose which backup to
     * restore" picker rather than always assuming the single fixed-name file is the one wanted. */
    suspend fun listAvailableBackups(context: Context): List<DropboxBackupInfo> = withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext emptyList()
            val folderPath = getDropboxBackupFolderPath(context) ?: getDropboxPhotoFolderPath(context) ?: ""
            client.files().listFolder(folderPath.ifBlank { "" }).entries
                .filterIsInstance<FileMetadata>()
                .filter { it.name.startsWith("garden_mapper_backup") && it.name.endsWith(".json") }
                .map { DropboxBackupInfo(it.name, it.serverModified) }
                .sortedByDescending { it.modifiedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * [jsonFileName] defaults to the single rolling snapshot (replaced in place each time) — pass
     * [newDatedBackupFileName] instead for a "Create new" backup that leaves every existing one
     * untouched. The custom map image (if any) gets a filename derived from [jsonFileName] too, so
     * each dated backup stays fully self-contained rather than all sharing one map file that a later
     * backup could silently replace out from under an older JSON that still references it.
     */
    suspend fun createBackup(
        context: Context,
        plants: List<PlantEntity>,
        paths: List<IrrigationPathEntity>,
        events: List<WateringEvent>,
        jsonFileName: String = BACKUP_JSON_FILENAME
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext BackupResult(false, "Dropbox isn't connected")
            val folderPath = getDropboxBackupFolderPath(context) ?: getDropboxPhotoFolderPath(context) ?: ""
            val mapFileNameBase = jsonFileName.removeSuffix(".json") + "_map"
            val payload = buildBackupPayload(context, plants, paths, events, mapFileNameBase)

            // WriteMode.OVERWRITE replaces a same-named file in place instead of the default ADD
            // mode, which throws a conflict error rather than actually overwriting — matters for the
            // rolling-snapshot filename (backed up before), a no-op safety net for a fresh dated one.
            if (payload.mapBytes != null && payload.mapFileName != null) {
                val mapPath = "$folderPath/${payload.mapFileName}".replace("//", "/")
                client.files().uploadBuilder(mapPath).withMode(WriteMode.OVERWRITE).uploadAndFinish(payload.mapBytes.inputStream())
            }

            val jsonPath = "$folderPath/$jsonFileName".replace("//", "/")
            client.files().uploadBuilder(jsonPath).withMode(WriteMode.OVERWRITE).uploadAndFinish(payload.root.toString().toByteArray().inputStream())

            BackupResult(true, "Backup complete — ${payload.counts.summary()}")
        } catch (e: Exception) {
            BackupResult(false, e.message ?: "Unknown error")
        }
    }

    /** Exports the same backup (JSON + custom map image, if any) into a user-chosen folder on the device — no Dropbox needed. */
    suspend fun createLocalBackup(
        context: Context,
        plants: List<PlantEntity>,
        paths: List<IrrigationPathEntity>,
        events: List<WateringEvent>,
        folderUri: Uri
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext BackupResult(false, "Couldn't open the chosen folder")
            val payload = buildBackupPayload(context, plants, paths, events)

            if (payload.mapBytes != null && payload.mapFileName != null) {
                val mimeType = when {
                    payload.mapFileName.endsWith(".png") -> "image/png"
                    payload.mapFileName.endsWith(".webp") -> "image/webp"
                    else -> "image/jpeg"
                }
                folder.findFile(payload.mapFileName)?.delete()
                val mapFile = folder.createFile(mimeType, payload.mapFileName) ?: return@withContext BackupResult(false, "Couldn't write the map image")
                context.contentResolver.openOutputStream(mapFile.uri)?.use { it.write(payload.mapBytes) }
            }

            folder.findFile(BACKUP_JSON_FILENAME)?.delete()
            val jsonFile = folder.createFile("application/json", BACKUP_JSON_FILENAME) ?: return@withContext BackupResult(false, "Couldn't write the backup file")
            context.contentResolver.openOutputStream(jsonFile.uri)?.use { it.write(payload.root.toString().toByteArray()) }

            BackupResult(true, "Backup complete — ${payload.counts.summary()}")
        } catch (e: Exception) {
            BackupResult(false, e.message ?: "Unknown error")
        }
    }

    /**
     * [forceFresh] controls whether the restored data should defer to genuinely newer edits from
     * other synced devices (false — the default, safe for "restore onto a device that lost local
     * data") or override them everywhere (true — for a deliberate "roll back to this backup" that
     * should win even against other devices' more recent changes; also pushes the result to the
     * server immediately afterward, rather than waiting for the next passive auto-sync tick, which
     * would otherwise use the backup's own old timestamps and could still lose a last-write-wins
     * race against a device that syncs in the meantime). See feedback_plant_id_cross_garden_collision.
     */
    suspend fun restoreBackup(
        context: Context,
        viewModel: PlantViewModel,
        pathViewModel: IrrigationPathViewModel,
        wateringViewModel: WateringZoneViewModel,
        jsonFileName: String = BACKUP_JSON_FILENAME,
        forceFresh: Boolean = false
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext RestoreResult(false, "Dropbox isn't connected")
            val folderPath = getDropboxBackupFolderPath(context) ?: getDropboxPhotoFolderPath(context) ?: ""
            val jsonPath = "$folderPath/$jsonFileName".replace("//", "/")

            val out = java.io.ByteArrayOutputStream()
            try {
                client.files().download(jsonPath).download(out)
            } catch (_: Exception) {
                return@withContext RestoreResult(false, "No backup found in this Dropbox folder")
            }
            val root = JSONObject(out.toString("UTF-8"))
            val counts = applyBackupRoot(context, root, viewModel, pathViewModel, wateringViewModel, forceFresh)

            val mapFileName = if (root.isNull("customMapFileName")) null else root.optString("customMapFileName")
            if (!mapFileName.isNullOrBlank()) {
                val mapPath = "$folderPath/$mapFileName".replace("//", "/")
                val mapOut = java.io.ByteArrayOutputStream()
                try {
                    client.files().download(mapPath).download(mapOut)
                    val localFile = File(context.filesDir, mapFileName)
                    localFile.writeBytes(mapOut.toByteArray())
                    setCustomMapUri(context, Uri.fromFile(localFile))
                } catch (_: Exception) { /* map missing or unreachable — rest of restore still succeeds */ }
            }

            if (forceFresh) {
                GardenSyncClient.sync(context, getOrCreateInstallId(context), effectiveGardenId(context))
            }

            RestoreResult(true, "Restore complete — ${counts.summary()}")
        } catch (e: Exception) {
            RestoreResult(false, e.message ?: "Unknown error")
        }
    }

    /** Restores from a backup JSON (+ custom map image, if present) previously exported to a device folder via [createLocalBackup]. See [restoreBackup] for [forceFresh]. */
    suspend fun restoreLocalBackup(
        context: Context,
        viewModel: PlantViewModel,
        pathViewModel: IrrigationPathViewModel,
        wateringViewModel: WateringZoneViewModel,
        folderUri: Uri,
        forceFresh: Boolean = false
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext RestoreResult(false, "Couldn't open the chosen folder")
            val jsonFile = folder.findFile(BACKUP_JSON_FILENAME) ?: return@withContext RestoreResult(false, "No backup found in this folder")
            val text = context.contentResolver.openInputStream(jsonFile.uri)?.use { it.bufferedReader().readText() }
                ?: return@withContext RestoreResult(false, "Couldn't read the backup file")
            val root = JSONObject(text)
            val counts = applyBackupRoot(context, root, viewModel, pathViewModel, wateringViewModel, forceFresh)

            val mapFileName = if (root.isNull("customMapFileName")) null else root.optString("customMapFileName")
            if (!mapFileName.isNullOrBlank()) {
                folder.findFile(mapFileName)?.let { mapFile ->
                    try {
                        val bytes = context.contentResolver.openInputStream(mapFile.uri)?.use { it.readBytes() }
                        if (bytes != null) {
                            val localFile = File(context.filesDir, mapFileName)
                            localFile.writeBytes(bytes)
                            setCustomMapUri(context, Uri.fromFile(localFile))
                        }
                    } catch (_: Exception) { /* map missing or unreachable — rest of restore still succeeds */ }
                }
            }

            if (forceFresh) {
                GardenSyncClient.sync(context, getOrCreateInstallId(context), effectiveGardenId(context))
            }

            RestoreResult(true, "Restore complete — ${counts.summary()}")
        } catch (e: Exception) {
            RestoreResult(false, e.message ?: "Unknown error")
        }
    }

    private fun guessImageExtension(context: Context, uri: Uri): String {
        val type = context.contentResolver.getType(uri) ?: return "jpg"
        return when {
            type.contains("png") -> "png"
            type.contains("webp") -> "webp"
            else -> "jpg"
        }
    }
}
