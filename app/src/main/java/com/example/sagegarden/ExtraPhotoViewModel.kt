package com.example.sagegarden

import android.app.Application
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ExtraPhotoViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).extraPhotoDao()

    val plantIdsWithPhotos: StateFlow<Set<String>> = snapshotFlow { effectiveGardenId(application) }
        .flatMapLatest { gardenId -> dao.getAllPlantIdsWithPhotos(gardenId) }
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun getForPlant(plantId: String, gardenId: String): StateFlow<List<ExtraPhotoEntity>> =
        dao.getForPlant(plantId, gardenId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addPhoto(plantId: String, uri: String, label: String = "") {
        viewModelScope.launch {
            dao.upsert(
                ExtraPhotoEntity(
                    id = "EP-${System.currentTimeMillis()}",
                    plantId = plantId, uri = uri, label = label,
                    addedAt = System.currentTimeMillis(),
                    gardenId = effectiveGardenId(getApplication())
                )
            )
        }
    }

    fun updateLabel(photo: ExtraPhotoEntity, label: String) {
        viewModelScope.launch { dao.upsert(photo.copy(label = label)) }
    }

    fun updateUri(photo: ExtraPhotoEntity, uri: String) {
        viewModelScope.launch { dao.upsert(photo.copy(uri = uri)) }
    }

    fun delete(id: String) = viewModelScope.launch { dao.deleteById(id) }
}
