package com.example.sagegarden

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Not backed up and never shared between installs — each user connects their own Rachio account
 * (Help screen) with a personal API token copied from the Rachio app (Profile → API key). Much
 * simpler auth than Tuya: a single bearer token, no client id/secret pair and no HMAC signing.
 *
 * Rachio's polled event-history endpoint (unlike Tuya's structured dp-code logs) doesn't return a
 * structured zoneId/duration per event — only a category/type/subType plus a human-readable
 * "summary" sentence (e.g. "Zone 1 began watering at 07:19 PM (MDT)."). Watering sessions are
 * reconstructed here by matching the zone's own name against the start of that summary string and
 * pairing each ZONE_STARTED with the next ZONE_STOPPED/ZONE_COMPLETED for that zone — the same
 * spirit as Tuya's ON/OFF pairing, but resting on an undocumented text format rather than
 * documented dp codes, so it's inherently less robust if Rachio ever changes that wording.
 */
object RachioClient {
    private const val BASE_URL = "https://api.rach.io/1"

    private val httpClient = OkHttpClient()

    data class RachioZoneInfo(val id: String, val name: String, val zoneNumber: Int, val enabled: Boolean)
    data class RachioDeviceInfo(val id: String, val name: String, val zones: List<RachioZoneInfo>)

    private fun requireToken(context: Context): String {
        val token = getRachioApiToken(context)
        if (token.isBlank()) throw RuntimeException("Rachio isn't connected — add your API token in Help first")
        return token
    }

    private suspend fun get(path: String, token: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BASE_URL + path)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code} from Rachio")
            response.body?.string() ?: throw RuntimeException("Empty response from $path")
        }
    }

    suspend fun getPersonId(context: Context): String {
        val token = requireToken(context)
        return JSONObject(get("/public/person/info", token)).getString("id")
    }

    /** Lists every controller on the account and each controller's zones — used by "Test connection" and to help fill in the zone-mapping rows. */
    suspend fun getDevices(context: Context): List<RachioDeviceInfo> {
        val token = requireToken(context)
        val personId = getPersonId(context)
        val json = JSONObject(get("/public/person/$personId", token))
        val devicesArr = json.optJSONArray("devices") ?: JSONArray()
        return (0 until devicesArr.length()).map { i ->
            val d = devicesArr.getJSONObject(i)
            val zonesArr = d.optJSONArray("zones") ?: JSONArray()
            val zones = (0 until zonesArr.length()).map { j ->
                val z = zonesArr.getJSONObject(j)
                RachioZoneInfo(z.getString("id"), z.optString("name", "Zone"), z.optInt("zoneNumber", 0), z.optBoolean("enabled", true))
            }
            RachioDeviceInfo(d.getString("id"), d.optString("name", "Controller"), zones)
        }
    }

    /**
     * Fetches watering sessions for a single zone on a single device by pairing ZONE_STARTED with
     * the next ZONE_STOPPED/ZONE_COMPLETED event whose "summary" text starts with this zone's name
     * — see the class doc for why this can't rest on a structured zoneId/duration field the way
     * Tuya's dp-logs can.
     */
    suspend fun fetchWateringEvents(
        context: Context, deviceId: String, zoneId: String, zoneName: String, startMs: Long, endMs: Long
    ): List<WateringEvent> {
        val token = requireToken(context)
        val body = get("/public/device/$deviceId/event?startTime=$startMs&endTime=$endMs", token)
        val arr = JSONArray(body)

        data class ZoneStatusEvent(val ts: Long, val subType: String)
        val zoneStatusEvents = mutableListOf<ZoneStatusEvent>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            if (e.optString("type") != "ZONE_STATUS") continue
            val summary = e.optString("summary")
            // A plain startsWith would also match "Zone 10"/"Zone 11" summaries when zoneName is
            // "Zone 1" — require the character right after the name to not be alphanumeric (or the
            // name to be the whole string) so it's a real word-boundary match, not just a prefix.
            val matchesZone = summary.startsWith(zoneName) &&
                (summary.length == zoneName.length || !summary[zoneName.length].isLetterOrDigit())
            if (!matchesZone) continue
            val subType = e.optString("subType")
            if (subType != "ZONE_STARTED" && subType != "ZONE_STOPPED" && subType != "ZONE_COMPLETED") continue
            zoneStatusEvents.add(ZoneStatusEvent(e.optLong("eventDate"), subType))
        }
        zoneStatusEvents.sortBy { it.ts }

        val events = mutableListOf<WateringEvent>()
        var pendingStart: Long? = null
        zoneStatusEvents.forEach { ev ->
            if (ev.subType == "ZONE_STARTED") {
                pendingStart = ev.ts
            } else {
                val startTs = pendingStart
                if (startTs != null) {
                    val durationMinutes = ((ev.ts - startTs) / 60_000L).toInt()
                    if (durationMinutes > 0) {
                        events.add(
                            WateringEvent(
                                id = "$deviceId-$zoneId-$startTs",
                                zone = zoneName,
                                outlet = zoneId,
                                startTime = startTs,
                                durationMinutes = durationMinutes,
                                source = "Rachio"
                            )
                        )
                    }
                    pendingStart = null
                }
            }
        }
        return events
    }
}
