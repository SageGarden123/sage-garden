import org.json.JSONObject
import java.io.File

/**
 * Small local settings file (separate from the garden data files, since this is desktop-only
 * config) holding this install's identity, the gardens it belongs to and their member tokens, which
 * garden is open, and the change counters live updates compare against.
 */
object GardenSyncSettings {
    private fun file(): File = File(System.getProperty("user.home"), "SageGardenDesktop/sync_settings.json")

    @Synchronized private fun read(): JSONObject {
        val f = file()
        if (!f.exists()) return JSONObject()
        return runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    }

    @Synchronized private fun write(json: JSONObject) {
        val f = file()
        f.parentFile?.mkdirs()
        f.writeText(json.toString(2))
    }

    private fun update(block: (JSONObject) -> Unit) = write(read().also(block))
    private fun map(key: String): JSONObject = read().optJSONObject(key) ?: JSONObject()

    /** The phone Install ID this desktop was linked to — that phone's own garden. */
    fun getLinkedDeviceId(): String? = read().optString("linkedDeviceId", "").ifBlank { null }
    fun setLinkedDeviceId(deviceId: String) = update { it.put("linkedDeviceId", deviceId.trim()) }

    /**
     * This desktop install's OWN identity. It used to sync AS the linked phone (the phone's Install
     * ID as its device id, with no token), which the server treated as the phone having lost its
     * token — so each desktop sync silently issued the phone a new one and broke the phone's
     * owner-only actions until the phone next synced. Now the desktop is its own garden member.
     */
    fun getOwnDeviceId(): String {
        read().optString("ownDeviceId", "").takeIf { it.isNotBlank() }?.let { return it }
        val id = "desktop-" + java.util.UUID.randomUUID().toString()
        update { it.put("ownDeviceId", id) }
        return id
    }

    /** Member tokens per garden. Migrates the single token older versions kept for the linked garden. */
    fun getMemberToken(gardenId: String): String? {
        val json = read()
        json.optString("memberToken", "").takeIf { it.isNotBlank() }?.let { legacy ->
            val linked = json.optString("linkedDeviceId", "")
            json.remove("memberToken")
            if (linked.isNotBlank()) json.put("memberTokens", (json.optJSONObject("memberTokens") ?: JSONObject()).put(linked, legacy))
            write(json)
        }
        return map("memberTokens").optString(gardenId, "").ifBlank { null }
    }
    fun setMemberToken(gardenId: String, token: String?) = update {
        val tokens = it.optJSONObject("memberTokens") ?: JSONObject()
        if (token == null) tokens.remove(gardenId) else tokens.put(gardenId, token)
        it.put("memberTokens", tokens)
    }

    /** Which garden the desktop shows; null means the linked phone's garden. */
    fun getActiveGardenId(): String? = read().optString("activeGardenId", "").ifBlank { null }
    fun setActiveGardenId(gardenId: String?) = update { if (gardenId == null) it.remove("activeGardenId") else it.put("activeGardenId", gardenId) }

    fun getGardenName(gardenId: String): String? = map("gardenNames").optString(gardenId, "").ifBlank { null }
    fun setGardenNames(names: Map<String, String>) = update { json ->
        val m = json.optJSONObject("gardenNames") ?: JSONObject()
        names.forEach { (id, name) -> m.put(id, name) }
        json.put("gardenNames", m)
    }

    /** Change counters (see Cloud.gardenSignal) last acted on, per garden. */
    fun getSeenRev(gardenId: String, kind: String): Long = map("seenRevs").optJSONObject(gardenId)?.optLong(kind, -1L) ?: -1L
    fun setSeenRev(gardenId: String, kind: String, rev: Long) = update { json ->
        val all = json.optJSONObject("seenRevs") ?: JSONObject()
        all.put(gardenId, (all.optJSONObject(gardenId) ?: JSONObject()).put(kind, rev))
        json.put("seenRevs", all)
    }

    fun getLastSyncedAt(): Long = read().optLong("lastSyncedAt", 0L)
    fun setLastSyncedAt(millis: Long) = update { it.put("lastSyncedAt", millis) }

    fun getLastAutoBackupAt(): Long = read().optLong("lastAutoBackupAt", 0L)
    fun setLastAutoBackupAt(millis: Long) = update { it.put("lastAutoBackupAt", millis) }
}
