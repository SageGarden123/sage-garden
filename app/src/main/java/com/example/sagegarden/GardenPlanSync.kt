package com.example.sagegarden

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Bumped by the garden-map settings (upload / rotate / remove) so the owner's plan is re-pushed. */
object GardenPlanEdits {
    var count by mutableStateOf(0)
}

/**
 * Syncs the garden "plan" — the owner's uploaded garden map image, the irrigation paths and sun
 * zones drawn on it, and the garden's irrigation zone names — via the syncGardenPlan Cloud
 * Function. Until now these lived only on the owner's phone, so members, the desktop app (which
 * builds printable reports from them) and the car display never saw them.
 *
 * The OWNER pushes (see [push]); everyone else pulls (see [pull]) and gets a read-only copy stored
 * against that garden: paths and sun zones in Room under the garden's id, and the image as a local
 * file set as that garden's customMapUri. The image goes up already rotated and downscaled, and is
 * only transferred when its hash changes.
 */
object GardenPlanSync {
    private const val TAG = "GardenPlanSync"
    private const val BASE_URL = BuildConfig.SAGE_API_BASE_URL
    private const val MAX_IMAGE_PX = 1600
    private const val MAX_IMAGE_BASE64 = 900_000

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).writeTimeout(40, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()

    private fun prefs(context: Context) = context.getSharedPreferences("garden_plan_sync", Context.MODE_PRIVATE)

    /** True once a member has a copy of the owner's plan for [gardenId] — lets the Map tab show it read-only. */
    fun hasPulledPlan(context: Context, gardenId: String): Boolean = prefs(context).getBoolean("pulled.$gardenId", false)

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }

    private suspend fun post(path: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$BASE_URL/$path")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            httpClient.newCall(request).execute().use { r ->
                val text = r.body?.string()
                if (!r.isSuccessful || text == null) { Log.w(TAG, "$path failed: HTTP ${r.code}"); null } else JSONObject(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "$path threw", e); null
        }
    }

    private fun authBody(context: Context, gardenId: String): JSONObject? {
        val token = GardenMembershipStore.getMemberToken(context, gardenId) ?: return null
        return JSONObject().put("deviceId", getOrCreateInstallId(context)).put("gardenId", gardenId).put("memberToken", token)
    }

    /** The owner's map as an already-rotated, downscaled JPEG, cached until the image or rotation changes. */
    private suspend fun encodedMapImage(context: Context, gardenId: String): Triple<String, String, Pair<Int, Int>>? = withContext(Dispatchers.IO) {
        val settings = GardenSettings.of(context, gardenId)
        val uri = settings.customMapUri ?: return@withContext null
        val source = "$uri|${settings.customMapRotation}"
        val cacheFile = File(context.cacheDir, "plan_upload_${gardenId.hashCode()}.b64")
        val p = prefs(context)
        if (p.getString("src.$gardenId", null) == source && cacheFile.exists()) {
            return@withContext Triple(cacheFile.readText(), p.getString("hash.$gardenId", "")!!, p.getInt("w.$gardenId", 0) to p.getInt("h.$gardenId", 0))
        }
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_PX) sample *= 2
            var bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@withContext null
            val scale = MAX_IMAGE_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
            val matrix = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                postRotate(settings.customMapRotation.toFloat())
            }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            var quality = 82
            var bytes: ByteArray
            var encoded: String
            do {
                bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                quality -= 12
            } while (encoded.length > MAX_IMAGE_BASE64 && quality > 30)
            if (encoded.length > MAX_IMAGE_BASE64) return@withContext null
            val hash = sha(bytes)
            cacheFile.writeText(encoded)
            p.edit().putString("src.$gardenId", source).putString("hash.$gardenId", hash)
                .putInt("w.$gardenId", bitmap.width).putInt("h.$gardenId", bitmap.height).apply()
            Triple(encoded, hash, bitmap.width to bitmap.height)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't encode the garden map image", e); null
        }
    }

    /** Owner only: uploads this garden's plan if anything changed since the last successful push. */
    suspend fun push(context: Context, gardenId: String) = mutex.withLock {
        if (!isOwnerOfGarden(context, gardenId)) return@withLock
        val body = authBody(context, gardenId) ?: return@withLock
        val db = AppDatabase.getInstance(context)
        val settings = GardenSettings.of(context, gardenId)

        val paths = JSONArray(db.irrigationPathDao().getAllOnceForGarden(gardenId).sortedBy { it.id }.map { p ->
            JSONObject().put("id", p.id).put("zone", p.zone).put("outletX", p.outletX).put("outletY", p.outletY)
                .put("segments", runCatching { JSONArray(p.segmentsJson) }.getOrDefault(JSONArray()))
        })
        val sunZones = JSONArray(db.sunZoneDao().getAllOnceForGarden(gardenId).sortedBy { it.id }.map { z ->
            JSONObject().put("id", z.id).put("category", z.category).put("mapType", z.mapType)
                .put("points", runCatching { JSONArray(z.pointsJson) }.getOrDefault(JSONArray()))
        })
        val zoneNames = (db.irrigationPathDao().getAllOnceForGarden(gardenId).map { it.zone } +
            settings.tuyaZoneMappings.map { it.zone } + settings.rachioZoneMappings.map { it.zone })
            .map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()
        val image = encodedMapImage(context, gardenId)

        val plan = JSONObject().put("paths", paths).put("sunZones", sunZones).put("irrigationZones", JSONArray(zoneNames))
            .put("imageHash", image?.second ?: JSONObject.NULL)
            .put("imageWidth", image?.third?.first ?: JSONObject.NULL).put("imageHeight", image?.third?.second ?: JSONObject.NULL)
        val fingerprint = sha(plan.toString().toByteArray())
        val p = prefs(context)
        if (p.getString("pushed.$gardenId", null) == fingerprint) return@withLock

        body.put("plan", plan)
        if (image != null && p.getString("uploadedHash.$gardenId", null) != image.second) body.put("image", image.first)
        val response = post("syncGardenPlan", body) ?: return@withLock
        p.edit().putString("pushed.$gardenId", fingerprint).putString("uploadedHash.$gardenId", image?.second)
            .putLong("planRev.$gardenId", response.optLong("planRev", 0)).apply()
    }

    /** Members: fetches the owner's plan and stores a read-only local copy for [gardenId]. */
    suspend fun pull(context: Context, gardenId: String) = mutex.withLock {
        if (isOwnerOfGarden(context, gardenId)) return@withLock
        val body = authBody(context, gardenId) ?: return@withLock
        val p = prefs(context)
        p.getString("pulledHash.$gardenId", null)?.let { body.put("knownImageHash", it) }
        val response = post("syncGardenPlan", body) ?: return@withLock
        val plan = response.optJSONObject("plan")
        val db = AppDatabase.getInstance(context)
        val settings = GardenSettings.of(context, gardenId)

        db.withTransaction {
            db.irrigationPathDao().deleteForGarden(gardenId)
            db.sunZoneDao().deleteForGarden(gardenId)
            val paths = plan?.optJSONArray("paths") ?: JSONArray()
            for (i in 0 until paths.length()) {
                val o = paths.getJSONObject(i)
                db.irrigationPathDao().upsert(IrrigationPathEntity(
                    id = "$gardenId:${o.getString("id")}", zone = o.optString("zone"),
                    outletX = o.optDouble("outletX"), outletY = o.optDouble("outletY"),
                    segmentsJson = (o.optJSONArray("segments") ?: JSONArray()).toString(), gardenId = gardenId
                ))
            }
            val zones = plan?.optJSONArray("sunZones") ?: JSONArray()
            for (i in 0 until zones.length()) {
                val o = zones.getJSONObject(i)
                db.sunZoneDao().upsert(SunZoneEntity(
                    id = "$gardenId:${o.getString("id")}", category = o.optString("category"),
                    pointsJson = (o.optJSONArray("points") ?: JSONArray()).toString(),
                    mapType = o.optString("mapType", "custom"), gardenId = gardenId
                ))
            }
        }

        val imageHash = plan?.optString("imageHash")?.takeIf { it.isNotBlank() && it != "null" }
        val file = File(context.filesDir, "garden_plans/${gardenId.replace("/", "_")}.jpg")
        when {
            imageHash == null -> {
                file.delete()
                settings.customMapUri = null
                p.edit().remove("pulledHash.$gardenId").apply()
            }
            !response.isNull("image") -> {
                file.parentFile?.mkdirs()
                file.writeBytes(Base64.decode(response.getString("image"), Base64.DEFAULT))
                settings.customMapUri = Uri.fromFile(file)
                p.edit().putString("pulledHash.$gardenId", imageHash).apply()
            }
        }
        // The image arrives already rotated.
        settings.customMapRotation = 0
        p.edit().putBoolean("pulled.$gardenId", plan != null).putLong("planRev.$gardenId", response.optLong("planRev", 0)).apply()
        GardenPlanEdits.count++ // lets the Map tab pick up the new copy
    }

    fun lastSeenPlanRev(context: Context, gardenId: String): Long = prefs(context).getLong("planRev.$gardenId", -1L)
}
