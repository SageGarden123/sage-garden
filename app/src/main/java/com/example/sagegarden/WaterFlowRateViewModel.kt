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
class WaterFlowRateViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).waterFlowRateDao()

    val flowRates: StateFlow<List<WaterFlowRateEntity>> = snapshotFlow { effectiveGardenId(application) }
        .flatMapLatest { gardenId -> dao.getAll(gardenId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(zone: String, outlet: String, litersPerMinute: Double) =
        viewModelScope.launch { dao.upsert(WaterFlowRateEntity(zone, outlet, litersPerMinute, effectiveGardenId(getApplication()))) }

    fun delete(zone: String, outlet: String) =
        viewModelScope.launch { dao.deleteByZoneOutlet(effectiveGardenId(getApplication()), zone, outlet) }
}
