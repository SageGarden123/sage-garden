package com.example.dansgardenmapper

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WaterFlowRateViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).waterFlowRateDao()

    val flowRates: StateFlow<List<WaterFlowRateEntity>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(zone: String, outlet: String, litersPerMinute: Double) =
        viewModelScope.launch { dao.upsert(WaterFlowRateEntity(zone, outlet, litersPerMinute)) }

    fun delete(zone: String, outlet: String) = viewModelScope.launch { dao.deleteByZoneOutlet(zone, outlet) }
}
