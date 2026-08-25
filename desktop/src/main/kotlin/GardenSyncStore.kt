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
        json.put("linkedDeviceId", deviceId)
        write(json)
    }

    fun getLastSyncedAt(): Long = read().optLong("lastSyncedAt", 0L)

    fun setLastSyncedAt(millis: Long) {
        val json = read()
        json.put("lastSyncedAt", millis)
        write(json)
    }
}
