package com.example.sagegarden

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.dropbox.core.v2.files.WriteMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val BACKUP_JSON_FILENAME = "garden_mapper_backup.json"
private const val BACKUP_MAP_FILENAME_PREFIX = "garden_mapper_backup_map"

data class BackupResult(val success: Boolean, val message: String)
data class RestoreResult(val success: Boolean, val message: String)

private data class BackupCounts(val plants: Int, val paths: Int, val events: Int, val sunZones: Int, val growthPhotos: Int) {
    fun summary() = "$plants plant(s), $paths irrigation path(s), $events watering event(s), $sunZones sun zone(s), $growthPhotos growth photo(s)"
}

private data class BackupPayload(val root: JSONObject, val mapBytes: ByteArray?, val mapFileName: String?, val counts: BackupCounts)

object BackupHelper {

    private suspend fun buildBackupPayload(
        context: Context,
        plants: List<PlantEntity>,
        paths: List<IrrigationPathEntity>,
        events: List<WateringEvent>
    ): BackupPayload {
        val root = JSONObject()
        root.put("backupVersion", 2)
        root.put("createdAt", System.currentTimeMillis())

        val plantsArr = JSONArray()
        plants.forEach { p ->
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
            plantsArr.put(o)
        }
        root.put("plants", plantsArr)

        val pathsArr = JSONArray()
        paths.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id); o.put("zone", p.zone)
            o.put("outletX", p.outletX); o.put("outletY", p.outletY)
            o.put("segmentsJson", p.segmentsJson)
            pathsArr.put(o)
        }
        root.put("irrigationPaths", pathsArr)

        val eventsArr = JSONArray()
        events.forEach { e ->
            val o = JSONObject()
            o.put("id", e.id); o.put("zone", e.zone); o.put("outlet", e.outlet)
            o.put("startTime", e.startTime); o.put("durationMinutes", e.durationMinutes)
            o.put("source", e.source)
            eventsArr.put(o)
        }
        root.put("wateringEvents", eventsArr)

        val db = AppDatabase.getInstance(context)

        val sunZonesArr = JSONArray()
        db.sunZoneDao().getAllOnce().forEach { z ->
            val o = JSONObject()
            o.put("id", z.id); o.put("category", z.category); o.put("pointsJson", z.pointsJson); o.put("mapType", z.mapType)
            sunZonesArr.put(o)
        }
        root.put("sunZones", sunZonesArr)

        val growthPhotosArr = JSONArray()
        db.growthPhotoDao().getAllOnce().forEach { g ->
            val o = JSONObject()
            o.put("id", g.id); o.put("plantId", g.plantId); o.put("uri", g.uri)
            o.put("takenAt", g.takenAt); o.put("label", g.label)
            growthPhotosArr.put(o)
        }
        root.put("growthPhotos", growthPhotosArr)

        val careLogArr = JSONArray()
        db.careLogDao().getAllOnce().forEach { c ->
            val o = JSONObject()
            o.put("id", c.id); o.put("plantId", c.plantId); o.put("type", c.type)
            o.put("date", c.date); o.put("notes", c.notes)
            careLogArr.put(o)
        }
        root.put("careLog", careLogArr)

        val flowRatesArr = JSONArray()
        db.waterFlowRateDao().getAllOnce().forEach { f ->
            val o = JSONObject()
            o.put("zone", f.zone); o.put("outlet", f.outlet); o.put("litersPerMinute", f.litersPerMinute)
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

        val settings = JSONObject()
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
        root.put("settings", settings)

        val mapUri = getCustomMapUri(context)
        var mapBytes: ByteArray? = null
        var mapFileName: String? = null
        if (mapUri != null) {
            val bytes = context.contentResolver.openInputStream(mapUri)?.use { it.readBytes() }
            if (bytes != null) {
                val ext = guessImageExtension(context, mapUri)
                mapFileName = "$BACKUP_MAP_FILENAME_PREFIX.$ext"
                mapBytes = bytes
            }
        }
        root.put("customMapFileName", mapFileName ?: JSONObject.NULL)

        val counts = BackupCounts(plantsArr.length(), pathsArr.length(), eventsArr.length(), sunZonesArr.length(), growthPhotosArr.length())
        return BackupPayload(root, mapBytes, mapFileName, counts)
    }

    private suspend fun applyBackupRoot(
        context: Context,
        root: JSONObject,
        viewModel: PlantViewModel,
        pathViewModel: IrrigationPathViewModel,
        wateringViewModel: WateringZoneViewModel
    ): BackupCounts {
        val plantsArr = root.optJSONArray("plants") ?: JSONArray()
        for (i in 0 until plantsArr.length()) {
            val o = plantsArr.getJSONObject(i)
            val photoUrisArr = o.optJSONArray("photoUris") ?: JSONArray()
            val photoUris = (0 until photoUrisArr.length()).map { photoUrisArr.getString(it) }
            viewModel.save(
                PlantEntity(
                    id = o.getString("id"), name = o.getString("name"), sci = o.optString("sci", ""),
                    location = o.optString("location", ""), sun = o.optString("sun", ""),
                    water = o.optString("water", ""), soil = o.optString("soil", ""), frost = o.optString("frost", ""),
                    native = o.optString("native", "Native (Aus)"), pollinator = o.optString("pollinator", ""),
                    source = o.optString("source", ""), date = o.optString("date", ""), qty = o.optInt("qty", 1),
                    notes = o.optString("notes", ""), wateringSystem = o.optString("wateringSystem", ""),
                    lat = if (o.isNull("lat")) null else o.optDouble("lat"),
                    lng = if (o.isNull("lng")) null else o.optDouble("lng"),
                    photoUri = if (o.isNull("photoUri")) null else o.optString("photoUri"),
                    photoUris = photoUris,
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
                    feedFrequencyDays = if (o.isNull("feedFrequencyDays")) null else o.optInt("feedFrequencyDays")
                )
            )
        }

        val pathsArr = root.optJSONArray("irrigationPaths") ?: JSONArray()
        for (i in 0 until pathsArr.length()) {
            val o = pathsArr.getJSONObject(i)
            pathViewModel.save(
                IrrigationPathEntity(
                    id = o.getString("id"), zone = o.getString("zone"),
                    outletX = o.getDouble("outletX"), outletY = o.getDouble("outletY"),
                    segmentsJson = o.getString("segmentsJson")
                )
            )
        }

        val eventsArr = root.optJSONArray("wateringEvents") ?: JSONArray()
        val restoredEvents = (0 until eventsArr.length()).map { i ->
            val o = eventsArr.getJSONObject(i)
            WateringEvent(
                id = o.getString("id"), zone = o.getString("zone"), outlet = o.optString("outlet", "1"),
                startTime = o.getLong("startTime"), durationMinutes = o.getInt("durationMinutes"),
                source = o.optString("source", "Tuya")
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
                    mapType = o.optString("mapType", "custom") // older backups predate real-map zones
                )
            )
        }

        val growthPhotosArr = root.optJSONArray("growthPhotos") ?: JSONArray()
        for (i in 0 until growthPhotosArr.length()) {
            val o = growthPhotosArr.getJSONObject(i)
            db.growthPhotoDao().upsert(
                GrowthPhotoEntity(
                    id = o.getString("id"), plantId = o.getString("plantId"), uri = o.getString("uri"),
                    takenAt = o.getLong("takenAt"), label = o.optString("label", "")
                )
            )
        }

        val careLogArr = root.optJSONArray("careLog") ?: JSONArray()
        for (i in 0 until careLogArr.length()) {
            val o = careLogArr.getJSONObject(i)
            db.careLogDao().upsert(
                CareLogEntity(
                    id = o.getString("id"), plantId = o.getString("plantId"), type = o.getString("type"),
                    date = o.getLong("date"), notes = o.optString("notes", "")
                )
            )
        }

        val flowRatesArr = root.optJSONArray("waterFlowRates") ?: JSONArray()
        for (i in 0 until flowRatesArr.length()) {
            val o = flowRatesArr.getJSONObject(i)
            db.waterFlowRateDao().upsert(
                WaterFlowRateEntity(zone = o.getString("zone"), outlet = o.optString("outlet", "1"), litersPerMinute = o.getDouble("litersPerMinute"))
            )
        }

        val tuyaArr = root.optJSONArray("tuyaZoneMappings") ?: JSONArray()
        val tuyaMappings = (0 until tuyaArr.length()).map { i ->
            val o = tuyaArr.getJSONObject(i)
            TuyaZoneMapping(o.getString("zone"), o.getString("deviceId"), o.optString("outlet", "1"))
        }
        setTuyaZoneMappings(context, tuyaMappings)

        root.optJSONObject("settings")?.let { s ->
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
            setNotificationsEnabled(context, s.optBoolean("notificationsEnabled", false))
            setNotificationStyle(context, s.optString("notificationStyle", "lockscreen"))
            s.optString("notificationOffsets", "0").split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
                .let { if (it.isNotEmpty()) setNotificationOffsets(context, it) }
            setNotificationTime(context, s.optInt("notificationHour", 8), s.optInt("notificationMinute", 0))
            setOverdueRepeatEnabled(context, s.optBoolean("overdueRepeatEnabled", true))
            setOverdueRepeatDays(context, s.optInt("overdueRepeatDays", 3))
            setFertiliseRemindersEnabled(context, s.optBoolean("fertiliseRemindersEnabled", false))
            setPruneRemindersEnabled(context, s.optBoolean("pruneRemindersEnabled", false))
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
        }

        return BackupCounts(plantsArr.length(), pathsArr.length(), eventsArr.length(), sunZonesArr.length(), growthPhotosArr.length())
    }

    suspend fun createBackup(
        context: Context,
        plants: List<PlantEntity>,
        paths: List<IrrigationPathEntity>,
        events: List<WateringEvent>
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext BackupResult(false, "Dropbox isn't connected")
            val folderPath = getDropboxBackupFolderPath(context) ?: getDropboxPhotoFolderPath(context) ?: ""
            val payload = buildBackupPayload(context, plants, paths, events)

            // This backup is meant to be a single rolling snapshot, not an accumulating history —
            // WriteMode.OVERWRITE replaces the existing file in place instead of the default ADD
            // mode, which throws a conflict error on every backup after the first.
            if (payload.mapBytes != null && payload.mapFileName != null) {
                val mapPath = "$folderPath/${payload.mapFileName}".replace("//", "/")
                client.files().uploadBuilder(mapPath).withMode(WriteMode.OVERWRITE).uploadAndFinish(payload.mapBytes.inputStream())
            }

            val jsonPath = "$folderPath/$BACKUP_JSON_FILENAME".replace("//", "/")
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

    suspend fun restoreBackup(
        context: Context,
        viewModel: PlantViewModel,
        pathViewModel: IrrigationPathViewModel,
        wateringViewModel: WateringZoneViewModel
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext RestoreResult(false, "Dropbox isn't connected")
            val folderPath = getDropboxBackupFolderPath(context) ?: getDropboxPhotoFolderPath(context) ?: ""
            val jsonPath = "$folderPath/$BACKUP_JSON_FILENAME".replace("//", "/")

            val out = java.io.ByteArrayOutputStream()
            try {
                client.files().download(jsonPath).download(out)
            } catch (_: Exception) {
                return@withContext RestoreResult(false, "No backup found in this Dropbox folder")
            }
            val root = JSONObject(out.toString("UTF-8"))
            val counts = applyBackupRoot(context, root, viewModel, pathViewModel, wateringViewModel)

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

            RestoreResult(true, "Restore complete — ${counts.summary()}")
        } catch (e: Exception) {
            RestoreResult(false, e.message ?: "Unknown error")
        }
    }

    /** Restores from a backup JSON (+ custom map image, if present) previously exported to a device folder via [createLocalBackup]. */
    suspend fun restoreLocalBackup(
        context: Context,
        viewModel: PlantViewModel,
        pathViewModel: IrrigationPathViewModel,
        wateringViewModel: WateringZoneViewModel,
        folderUri: Uri
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext RestoreResult(false, "Couldn't open the chosen folder")
            val jsonFile = folder.findFile(BACKUP_JSON_FILENAME) ?: return@withContext RestoreResult(false, "No backup found in this folder")
            val text = context.contentResolver.openInputStream(jsonFile.uri)?.use { it.bufferedReader().readText() }
                ?: return@withContext RestoreResult(false, "Couldn't read the backup file")
            val root = JSONObject(text)
            val counts = applyBackupRoot(context, root, viewModel, pathViewModel, wateringViewModel)

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
