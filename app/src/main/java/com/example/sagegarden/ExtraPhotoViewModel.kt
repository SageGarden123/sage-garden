package com.example.sagegarden

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExtraPhotoViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).extraPhotoDao()

    fun getForPlant(plantId: String): StateFlow<List<ExtraPhotoEntity>> =
        dao.getForPlant(plantId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addPhoto(plantId: String, uri: String, label: String = "") {
        viewModelScope.launch {
            dao.upsert(
                ExtraPhotoEntity(
                    id = "EP-${System.currentTimeMillis()}",
                    plantId = plantId, uri = uri, label = label,
                    addedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun updateLabel(photo: ExtraPhotoEntity, label: String) {
        viewModelScope.launch { dao.upsert(photo.copy(label = label)) }
    }

    fun delete(id: String) = viewModelScope.launch { dao.deleteById(id) }
}
