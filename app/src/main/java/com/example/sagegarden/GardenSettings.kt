@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.google.maps.android.compose.*
import kotlinx.coroutines.delay

// ============================================================================
// NOTIFICATIONS/REMINDERS
// ============================================================================

internal fun gardenPrefs(context: Context) = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)

/**
 * Per-garden settings (hemisphere, plant notifications, weather-aware reminders, garden
 * address/zones, custom map, irrigation setup) are stored under a key suffixed by the active
 * garden's id, so switching gardens in Help's picker swaps out an entirely different set of
 * values. The FIRST read for the device's OWN ORIGINAL default garden (gardenId == installId)
 * falls back to the legacy unscoped key — this is what makes the switch from single-garden to
 * multi-garden invisible to existing users: their current settings simply become their default
 * garden's settings, with no explicit migration step.
 *
 * That fallback deliberately does NOT extend to any other garden (one you just created, or one
 * you joined) — those never had a "legacy" value to inherit, so a not-yet-configured setting on a
 * brand-new garden must show its own plain default (blank/off), not silently reuse whatever your
 * default garden happens to have. Skipping this distinction was a real bug: a freshly created or
 * joined garden showed the owner's existing custom map image and Tuya/Rachio credentials/zones
 * before either had ever been set for it, which looked exactly like cross-garden data leakage even
 * though nothing had actually been written to the new garden's own key yet.
 */
/** [gardenIdOverride] lets a caller read/write a SPECIFIC garden's setting regardless of which garden is currently active — used by WateringReminderWorker/widgets to apply each garden's own settings to that garden's plants, without touching the live ActiveGardenState singleton (which would risk a visible flicker if the UI happened to be open at the same moment a background worker runs). */
internal fun gardenScopedKey(context: Context, baseKey: String, gardenIdOverride: String? = null) = "$baseKey.${gardenIdOverride ?: effectiveGardenId(context)}"

internal fun canFallBackToLegacyKey(context: Context, gardenIdOverride: String? = null) = (gardenIdOverride ?: effectiveGardenId(context)) == getOrCreateInstallId(context)

internal fun gardenScopedBoolean(context: Context, baseKey: String, default: Boolean, prefs: android.content.SharedPreferences = gardenPrefs(context), gardenIdOverride: String? = null): Boolean {
    val scopedKey = gardenScopedKey(context, baseKey, gardenIdOverride)
    if (prefs.contains(scopedKey)) return prefs.getBoolean(scopedKey, default)
    return if (canFallBackToLegacyKey(context, gardenIdOverride)) prefs.getBoolean(baseKey, default) else default
}
internal fun setGardenScopedBoolean(context: Context, baseKey: String, value: Boolean, prefs: android.content.SharedPreferences = gardenPrefs(context), gardenIdOverride: String? = null) {
    prefs.edit().putBoolean(gardenScopedKey(context, baseKey, gardenIdOverride), value).apply()
}
internal fun gardenScopedInt(context: Context, baseKey: String, default: Int, prefs: android.content.SharedPreferences = gardenPrefs(context), gardenIdOverride: String? = null): Int {
    val scopedKey = gardenScopedKey(context, baseKey, gardenIdOverride)
    if (prefs.contains(scopedKey)) return prefs.getInt(scopedKey, default)
    return if (canFallBackToLegacyKey(context, gardenIdOverride)) prefs.getInt(baseKey, default) else default
}
internal fun setGardenScopedInt(context: Context, baseKey: String, value: Int, prefs: android.content.SharedPreferences = gardenPrefs(context), gardenIdOverride: String? = null) {
    prefs.edit().putInt(gardenScopedKey(context, baseKey, gardenIdOverride), value).apply()
}
internal fun gardenScopedFloat(context: Context, baseKey: String, default: Float, prefs: android.content.SharedPreferences = gardenPrefs(context), gardenIdOverride: String? = null): Float {
    val scopedKey = gardenScopedKey(context, baseKey, gardenIdOverride)
    if (prefs.contains(scopedKey)) return prefs.getFloat(scopedKey, default)
    return if (canFallBackToLegacyKey(context, gardenIdOverride)) prefs.getFloat(baseKey, default) else default
}
internal fun setGardenScopedFloat(context: Context, baseKey: String, value: Float, prefs: android.content.SharedPreferences = gardenPrefs(context), gardenIdOverride: String? = null) {
    prefs.edit().putFloat(gardenScopedKey(context, baseKey, gardenIdOverride), value).apply()
}
/** [prefs] lets a scoped setting live in a different backing file than "garden_mapper_prefs" (e.g. credentialPrefs for Tuya/Rachio secrets) while still keying off the same active-garden id. */
internal fun gardenScopedString(context: Context, baseKey: String, default: String, prefs: android.content.SharedPreferences = gardenPrefs(context), gardenIdOverride: String? = null): String {
    val scopedKey = gardenScopedKey(context, baseKey, gardenIdOverride)
    if (prefs.contains(scopedKey)) return prefs.getString(scopedKey, default) ?: default
    return if (canFallBackToLegacyKey(context, gardenIdOverride)) (prefs.getString(baseKey, default) ?: default) else default
}
internal fun setGardenScopedString(context: Context, baseKey: String, value: String, prefs: android.content.SharedPreferences = gardenPrefs(context), gardenIdOverride: String? = null) {
    prefs.edit().putString(gardenScopedKey(context, baseKey, gardenIdOverride), value).apply()
}

/**
 * Auto-detected from the garden's coordinates (latitude >= 0 is Northern) whenever an address has
 * been set, rather than a separate manual toggle the user has to remember to keep in sync with
 * where their garden actually is — this is what drives the seasonal-watering override's idea of
 * "summer" vs "winter". Falls back to the old manually-stored value (default Southern, matching
 * every install's behaviour before this setting existed) only when no garden address/coordinates
 * have been configured yet, so a garden with no address still has SOME sensible current behaviour.
 */
fun getHemisphere(context: Context): Hemisphere {
    getGardenLatLng(context)?.let { (lat, _) -> return if (lat >= 0) Hemisphere.NORTHERN else Hemisphere.SOUTHERN }
    val raw = gardenScopedString(context, "garden_hemisphere", Hemisphere.SOUTHERN.name)
    return if (raw == Hemisphere.NORTHERN.name) Hemisphere.NORTHERN else Hemisphere.SOUTHERN
}
/** Manual fallback only — once a garden address is set, getHemisphere() derives from its coordinates instead and ignores this. Kept so a garden with no address yet still has a settable default. */
fun setHemisphere(context: Context, value: Hemisphere) {
    setGardenScopedString(context, "garden_hemisphere", value.name)
    HemisphereState.value = getHemisphere(context)
}
/** Reads a SPECIFIC garden's hemisphere regardless of which garden is currently active — see gardenScopedKey's gardenIdOverride. Used by WateringReminderWorker to apply each garden's own hemisphere to that garden's plants when checking due dates across every garden this device has access to, not just the active one. */
fun getHemisphereFor(context: Context, gardenId: String): Hemisphere {
    getGardenLatLngFor(context, gardenId)?.let { (lat, _) -> return if (lat >= 0) Hemisphere.NORTHERN else Hemisphere.SOUTHERN }
    val raw = gardenScopedString(context, "garden_hemisphere", Hemisphere.SOUTHERN.name, gardenIdOverride = gardenId)
    return if (raw == Hemisphere.NORTHERN.name) Hemisphere.NORTHERN else Hemisphere.SOUTHERN
}

fun getNotificationsEnabled(context: Context): Boolean = gardenScopedBoolean(context, "notifications_enabled", false)
fun setNotificationsEnabled(context: Context, value: Boolean) = setGardenScopedBoolean(context, "notifications_enabled", value)
fun getNotificationsEnabledFor(context: Context, gardenId: String): Boolean =
    gardenScopedBoolean(context, "notifications_enabled", false, gardenIdOverride = gardenId)
/**
 * Whether the shared daily reminder alarm should be armed at all. There is only ONE alarm, and
 * WateringReminderWorker checks every known garden each time it fires — so gating the alarm on the
 * ACTIVE garden's toggle alone (as BootReceiver/WateringReminderReceiver/app start used to) meant
 * having a shared garden with reminders off on screen silently stopped your own garden's reminders
 * too (the receiver skipped both the check and tomorrow's re-arm), and switching reminders off for
 * one garden cancelled them for all of them.
 */
fun anyGardenNotificationsEnabled(context: Context): Boolean =
    allKnownGardenIds(context).any { getNotificationsEnabledFor(context, it) }

/** "lockscreen", "popup", or "both" */
fun getNotificationStyle(context: Context): String = gardenScopedString(context, "notification_style", "lockscreen")
fun setNotificationStyle(context: Context, value: String) = setGardenScopedString(context, "notification_style", value)

/** Comma-separated "days before due" offsets, e.g. "0,2" = day-of AND 2 days before */
fun getNotificationOffsets(context: Context): Set<Int> {
    val raw = gardenScopedString(context, "notification_offsets", "0")
    return raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet().ifEmpty { setOf(0) }
}
fun setNotificationOffsets(context: Context, offsets: Set<Int>) =
    setGardenScopedString(context, "notification_offsets", offsets.sorted().joinToString(","))
fun getNotificationOffsetsFor(context: Context, gardenId: String): Set<Int> {
    val raw = gardenScopedString(context, "notification_offsets", "0", gardenIdOverride = gardenId)
    return raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet().ifEmpty { setOf(0) }
}

fun getOverdueRepeatEnabled(context: Context): Boolean = gardenScopedBoolean(context, "overdue_repeat_enabled", true)
fun setOverdueRepeatEnabled(context: Context, value: Boolean) = setGardenScopedBoolean(context, "overdue_repeat_enabled", value)
fun getOverdueRepeatEnabledFor(context: Context, gardenId: String): Boolean =
    gardenScopedBoolean(context, "overdue_repeat_enabled", true, gardenIdOverride = gardenId)
fun getOverdueRepeatDays(context: Context): Int = gardenScopedInt(context, "overdue_repeat_days", 3)
fun setOverdueRepeatDays(context: Context, value: Int) = setGardenScopedInt(context, "overdue_repeat_days", value.coerceAtLeast(1))
fun getOverdueRepeatDaysFor(context: Context, gardenId: String): Int =
    gardenScopedInt(context, "overdue_repeat_days", 3, gardenIdOverride = gardenId)

fun getFertiliseRemindersEnabled(context: Context): Boolean = gardenScopedBoolean(context, "fertilise_reminders_enabled", false)
fun setFertiliseRemindersEnabled(context: Context, value: Boolean) = setGardenScopedBoolean(context, "fertilise_reminders_enabled", value)
fun getFertiliseRemindersEnabledFor(context: Context, gardenId: String): Boolean =
    gardenScopedBoolean(context, "fertilise_reminders_enabled", false, gardenIdOverride = gardenId)
fun getPruneRemindersEnabled(context: Context): Boolean = gardenScopedBoolean(context, "prune_reminders_enabled", false)
fun setPruneRemindersEnabled(context: Context, value: Boolean) = setGardenScopedBoolean(context, "prune_reminders_enabled", value)
fun getPruneRemindersEnabledFor(context: Context, gardenId: String): Boolean =
    gardenScopedBoolean(context, "prune_reminders_enabled", false, gardenIdOverride = gardenId)
fun getFeedRemindersEnabled(context: Context): Boolean = gardenScopedBoolean(context, "feed_reminders_enabled", false)
fun setFeedRemindersEnabled(context: Context, value: Boolean) = setGardenScopedBoolean(context, "feed_reminders_enabled", value)
fun getFeedRemindersEnabledFor(context: Context, gardenId: String): Boolean =
    gardenScopedBoolean(context, "feed_reminders_enabled", false, gardenIdOverride = gardenId)
fun getProgressPhotoRemindersEnabled(context: Context): Boolean = gardenScopedBoolean(context, "progress_photo_reminders_enabled", false)
fun setProgressPhotoRemindersEnabled(context: Context, value: Boolean) = setGardenScopedBoolean(context, "progress_photo_reminders_enabled", value)
fun getProgressPhotoRemindersEnabledFor(context: Context, gardenId: String): Boolean =
    gardenScopedBoolean(context, "progress_photo_reminders_enabled", false, gardenIdOverride = gardenId)

/** How often a zone should get a fresh progress photo before it's flagged as due — fixed at 3
 * months (approximated as 90 days, consistent with every other day-based frequency in this app)
 * rather than a per-garden setting, since there's no obvious reason different gardens would want
 * a different cadence for this the way they legitimately do for watering. */
const val PROGRESS_PHOTO_REMINDER_DAYS = 90L

fun getNotificationHour(context: Context): Int = gardenScopedInt(context, "notification_hour", 8)
fun getNotificationMinute(context: Context): Int = gardenScopedInt(context, "notification_minute", 0)
fun setNotificationTime(context: Context, hour: Int, minute: Int) {
    setGardenScopedInt(context, "notification_hour", hour)
    setGardenScopedInt(context, "notification_minute", minute)
}

/** Next occurrence (today if still ahead, else tomorrow) of the saved notification time. */
fun nextWateringAlarmTarget(context: Context): Long {
    val target = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, getNotificationHour(context))
        set(java.util.Calendar.MINUTE, getNotificationMinute(context))
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        if (before(java.util.Calendar.getInstance())) add(java.util.Calendar.DAY_OF_YEAR, 1)
    }
    return target.timeInMillis
}

internal fun wateringAlarmPendingIntent(context: Context): android.app.PendingIntent {
    val intent = android.content.Intent(context, WateringReminderReceiver::class.java)
    return android.app.PendingIntent.getBroadcast(
        context, 2001, intent,
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * Uses an exact AlarmManager alarm rather than a periodic WorkManager job — periodic work's
 * initial delay is only a lower bound and the OS can defer it well past the requested time
 * (especially under Doze/App Standby), so reminders would silently miss the configured time.
 * The receiver re-arms the next day's alarm each time it fires, and BootReceiver re-arms it
 * after a reboot since exact alarms don't survive a restart.
 */
fun scheduleWateringReminders(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    val pendingIntent = wateringAlarmPendingIntent(context)
    val targetMillis = nextWateringAlarmTarget(context)
    val canScheduleExact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    if (canScheduleExact) {
        alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, targetMillis, pendingIntent)
    } else {
        alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, targetMillis, pendingIntent)
    }
}
fun cancelWateringReminders(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    alarmManager.cancel(wateringAlarmPendingIntent(context))
}

// ============================================================================
// WEATHER-AWARE REMINDER SKIPPING
// ============================================================================

fun getGardenLatLng(context: Context): Pair<Double, Double>? {
    val lat = gardenScopedString(context, "garden_lat", "").toDoubleOrNull()
    val lng = gardenScopedString(context, "garden_lng", "").toDoubleOrNull()
    return if (lat != null && lng != null) lat to lng else null
}
fun getGardenLatLngFor(context: Context, gardenId: String): Pair<Double, Double>? {
    val lat = gardenScopedString(context, "garden_lat", "", gardenIdOverride = gardenId).toDoubleOrNull()
    val lng = gardenScopedString(context, "garden_lng", "", gardenIdOverride = gardenId).toDoubleOrNull()
    return if (lat != null && lng != null) lat to lng else null
}
fun setGardenLatLng(context: Context, lat: Double, lng: Double) = setGardenLatLngFor(context, effectiveGardenId(context), lat, lng)
/**
 * Writes a SPECIFIC garden's coordinates. GardenSyncClient must use this (never the active-garden
 * setGardenLatLng) because it syncs every known garden in the background, not just the active one —
 * writing a synced garden's echoed-back coordinates via the active-garden setter was a real bug:
 * syncing a shared garden you're a member of while your own garden was active silently overwrote
 * YOUR garden's address/coordinates with THEIRS (and the owner's next sync then pushed that wrong
 * address up to the server for their own garden too).
 *
 * Hemisphere is derived from these coordinates, so the reactive singletons are refreshed immediately
 * — but only when [gardenId] is the garden actually on screen; a background sync of some other garden
 * must not repaint the active garden's address section or hemisphere-dependent "due" status.
 */
fun setGardenLatLngFor(context: Context, gardenId: String, lat: Double, lng: Double) {
    setGardenScopedString(context, "garden_lat", lat.toString(), gardenIdOverride = gardenId)
    setGardenScopedString(context, "garden_lng", lng.toString(), gardenIdOverride = gardenId)
    if (gardenId == effectiveGardenId(context)) {
        HemisphereState.value = getHemisphere(context)
        GardenAddressState.latLng = lat to lng
    }
}
internal const val MAP_FALLBACK_LAT = 40.785091
internal const val MAP_FALLBACK_LNG = -73.968285

/**
 * Where the user last left the real map's camera (pan/zoom) — read as the map's initial position so
 * it doesn't reset to the garden address every time the tab is reopened. Self-heals a specific past
 * bug (fixed, but already-affected devices still carry the bad saved value): the camera-persistence
 * effect used to fire on its very first "settled" callback — an artifact of initial composition, not
 * a real pan — which locked in whatever fallback position the map happened to start at (this exact
 * hardcoded coordinate) before the real garden address or plant-marker auto-fit ever got a chance to
 * run, permanently shadowing them from then on. A genuine user pan landing on this exact coordinate
 * is practically impossible, so treating it as "nothing saved yet" lets a real address/auto-fit apply.
 */
fun getMapCameraPosition(context: Context): Triple<Double, Double, Float>? {
    val lat = gardenScopedString(context, "map_camera_lat", "").toDoubleOrNull()
    val lng = gardenScopedString(context, "map_camera_lng", "").toDoubleOrNull()
    val zoom = gardenScopedFloat(context, "map_camera_zoom", -1f).takeIf { it > 0f }
    if (lat == null || lng == null || zoom == null) return null
    if (lat == MAP_FALLBACK_LAT && lng == MAP_FALLBACK_LNG) return null
    return Triple(lat, lng, zoom)
}
fun setMapCameraPosition(context: Context, lat: Double, lng: Double, zoom: Float) {
    setGardenScopedString(context, "map_camera_lat", lat.toString())
    setGardenScopedString(context, "map_camera_lng", lng.toString())
    setGardenScopedFloat(context, "map_camera_zoom", zoom)
}
fun getGardenAddress(context: Context): String = getGardenAddressFor(context, effectiveGardenId(context))
fun getGardenAddressFor(context: Context, gardenId: String): String = gardenScopedString(context, "garden_address", "", gardenIdOverride = gardenId)
fun setGardenAddress(context: Context, address: String) = setGardenAddressFor(context, effectiveGardenId(context), address)
/** Writes a SPECIFIC garden's address — see setGardenLatLngFor for why GardenSyncClient must use this. */
fun setGardenAddressFor(context: Context, gardenId: String, address: String) {
    setGardenScopedString(context, "garden_address", address, gardenIdOverride = gardenId)
    if (gardenId == effectiveGardenId(context)) GardenAddressState.address = address
}
/** Null distinguishes "never set up" (seed from existing plants' locations) from "explicitly emptied". */
fun getGardenLocations(context: Context): List<String>? = getGardenLocationsFor(context, effectiveGardenId(context))
fun getGardenLocationsFor(context: Context, gardenId: String): List<String>? {
    val prefs = gardenPrefs(context)
    val scopedKey = gardenScopedKey(context, "garden_locations", gardenId)
    // Unlike gardenScopedString, this used to fall back to the legacy unscoped key unconditionally
    // whenever the scoped key didn't exist yet — even for a garden that isn't this device's own
    // default one (see feedback_garden_scoped_setting_fallback). That meant a member viewing a
    // shared garden before its zones had ever synced down saw THEIR OWN pre-existing legacy zone
    // list instead of nothing, which getOrSeedGardenLocations then trusted as the real value and
    // never replaced with the owner's actual synced zones. Gated the same way every other scoped
    // getter already is.
    val raw = (if (prefs.contains(scopedKey)) prefs.getString(scopedKey, null) else if (canFallBackToLegacyKey(context, gardenId)) prefs.getString("garden_locations", null) else null) ?: return null
    return raw.split("").filter { it.isNotBlank() }
}
fun setGardenLocations(context: Context, locations: List<String>) = setGardenLocationsFor(context, effectiveGardenId(context), locations)
/** Writes a SPECIFIC garden's zones — see setGardenLatLngFor for why GardenSyncClient must use this. */
fun setGardenLocationsFor(context: Context, gardenId: String, locations: List<String>) {
    if (gardenId == effectiveGardenId(context)) GardenAddressState.locations = locations
    setGardenScopedString(context, "garden_locations", locations.joinToString(""), gardenIdOverride = gardenId)
}
/**
 * Reads the managed garden-locations list, seeding it from existing plants' distinct locations the
 * first time anything asks (so nothing is orphaned for a garden that predates this feature). Only
 * PERSISTS that seed for the garden's owner — a non-owner member's local seed must stay transient
 * (recomputed fresh each call, never written) until the real synced value arrives from the owner.
 * Persisting it immediately was a real bug: GardenSyncClient only ever sends this device's local
 * `gardenLocations` up during sync when it's non-null, specifically so an untouched device doesn't
 * overwrite the shared value before it's received the real one — but merely OPENING "Garden zones"
 * to look at it (before any sync had completed) counted as "touched", silently persisting a seed
 * built from whatever plants happened to be locally synced so far (often none yet), which then
 * became this device's own committed local value and could get pushed up on the very next sync,
 * clobbering the owner's real zone list. A write-permission member adding/renaming/removing a zone
 * still persists normally, since that goes through setGardenLocations directly — only the passive
 * read-triggered seed is affected.
 */
fun getOrSeedGardenLocations(context: Context, plants: List<PlantEntity>): List<String> {
    getGardenLocations(context)?.let { return it }
    val seeded = plants.map { it.location }.filter { it.isNotBlank() }.distinct().sorted()
    if (isOwnerOfActiveGarden(context)) setGardenLocations(context, seeded)
    return seeded
}
fun getWeatherSkipEnabled(context: Context): Boolean = gardenScopedBoolean(context, "weather_skip_enabled", false)
fun setWeatherSkipEnabled(context: Context, value: Boolean) = setGardenScopedBoolean(context, "weather_skip_enabled", value)
fun getWeatherSkipEnabledFor(context: Context, gardenId: String): Boolean =
    gardenScopedBoolean(context, "weather_skip_enabled", false, gardenIdOverride = gardenId)
fun getRainProbabilityThreshold(context: Context): Int = gardenScopedInt(context, "rain_probability_threshold", 60)
fun setRainProbabilityThreshold(context: Context, value: Int) = setGardenScopedInt(context, "rain_probability_threshold", value)
fun getRainProbabilityThresholdFor(context: Context, gardenId: String): Int =
    gardenScopedInt(context, "rain_probability_threshold", 60, gardenIdOverride = gardenId)
/** Minimum forecast rainfall (mm) required before a reminder is flagged — filters out high-probability drizzle. */
fun getRainAmountThreshold(context: Context): Float = gardenScopedFloat(context, "rain_amount_threshold_mm", 1.0f)
fun setRainAmountThreshold(context: Context, value: Float) = setGardenScopedFloat(context, "rain_amount_threshold_mm", value)
fun getRainAmountThresholdFor(context: Context, gardenId: String): Float =
    gardenScopedFloat(context, "rain_amount_threshold_mm", 1.0f, gardenIdOverride = gardenId)

// ============================================================================
// WATER USAGE & COST
// ============================================================================

/** Dollars per kiloliter (1000L) — 0.0 means the user hasn't set a rate yet. */
fun getWaterRatePerKiloliter(context: Context): Double {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getFloat("water_rate_per_kl", 0f).toDouble()
}
fun setWaterRatePerKiloliter(context: Context, value: Double) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putFloat("water_rate_per_kl", value.toFloat()).apply()
}

// ============================================================================
// FIRST-PLANT NOTIFICATION HINT
// ============================================================================

/** Whether the one-time "set up reminders" hint has already been shown, device-wide (never re-shown after that). */
fun hasShownNotificationHint(context: Context): Boolean {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("notification_hint_shown", false)
}
fun setNotificationHintShown(context: Context) {
    val prefs = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("notification_hint_shown", true).apply()
}
