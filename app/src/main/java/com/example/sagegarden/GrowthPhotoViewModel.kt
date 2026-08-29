package com.example.sagegarden

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class GrowthPhotoViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).growthPhotoDao()

    val plantIdsWithPhotos: StateFlow<Set<String>> = dao.getAllPlantIdsWithPhotos()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun getForPlant(plantId: String): StateFlow<List<GrowthPhotoEntity>> =
        dao.getForPlant(plantId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addPhoto(plantId: String, uri: String, label: String = "", takenAtOverride: Long? = null) {
        viewModelScope.launch {
            val takenAt = takenAtOverride ?: withContext(Dispatchers.IO) { extractPhotoTakenAt(getApplication(), uri) }
            dao.upsert(
                GrowthPhotoEntity(
                    id = "GP-${System.currentTimeMillis()}",
                    plantId = plantId, uri = uri,
                    takenAt = takenAt, label = label
                )
            )
        }
    }

    private fun extractPhotoTakenAt(context: Context, uriString: String): Long {
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "content" || uri.scheme == "file") {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    dateStr?.let { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time }
                }
            } else null
        } catch (_: Exception) {
            null
        } ?: System.currentTimeMillis()
    }

    fun updateUri(photo: GrowthPhotoEntity, uri: String) {
        viewModelScope.launch { dao.upsert(photo.copy(uri = uri)) }
    }

    fun delete(id: String) = viewModelScope.launch { dao.deleteById(id) }
}