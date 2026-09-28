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
            _lastSyncResult.value = syncIrrigationHistory(context, effectiveGardenId(context))
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