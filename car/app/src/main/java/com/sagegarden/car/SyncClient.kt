package com.sagegarden.car

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class SyncResult {
    data class Success(val plants: List<Plant>, val gardenLat: Double?, val gardenLng: Double?, val memberToken: String?) : SyncResult()
    data object NetworkError : SyncResult()
    data object ServerError : SyncResult()
    data object NotAuthorized : SyncResult()
}

/**
 * Same backend as the phone app and the desktop app (see GardenSyncClient.kt in both) — the plain
 * HTTPS syncGarden Cloud Function, no Firebase SDK needed. This viewer always sends EMPTY
 * plants/careLog/tombstone arrays: mergeCollection (gardenSync.ts) only ever ADDS/updates from
 * what's incoming and only ever DELETES via a tombstone, so an empty payload is a pure read — the
 * server's stored state for the garden comes back unchanged, nothing is pushed or lost.
 *
 * [gardenId] is the phone's Install ID (its own garden); [ownDeviceId] is this display's own id
 * (see getOwnDeviceId). The first sync joins the garden as its own member and returns a token that
 * every later sync presents — so this app never impersonates the phone.
 */
object SyncClient {
    private const val BASE_URL = "https://us-central1-gardenmapper-a68ec.cloudfunctions.net"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun fetch(ownDeviceId: String, gardenId: String, memberToken: String?): SyncResult {
        return try {
            val body = JSONObject().apply {
                put("deviceId", ownDeviceId)
                put("gardenId", gardenId)
                memberToken?.let { put("memberToken", it) }
                put("deviceName", "Car display")
                put("plants", JSONArray())
                put("plantTombstones", JSONArray())
                put("careLog", JSONArray())
                put("careLogTombstones", JSONArray())
            }
            val request = Request.Builder()
                .url("$BASE_URL/syncGarden")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code == 403) return SyncResult.NotAuthorized
                val text = response.body?.string() ?: return SyncResult.NetworkError
                if (!response.isSuccessful) return SyncResult.ServerError
                val json = JSONObject(text)
                val plantsArr = json.getJSONArray("plants")
                val plants = (0 until plantsArr.length()).map { jsonToPlant(plantsArr.getJSONObject(it)) }
                // Sent back to every caller regardless of write permission (see the phone app's
                // GardenSyncClient) — used here only as the map's default camera position.
                val gardenLat = if (json.isNull("gardenLat")) null else json.optDouble("gardenLat")
                val gardenLng = if (json.isNull("gardenLng")) null else json.optDouble("gardenLng")
                SyncResult.Success(plants, gardenLat, gardenLng, json.optString("memberToken", "").ifBlank { null })
            }
        } catch (_: Exception) {
            SyncResult.NetworkError
        }
    }
}
