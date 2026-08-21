package com.example.sagegarden

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SunZoneViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).sunZoneDao()

    val zones: StateFlow<List<SunZoneEntity>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(zone: SunZoneEntity) = viewModelScope.launch { dao.upsert(zone) }
    fun delete(id: String) = viewModelScope.launch { dao.deleteById(id) }
}