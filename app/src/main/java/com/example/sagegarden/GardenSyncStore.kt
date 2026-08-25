package com.example.sagegarden

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SyncTombstone(val id: String, val deletedAt: Long)

private const val SYNC_PREFS = "garden_mapper_sync_prefs"
private const val KEY_PLANT_TOMBSTONES = "plant_tombstones"
private const val KEY_CARE_LOG_TOMBSTONES = "care_log_tombstones"
private const val KEY_LAST_SYNCED_AT = "garden_last_synced_at"

/**
 * Local record of "this id was deleted" for the phone/desktop sync feature (see GardenSyncClient).
 * A deleted plant/care-log row is just gone from Room, so without a separate tombstone list the
 * next sync couldn't tell "deleted" apart from "this device just hasn't seen it yet" — it would
 * silently resurrect the row from whatever the other device last had. The full tombstone list is
 * sent on every sync and then overwritten with the server's response, which is always a superset
 * (see gardenSync.ts's mergeCollection) — so this store is really just a local mirror of the
 * server's tombstone set, plus whatever's been deleted here since the last successful sync.
 */
object GardenSyncStore {
    private fun prefs(context: Context) = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)

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

    fun getPlantTombstones(context: Context): List<SyncTombstone> = getTombstones(context, KEY_PLANT_TOMBSTONES)
    fun setPlantTombstones(context: Context, tombstones: List<SyncTombstone>) = setTombstones(context, KEY_PLANT_TOMBSTONES, tombstones)
    fun recordPlantDeleted(context: Context, id: String) = addTombstone(context, KEY_PLANT_TOMBSTONES, id)

    fun getCareLogTombstones(context: Context): List<SyncTombstone> = getTombstones(context, KEY_CARE_LOG_TOMBSTONES)
    fun setCareLogTombstones(context: Context, tombstones: List<SyncTombstone>) = setTombstones(context, KEY_CARE_LOG_TOMBSTONES, tombstones)
    fun recordCareLogDeleted(context: Context, id: String) = addTombstone(context, KEY_CARE_LOG_TOMBSTONES, id)

    fun getLastSyncedAt(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNCED_AT, 0L)
    fun setLastSyncedAt(context: Context, millis: Long) = prefs(context).edit().putLong(KEY_LAST_SYNCED_AT, millis).apply()
}
