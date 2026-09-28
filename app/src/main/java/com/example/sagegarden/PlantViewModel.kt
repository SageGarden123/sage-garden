@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.maps.android.compose.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ============================================================================
// DATA LAYER (Room database)
// ============================================================================

// Room entities moved to separate files

@OptIn(ExperimentalCoroutinesApi::class)
class PlantViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).plantDao()

    // Re-queries whenever the active garden changes (see ActiveGardenState/effectiveGardenId in
    // GardenMembershipClient.kt) rather than once at construction, so switching gardens updates
    // every screen reading this without needing to recreate the ViewModel graph.
    val plants: StateFlow<List<PlantEntity>> = snapshotFlow { effectiveGardenId(application) }
        .flatMapLatest { gardenId -> dao.getAll(gardenId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _filters = MutableStateFlow(DashboardFilters())
    val filters: StateFlow<DashboardFilters> = _filters.asStateFlow()

    val filteredPlants: StateFlow<List<PlantEntity>> = combine(plants, _filters) { list, f ->
        list.filter { p ->
            (f.location == "All" || p.location == f.location) &&
                    (f.source == "All" || p.source == f.source) &&
                    (f.plant == "All" || p.name == f.plant) &&
                    (f.sun == "All" || p.sun == f.sun) &&
                    (f.soil == "All" || p.soil == f.soil) &&
                    (f.soilPh == "All" || p.soilPh == f.soilPh) &&
                    (f.category == "All" || p.category == f.category) &&
                    (f.water == "All" || p.water == f.water) &&
                    (f.frost == "All" || p.frost == f.frost)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setFilters(newFilters: DashboardFilters) { _filters.value = newFilters }

    /**
     * Every plant on this device across EVERY garden — used only to pick a freshly auto-generated
     * plant id (see generateNextPlantId) that's guaranteed not to collide with a plant in a
     * DIFFERENT garden. [plants] above is scoped to just the active garden, which used to be what
     * new-plant id generation checked against — a brand new second garden's first plant then always
     * got "P0001" again, colliding with (and silently overwriting, via upsert's REPLACE conflict
     * strategy) the original garden's own "P0001" plant, since `id` alone is this table's primary
     * key and both gardens' plants share one local table.
     */
    suspend fun getAllPlantsOnDevice(): List<PlantEntity> = dao.getAllOnce()

    /** Fires a background push for [gardenId] when it isn't the currently active garden — MainActivity's
     * periodic/on-resume auto-sync loop only ever pushes whichever garden is ACTIVE in the UI, so a
     * plant/care-log edit made against a different garden (e.g. opened via a widget/notification deep
     * link, which never switches the active garden) would otherwise sit correctly-scoped in local Room
     * forever without ever reaching the server. Not awaited by the caller — a normal save against the
     * already-active garden (the overwhelmingly common case, already covered by that loop) shouldn't
     * pick up an extra network round trip on every "Save plant" tap. */
    private fun syncIfNotActiveGarden(context: Context, gardenId: String) {
        if (gardenId != effectiveGardenId(context)) {
            viewModelScope.launch { GardenSyncClient.sync(context, getOrCreateInstallId(context), gardenId) }
        }
    }

    /** Suspends until the write (and widget refresh) complete — for callers that need to sequence further work after the save actually lands. */
    suspend fun saveSync(plant: PlantEntity) {
        // A brand-new plant (built fresh by FormScreen, never carrying a garden id forward) is
        // stamped with whichever garden is currently active; an edit of an existing plant already
        // carries its real gardenId through FormScreen's own state and is left untouched.
        val stamped = if (plant.gardenId.isBlank()) plant.copy(gardenId = effectiveGardenId(getApplication())) else plant
        dao.upsert(stamped.copy(updatedAt = System.currentTimeMillis()))
        refreshWateringWidgets(getApplication())
        syncIfNotActiveGarden(getApplication(), stamped.gardenId)
    }
    fun save(plant: PlantEntity) = viewModelScope.launch { saveSync(plant) }
    /** [gardenId] must be the plant's own real garden — the caller (FormScreen) already resolved this
     * via resolvePlantById when it loaded the plant, so re-deriving it here via another bare-id lookup
     * would just reopen the same cross-garden ambiguity (see resolvePlantById's doc comment) for a
     * DELETE specifically — the one operation where getting the wrong garden's same-id plant would be
     * genuinely destructive, not just a wrong-screen display. */
    fun delete(gardenId: String, id: String) = viewModelScope.launch {
        GardenSyncStore.recordPlantDeleted(getApplication(), gardenId, id)
        dao.deleteById(gardenId, id)
        syncIfNotActiveGarden(getApplication(), gardenId)
    }
    fun resetAll() = viewModelScope.launch {
        val gardenId = effectiveGardenId(getApplication())
        dao.getAllOnceForGarden(gardenId).forEach { GardenSyncStore.recordPlantDeleted(getApplication(), gardenId, it.id) }
        dao.deleteForGarden(gardenId)
    }

    fun runDropboxAutoLink(context: Context, folderPath: String) {
        if (DropboxLinkState.linking) return
        DropboxLinkState.start()
        viewModelScope.launch {
            val currentPlants = plants.value
            val result = autoLinkDropboxPhotos(
                context, folderPath, currentPlants,
                onProgress = { c, t -> DropboxLinkState.updateProgress(c, t) }
            ) { save(it) }
            val message = if (result.errorMessage == null) {
                "${result.linkedCount} of ${result.matchedCount} plants linked to photos"
            } else {
                "Linking unsuccessful due to ${result.errorMessage}"
            }
            setLastDropboxLinkResult(context, message)
            DropboxLinkState.finish(message)
        }
    }

    suspend fun getById(id: String): PlantEntity? = resolvePlantById(getApplication(), id)
}

/**
 * Resolves a plant by its bare id — every route/deep-link that opens a plant (`form_edit/{id}`,
 * `growth/{id}`, `care/{id}`, a widget row tap, a notification tap) only ever carries the plant's
 * bare id, never its gardenId. That was harmless while `id` alone was the table's primary key, but
 * now that (gardenId, id) is (see PlantEntity's doc comment / feedback_plant_id_cross_garden_collision),
 * two different gardens synced onto the same device can legitimately share an id — a bare
 * `PlantDao.getById(id)` is then ambiguous and, confirmed in practice 2026-09-08, resolved to the
 * WRONG garden's plant (viewing a shared garden's plant showed the device's own same-numbered plant
 * instead). Fixed by preferring the CURRENTLY ACTIVE garden's own copy first — correct for ordinary
 * in-app navigation, since Map/List/Irrigation/Dashboard only ever show the active garden's own
 * plants, so a tapped id always means THAT garden's copy — falling back to the ambiguous cross-garden
 * lookup only when the active garden has no matching plant at all, which is the one legitimate case
 * for that: a widget/notification deep link opening a plant in a garden that isn't currently active.
 */
suspend fun resolvePlantById(context: Context, id: String): PlantEntity? {
    val dao = AppDatabase.getInstance(context).plantDao()
    return dao.getByIdForGarden(effectiveGardenId(context), id) ?: dao.getById(id)
}
