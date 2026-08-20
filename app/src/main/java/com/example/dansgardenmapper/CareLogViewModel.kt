package com.example.dansgardenmapper

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class CareLogViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).careLogDao()
    private val plantDao = AppDatabase.getInstance(application).plantDao()

    fun getForPlant(plantId: String): StateFlow<List<CareLogEntity>> =
        dao.getForPlant(plantId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Logs the event AND updates the plant's lastWateredDate/lastFertilisedDate/lastPrunedDate so due-date tracking stays in sync.
     * Suspends until both writes land — callers that log multiple types for the same plant in one action (e.g. FormScreen's
     * save button) must call this sequentially via their own coroutine rather than firing several off in parallel, since each
     * call re-reads the plant to apply its single-field change and a stale read would silently drop an earlier change.
     */
    suspend fun logCareSync(plantId: String, type: String, date: Long, notes: String = "") {
        dao.upsert(CareLogEntity(id = UUID.randomUUID().toString(), plantId = plantId, type = type, date = date, notes = notes))
        val plant = plantDao.getById(plantId) ?: return
        val updated = when (type) {
            "watering" -> plant.copy(lastWateredDate = date)
            "fertilise" -> plant.copy(lastFertilisedDate = date)
            "prune" -> plant.copy(lastPrunedDate = date)
            "feed" -> plant.copy(lastFedDate = date)
            else -> plant
        }
        plantDao.upsert(updated)
        if (type == "watering") refreshWateringWidgets(getApplication())
    }

    /** Fire-and-forget wrapper for simple single-call sites (e.g. the dedicated "Log watering/fertilising/pruning" buttons). */
    fun logCare(plantId: String, type: String, date: Long, notes: String = "") {
        viewModelScope.launch { logCareSync(plantId, type, date, notes) }
    }

    fun delete(id: String) = viewModelScope.launch { dao.deleteById(id) }
}
