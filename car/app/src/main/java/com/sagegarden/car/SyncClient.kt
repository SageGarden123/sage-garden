package com.sagegarden.car

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class SyncResult {
    data class Success(val plants: List<Plant>, val gardenLat: Double?, val gardenLng: Double?, val memberToken: String?, val photos: List<GardenPhoto>? = null) : SyncResult()
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

    data class Garden(val gardenId: String, val name: String, val memberToken: String)
    data class Gardens(val gardens: List<Garden>, val pending: List<String>)

    /** Gardens this display belongs to (and names of gardens it's waiting to be approved into). */
    fun listGardens(ownDeviceId: String): Gardens? = try {
        val request = Request.Builder().url("$BASE_URL/listMyGardens?deviceId=${java.net.URLEncoder.encode(ownDeviceId, "UTF-8")}").get().build()
        httpClient.newCall(request).execute().use { r ->
            val text = r.body?.string()
            if (!r.isSuccessful || text == null) null else {
                val json = JSONObject(text)
                val g = json.getJSONArray("gardens")
                val p = json.optJSONArray("pendingRequests") ?: JSONArray()
                Gardens(
                    (0 until g.length()).map { g.getJSONObject(it) }.map { Garden(it.getString("gardenId"), it.optString("name", "Garden"), it.optString("memberToken")) },
                    (0 until p.length()).map { p.getJSONObject(it).optString("name", "Garden") }
                )
            }
        }
    } catch (_: Exception) { null }

    /** Asks to join the garden behind [inviteCode] with view-only access. Returns null on success, or an error message. */
    fun requestJoin(ownDeviceId: String, inviteCode: String): String? = try {
        val body = JSONObject().put("deviceId", ownDeviceId).put("inviteCode", inviteCode.trim().uppercase())
            .put("requestedPermission", "read").put("displayName", "Car display")
        val request = Request.Builder().url("$BASE_URL/requestJoinGarden")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        httpClient.newCall(request).execute().use { r ->
            when {
                r.isSuccessful -> null
                r.code == 404 -> "That invite code wasn't recognised."
                else -> "Couldn't send the request (HTTP ${r.code})."
            }
        }
    } catch (e: Exception) { "Couldn't reach the server." }

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
                // Null when the server predates photo sync — the caller keeps its cached photos.
                val photos = json.optJSONArray("photos")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::jsonToPhoto) } }
                SyncResult.Success(plants, gardenLat, gardenLng, json.optString("memberToken", "").ifBlank { null }, photos)
            }
        } catch (_: Exception) {
            SyncResult.NetworkError
        }
    }
}
