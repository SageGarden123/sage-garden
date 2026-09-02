package com.example.sagegarden

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ManualZoneScheduleViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).manualZoneScheduleDao()

    fun getForZone(zone: String, gardenId: String): StateFlow<List<ManualZoneScheduleEntity>> =
        dao.getForZone(zone, gardenId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(zone: String, gardenId: String, daysOfWeek: String, durationMinutes: Int) {
        viewModelScope.launch {
            dao.upsert(
                ManualZoneScheduleEntity(
                    id = "MS-${System.currentTimeMillis()}",
                    zone = zone, gardenId = gardenId,
                    daysOfWeek = daysOfWeek, durationMinutes = durationMinutes,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun delete(id: String) = viewModelScope.launch { dao.deleteById(id) }
}
