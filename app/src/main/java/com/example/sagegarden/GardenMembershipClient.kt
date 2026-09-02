package com.example.sagegarden

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A garden this device is a member of (owner or invited member), as returned by listMyGardens/createGarden/respondToJoinRequest. */
data class KnownGarden(val gardenId: String, val name: String, val role: String, val permission: String, val memberToken: String)

/** A join request from another device, waiting on this device's (as owner's) approval — see GardenMembershipClient.listPendingRequestsForGarden. */
data class PendingGardenRequest(val requestingDeviceId: String, val requestedPermission: String, val requestedAt: Long, val displayName: String?)

/** A join request this device has made against someone else's garden, still awaiting the owner's approval. */
data class PendingJoinRequest(val gardenId: String, val name: String, val requestedPermission: String, val requestedAt: Long)

/** An approved member of a garden (owner or member), as returned by GardenMembershipClient.listMembers — backs the "who has access" management UI. */
data class GardenMember(val deviceId: String, val role: String, val permission: String, val joinedAt: Long, val displayName: String?)

/**
 * Local cache of this device's garden memberships — mirrors the server's deviceGardens/{deviceId}
 * doc (see gardenMembers.ts) so the garden picker/sharing UI has something to show immediately
 * without a network round trip, and so GardenSyncClient always has the memberToken it needs handy.
 * Refreshed from the server via GardenMembershipClient.refreshKnownGardens, but every mutating call
 * (create/join/approve) also updates it directly from that call's own response.
 */
object GardenMembershipStore {
    private const val PREFS = "garden_membership_prefs"
    private const val KEY_ACTIVE_GARDEN_ID = "active_garden_id"
    private const val KEY_KNOWN_GARDENS = "known_gardens"
    private const val KEY_PENDING_REQUESTS = "pending_requests"
    private const val KEY_DEVICE_DISPLAY_NAME = "device_display_name"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Null means "no garden explicitly selected" — the device's own install-id-keyed default garden. */
    fun getActiveGardenId(context: Context): String? = prefs(context).getString(KEY_ACTIVE_GARDEN_ID, null)
    fun setActiveGardenId(context: Context, gardenId: String?) {
        prefs(context).edit().putString(KEY_ACTIVE_GARDEN_ID, gardenId).apply()
        ActiveGardenState.activeGardenId = gardenId
        // HemisphereState is read from screens other than Help (e.g. watering-due calculations on
        // Dashboard/List/Irrigation) that won't otherwise notice the newly-active garden's own
        // hemisphere setting until this singleton itself is refreshed — see
        // feedback-compose-reactive-staleness for why a raw prefs read isn't enough here.
        HemisphereState.value = getHemisphere(context)
        // Same reasoning as HemisphereState above — GardenAddressSection/GardenZonesSection must see
        // the newly-active garden's own address/zones immediately, not whatever this device last had
        // loaded for the previous garden.
        GardenAddressState.address = getGardenAddress(context)
        GardenAddressState.latLng = getGardenLatLng(context)
        GardenAddressState.locations = getGardenLocations(context)
        TuyaZoneMappingState.mappings = getTuyaZoneMappings(context)
    }

    fun getKnownGardens(context: Context): List<KnownGarden> {
        val raw = prefs(context).getString(KEY_KNOWN_GARDENS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            KnownGarden(o.getString("gardenId"), o.getString("name"), o.getString("role"), o.getString("permission"), o.getString("memberToken"))
        }
    }
    fun setKnownGardens(context: Context, gardens: List<KnownGarden>) {
        val arr = JSONArray()
        gardens.forEach { g ->
            arr.put(JSONObject().apply {
                put("gardenId", g.gardenId); put("name", g.name); put("role", g.role)
                put("permission", g.permission); put("memberToken", g.memberToken)
            })
        }
        prefs(context).edit().putString(KEY_KNOWN_GARDENS, arr.toString()).apply()
    }

    /** Upserts a single garden into the known-gardens cache — used after create/join/approve so the UI reflects it immediately, without waiting on a full refresh. */
    fun upsertKnownGarden(context: Context, garden: KnownGarden) {
        val updated = getKnownGardens(context).filterNot { it.gardenId == garden.gardenId } + garden
        setKnownGardens(context, updated)
    }

    fun getPendingRequests(context: Context): List<PendingJoinRequest> {
        val raw = prefs(context).getString(KEY_PENDING_REQUESTS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PendingJoinRequest(o.getString("gardenId"), o.getString("name"), o.getString("requestedPermission"), o.getLong("requestedAt"))
        }
    }
    fun setPendingRequests(context: Context, requests: List<PendingJoinRequest>) {
        val arr = JSONArray()
        requests.forEach { r ->
            arr.put(JSONObject().apply {
                put("gardenId", r.gardenId); put("name", r.name)
                put("requestedPermission", r.requestedPermission); put("requestedAt", r.requestedAt)
            })
        }
        prefs(context).edit().putString(KEY_PENDING_REQUESTS, arr.toString()).apply()
    }

    /** The memberToken GardenSyncClient must present for a given garden — null until this device has created/joined/auto-provisioned it at least once. */
    fun getMemberToken(context: Context, gardenId: String): String? =
        getKnownGardens(context).firstOrNull { it.gardenId == gardenId }?.memberToken

    /** This device's own chosen name (e.g. "My phone"), shown to a garden's owner on a join request and in the members list — otherwise they'd only see an anonymous "A device"/raw id. Remembered locally so it's pre-filled on every future join request without retyping. */
    fun getDeviceDisplayName(context: Context): String = prefs(context).getString(KEY_DEVICE_DISPLAY_NAME, "") ?: ""
    fun setDeviceDisplayName(context: Context, value: String) {
        prefs(context).edit().putString(KEY_DEVICE_DISPLAY_NAME, value).apply()
    }
}

/**
 * Which garden's data is currently shown across the app — a Compose-observable mirror of
 * GardenMembershipStore's persisted active-garden-id, following this codebase's established
 * pattern (see HemisphereState, AdvancedModeState) for settings whose effect is read in a
 * different composable than where they're changed. Synced from prefs once at app startup
 * (MainActivity.onCreate); every actual change should go through
 * GardenMembershipStore.setActiveGardenId so the two stay in sync.
 */
object ActiveGardenState {
    var activeGardenId by mutableStateOf<String?>(null)
}

/**
 * Live, Compose-observable mirror of the active garden's address/coordinates/zones — same rationale
 * as [ActiveGardenState]/[HemisphereState]. Without this, GardenAddressSection/GardenZonesSection
 * (Help screen) captured a one-shot snapshot via `remember(ActiveGardenState.activeGardenId)`, which
 * only re-read prefs when the garden itself changed — a real sync landing shortly *after* that
 * snapshot (the normal case: switching garden fires an async network call, but the Help screen may
 * already be composed and reading stale prefs before the response comes back) never refreshed it, so
 * a member could be looking at their own device's blank/stale value indefinitely, only fixed by
 * navigating away and back. Refreshed by setGardenAddress/setGardenLatLng/setGardenLocations
 * (whether from a local edit or a sync pull) and by GardenMembershipStore.setActiveGardenId on
 * garden switch.
 */
object GardenAddressState {
    var address by mutableStateOf<String?>(null)
    var latLng by mutableStateOf<Pair<Double, Double>?>(null)
    var locations by mutableStateOf<List<String>?>(null)
}

/** The garden id that should actually be used for sync/data calls right now: the explicitly-selected active garden, or this device's own install-id-keyed default garden if none has been chosen. */
/**
 * ActiveGardenState.activeGardenId is only synced from persisted storage in MainActivity.onCreate
 * — a cold-started process that never ran that (a WorkManager job, a widget refresh, BootReceiver)
 * would otherwise see it at its class-default null and silently fall back to this device's own
 * default garden even when the user has a different one active. Falling back to the persisted
 * store directly (rather than only the in-memory singleton) fixes that without giving up the
 * singleton's live reactivity for the normal in-app case, since setActiveGardenId always writes
 * both together.
 */
fun effectiveGardenId(context: Context): String =
    ActiveGardenState.activeGardenId ?: GardenMembershipStore.getActiveGardenId(context) ?: getOrCreateInstallId(context)

/**
 * Whether this device owns the currently active garden (its own default garden, or one it created)
 * versus merely being a member someone else invited in. The custom map drawing and sun map are
 * per-device local data that never syncs (see migration24To25/GardenSyncStore) — showing them while
 * viewing a shared garden you don't own would just show YOUR OWN unrelated drawing/zones next to
 * someone else's plants, which is more confusing than showing nothing, so those features are hidden
 * entirely rather than synced for a non-owner viewing a shared garden.
 */
fun isOwnerOfActiveGarden(context: Context): Boolean = isOwnerOfGarden(context, effectiveGardenId(context))

/** Same as [isOwnerOfActiveGarden] but for an explicit gardenId — used by GardenSyncClient, which syncs whatever garden it's told to regardless of which one is currently active in the UI. */
fun isOwnerOfGarden(context: Context, gardenId: String): Boolean {
    if (gardenId == getOrCreateInstallId(context)) return true
    return GardenMembershipStore.getKnownGardens(context).firstOrNull { it.gardenId == gardenId }?.role == "owner"
}

/**
 * Whether this device may edit the active garden's data — false for a member the owner granted
 * view-only access. The server already discards a read-only member's pushed plant/care-log changes
 * (see syncGarden.ts), but until now nothing stopped the UI from letting them fill out and "save"
 * the edit form anyway, only to have it silently discarded on the next sync — this is what the edit
 * form (FormScreen) gates its Save/Delete buttons and field editability on.
 */
fun hasWriteAccessToActiveGarden(context: Context): Boolean {
    val activeId = effectiveGardenId(context)
    if (activeId == getOrCreateInstallId(context)) return true
    return GardenMembershipStore.getKnownGardens(context).firstOrNull { it.gardenId == activeId }?.permission != "read"
}

/**
 * Every garden this device has access to (its own default garden, plus every garden it's created or
 * been approved into) — not just whichever one is currently "active" in the UI. Used by background
 * work (the watering-reminder worker, home-screen widgets) that needs to check due dates and apply
 * settings across ALL of a user's gardens at once, e.g. someone watching a friend's garden while they're
 * away who still wants their own reminders too. Deduplicated since the device's own default garden may
 * also appear in the known-gardens cache (e.g. after a token refresh upserts it there).
 */
fun allKnownGardenIds(context: Context): List<String> {
    val ownId = getOrCreateInstallId(context)
    val known = GardenMembershipStore.getKnownGardens(context).map { it.gardenId }
    return (listOf(ownId) + known).distinct()
}

sealed class GardenMembershipResult<out T> {
    data class Success<T>(val value: T) : GardenMembershipResult<T>()
    data class Failure(val error: String) : GardenMembershipResult<Nothing>()
}

/** Client for the garden-sharing Cloud Functions (createGarden, requestJoinGarden, respondToJoinRequest, updateMemberPermission, listMyGardens, regenerateInviteCode) — see gardenMembers.ts for the server-side model these calls talk to. */
object GardenMembershipClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private const val BASE_URL = BuildConfig.SAGE_API_BASE_URL

    private fun jsonBody(json: JSONObject) =
        json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun post(path: String, body: JSONObject): JSONObject {
        val request = Request.Builder().url("$BASE_URL/$path").post(jsonBody(body)).build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: throw java.io.IOException("empty_response")
            val json = JSONObject(text)
            if (!response.isSuccessful) throw java.io.IOException(json.optString("error", "server_error"))
            return json
        }
    }

    /**
     * A device only ever gets a memberToken cached for its own default garden (gardenId ==
     * installId) after either a "Sync now" (syncGarden's legacy-bridge auto-provisions it) or
     * createGarden — there's no reason a brand-new install would have done either before trying
     * to share/manage it for the first time. Rather than surface "not a member" for a garden the
     * device plainly owns, transparently run one sync to obtain a token before failing — sync with
     * no local changes to contribute is a safe, idempotent no-op (it just re-fetches the garden's
     * already-known state), exactly what "Sync now" already does.
     */
    private suspend fun ensureMemberToken(context: Context, gardenId: String): String? {
        GardenMembershipStore.getMemberToken(context, gardenId)?.let { return it }
        if (gardenId != getOrCreateInstallId(context)) return null
        GardenSyncClient.sync(context, getOrCreateInstallId(context), gardenId)
        return GardenMembershipStore.getMemberToken(context, gardenId)
    }

    suspend fun createGarden(context: Context, name: String): GardenMembershipResult<KnownGarden> = withContext(Dispatchers.IO) {
        try {
            val deviceId = getOrCreateInstallId(context)
            val json = post("createGarden", JSONObject().apply { put("deviceId", deviceId); put("name", name) })
            val garden = KnownGarden(json.getString("gardenId"), json.getString("name"), "owner", "write", json.getString("memberToken"))
            GardenMembershipStore.upsertKnownGarden(context, garden)
            GardenMembershipResult.Success(garden)
        } catch (e: Exception) {
            GardenMembershipResult.Failure(e.message ?: "unknown_error")
        }
    }

    suspend fun requestJoinGarden(context: Context, inviteCode: String, requestedPermission: String, displayName: String? = null): GardenMembershipResult<String> = withContext(Dispatchers.IO) {
        try {
            val deviceId = getOrCreateInstallId(context)
            val json = post("requestJoinGarden", JSONObject().apply {
                put("deviceId", deviceId); put("inviteCode", inviteCode)
                put("requestedPermission", requestedPermission); put("displayName", displayName ?: JSONObject.NULL)
            })
            GardenMembershipResult.Success(json.optString("status", "pending"))
        } catch (e: Exception) {
            GardenMembershipResult.Failure(e.message ?: "unknown_error")
        }
    }

    /** Owner-only. [ownerGardenId] is the owner's own garden (the one being shared), not the target — respondToJoinRequest is always called from the garden's perspective, using the caller's own memberToken for it. */
    suspend fun respondToJoinRequest(context: Context, ownerGardenId: String, requestingDeviceId: String, approve: Boolean, permission: String? = null): GardenMembershipResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val ownerDeviceId = getOrCreateInstallId(context)
            val ownerMemberToken = ensureMemberToken(context, ownerGardenId)
                ?: return@withContext GardenMembershipResult.Failure("not_a_member")
            post("respondToJoinRequest", JSONObject().apply {
                put("ownerDeviceId", ownerDeviceId); put("ownerMemberToken", ownerMemberToken)
                put("gardenId", ownerGardenId); put("requestingDeviceId", requestingDeviceId)
                put("approve", approve); if (permission != null) put("permission", permission)
            })
            GardenMembershipResult.Success(Unit)
        } catch (e: Exception) {
            GardenMembershipResult.Failure(e.message ?: "unknown_error")
        }
    }

    suspend fun listPendingRequestsForGarden(context: Context, gardenId: String): GardenMembershipResult<List<PendingGardenRequest>> = withContext(Dispatchers.IO) {
        try {
            val ownerDeviceId = getOrCreateInstallId(context)
            val ownerMemberToken = ensureMemberToken(context, gardenId)
                ?: return@withContext GardenMembershipResult.Failure("not_a_member")
            val url = "$BASE_URL/listPendingRequests?ownerDeviceId=$ownerDeviceId&ownerMemberToken=$ownerMemberToken&gardenId=$gardenId"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: return@withContext GardenMembershipResult.Failure("empty_response")
                if (!response.isSuccessful) return@withContext GardenMembershipResult.Failure("server_error")
                val json = JSONObject(text)
                val arr = json.getJSONArray("requests")
                val requests = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    PendingGardenRequest(
                        requestingDeviceId = o.getString("requestingDeviceId"),
                        requestedPermission = o.getString("requestedPermission"),
                        requestedAt = o.getLong("requestedAt"),
                        displayName = if (o.isNull("displayName")) null else o.optString("displayName")
                    )
                }
                GardenMembershipResult.Success(requests)
            }
        } catch (e: Exception) {
            GardenMembershipResult.Failure(e.message ?: "unknown_error")
        }
    }

    suspend fun updateMemberPermission(context: Context, gardenId: String, targetDeviceId: String, permission: String): GardenMembershipResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val ownerDeviceId = getOrCreateInstallId(context)
            val ownerMemberToken = ensureMemberToken(context, gardenId)
                ?: return@withContext GardenMembershipResult.Failure("not_a_member")
            post("updateMemberPermission", JSONObject().apply {
                put("ownerDeviceId", ownerDeviceId); put("ownerMemberToken", ownerMemberToken)
                put("gardenId", gardenId); put("targetDeviceId", targetDeviceId); put("permission", permission)
            })
            GardenMembershipResult.Success(Unit)
        } catch (e: Exception) {
            GardenMembershipResult.Failure(e.message ?: "unknown_error")
        }
    }

    /** Owner-only: everyone with access to this garden (including the owner themselves), for the "manage access" list. */
    suspend fun listMembers(context: Context, gardenId: String): GardenMembershipResult<List<GardenMember>> = withContext(Dispatchers.IO) {
        try {
            val ownerDeviceId = getOrCreateInstallId(context)
            val ownerMemberToken = ensureMemberToken(context, gardenId)
                ?: return@withContext GardenMembershipResult.Failure("not_a_member")
            val url = "$BASE_URL/listGardenMembers?ownerDeviceId=$ownerDeviceId&ownerMemberToken=$ownerMemberToken&gardenId=$gardenId"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: return@withContext GardenMembershipResult.Failure("empty_response")
                if (!response.isSuccessful) return@withContext GardenMembershipResult.Failure("server_error")
                val json = JSONObject(text)
                val arr = json.getJSONArray("members")
                val members = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    GardenMember(
                        deviceId = o.getString("deviceId"), role = o.getString("role"), permission = o.getString("permission"),
                        joinedAt = o.getLong("joinedAt"), displayName = if (o.isNull("displayName")) null else o.optString("displayName")
                    )
                }
                GardenMembershipResult.Success(members)
            }
        } catch (e: Exception) {
            GardenMembershipResult.Failure(e.message ?: "unknown_error")
        }
    }

    /** Owner-only: revokes another member's access outright — distinct from updateMemberPermission, which merely changes read/write. */
    suspend fun removeMember(context: Context, gardenId: String, targetDeviceId: String): GardenMembershipResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val ownerDeviceId = getOrCreateInstallId(context)
            val ownerMemberToken = ensureMemberToken(context, gardenId)
                ?: return@withContext GardenMembershipResult.Failure("not_a_member")
            post("removeMember", JSONObject().apply {
                put("ownerDeviceId", ownerDeviceId); put("ownerMemberToken", ownerMemberToken)
                put("gardenId", gardenId); put("targetDeviceId", targetDeviceId)
            })
            GardenMembershipResult.Success(Unit)
        } catch (e: Exception) {
            GardenMembershipResult.Failure(e.message ?: "unknown_error")
        }
    }

    /** Self-service: removes this device's own access to someone else's garden — the owner can't leave their own garden this way. Also drops it from the local known-gardens cache and, if it was active, falls back to this device's own default garden. */
    suspend fun leaveGarden(context: Context, gardenId: String): GardenMembershipResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val deviceId = getOrCreateInstallId(context)
            val memberToken = GardenMembershipStore.getMemberToken(context, gardenId)
                ?: return@withContext GardenMembershipResult.Failure("not_a_member")
            post("leaveGarden", JSONObject().apply {
                put("deviceId", deviceId); put("memberToken", memberToken); put("gardenId", gardenId)
            })
            GardenMembershipStore.setKnownGardens(context, GardenMembershipStore.getKnownGardens(context).filterNot { it.gardenId == gardenId })
            if (effectiveGardenId(context) == gardenId) GardenMembershipStore.setActiveGardenId(context, null)
            GardenMembershipResult.Success(Unit)
        } catch (e: Exception) {
            GardenMembershipResult.Failure(e.message ?: "unknown_error")
        }
    }

    /**
     * Wipes every local Room row scoped to [gardenId] — plants, care log, sun zones, irrigation
     * paths, water flow rates, and watering (irrigation-history) events, plus any extra/growth
     * photos belonging to one of this garden's plants (those two tables aren't gardenId-scoped
     * themselves, so plant ids are collected first, before the plants table is wiped). Does NOT
     * touch garden-scoped SharedPreferences settings (custom map, irrigation credentials, etc.) —
     * those are harmless left orphaned under a gardenId nothing references anymore. Used by
     * deleteGarden (never by "Reset garden", which deliberately keeps the garden itself intact).
     */
    private suspend fun deleteAllLocalDataForGarden(context: Context, gardenId: String) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        db.plantDao().deleteForGarden(gardenId)
        db.careLogDao().deleteForGarden(gardenId)
        db.sunZoneDao().deleteForGarden(gardenId)
        db.irrigationPathDao().deleteForGarden(gardenId)
        db.waterFlowRateDao().deleteForGarden(gardenId)
        db.wateringEventDao().deleteForGarden(gardenId)
        db.extraPhotoDao().deleteForGarden(gardenId)
        db.growthPhotoDao().deleteForGarden(gardenId)
        db.locationPhotoDao().deleteForGarden(gardenId)
        db.manualZoneScheduleDao().deleteForGarden(gardenId)
    }

    /**
     * Owner-only: permanently deletes a garden for every member (server-side data included),
     * distinct from [PlantViewModel.resetAll]'s "Reset garden" which only wipes local plants and
     * keeps the garden/sharing setup intact. Also cleans up this device's own local plants/care
     * log/etc. for the garden and, if it was active, falls back to this device's own default
     * garden — matching leaveGarden's local cleanup.
     */
    suspend fun deleteGarden(context: Context, gardenId: String): GardenMembershipResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val ownerDeviceId = getOrCreateInstallId(context)
            val ownerMemberToken = ensureMemberToken(context, gardenId)
                ?: return@withContext GardenMembershipResult.Failure("not_a_member")
            post("deleteGarden", JSONObject().apply {
                put("ownerDeviceId", ownerDeviceId); put("ownerMemberToken", ownerMemberToken); put("gardenId", gardenId)
            })
            deleteAllLocalDataForGarden(context, gardenId)
            GardenMembershipStore.setKnownGardens(context, GardenMembershipStore.getKnownGardens(context).filterNot { it.gardenId == gardenId })
            if (effectiveGardenId(context) == gardenId) GardenMembershipStore.setActiveGardenId(context, null)
            GardenMembershipResult.Success(Unit)
        } catch (e: Exception) {
            GardenMembershipResult.Failure(e.message ?: "unknown_error")
        }
    }

    suspend fun regenerateInviteCode(context: Context, gardenId: String): GardenMembershipResult<String> = withContext(Dispatchers.IO) {
        try {
            val ownerDeviceId = getOrCreateInstallId(context)
            val ownerMemberToken = ensureMemberToken(context, gardenId)
                ?: return@withContext GardenMembershipResult.Failure("not_a_member")
            val json = post("regenerateInviteCode", JSONObject().apply {
                put("ownerDeviceId", ownerDeviceId); put("ownerMemberToken", ownerMemberToken); put("gardenId", gardenId)
            })
            GardenMembershipResult.Success(json.getString("inviteCode"))
        } catch (e: Exception) {
            GardenMembershipResult.Failure(e.message ?: "unknown_error")
        }
    }

    /** Reads the garden's current invite code without changing it — use this whenever the Share dialog opens; only an explicit "Regenerate" action should call regenerateInviteCode, since that invalidates whatever's already been given out. */
    suspend fun getInviteCode(context: Context, gardenId: String): GardenMembershipResult<String?> = withContext(Dispatchers.IO) {
        try {
            val ownerDeviceId = getOrCreateInstallId(context)
            val ownerMemberToken = ensureMemberToken(context, gardenId)
                ?: return@withContext GardenMembershipResult.Failure("not_a_member")
            val url = "$BASE_URL/getInviteCode?ownerDeviceId=$ownerDeviceId&ownerMemberToken=$ownerMemberToken&gardenId=$gardenId"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: return@withContext GardenMembershipResult.Failure("empty_response")
                if (!response.isSuccessful) return@withContext GardenMembershipResult.Failure("server_error")
                val json = JSONObject(text)
                GardenMembershipResult.Success(if (json.isNull("inviteCode")) null else json.optString("inviteCode"))
            }
        } catch (e: Exception) {
            GardenMembershipResult.Failure(e.message ?: "unknown_error")
        }
    }

    /** Owner-only. Updates both the server's gardens/{gardenId}.name and this device's own known-gardens cache immediately, so the picker reflects it without waiting on a refreshKnownGardens round trip. */
    suspend fun renameGarden(context: Context, gardenId: String, newName: String): GardenMembershipResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val ownerDeviceId = getOrCreateInstallId(context)
            val ownerMemberToken = ensureMemberToken(context, gardenId)
                ?: return@withContext GardenMembershipResult.Failure("not_a_member")
            post("renameGarden", JSONObject().apply {
                put("ownerDeviceId", ownerDeviceId); put("ownerMemberToken", ownerMemberToken)
                put("gardenId", gardenId); put("name", newName)
            })
            val existing = GardenMembershipStore.getKnownGardens(context).firstOrNull { it.gardenId == gardenId }
            if (existing != null) GardenMembershipStore.upsertKnownGarden(context, existing.copy(name = newName))
            GardenMembershipResult.Success(Unit)
        } catch (e: Exception) {
            GardenMembershipResult.Failure(e.message ?: "unknown_error")
        }
    }

    /** Refreshes the local known-gardens/pending-requests cache from the server — call whenever the sharing UI is opened. */
    suspend fun refreshKnownGardens(context: Context): GardenMembershipResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val deviceId = getOrCreateInstallId(context)
            val request = Request.Builder().url("$BASE_URL/listMyGardens?deviceId=$deviceId").get().build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: return@withContext GardenMembershipResult.Failure("empty_response")
                if (!response.isSuccessful) return@withContext GardenMembershipResult.Failure("server_error")
                val json = JSONObject(text)

                val gardensArr = json.getJSONArray("gardens")
                val gardens = (0 until gardensArr.length()).map { i ->
                    val o = gardensArr.getJSONObject(i)
                    KnownGarden(o.getString("gardenId"), o.getString("name"), o.getString("role"), o.getString("permission"), o.getString("memberToken"))
                }
                GardenMembershipStore.setKnownGardens(context, gardens)

                val pendingArr = json.getJSONArray("pendingRequests")
                val pending = (0 until pendingArr.length()).map { i ->
                    val o = pendingArr.getJSONObject(i)
                    PendingJoinRequest(o.getString("gardenId"), o.getString("name"), o.getString("requestedPermission"), o.getLong("requestedAt"))
                }
                GardenMembershipStore.setPendingRequests(context, pending)

                GardenMembershipResult.Success(Unit)
            }
        } catch (e: Exception) {
            GardenMembershipResult.Failure(e.message ?: "unknown_error")
        }
    }
}
