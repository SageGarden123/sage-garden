import org.json.JSONObject
import java.io.File

/**
 * Small local settings file (separate from garden_data.json, since this is desktop-only config,
 * not garden data that should round-trip through a phone backup) holding which device this
 * desktop install is linked to for syncing, and when it last synced successfully.
 */
object GardenSyncSettings {
    private fun file(): File = File(System.getProperty("user.home"), "SageGardenDesktop/sync_settings.json")

    private fun read(): JSONObject {
        val f = file()
        if (!f.exists()) return JSONObject()
        return runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    }

    private fun write(json: JSONObject) {
        val f = file()
        f.parentFile?.mkdirs()
        f.writeText(json.toString(2))
    }

    fun getLinkedDeviceId(): String? = read().optString("linkedDeviceId", "").ifBlank { null }

    fun setLinkedDeviceId(deviceId: String) {
        val json = read()
        if (json.optString("linkedDeviceId", "") != deviceId) json.remove("memberToken") // new garden, new membership
        json.put("linkedDeviceId", deviceId)
        write(json)
    }

    /**
     * This desktop install's OWN identity. It used to sync AS the linked phone (the phone's Install
     * ID as its device id, with no token), which the server treated as the phone having lost its
     * token — so each desktop sync silently issued the phone a new one and broke the phone's
     * owner-only actions (invites, managing access) until the phone next synced. Now the desktop
     * joins the phone's garden as its own member with its own token.
     */
    fun getOwnDeviceId(): String {
        val json = read()
        json.optString("ownDeviceId", "").takeIf { it.isNotBlank() }?.let { return it }
        val id = "desktop-" + java.util.UUID.randomUUID().toString()
        json.put("ownDeviceId", id)
        write(json)
        return id
    }

    fun getMemberToken(): String? = read().optString("memberToken", "").ifBlank { null }

    fun setMemberToken(token: String?) {
        val json = read()
        if (token == null) json.remove("memberToken") else json.put("memberToken", token)
        write(json)
    }

    fun getLastSyncedAt(): Long = read().optLong("lastSyncedAt", 0L)

    fun setLastSyncedAt(millis: Long) {
        val json = read()
        json.put("lastSyncedAt", millis)
        write(json)
    }

    fun getLastAutoBackupAt(): Long = read().optLong("lastAutoBackupAt", 0L)

    fun setLastAutoBackupAt(millis: Long) {
        val json = read()
        json.put("lastAutoBackupAt", millis)
        write(json)
    }
}
