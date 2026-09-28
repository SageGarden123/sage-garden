@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import android.util.Log
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// ============================================================================
// AI PLANT IDENTIFICATION (Pl@ntNet API)
// ============================================================================

const val PLANTNET_API_KEY = BuildConfig.PLANTNET_API_KEY

// Free PlantNet accounts are capped at 500 requests/day, shared across every install of the app —
// without a per-device limit, a handful of trial users could exhaust that budget for everyone.
// Only real Pro-equivalent access (promo code or an explicit override) gets the full daily budget;
// the trial itself (and a lapsed trial with no promo/override) is capped much lower.
const val PLANTNET_TRIAL_DAILY_LIMIT = 5
const val PLANTNET_PRO_DAILY_LIMIT = 500

internal fun plantIdPrefs(context: Context) = context.getSharedPreferences("garden_mapper_plant_id_prefs", Context.MODE_PRIVATE)

internal fun todayKey(): String {
    val cal = java.util.Calendar.getInstance()
    return "%04d-%02d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
}

fun plantIdDailyLimit(context: Context): Int =
    when (EntitlementManager.getCached(context).source) {
        EntitlementSource.PROMO_CODE, EntitlementSource.OVERRIDE -> PLANTNET_PRO_DAILY_LIMIT
        else -> PLANTNET_TRIAL_DAILY_LIMIT
    }

fun plantIdCallsUsedToday(context: Context): Int {
    val prefs = plantIdPrefs(context)
    return if (prefs.getString("id_day", null) == todayKey()) prefs.getInt("id_count", 0) else 0
}

internal fun recordPlantIdCall(context: Context) {
    val prefs = plantIdPrefs(context)
    val today = todayKey()
    val current = if (prefs.getString("id_day", null) == today) prefs.getInt("id_count", 0) else 0
    prefs.edit().putString("id_day", today).putInt("id_count", current + 1).apply()
}

sealed class PlantIdResult {
    data class Success(val commonName: String, val scientificName: String) : PlantIdResult()
    data object Failed : PlantIdResult()
    data class DailyLimitReached(val limit: Int, val isProLimit: Boolean) : PlantIdResult()
}

suspend fun identifyPlantFromUri(context: Context, uri: Uri): PlantIdResult {
    val limit = plantIdDailyLimit(context)
    if (plantIdCallsUsedToday(context) >= limit) {
        return PlantIdResult.DailyLimitReached(limit, isProLimit = limit == PLANTNET_PRO_DAILY_LIMIT)
    }
    return withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()

            // Dropbox-chosen photos are stored as a remote https:// link, not a local content
            // URI — the ContentResolver can't read those, so fetch the bytes over the network instead.
            val bytes = if (uri.scheme == "http" || uri.scheme == "https") {
                client.newCall(Request.Builder().url(uri.toString()).build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext PlantIdResult.Failed
                    response.body?.bytes()
                } ?: return@withContext PlantIdResult.Failed
            } else {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext PlantIdResult.Failed
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "images", "photo.jpg",
                    bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .addFormDataPart("organs", "auto")
                .build()

            val request = Request.Builder()
                .url("https://my-api.plantnet.org/v2/identify/all?api-key=$PLANTNET_API_KEY")
                .post(requestBody)
                .build()

            recordPlantIdCall(context) // counts against the daily budget regardless of outcome — it's still a billed PlantNet call

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext PlantIdResult.Failed
                val body = response.body?.string() ?: return@withContext PlantIdResult.Failed
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: return@withContext PlantIdResult.Failed
                if (results.length() == 0) return@withContext PlantIdResult.Failed

                val top = results.getJSONObject(0)
                val species = top.optJSONObject("species")
                val sciName = species?.optString("scientificNameWithoutAuthor") ?: ""
                val commonNames = species?.optJSONArray("commonNames")
                val commonName = if (commonNames != null && commonNames.length() > 0) {
                    commonNames.getString(0)
                } else sciName

                PlantIdResult.Success(commonName, sciName)
            }
        } catch (e: Exception) {
            // Was silently swallowed — a report that AI ID fails specifically for a just-taken
            // camera photo (but works for the identical photo re-picked from the gallery) has no
            // way to be diagnosed further without knowing whether this is a read failure on the
            // MediaStore uri (SecurityException/FileNotFoundException — the interesting case) versus
            // a network/parsing failure, which would point somewhere else entirely.
            Log.w("PlantId", "identifyPlantFromUri failed for $uri", e)
            PlantIdResult.Failed
        }
    }
}
