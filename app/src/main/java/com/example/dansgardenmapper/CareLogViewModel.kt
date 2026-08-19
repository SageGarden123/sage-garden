package com.example.dansgardenmapper

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CareLogViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).careLogDao()
    private val plantDao = AppDatabase.getInstance(application).plantDao()

    fun getForPlant(plantId: String): StateFlow<List<CareLogEntity>> =
        dao.getForPlant(plantId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Logs the event AND updates the plant's lastFertilisedDate/lastPrunedDate so due-date tracking stays in sync. */
    fun logCare(plantId: String, type: String, date: Long, notes: String = "") {
        viewModelScope.launch {
            dao.upsert(CareLogEntity(id = "CL-${System.currentTimeMillis()}", plantId = plantId, type = type, date = date, notes = notes))
            val plant = plantDao.getById(plantId) ?: return@launch
            val updated = when (type) {
                "fertilise" -> plant.copy(lastFertilisedDate = date)
                "prune" -> plant.copy(lastPrunedDate = date)
                else -> plant
            }
            plantDao.upsert(updated)
        }
    }

    fun delete(id: String) = viewModelScope.launch { dao.deleteById(id) }
}