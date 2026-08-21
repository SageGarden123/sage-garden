package com.example.sagegarden

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WateringZoneViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).wateringEventDao()

    val events: StateFlow<List<WateringEvent>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _lastSyncResult = MutableStateFlow<String?>(null)
    val lastSyncResult: StateFlow<String?> = _lastSyncResult.asStateFlow()

    fun sync(context: Context) {
        viewModelScope.launch {
            _syncing.value = true
            val mappings = getTuyaZoneMappings(context)
            if (mappings.isEmpty()) {
                _lastSyncResult.value = "No zones configured yet — add device IDs in Help."
                _syncing.value = false
                return@launch
            }
            if (getTuyaClientId(context).isBlank() || getTuyaClientSecret(context).isBlank()) {
                _lastSyncResult.value = "Tuya isn't connected — add your Client ID and Secret in Help first."
                _syncing.value = false
                return@launch
            }
            val end = System.currentTimeMillis()
            val start = end - (30L * 24 * 60 * 60 * 1000)
            var successCount = 0
            val errorZones = mutableListOf<String>()
            val allNewEvents = mutableListOf<WateringEvent>()

            mappings.forEach { mapping ->
                try {
                    val zoneEvents = TuyaClient.fetchWateringEvents(
                        context, mapping.deviceId, mapping.zone, mapping.outlet, start, end
                    )
                    dao.insertAll(zoneEvents)
                    allNewEvents.addAll(zoneEvents)
                    successCount++
                } catch (e: Exception) {
                    errorZones.add("${mapping.zone} (${e.message ?: "unknown error"})")
                }
            }

            val csvSaved = if (allNewEvents.isEmpty()) true
            else if (getPhotoStorageMode(context) == "cloud") saveIrrigationCsvDropbox(context, allNewEvents)
            else saveIrrigationCsvLocal(context, allNewEvents)

            _lastSyncResult.value = buildString {
                append(
                    if (errorZones.isEmpty()) "Synced $successCount zone(s) successfully"
                    else "Synced $successCount zone(s), failed: ${errorZones.joinToString(", ")}"
                )
                if (!csvSaved) append(" (CSV backup not saved — check your photo storage folder/Dropbox connection)")
            }
            _syncing.value = false
        }
    }
    fun importEvents(events: List<WateringEvent>) {
        viewModelScope.launch { dao.insertAll(events) }
    }
}