package com.example.sagegarden

import android.app.Application
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SunZoneViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).sunZoneDao()

    val zones: StateFlow<List<SunZoneEntity>> = snapshotFlow { effectiveGardenId(application) }
        .flatMapLatest { gardenId -> dao.getAll(gardenId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(zone: SunZoneEntity) = viewModelScope.launch {
        val stamped = if (zone.gardenId.isBlank()) zone.copy(gardenId = effectiveGardenId(getApplication())) else zone
        dao.upsert(stamped)
    }
    fun delete(id: String) = viewModelScope.launch { dao.deleteById(id) }
}
