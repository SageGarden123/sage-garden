package com.example.dansgardenmapper

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val BACKUP_JSON_FILENAME = "garden_mapper_backup.json"
private const val BACKUP_MAP_FILENAME_PREFIX = "garden_mapper_backup_map"

data class BackupResult(val success: Boolean, val message: String)
data class RestoreResult(val success: Boolean, val message: String)

object BackupHelper {

    suspend fun createBackup(
        context: Context,
        plants: List<PlantEntity>,
        paths: List<IrrigationPathEntity>,
        events: List<WateringEvent>
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val client = getDropboxClient(context) ?: return@withContext BackupResult(false, "Dropbox isn't connected")
            val folderPath = getDropboxPhotoFolderPath(context) ?: ""

            val root = JSONObject()
            root.put("backupVersion", 1)
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
            settings.put("usingCustomMap", isUsingCustomMap(context))
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
            root.put("settings", settings)

            val mapUri = getCustomMapUri(context)
            var mapFileName: String? = null
            if (mapUri != null) {
                val bytes = context.contentResolver.openInputStream(mapUri)?.use { it.readBytes() }
                if (bytes != null) {
                    val ext = guessImageExtension(context, mapUri)
                    mapFileName = "$BACKUP_MAP_FILENAME_PREFIX.$ext"
                    val mapPath = "$folderPath/$mapFileName".replace("//", "/")
                    client.files().uploadBuilder(mapPath).uploadAndFinish(bytes.inputStream())
                }
            }
            root.put("customMapFileName", mapFileName ?: JSONObject.NULL)

            val jsonPath = "$folderPath/$BACKUP_JSON_FILENAME".replace("//", "/")
            client.files().uploadBuilder(jsonPath).uploadAndFinish(root.toString().toByteArray().inputStream())

            BackupResult(true, "Backup complete — ${plants.size} plant(s), ${paths.size} irrigation path(s), ${events.size} watering event(s)")
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
            val folderPath = getDropboxPhotoFolderPath(context) ?: ""
            val jsonPath = "$folderPath/$BACKUP_JSON_FILENAME".replace("//", "/")

            val out = java.io.ByteArrayOutputStream()
            try {
                client.files().download(jsonPath).download(out)
            } catch (_: Exception) {
                return@withContext RestoreResult(false, "No backup found in this Dropbox folder")
            }
            val root = JSONObject(out.toString("UTF-8"))

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
                        manualWateringOnly = o.optBoolean("manualWateringOnly", false)
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

            val tuyaArr = root.optJSONArray("tuyaZoneMappings") ?: JSONArray()
            val tuyaMappings = (0 until tuyaArr.length()).map { i ->
                val o = tuyaArr.getJSONObject(i)
                TuyaZoneMapping(o.getString("zone"), o.getString("deviceId"), o.optString("outlet", "1"))
            }
            setTuyaZoneMappings(context, tuyaMappings)

            root.optJSONObject("settings")?.let { s ->
                setPhotoStorageMode(context, s.optString("photoStorageMode", "local"))
                setUsingCustomMap(context, s.optBoolean("usingCustomMap", false))
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
            }

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

            RestoreResult(true, "Restore complete — ${plantsArr.length()} plant(s), ${pathsArr.length()} irrigation path(s), ${eventsArr.length()} watering event(s)")
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