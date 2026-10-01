package com.example.sagegarden

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SyncTombstone(val id: String, val deletedAt: Long)

private const val SYNC_PREFS = "garden_mapper_sync_prefs"
private const val KEY_PLANT_TOMBSTONES = "plant_tombstones"
private const val KEY_CARE_LOG_TOMBSTONES = "care_log_tombstones"
private const val KEY_PHOTO_TOMBSTONES = "photo_tombstones"
private const val KEY_LAST_SYNCED_AT = "garden_last_synced_at"

/**
 * Local record of "this id was deleted" for the phone/desktop sync feature (see GardenSyncClient).
 * A deleted plant/care-log row is just gone from Room, so without a separate tombstone list the
 * next sync couldn't tell "deleted" apart from "this device just hasn't seen it yet" — it would
 * silently resurrect the row from whatever the other device last had. The full tombstone list is
 * sent on every sync and then overwritten with the server's response, which is always a superset
 * (see gardenSync.ts's mergeCollection) — so this store is really just a local mirror of the
 * server's tombstone set, plus whatever's been deleted here since the last successful sync.
 *
 * Every key here is suffixed by gardenId — a device with access to more than one garden (multi-
 * garden sharing) must not let one garden's tombstone set bleed into another's: syncing garden B
 * after garden A would otherwise send A's tombstones as if they belonged to B, capable of
 * incorrectly deleting a genuinely-still-there plant/care-log row in B if an id ever happened to
 * collide, and vice versa when overwriting the local cache with the server's response afterwards.
 */
object GardenSyncStore {
    private fun prefs(context: Context) = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
    private fun scopedKey(baseKey: String, gardenId: String) = "$baseKey.$gardenId"

    private fun getTombstones(context: Context, key: String): List<SyncTombstone> {
        val raw = prefs(context).getString(key, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SyncTombstone(o.getString("id"), o.getLong("deletedAt"))
        }
    }

    private fun setTombstones(context: Context, key: String, tombstones: List<SyncTombstone>) {
        val arr = JSONArray()
        tombstones.forEach { arr.put(JSONObject().put("id", it.id).put("deletedAt", it.deletedAt)) }
        prefs(context).edit().putString(key, arr.toString()).apply()
    }

    private fun addTombstone(context: Context, key: String, id: String) {
        val existing = getTombstones(context, key).associateBy { it.id }.toMutableMap()
        val now = System.currentTimeMillis()
        val current = existing[id]
        existing[id] = SyncTombstone(id, maxOf(current?.deletedAt ?: 0L, now))
        setTombstones(context, key, existing.values.toList())
    }

    fun getPlantTombstones(context: Context, gardenId: String): List<SyncTombstone> =
        getTombstones(context, scopedKey(KEY_PLANT_TOMBSTONES, gardenId))
    fun setPlantTombstones(context: Context, gardenId: String, tombstones: List<SyncTombstone>) =
        setTombstones(context, scopedKey(KEY_PLANT_TOMBSTONES, gardenId), tombstones)
    /** [gardenId] is the plant's own gardenId (i.e. whichever garden was active when it was deleted), not necessarily whatever's active right now. */
    fun recordPlantDeleted(context: Context, gardenId: String, id: String) =
        addTombstone(context, scopedKey(KEY_PLANT_TOMBSTONES, gardenId), id)

    fun getCareLogTombstones(context: Context, gardenId: String): List<SyncTombstone> =
        getTombstones(context, scopedKey(KEY_CARE_LOG_TOMBSTONES, gardenId))
    fun setCareLogTombstones(context: Context, gardenId: String, tombstones: List<SyncTombstone>) =
        setTombstones(context, scopedKey(KEY_CARE_LOG_TOMBSTONES, gardenId), tombstones)
    fun recordCareLogDeleted(context: Context, gardenId: String, id: String) =
        addTombstone(context, scopedKey(KEY_CARE_LOG_TOMBSTONES, gardenId), id)

    /** One shared set for all three photo tables (extra / progress / growth) — they sync as one
     * "photos" collection, and their ids are UUID-based so they can't collide across kinds. */
    fun getPhotoTombstones(context: Context, gardenId: String): List<SyncTombstone> =
        getTombstones(context, scopedKey(KEY_PHOTO_TOMBSTONES, gardenId))
    fun setPhotoTombstones(context: Context, gardenId: String, tombstones: List<SyncTombstone>) =
        setTombstones(context, scopedKey(KEY_PHOTO_TOMBSTONES, gardenId), tombstones)
    fun recordPhotoDeleted(context: Context, gardenId: String, id: String) =
        addTombstone(context, scopedKey(KEY_PHOTO_TOMBSTONES, gardenId), id)

    /** The gardenSignals rev this device's local copy of [gardenId] is current as of — see RealtimeGardenSync. */
    fun getSignalRev(context: Context, gardenId: String): Long = prefs(context).getLong(scopedKey("signal_rev", gardenId), -1L)
    fun setSignalRev(context: Context, gardenId: String, rev: Long) = prefs(context).edit().putLong(scopedKey("signal_rev", gardenId), rev).apply()
    /** Whether the server has confirmed this device's Firebase uid may listen to [gardenId]'s change signal. */
    fun isSignalGranted(context: Context, gardenId: String): Boolean = prefs(context).getBoolean(scopedKey("signal_granted", gardenId), false)
    fun setSignalGranted(context: Context, gardenId: String, granted: Boolean) = prefs(context).edit().putBoolean(scopedKey("signal_granted", gardenId), granted).apply()
    fun getMembershipRev(context: Context, gardenId: String): Long = prefs(context).getLong(scopedKey("membership_rev", gardenId), -1L)
    fun setMembershipRev(context: Context, gardenId: String, rev: Long) = prefs(context).edit().putLong(scopedKey("membership_rev", gardenId), rev).apply()

    fun getLastSyncedAt(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNCED_AT, 0L)
    fun setLastSyncedAt(context: Context, millis: Long) = prefs(context).edit().putLong(KEY_LAST_SYNCED_AT, millis).apply()
}
