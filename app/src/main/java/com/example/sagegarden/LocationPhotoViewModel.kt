package com.example.sagegarden

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class LocationPhotoViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).locationPhotoDao()

    val locationsWithPhotoCounts: StateFlow<Map<String, Int>> = dao.getLocationPhotoCounts()
        .map { counts -> counts.associate { it.location to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun getForLocation(location: String): StateFlow<List<LocationPhotoEntity>> =
        dao.getForLocation(location)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addPhoto(location: String, uri: String, label: String = "", takenAtOverride: Long? = null) {
        viewModelScope.launch {
            val takenAt = takenAtOverride ?: withContext(Dispatchers.IO) { extractPhotoTakenAt(getApplication(), uri) }
            dao.upsert(
                LocationPhotoEntity(
                    id = "LP-${System.currentTimeMillis()}",
                    location = location, uri = uri,
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

    fun delete(id: String) = viewModelScope.launch { dao.deleteById(id) }
}
