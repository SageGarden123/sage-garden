package com.example.sagegarden

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Not backed up and never shared between installs — each user connects their own Tuya Cloud project (Help screen). */
object TuyaClient {
    private const val BASE_URL = "https://openapi.tuyaeu.com" // Central Europe endpoint

    // Always query both outlets' switch + duration codes; we filter to the
    // requested outlet after fetching, same set as ALL_CODES in the Python script.
    private val ALL_CODES = listOf("switch_1", "switch_2", "use_time_1", "use_time_2")

    private const val ON_MATCH_WINDOW_MS = 20_000L
    private const val OFF_MATCH_WINDOW_MS = 5_000L

    private val httpClient = OkHttpClient()
    private var cachedToken: String? = null
    private var tokenExpiresAt: Long = 0L

    data class DpLogEntry(val code: String, val value: Any?, val eventTimeMs: Long)

    /** Call after the user changes their stored Tuya credentials so a token signed with the old secret isn't reused. */
    fun invalidateToken() {
        cachedToken = null
        tokenExpiresAt = 0L
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(data: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .uppercase()
    }

    private suspend fun getToken(context: Context, clientId: String, clientSecret: String): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < tokenExpiresAt) return@withContext it }

        val t = now.toString()
        val method = "GET"
        val urlPath = "/v1.0/token?grant_type=1"
        val contentSha256 = sha256Hex("")
        val stringToSign = "$method\n$contentSha256\n\n$urlPath"
        val signStr = clientId + t + stringToSign
        val sign = hmacSha256(signStr, clientSecret)

        val request = Request.Builder()
            .url(BASE_URL + urlPath)
            .header("client_id", clientId)
            .header("sign", sign)
            .header("t", t)
            .header("sign_method", "HMAC-SHA256")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code} from Tuya")
            val body = response.body?.string() ?: throw RuntimeException("Empty token response")
            val json = JSONObject(body)
            if (!json.optBoolean("success")) throw RuntimeException("Token request failed: $body")
            val result = json.getJSONObject("result")
            val token = result.getString("access_token")
            val expiresInSec = result.optLong("expire_time", 7200L)
            cachedToken = token
            tokenExpiresAt = now + (expiresInSec * 1000) - 30_000
            token
        }
    }

    private suspend fun signedGet(
        path: String, accessToken: String, clientId: String, clientSecret: String, params: Map<String, String>? = null
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val t = System.currentTimeMillis().toString()
            val method = "GET"

            val query = if (!params.isNullOrEmpty()) {
                "?" + params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
            } else ""
            val urlPath = path + query

            val contentSha256 = sha256Hex("")
            val stringToSign = "$method\n$contentSha256\n\n$urlPath"
            val signStr = clientId + accessToken + t + stringToSign
            val sign = hmacSha256(signStr, clientSecret)

            val request = Request.Builder()
                .url(BASE_URL + urlPath)
                .header("client_id", clientId)
                .header("access_token", accessToken)
                .header("sign", sign)
                .header("t", t)
                .header("sign_method", "HMAC-SHA256")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code} from Tuya")
                val body = response.body?.string() ?: throw RuntimeException("Empty response from $path")
                JSONObject(body)
            }
        }

    private fun requireCredentials(context: Context): Pair<String, String> {
        val clientId = getTuyaClientId(context)
        val clientSecret = getTuyaClientSecret(context)
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw RuntimeException("Tuya isn't connected — add your Client ID and Secret in Help first")
        }
        return clientId to clientSecret
    }

    suspend fun getDeviceDpCodes(context: Context, deviceId: String): List<String> {
        val (clientId, clientSecret) = requireCredentials(context)
        val token = getToken(context, clientId, clientSecret)
        val data = signedGet("/v1.0/devices/$deviceId/status", token, clientId, clientSecret)
        if (!data.optBoolean("success")) {
            throw RuntimeException(data.optString("msg", "Device lookup failed (check the Device ID)"))
        }
        val result = data.optJSONArray("result") ?: JSONArray()
        return (0 until result.length()).map { result.getJSONObject(it).getString("code") }
    }

    suspend fun getDpLogs(context: Context, deviceId: String, codes: List<String>, startMs: Long, endMs: Long): List<DpLogEntry> {
        val (clientId, clientSecret) = requireCredentials(context)
        val token = getToken(context, clientId, clientSecret)
        val allLogs = mutableListOf<DpLogEntry>()
        var rowKey = ""
        var firstPage = true

        while (true) {
            val params = mutableMapOf(
                "codes" to codes.joinToString(","),
                "type" to "7",
                "start_time" to startMs.toString(),
                "end_time" to endMs.toString(),
                "size" to "100"
            )
            if (rowKey.isNotEmpty()) params["start_row_key"] = rowKey

            val data = signedGet("/v1.0/devices/$deviceId/logs", token, clientId, clientSecret, params)
            if (!data.optBoolean("success")) {
                if (firstPage) throw RuntimeException(data.optString("msg", "Log fetch failed for device $deviceId"))
                break
            }
            firstPage = false

            val result = data.optJSONObject("result") ?: break
            val logs = result.optJSONArray("logs") ?: JSONArray()
            for (i in 0 until logs.length()) {
                val entry = logs.getJSONObject(i)
                allLogs.add(DpLogEntry(entry.optString("code"), entry.opt("value"), entry.optLong("event_time")))
            }
            if (!result.optBoolean("has_next")) break
            rowKey = result.optString("next_row_key", "")
            if (rowKey.isEmpty()) break
        }
        return allLogs
    }

    private fun outletNumber(code: String) = if (code.endsWith("_2")) "2" else "1"

    /**
     * Fetches watering sessions for a single outlet on a single device.
     * Mirrors pull_irrigation_logs.py: anchors on "use_time_X" duration readings
     * (only emitted after a genuine watering session) and pairs each with the
     * nearest matching ON event, rather than trusting raw switch toggles alone —
     * those include frequent background/heartbeat pings that look identical to
     * real OFF events by timestamp.
     *
     * Some devices/firmware only report use_time_X for scheduler-triggered runs and never emit
     * it for a manual on/off toggle — anchoring exclusively on use_time_X then silently returns
     * zero events for those devices, with no error (sync still reports "success"). If this fetch
     * window has no use_time_X readings at all, fall back to pairing raw switch_X toggles
     * directly (see buildEventsFromSwitchPairs) — this reintroduces the heartbeat-noise risk the
     * use_time anchor exists to avoid, but for a device that never reports use_time there's no
     * better signal available, and showing a plausibly-noisy event beats showing nothing.
     */
    suspend fun fetchWateringEvents(
        context: Context, deviceId: String, zone: String, outlet: String, startMs: Long, endMs: Long
    ): List<WateringEvent> {
        val logs = getDpLogs(context, deviceId, ALL_CODES, startMs, endMs).sortedBy { it.eventTimeMs }

        val ons = mutableListOf<OnOff>()
        val offs = mutableListOf<OnOff>()
        val durations = mutableListOf<Dur>()

        logs.forEach { log ->
            if (outletNumber(log.code) != outlet) return@forEach
            when {
                log.code.startsWith("switch") -> {
                    val isOn = log.value == true || log.value?.toString()?.equals("true", ignoreCase = true) == true
                    if (isOn) ons.add(OnOff(log.eventTimeMs)) else offs.add(OnOff(log.eventTimeMs))
                }
                log.code.startsWith("use_time") -> {
                    durations.add(Dur(log.eventTimeMs, log.value?.toString()?.toDoubleOrNull()))
                }
            }
        }

        return if (durations.isNotEmpty()) {
            buildEventsFromDurations(durations, ons, offs, deviceId, zone, outlet)
        } else {
            buildEventsFromSwitchPairs(ons, offs, deviceId, zone, outlet)
        }
    }

    private data class OnOff(val ts: Long)
    private data class Dur(val ts: Long, val seconds: Double?)

    private fun buildEventsFromDurations(
        durations: List<Dur>, ons: List<OnOff>, offs: List<OnOff>, deviceId: String, zone: String, outlet: String
    ): List<WateringEvent> {
        val usedOnIndices = mutableSetOf<Int>()
        val events = mutableListOf<WateringEvent>()

        durations.forEach { dur ->
            val seconds = dur.seconds
            var startTs = if (seconds != null) dur.ts - (seconds * 1000).toLong() else dur.ts

            if (seconds != null) {
                var bestIdx = -1
                var bestDiff = Long.MAX_VALUE
                ons.forEachIndexed { idx, on ->
                    if (idx in usedOnIndices) return@forEachIndexed
                    val diff = kotlin.math.abs(on.ts - startTs)
                    if (diff <= ON_MATCH_WINDOW_MS && diff < bestDiff) { bestDiff = diff; bestIdx = idx }
                }
                if (bestIdx >= 0) { usedOnIndices.add(bestIdx); startTs = ons[bestIdx].ts }
            }

            var endTs = dur.ts
            offs.firstOrNull { kotlin.math.abs(it.ts - dur.ts) <= OFF_MATCH_WINDOW_MS }?.let { endTs = it.ts }

            // A real use_time_X reading is genuine signal even when it rounds under a minute
            // (e.g. a quick manual test) — floor to 1 rather than dropping it, so a real
            // watering session is never silently invisible in the history.
            val durationMinutes = maxOf(1, seconds?.let { Math.round(it / 60.0).toInt() } ?: ((endTs - startTs) / 60_000L).toInt())
            events.add(
                WateringEvent(
                    id = "$deviceId-$outlet-$startTs",
                    zone = zone,
                    outlet = outlet,
                    startTime = startTs,
                    durationMinutes = durationMinutes,
                    source = "Tuya"
                )
            )
        }
        return events
    }

    /** Pairs each ON with the next OFF after it — used only when a device never reports use_time_X at all (see fetchWateringEvents doc). */
    private fun buildEventsFromSwitchPairs(
        ons: List<OnOff>, offs: List<OnOff>, deviceId: String, zone: String, outlet: String
    ): List<WateringEvent> {
        val sortedOns = ons.sortedBy { it.ts }
        val sortedOffs = offs.sortedBy { it.ts }
        val events = mutableListOf<WateringEvent>()
        var offIdx = 0
        sortedOns.forEach { on ->
            while (offIdx < sortedOffs.size && sortedOffs[offIdx].ts <= on.ts) offIdx++
            if (offIdx < sortedOffs.size) {
                val off = sortedOffs[offIdx]
                val durationMinutes = maxOf(1, ((off.ts - on.ts) / 60_000L).toInt())
                events.add(
                    WateringEvent(
                        id = "$deviceId-$outlet-${on.ts}",
                        zone = zone,
                        outlet = outlet,
                        startTime = on.ts,
                        durationMinutes = durationMinutes,
                        source = "Tuya"
                    )
                )
                offIdx++
            }
        }
        return events
    }
}