package com.example.sagegarden

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Common shape both TuyaZoneMapping and RachioZoneMapping reduce to, so syncIrrigationHistory has one loop instead of one per vendor. */
private data class VendorZoneMapping(val zone: String, val deviceId: String, val key: String)

private data class VendorSyncConfig(
    val mappings: List<VendorZoneMapping>,
    val missingCredentialMessage: String?,
    val fetchEvents: suspend (mapping: VendorZoneMapping, startMs: Long, endMs: Long) -> List<WateringEvent>
)

/** How often each garden's watering history is pulled automatically — comfortably inside Tuya's ~7-day log retention, so a missed or Doze-delayed run still has a day or two of slack before anything is lost. */
const val IRRIGATION_AUTO_SYNC_DAYS = 5L

private fun vendorSyncConfig(context: Context, gardenId: String, system: IrrigationSystem): VendorSyncConfig =
    if (system == IrrigationSystem.TUYA) {
        VendorSyncConfig(
            mappings = GardenSettings.of(context, gardenId).tuyaZoneMappings.map { VendorZoneMapping(it.zone, it.deviceId, it.outlet) },
            missingCredentialMessage = if (GardenSettings.of(context, gardenId).tuyaClientId.isBlank() || GardenSettings.of(context, gardenId).tuyaClientSecret.isBlank())
                "Tuya isn't connected — add your Client ID and Secret in Settings → Irrigation first." else null
        ) { mapping, start, end -> TuyaClient.fetchWateringEvents(context, mapping.deviceId, mapping.zone, mapping.key, start, end, gardenId) }
    } else {
        VendorSyncConfig(
            mappings = GardenSettings.of(context, gardenId).rachioZoneMappings.map { VendorZoneMapping(it.zone, it.deviceId, it.zoneId) },
            missingCredentialMessage = if (GardenSettings.of(context, gardenId).rachioApiToken.isBlank())
                "Rachio isn't connected — add your API token in Settings → Irrigation first." else null
        ) { mapping, start, end -> RachioClient.fetchWateringEvents(context, mapping.deviceId, mapping.key, mapping.zone, start, end, gardenId) }
    }

/** Whether [gardenId] has everything a sync needs on THIS device (vendor chosen, zones mapped, credentials entered) — irrigation setup is device-local, so a garden you're only a member of usually won't. */
fun isIrrigationSyncConfigured(context: Context, gardenId: String): Boolean {
    val system = GardenSettings.of(context, gardenId).irrigationSystem
    if (system == IrrigationSystem.NONE) return false
    val config = vendorSyncConfig(context, gardenId, system)
    return config.mappings.isNotEmpty() && config.missingCredentialMessage == null
}

/**
 * Pulls the last 30 days of watering activity for [gardenId] from Tuya/Rachio into Room and appends
 * new sessions to irrigation_log.csv, returning a human-readable summary. Shared by the Help screen's
 * "Sync watering history" button (active garden) and [IrrigationHistorySyncWorker] (every configured
 * garden) — everything here reads [gardenId]'s own settings via the *For getters, never the active
 * garden's, since the worker runs for gardens that aren't on screen.
 */
suspend fun syncIrrigationHistory(context: Context, gardenId: String): String {
    val system = GardenSettings.of(context, gardenId).irrigationSystem
    if (system == IrrigationSystem.NONE) return "Select an irrigation system in Settings → Irrigation first."

    val config = vendorSyncConfig(context, gardenId, system)
    if (config.mappings.isEmpty()) return "No zones configured yet — add ${if (system == IrrigationSystem.TUYA) "device IDs" else "zone IDs"} in Settings → Irrigation."
    config.missingCredentialMessage?.let { return it }

    val dao = AppDatabase.getInstance(context).wateringEventDao()
    val end = System.currentTimeMillis()
    val start = end - (30L * 24 * 60 * 60 * 1000)
    var successCount = 0
    val errorZones = mutableListOf<String>()
    val allNewEvents = mutableListOf<WateringEvent>()

    config.mappings.forEach { mapping ->
        try {
            val zoneEvents = config.fetchEvents(mapping, start, end).map { it.copy(gardenId = gardenId) }
            dao.insertAll(zoneEvents)
            allNewEvents.addAll(zoneEvents)
            successCount++
        } catch (e: Exception) {
            errorZones.add("${mapping.zone} (${e.message ?: "unknown error"})")
        }
    }
    // Only a fully clean pull resets the auto-sync clock — if any zone failed, the worker retries it
    // on its next daily check instead of waiting another 5 days and risking Tuya's retention window.
    if (errorZones.isEmpty()) GardenSettings.of(context, gardenId).lastIrrigationSyncAt = end

    val usingCloud = getPhotoStorageMode(context) == "cloud"
    // saveIrrigationCsvLocal/Dropbox both fail silently (return false, write nothing) when
    // there's no folder to write to — a device that's never picked a local folder or connected
    // Dropbox would otherwise report a successful sync while the CSV quietly never gets
    // created, with only a vague "backup not saved" afterthought explaining why. Reads the stored
    // token rather than DropboxAuthState, which is never populated in a cold background process.
    val storageConfigured = if (usingCloud) getDropboxAccessToken(context) != null else getIrrigationLogFolderUri(context) != null
    val csvSaved = if (allNewEvents.isEmpty()) true
    else if (usingCloud) saveIrrigationCsvDropbox(context, allNewEvents)
    else saveIrrigationCsvLocal(context, allNewEvents)

    return buildString {
        append(
            if (errorZones.isEmpty()) "Synced $successCount zone(s) — ${allNewEvents.size} new event(s) found"
            else "Synced $successCount zone(s) (${allNewEvents.size} new event(s)), failed: ${errorZones.joinToString(", ")}"
        )
        if (allNewEvents.isNotEmpty()) {
            when {
                csvSaved -> append(if (usingCloud) " — appended to irrigation_log.csv in Dropbox" else " — appended to irrigation_log.csv on device")
                !storageConfigured -> append(" — choose an irrigation log location (Settings → Irrigation) to save irrigation_log.csv")
                else -> append(" (couldn't save irrigation_log.csv — check your irrigation log folder/Dropbox connection)")
            }
        }
    }
}

/**
 * Keeps every configured garden's watering history pulled without anyone tapping "Sync watering
 * history". Runs as a DAILY check rather than a 5-day periodic job: each garden is only synced once
 * its own last clean sync (manual or automatic) is [IRRIGATION_AUTO_SYNC_DAYS] old, so a manual tap
 * resets that garden's clock, a failed pull is retried the next day, and WorkManager's periodic-work
 * drift (which can be large under Doze) never stacks up into a gap longer than Tuya retains logs for.
 * A garden without irrigation set up on this device costs nothing — it's skipped before any network call.
 */
class IrrigationHistorySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val dueAfterMillis = TimeUnit.DAYS.toMillis(IRRIGATION_AUTO_SYNC_DAYS)
        for (gardenId in allKnownGardenIds(applicationContext)) {
            if (!isIrrigationSyncConfigured(applicationContext, gardenId)) continue
            if (now - GardenSettings.of(applicationContext, gardenId).lastIrrigationSyncAt < dueAfterMillis) continue
            val summary = try {
                syncIrrigationHistory(applicationContext, gardenId)
            } catch (e: Exception) {
                "threw: ${e.message}"
            }
            Log.d("IrrigationAutoSync", "sync($gardenId): $summary")
        }
        return Result.success()
    }
}

/** Idempotent (KEEP) — safe to call on every app start; WorkManager persists the schedule across reboots on its own. */
fun scheduleIrrigationHistorySync(context: Context) {
    val request = PeriodicWorkRequestBuilder<IrrigationHistorySyncWorker>(1, TimeUnit.DAYS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("irrigation_history_auto_sync", ExistingPeriodicWorkPolicy.KEEP, request)
}
