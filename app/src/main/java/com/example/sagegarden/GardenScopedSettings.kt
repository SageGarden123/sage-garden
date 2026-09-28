package com.example.sagegarden

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * Every per-garden setting (reminders, address/zones, weather/frost, irrigation, custom map), for
 * ONE explicitly named garden. Two gardens are two different physical properties, so each keeps its
 * own values, stored under "<baseKey>.<gardenId>".
 *
 * There is deliberately no way to read a garden setting without saying which garden: construct with
 * [of] for a specific garden (sync, background workers, widgets, deep links: anything that can run
 * for a garden other than the one on screen), or [active] when the caller really does mean "the
 * garden currently on screen". The previous free functions (getGardenAddress(context), etc.) resolved
 * the active garden implicitly, and forgetting that caused several real cross-garden leaks: a shared
 * garden's address overwriting your own during background sync, and reminders silenced by whichever
 * garden happened to be open.
 *
 * Legacy fallback: the device's OWN original garden (gardenId == installId) reads the pre-multi-garden
 * unscoped key when its scoped key has never been written, so upgrading from single-garden versions
 * was invisible. No other garden ever falls back, so a new or joined garden starts blank rather
 * than silently inheriting your own garden's values.
 *
 * Writes don't touch any UI state directly: [ActiveGardenSettingsObserver] watches these prefs and
 * refreshes the Compose-observable mirrors (HemisphereState, GardenAddressState) whenever a key for
 * the ACTIVE garden changes, so a write for any other garden can never repaint the screen.
 */
class GardenSettings private constructor(private val context: Context, val gardenId: String) {
    companion object {
        fun of(context: Context, gardenId: String) = GardenSettings(context.applicationContext, gardenId)
        /** The garden currently on screen — only for UI code acting on what the user is looking at. */
        fun active(context: Context) = of(context, effectiveGardenId(context))

        internal const val LOCATIONS_SEPARATOR = "\u001f"
    }

    private val prefs: SharedPreferences get() = gardenPrefs(context)
    private val credentials: SharedPreferences get() = credentialPrefs(context)
    private val canUseLegacyKey: Boolean get() = gardenId == getOrCreateInstallId(context)

    internal fun key(baseKey: String) = "$baseKey.$gardenId"

    private fun bool(baseKey: String, default: Boolean, p: SharedPreferences = prefs): Boolean = when {
        p.contains(key(baseKey)) -> p.getBoolean(key(baseKey), default)
        canUseLegacyKey -> p.getBoolean(baseKey, default)
        else -> default
    }
    private fun int(baseKey: String, default: Int): Int = when {
        prefs.contains(key(baseKey)) -> prefs.getInt(key(baseKey), default)
        canUseLegacyKey -> prefs.getInt(baseKey, default)
        else -> default
    }
    private fun float(baseKey: String, default: Float): Float = when {
        prefs.contains(key(baseKey)) -> prefs.getFloat(key(baseKey), default)
        canUseLegacyKey -> prefs.getFloat(baseKey, default)
        else -> default
    }
    private fun string(baseKey: String, default: String, p: SharedPreferences = prefs): String = when {
        p.contains(key(baseKey)) -> p.getString(key(baseKey), default) ?: default
        canUseLegacyKey -> p.getString(baseKey, default) ?: default
        else -> default
    }
    private fun nullableString(baseKey: String): String? = when {
        prefs.contains(key(baseKey)) -> prefs.getString(key(baseKey), null)
        canUseLegacyKey -> prefs.getString(baseKey, null)
        else -> null
    }
    private fun put(baseKey: String, value: Boolean) = prefs.edit().putBoolean(key(baseKey), value).apply()
    private fun put(baseKey: String, value: Int) = prefs.edit().putInt(key(baseKey), value).apply()
    private fun put(baseKey: String, value: Float) = prefs.edit().putFloat(key(baseKey), value).apply()
    private fun put(baseKey: String, value: String, p: SharedPreferences = prefs) = p.edit().putString(key(baseKey), value).apply()

    // ---- Reminders ---------------------------------------------------------------------------

    var notificationsEnabled: Boolean
        get() = bool("notifications_enabled", false)
        set(value) = put("notifications_enabled", value)

    /** "lockscreen", "popup", or "both". */
    var notificationStyle: String
        get() = string("notification_style", "lockscreen")
        set(value) = put("notification_style", value)

    /** "Days before due" offsets, e.g. {0, 2} = on the day AND 2 days before. */
    var notificationOffsets: Set<Int>
        get() = string("notification_offsets", "0").split(",").mapNotNull { it.trim().toIntOrNull() }.toSet().ifEmpty { setOf(0) }
        set(value) = put("notification_offsets", value.sorted().joinToString(","))

    var overdueRepeatEnabled: Boolean
        get() = bool("overdue_repeat_enabled", true)
        set(value) = put("overdue_repeat_enabled", value)

    var overdueRepeatDays: Int
        get() = int("overdue_repeat_days", 3)
        set(value) = put("overdue_repeat_days", value.coerceAtLeast(1))

    var fertiliseRemindersEnabled: Boolean
        get() = bool("fertilise_reminders_enabled", false)
        set(value) = put("fertilise_reminders_enabled", value)

    var pruneRemindersEnabled: Boolean
        get() = bool("prune_reminders_enabled", false)
        set(value) = put("prune_reminders_enabled", value)

    var feedRemindersEnabled: Boolean
        get() = bool("feed_reminders_enabled", false)
        set(value) = put("feed_reminders_enabled", value)

    /** Turning this on also stamps [progressPhotoRemindersEnabledAt] — see dueProgressPhotoZones. */
    var progressPhotoRemindersEnabled: Boolean
        get() = bool("progress_photo_reminders_enabled", false)
        set(value) {
            put("progress_photo_reminders_enabled", value)
            if (value) progressPhotoRemindersEnabledAt = System.currentTimeMillis()
        }

    /** When progress-photo reminders were last switched on — the baseline for a zone that has never had a photo. 0 = unknown (enabled before this was recorded). */
    var progressPhotoRemindersEnabledAt: Long
        get() = string("progress_photo_reminders_enabled_at", "").toLongOrNull() ?: 0L
        set(value) = put("progress_photo_reminders_enabled_at", value.toString())

    val notificationHour: Int get() = int("notification_hour", 8)
    val notificationMinute: Int get() = int("notification_minute", 0)
    fun setNotificationTime(hour: Int, minute: Int) {
        put("notification_hour", hour)
        put("notification_minute", minute)
    }

    // ---- Location, hemisphere, address, zones ------------------------------------------------

    /** Null until an address has been picked (or synced down from the owner). */
    val latLng: Pair<Double, Double>?
        get() {
            val lat = string("garden_lat", "").toDoubleOrNull()
            val lng = string("garden_lng", "").toDoubleOrNull()
            return if (lat != null && lng != null) lat to lng else null
        }
    fun setLatLng(lat: Double, lng: Double) {
        put("garden_lat", lat.toString())
        put("garden_lng", lng.toString())
    }

    /**
     * Derived from the garden's coordinates (latitude >= 0 is Northern) once an address is set —
     * this is what the seasonal-watering override means by "summer" vs "winter". Before that, falls
     * back to the manually chosen [hemisphereFallback] (default Southern, matching every install's
     * behaviour before the setting existed).
     */
    val hemisphere: Hemisphere
        get() = latLng?.let { (lat, _) -> if (lat >= 0) Hemisphere.NORTHERN else Hemisphere.SOUTHERN } ?: hemisphereFallback

    /** Manual choice, only used until the garden has an address — see [hemisphere]. */
    var hemisphereFallback: Hemisphere
        get() = if (string("garden_hemisphere", Hemisphere.SOUTHERN.name) == Hemisphere.NORTHERN.name) Hemisphere.NORTHERN else Hemisphere.SOUTHERN
        set(value) = put("garden_hemisphere", value.name)

    var address: String
        get() = string("garden_address", "")
        set(value) = put("garden_address", value)

    /** Named zones ("Front garden", …). Null means never set up (seed from plants — see [getOrSeedLocations]); empty means explicitly cleared. */
    var locations: List<String>?
        get() = nullableString("garden_locations")?.split(LOCATIONS_SEPARATOR)?.filter { it.isNotBlank() }
        set(value) = put("garden_locations", (value ?: emptyList()).joinToString(LOCATIONS_SEPARATOR))

    /**
     * The zone list, seeded from existing plants' distinct locations the first time anything asks.
     * Only the garden's OWNER persists that seed: a member's seed stays transient until the owner's
     * real list syncs down. Persisting it was a real bug — merely opening "Garden zones" before the
     * first sync committed a seed built from whatever plants had synced so far, which the next sync
     * then pushed up over the owner's real list.
     */
    fun getOrSeedLocations(plants: List<PlantEntity>): List<String> {
        locations?.let { return it }
        val seeded = plants.map { it.location }.filter { it.isNotBlank() }.distinct().sorted()
        if (isOwnerOfGarden(context, gardenId)) locations = seeded
        return seeded
    }

    /**
     * Where the user last left the real map's camera, so reopening the Map tab doesn't reset it.
     * Treats the old hardcoded fallback coordinate as "nothing saved": an old bug persisted the map's
     * initial fallback position before the real address/auto-fit could apply, permanently shadowing
     * them, and a genuine pan landing exactly there is practically impossible.
     */
    val mapCameraPosition: Triple<Double, Double, Float>?
        get() {
            val lat = string("map_camera_lat", "").toDoubleOrNull()
            val lng = string("map_camera_lng", "").toDoubleOrNull()
            val zoom = float("map_camera_zoom", -1f).takeIf { it > 0f }
            if (lat == null || lng == null || zoom == null) return null
            if (lat == MAP_FALLBACK_LAT && lng == MAP_FALLBACK_LNG) return null
            return Triple(lat, lng, zoom)
        }
    fun setMapCameraPosition(lat: Double, lng: Double, zoom: Float) {
        put("map_camera_lat", lat.toString())
        put("map_camera_lng", lng.toString())
        put("map_camera_zoom", zoom)
    }

    // ---- Weather & frost ----------------------------------------------------------------------

    var weatherSkipEnabled: Boolean
        get() = bool("weather_skip_enabled", false)
        set(value) = put("weather_skip_enabled", value)

    var rainProbabilityThreshold: Int
        get() = int("rain_probability_threshold", 60)
        set(value) = put("rain_probability_threshold", value)

    /** Minimum forecast rainfall (mm) before a reminder is flagged — filters out high-probability drizzle. */
    var rainAmountThresholdMm: Float
        get() = float("rain_amount_threshold_mm", 1.0f)
        set(value) = put("rain_amount_threshold_mm", value)

    var frostWarningsEnabled: Boolean
        get() = bool("frost_warnings_enabled", true)
        set(value) = put("frost_warnings_enabled", value)

    var frostTempThreshold: Double
        get() = float("frost_temp_threshold", 2.0f).toDouble()
        set(value) = put("frost_temp_threshold", value.toFloat())

    // ---- Irrigation (device-local: credentials are never synced or backed up) ----------------

    /** Defaults to TUYA when this garden already has Tuya credentials saved (so pre-existing testers saw no change), else NONE. */
    var irrigationSystem: IrrigationSystem
        get() {
            val stored = string("irrigation_system", "")
            if (stored.isNotBlank()) return IrrigationSystem.entries.firstOrNull { it.name == stored } ?: IrrigationSystem.NONE
            return if (tuyaClientId.isNotBlank() && tuyaClientSecret.isNotBlank()) IrrigationSystem.TUYA else IrrigationSystem.NONE
        }
        set(value) = put("irrigation_system", value.name)

    /** Stored as "zoneA=deviceId:outlet|zoneB=deviceId:outlet|…" — a zone is ONE outlet on ONE Tuya device. */
    var tuyaZoneMappings: List<TuyaZoneMapping>
        get() = string("tuya_device_mapping", "").split("|").filter { it.contains("=") }.mapNotNull { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val (zone, rest) = parts
            val restParts = rest.split(":")
            val deviceId = restParts.getOrNull(0) ?: return@mapNotNull null
            val outlet = restParts.getOrNull(1) ?: "1"
            if (zone.isBlank() || deviceId.isBlank()) null else TuyaZoneMapping(zone, deviceId, outlet)
        }
        // Deliberately doesn't refresh TuyaZoneMappingState: the zone editor saves through here and
        // would otherwise reset its own in-progress rows. Only a restore or a garden switch refreshes it.
        set(value) = put("tuya_device_mapping", value.joinToString("|") { "${it.zone}=${it.deviceId}:${it.outlet}" })

    var tuyaClientId: String
        get() { migrateCredential(context, "tuya_client_id"); return string("tuya_client_id", "", credentials) }
        set(value) = put("tuya_client_id", value, credentials)

    var tuyaClientSecret: String
        get() { migrateCredential(context, "tuya_client_secret"); return string("tuya_client_secret", "", credentials) }
        set(value) = put("tuya_client_secret", value, credentials)

    /** Stored as "zoneA=deviceId:zoneId|…" — Rachio zones carry their own vendor id. */
    var rachioZoneMappings: List<RachioZoneMapping>
        get() = string("rachio_device_mapping", "").split("|").filter { it.contains("=") }.mapNotNull { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val (zone, rest) = parts
            val restParts = rest.split(":")
            val deviceId = restParts.getOrNull(0) ?: return@mapNotNull null
            val zoneId = restParts.getOrNull(1) ?: ""
            if (zone.isBlank() || deviceId.isBlank() || zoneId.isBlank()) null else RachioZoneMapping(zone, deviceId, zoneId)
        }
        set(value) = put("rachio_device_mapping", value.joinToString("|") { "${it.zone}=${it.deviceId}:${it.zoneId}" })

    var rachioApiToken: String
        get() = string("rachio_api_token", "", credentials)
        set(value) = put("rachio_api_token", value, credentials)

    /** When watering history was last pulled from Tuya/Rachio with no zone failing (manual or automatic) — 0 if never. */
    var lastIrrigationSyncAt: Long
        get() = string("irrigation_last_sync_at", "").toLongOrNull() ?: 0L
        set(value) = put("irrigation_last_sync_at", value.toString())

    // ---- Custom (hand-drawn) map --------------------------------------------------------------

    var customMapUri: Uri?
        get() = string("custom_map_uri", "").takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        set(value) = put("custom_map_uri", value?.toString() ?: "")

    var usingCustomMap: Boolean
        get() = bool("use_custom_map", false)
        set(value) = put("use_custom_map", value)

    var customMapRotation: Int
        get() = int("custom_map_rotation", 0)
        set(value) = put("custom_map_rotation", ((value % 360) + 360) % 360)
}

/**
 * Keeps the Compose-observable mirrors of the ACTIVE garden's settings (HemisphereState,
 * GardenAddressState) in step with prefs automatically. They used to be assigned by hand inside
 * each setter, and every forgotten or wrongly-scoped assignment was either a "doesn't update until
 * I reopen the screen" bug or a cross-garden leak. Now any write for the active garden (local edit,
 * sync, restore) refreshes them, and a write for any other garden is ignored.
 */
object ActiveGardenSettingsObserver {
    // SharedPreferences holds listeners weakly — this strong reference keeps it alive.
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun install(context: Context) {
        if (listener != null) return
        val appContext = context.applicationContext
        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            val activeId = effectiveGardenId(appContext)
            if (key == null || key.endsWith(".$activeId") || !key.contains('.')) refresh(appContext)
        }
        gardenPrefs(appContext).registerOnSharedPreferenceChangeListener(listener)
        refresh(appContext)
    }

    /** Also called directly on a garden switch. */
    fun refresh(context: Context) {
        val settings = GardenSettings.active(context)
        HemisphereState.value = settings.hemisphere
        GardenAddressState.address = settings.address
        GardenAddressState.latLng = settings.latLng
        GardenAddressState.locations = settings.locations
    }
}

/** True when ANY garden has reminders on — there's one shared daily alarm and the worker checks every garden, so it must stay armed while any of them wants it. */
fun anyGardenNotificationsEnabled(context: Context): Boolean =
    allKnownGardenIds(context).any { GardenSettings.of(context, it).notificationsEnabled }
