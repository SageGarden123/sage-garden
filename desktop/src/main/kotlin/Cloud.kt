import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

const val CLOUD_BASE_URL = "https://us-central1-gardenmapper-a68ec.cloudfunctions.net"

/** A garden this desktop install belongs to (as returned by listMyGardens). */
data class KnownGarden(val gardenId: String, val name: String, val role: String, val permission: String, val memberToken: String)
data class PendingRequest(val gardenId: String, val name: String)
data class GardenSignal(val rev: Long, val membershipRev: Long, val planRev: Long)

sealed class CloudResult<out T> {
    data class Ok<T>(val value: T) : CloudResult<T>()
    data object NotAuthorized : CloudResult<Nothing>()
    data class Failed(val reason: String) : CloudResult<Nothing>()
}

/**
 * The garden-sharing Cloud Functions the desktop app uses besides syncGarden (see
 * GardenSyncClient): listing its gardens, joining one with an invite code, the cheap change check
 * that drives live updates, and fetching the garden plan. Plain HTTPS — no Firebase SDK or API key.
 */
object Cloud {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    private fun post(path: String, body: JSONObject, timeoutSeconds: Long = 30): Pair<Int, JSONObject?> {
        val request = HttpRequest.newBuilder().uri(URI.create("$CLOUD_BASE_URL/$path"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to runCatching { JSONObject(response.body()) }.getOrNull()
    }

    fun listMyGardens(deviceId: String): CloudResult<Pair<List<KnownGarden>, List<PendingRequest>>> = try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$CLOUD_BASE_URL/listMyGardens?deviceId=${URLEncoder.encode(deviceId, Charsets.UTF_8)}"))
            .timeout(Duration.ofSeconds(20)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) CloudResult.Failed("HTTP ${response.statusCode()}") else {
            val json = JSONObject(response.body())
            val gardens = json.getJSONArray("gardens").let { arr ->
                (0 until arr.length()).map { i -> arr.getJSONObject(i) }.map {
                    KnownGarden(it.getString("gardenId"), it.optString("name", "Garden"), it.optString("role"), it.optString("permission"), it.optString("memberToken"))
                }
            }
            val pending = json.optJSONArray("pendingRequests")?.let { arr ->
                (0 until arr.length()).map { i -> arr.getJSONObject(i) }.map { PendingRequest(it.getString("gardenId"), it.optString("name", "Garden")) }
            } ?: emptyList()
            CloudResult.Ok(gardens.sortedWith(compareByDescending<KnownGarden> { it.role == "owner" }.thenBy { it.name }) to pending)
        }
    } catch (e: Exception) { CloudResult.Failed(e.message ?: "network error") }

    /** Asks to join the garden behind [inviteCode]; returns "approved" or "pending". */
    fun requestJoinGarden(deviceId: String, inviteCode: String, permission: String): CloudResult<String> = try {
        val (code, json) = post("requestJoinGarden", JSONObject()
            .put("deviceId", deviceId).put("inviteCode", inviteCode.trim().uppercase())
            .put("requestedPermission", permission).put("displayName", "Desktop app"))
        when {
            code == 200 && json != null -> CloudResult.Ok(json.optString("status", "pending"))
            code == 404 -> CloudResult.Failed("That invite code wasn't recognised.")
            else -> CloudResult.Failed("Couldn't send the request (HTTP $code).")
        }
    } catch (e: Exception) { CloudResult.Failed(e.message ?: "network error") }

    fun gardenSignal(deviceId: String, gardenId: String, memberToken: String): CloudResult<GardenSignal> = try {
        val (code, json) = post("getGardenSignal", JSONObject().put("deviceId", deviceId).put("gardenId", gardenId).put("memberToken", memberToken), 15)
        when {
            code == 403 -> CloudResult.NotAuthorized
            code == 200 && json != null -> CloudResult.Ok(GardenSignal(json.optLong("rev"), json.optLong("membershipRev"), json.optLong("planRev")))
            else -> CloudResult.Failed("HTTP $code")
        }
    } catch (e: Exception) { CloudResult.Failed(e.message ?: "network error") }

    /** Satellite imagery for [view] via the satelliteMap Cloud Function (the Google key stays server-side). */
    fun satelliteImage(deviceId: String, gardenId: String, memberToken: String, view: SatelliteView): CloudResult<ByteArray> = try {
        val (code, json) = post("satelliteMap", JSONObject().put("deviceId", deviceId).put("gardenId", gardenId).put("memberToken", memberToken)
            .put("lat", view.lat).put("lng", view.lng).put("zoom", view.zoom).put("width", view.width).put("height", view.height), 45)
        when {
            code == 200 && json != null -> CloudResult.Ok(Base64.getDecoder().decode(json.getString("image")))
            code == 403 -> CloudResult.NotAuthorized
            code == 503 || code == 404 -> CloudResult.Failed("Satellite maps aren't set up yet.")
            code == 429 -> CloudResult.Failed("Today's satellite map limit for this computer has been reached — try again tomorrow.")
            else -> CloudResult.Failed("Google couldn't provide satellite imagery right now (HTTP $code).")
        }
    } catch (e: Exception) { CloudResult.Failed(e.message ?: "network error") }

    /** Fetches the owner's garden plan into the local plan cache; the image is only downloaded when it changed. */
    fun pullPlan(deviceId: String, gardenId: String, memberToken: String): CloudResult<GardenPlan?> = try {
        val body = JSONObject().put("deviceId", deviceId).put("gardenId", gardenId).put("memberToken", memberToken)
        GardenPlanCache.load(gardenId)?.imageHash?.let { body.put("knownImageHash", it) }
        val (code, json) = post("syncGardenPlan", body, 60)
        when {
            code == 403 -> CloudResult.NotAuthorized
            code != 200 || json == null -> CloudResult.Failed("HTTP $code")
            else -> {
                val planJson = json.optJSONObject("plan")
                if (planJson == null) { GardenPlanCache.clear(gardenId); CloudResult.Ok(null) } else {
                    if (!json.isNull("image")) GardenPlanCache.imageFile(gardenId).apply { parentFile.mkdirs() }
                        .writeBytes(Base64.getMimeDecoder().decode(json.getString("image")))
                    val plan = GardenPlan.fromJson(planJson)
                    if (plan.imageHash == null) GardenPlanCache.imageFile(gardenId).delete()
                    GardenPlanCache.save(gardenId, planJson)
                    CloudResult.Ok(plan)
                }
            }
        }
    } catch (e: Exception) { CloudResult.Failed(e.message ?: "network error") }
}

// ---- Garden plan (owner's map image + irrigation paths + sun zones), as synced from the phone ----

data class PlanSegment(val type: String, val points: List<Pair<Double, Double>>, val radius: Double?)
data class PlanPath(val id: String, val zone: String, val outletX: Double, val outletY: Double, val segments: List<PlanSegment>)
data class PlanSunZone(val category: String, val mapType: String, val points: List<Pair<Double, Double>>)
data class GardenPlan(
    val paths: List<PlanPath>,
    val sunZones: List<PlanSunZone>,
    val irrigationZones: List<String>,
    val imageHash: String?,
    val imageWidth: Int?,
    val imageHeight: Int?,
) {
    companion object {
        private fun points(arr: JSONArray?): List<Pair<Double, Double>> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                arr.optJSONArray(i)?.let { it.optDouble(0) to it.optDouble(1) }
            }

        fun fromJson(o: JSONObject) = GardenPlan(
            paths = o.optJSONArray("paths")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }.map { p ->
                    PlanPath(
                        p.optString("id"), p.optString("zone"), p.optDouble("outletX"), p.optDouble("outletY"),
                        p.optJSONArray("segments")?.let { segs ->
                            (0 until segs.length()).map { segs.getJSONObject(it) }.map { s ->
                                PlanSegment(s.optString("type", "main"), points(s.optJSONArray("points")), if (s.has("radius")) s.optDouble("radius") else null)
                            }
                        } ?: emptyList()
                    )
                }
            } ?: emptyList(),
            sunZones = o.optJSONArray("sunZones")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }.map { PlanSunZone(it.optString("category"), it.optString("mapType", "custom"), points(it.optJSONArray("points"))) }
            } ?: emptyList(),
            irrigationZones = o.optJSONArray("irrigationZones")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
            imageHash = o.optString("imageHash").takeIf { it.isNotBlank() && it != "null" },
            imageWidth = if (o.isNull("imageWidth")) null else o.optInt("imageWidth"),
            imageHeight = if (o.isNull("imageHeight")) null else o.optInt("imageHeight"),
        )
    }
}

object GardenPlanCache {
    private fun dir() = File(System.getProperty("user.home"), "SageGardenDesktop/plans")
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    fun imageFile(gardenId: String) = File(dir(), "${safe(gardenId)}.jpg")
    private fun jsonFile(gardenId: String) = File(dir(), "${safe(gardenId)}.json")

    fun load(gardenId: String): GardenPlan? =
        runCatching { GardenPlan.fromJson(JSONObject(jsonFile(gardenId).readText())) }.getOrNull()
    fun save(gardenId: String, json: JSONObject) {
        dir().mkdirs(); jsonFile(gardenId).writeText(json.toString())
    }
    fun clear(gardenId: String) { jsonFile(gardenId).delete(); imageFile(gardenId).delete() }
}
