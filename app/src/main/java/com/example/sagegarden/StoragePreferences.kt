@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.google.maps.android.compose.*

// ============================================================================
// SIMPLE PREFERENCES (photo storage mode: "local" or "cloud")
// ============================================================================

fun getPhotoStorageMode(context: Context): String {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getString("photo_storage_mode", "local") ?: "local"
}

fun setPhotoStorageMode(context: Context, mode: String) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("photo_storage_mode", mode).apply()
}

