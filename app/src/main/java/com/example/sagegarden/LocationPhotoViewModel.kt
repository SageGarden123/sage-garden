package com.example.sagegarden

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.snapshotFlow
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class LocationPhotoViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).locationPhotoDao()

    val locationsWithPhotoCounts: StateFlow<Map<String, Int>> = snapshotFlow { effectiveGardenId(application) }
        .flatMapLatest { gardenId -> dao.getLocationPhotoCounts(gardenId) }
        .map { counts -> counts.associate { it.location to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun getForLocation(location: String, gardenId: String): StateFlow<List<LocationPhotoEntity>> =
        dao.getForLocation(location, gardenId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addPhoto(location: String, uri: String, label: String = "", takenAtOverride: Long? = null) {
        viewModelScope.launch {
            val takenAt = takenAtOverride ?: withContext(Dispatchers.IO) { extractPhotoTakenAt(getApplication(), uri) }
            dao.upsert(
                LocationPhotoEntity(
                    id = "LP-${System.currentTimeMillis()}",
                    location = location, uri = uri,
                    takenAt = takenAt, label = label,
                    gardenId = effectiveGardenId(getApplication())
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

    fun updateUri(photo: LocationPhotoEntity, uri: String) {
        viewModelScope.launch { dao.upsert(photo.copy(uri = uri)) }
    }

    fun delete(id: String) = viewModelScope.launch { dao.deleteById(id) }
}
