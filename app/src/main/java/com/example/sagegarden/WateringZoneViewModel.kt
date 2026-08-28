package com.example.sagegarden

import android.app.Application
import android.content.Context
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Common shape both TuyaZoneMapping and RachioZoneMapping reduce to, so sync() has one loop instead of one per vendor. */
private data class VendorZoneMapping(val zone: String, val deviceId: String, val key: String)

private data class VendorSyncConfig(
    val mappings: List<VendorZoneMapping>,
    val missingCredentialMessage: String?,
    val fetchEvents: suspend (mapping: VendorZoneMapping, startMs: Long, endMs: Long) -> List<WateringEvent>
)

@OptIn(ExperimentalCoroutinesApi::class)
class WateringZoneViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).wateringEventDao()

    val events: StateFlow<List<WateringEvent>> = snapshotFlow { effectiveGardenId(application) }
        .flatMapLatest { gardenId -> dao.getAll(gardenId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _lastSyncResult = MutableStateFlow<String?>(null)
    val lastSyncResult: StateFlow<String?> = _lastSyncResult.asStateFlow()

    fun sync(context: Context) {
        viewModelScope.launch {
            _syncing.value = true
            val system = getIrrigationSystem(context)
            if (system == IrrigationSystem.NONE) {
                _lastSyncResult.value = "Select an irrigation system in Help first."
                _syncing.value = false
                return@launch
            }

            val config = if (system == IrrigationSystem.TUYA) {
                VendorSyncConfig(
                    mappings = getTuyaZoneMappings(context).map { VendorZoneMapping(it.zone, it.deviceId, it.outlet) },
                    missingCredentialMessage = if (getTuyaClientId(context).isBlank() || getTuyaClientSecret(context).isBlank())
                        "Tuya isn't connected — add your Client ID and Secret in Help first." else null
                ) { mapping, start, end -> TuyaClient.fetchWateringEvents(context, mapping.deviceId, mapping.zone, mapping.key, start, end) }
            } else {
                VendorSyncConfig(
                    mappings = getRachioZoneMappings(context).map { VendorZoneMapping(it.zone, it.deviceId, it.zoneId) },
                    missingCredentialMessage = if (getRachioApiToken(context).isBlank())
                        "Rachio isn't connected — add your API token in Help first." else null
                ) { mapping, start, end -> RachioClient.fetchWateringEvents(context, mapping.deviceId, mapping.key, mapping.zone, start, end) }
            }

            if (config.mappings.isEmpty()) {
                _lastSyncResult.value = "No zones configured yet — add ${if (system == IrrigationSystem.TUYA) "device IDs" else "zone IDs"} in Help."
                _syncing.value = false
                return@launch
            }
            if (config.missingCredentialMessage != null) {
                _lastSyncResult.value = config.missingCredentialMessage
                _syncing.value = false
                return@launch
            }

            val end = System.currentTimeMillis()
            val start = end - (30L * 24 * 60 * 60 * 1000)
            var successCount = 0
            val errorZones = mutableListOf<String>()
            val allNewEvents = mutableListOf<WateringEvent>()

            val gardenId = effectiveGardenId(context)
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

            val usingCloud = getPhotoStorageMode(context) == "cloud"
            // saveIrrigationCsvLocal/Dropbox both fail silently (return false, write nothing) when
            // there's no folder to write to — a device that's never picked a local folder or connected
            // Dropbox would otherwise report a successful sync while the CSV quietly never gets
            // created, with only a vague "backup not saved" afterthought explaining why.
            val storageConfigured = if (usingCloud) DropboxAuthState.token != null else getIrrigationLogFolderUri(context) != null
            val csvSaved = if (allNewEvents.isEmpty()) true
            else if (usingCloud) saveIrrigationCsvDropbox(context, allNewEvents)
            else saveIrrigationCsvLocal(context, allNewEvents)

            _lastSyncResult.value = buildString {
                append(
                    if (errorZones.isEmpty()) "Synced $successCount zone(s) — ${allNewEvents.size} new event(s) found"
                    else "Synced $successCount zone(s) (${allNewEvents.size} new event(s)), failed: ${errorZones.joinToString(", ")}"
                )
                if (allNewEvents.isNotEmpty()) {
                    when {
                        csvSaved -> append(if (usingCloud) " — appended to irrigation_log.csv in Dropbox" else " — appended to irrigation_log.csv on device")
                        !storageConfigured -> append(" — choose an irrigation log location (Help → Irrigation) to save irrigation_log.csv")
                        else -> append(" (couldn't save irrigation_log.csv — check your irrigation log folder/Dropbox connection)")
                    }
                }
            }
            _syncing.value = false
        }
    }
    fun importEvents(events: List<WateringEvent>) {
        viewModelScope.launch {
            val stamped = events.map { if (it.gardenId.isBlank()) it.copy(gardenId = effectiveGardenId(getApplication())) else it }
            dao.insertAll(stamped)
        }
    }
}