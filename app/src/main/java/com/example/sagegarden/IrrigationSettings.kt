@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.sagegarden

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.google.maps.android.compose.*

// ============================================================================
// IRRIGATION SYSTEM SELECTION
// ============================================================================
// Pure visibility toggle for the Help screen's Irrigation section — switching
// it never deletes either vendor's stored credentials or zone mappings below.

enum class IrrigationSystem { NONE, TUYA, RACHIO }

/**
 * Which irrigation vendor is active, plus every zone mapping and credential below, is per-garden —
 * two shared gardens are two different physical properties, plausibly with entirely different
 * irrigation controllers, so garden B seeing garden A's Tuya zones (or vice versa) is exactly the
 * kind of cross-garden bleed this scoping (mirroring hemisphere/notification settings) closes.
 * Defaults to TUYA when the device already has non-blank Tuya credentials saved for this garden
 * (pre-existing testers see zero change), else NONE.
 */
fun getIrrigationSystem(context: Context): IrrigationSystem = getIrrigationSystemFor(context, effectiveGardenId(context))
/** The *For variants below read a SPECIFIC garden's irrigation setup regardless of which garden is active — used by IrrigationHistorySyncWorker, which syncs every garden with irrigation configured. */
fun getIrrigationSystemFor(context: Context, gardenId: String): IrrigationSystem {
    val stored = gardenScopedString(context, "irrigation_system", "", gardenIdOverride = gardenId)
    if (stored.isNotBlank()) return IrrigationSystem.entries.firstOrNull { it.name == stored } ?: IrrigationSystem.NONE
    return if (getTuyaClientIdFor(context, gardenId).isNotBlank() && getTuyaClientSecretFor(context, gardenId).isNotBlank()) IrrigationSystem.TUYA else IrrigationSystem.NONE
}
fun setIrrigationSystem(context: Context, value: IrrigationSystem) {
    setGardenScopedString(context, "irrigation_system", value.name)
}

// ============================================================================
// TUYA ZONE MAPPING (outlet-level granularity)
// ============================================================================
// Each physical Tuya device has up to two independent outlets. A "zone" is a
// friendly, user-chosen name for ONE outlet on ONE device (e.g. "Front Garden"
// = deviceId X, outlet "1"; "Front Patch" = same deviceId X, outlet "2").
// Stored as: "zoneA=deviceId:outlet|zoneB=deviceId:outlet|..."

/**
 * Dedicated prefs file for actual secrets (OAuth tokens, API client secrets) — kept separate from
 * "garden_mapper_prefs" so it alone can be excluded from Android's backup/device-transfer (see
 * data_extraction_rules.xml / backup_rules.xml), without losing ordinary settings on restore.
 * [migrateCredential] is a one-time fallback for any install with an already-installed build that
 * wrote a given key into the old general prefs file, so existing testers aren't silently signed out.
 */
internal fun credentialPrefs(context: Context) = context.getSharedPreferences("garden_mapper_credential_prefs", Context.MODE_PRIVATE)

internal fun migrateCredential(context: Context, key: String): String? {
    val creds = credentialPrefs(context)
    creds.getString(key, null)?.let { return it }
    val general = context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)
    val legacy = general.getString(key, null) ?: return null
    creds.edit().putString(key, legacy).apply()
    general.edit().remove(key).apply()
    return legacy
}

data class TuyaZoneMapping(val zone: String, val deviceId: String, val outlet: String)

/**
 * Live, Compose-observable mirror of the persisted Tuya zone mapping — same rationale as
 * ActiveGardenState/GardenAddressState (see GardenMembershipClient.kt): the Help screen's zone
 * editor captures a one-shot snapshot via `remember(ActiveGardenState.activeGardenId)`, which never
 * re-reads prefs unless the active garden itself changes. A restore (Dropbox/local/auto-backup)
 * writes fresh zone mappings straight to prefs without switching gardens, so without this the
 * editor kept showing whatever it last had — even though the restore itself worked correctly —
 * until the user force-restarted the app. Refreshed by setTuyaZoneMappings, whether that's called
 * from the editor's own Save button or from a restore.
 */
object TuyaZoneMappingState {
    var mappings by mutableStateOf<List<TuyaZoneMapping>>(emptyList())
}

fun getTuyaZoneMappings(context: Context): List<TuyaZoneMapping> = getTuyaZoneMappingsFor(context, effectiveGardenId(context))
fun getTuyaZoneMappingsFor(context: Context, gardenId: String): List<TuyaZoneMapping> {
    val raw = gardenScopedString(context, "tuya_device_mapping", "", gardenIdOverride = gardenId)
    return raw.split("|").filter { it.contains("=") }.mapNotNull { entry ->
        val parts = entry.split("=", limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val (zone, rest) = parts
        val restParts = rest.split(":")
        val deviceId = restParts.getOrNull(0) ?: return@mapNotNull null
        val outlet = restParts.getOrNull(1) ?: "1"
        if (zone.isBlank() || deviceId.isBlank()) null else TuyaZoneMapping(zone, deviceId, outlet)
    }
}

fun setTuyaZoneMappings(context: Context, mappings: List<TuyaZoneMapping>) {
    val raw = mappings.joinToString("|") { "${it.zone}=${it.deviceId}:${it.outlet}" }
    setGardenScopedString(context, "tuya_device_mapping", raw)
    // Deliberately NOT refreshing TuyaZoneMappingState here — this function is also called by the
    // zone editor's own Save/Fetch-local-key actions, and refreshing from here would reset the
    // editor's in-progress row list from whatever was just saved (which filters out any row still
    // mid-edit with a blank field), discarding it. TuyaZoneMappingState should only change for
    // updates that happen OUTSIDE this screen — a restore, or switching gardens — see the explicit
    // refresh calls at those call sites instead.
}

/** Each user connects their own Tuya Cloud project — nothing is shared between installs. migrateCredential is a one-time, garden-independent hop from the old general prefs file into credentialPrefs; gardenScopedString's own legacy-key fallback then takes it from there per garden. */
fun getTuyaClientId(context: Context): String = getTuyaClientIdFor(context, effectiveGardenId(context))
fun getTuyaClientIdFor(context: Context, gardenId: String): String {
    migrateCredential(context, "tuya_client_id")
    return gardenScopedString(context, "tuya_client_id", "", credentialPrefs(context), gardenIdOverride = gardenId)
}
fun setTuyaClientId(context: Context, value: String) {
    setGardenScopedString(context, "tuya_client_id", value, credentialPrefs(context))
}
fun getTuyaClientSecret(context: Context): String = getTuyaClientSecretFor(context, effectiveGardenId(context))
fun getTuyaClientSecretFor(context: Context, gardenId: String): String {
    migrateCredential(context, "tuya_client_secret")
    return gardenScopedString(context, "tuya_client_secret", "", credentialPrefs(context), gardenIdOverride = gardenId)
}
fun setTuyaClientSecret(context: Context, value: String) {
    setGardenScopedString(context, "tuya_client_secret", value, credentialPrefs(context))
}

// ============================================================================
// RACHIO ZONE MAPPING
// ============================================================================
// Unlike Tuya, a Rachio zone already carries its own vendor-assigned name and
// id — a "zone" here is just that zone on a given device (deviceId + zoneId).
// Stored as: "zoneA=deviceId:zoneId|zoneB=deviceId:zoneId|..."

data class RachioZoneMapping(val zone: String, val deviceId: String, val zoneId: String)

fun getRachioZoneMappings(context: Context): List<RachioZoneMapping> = getRachioZoneMappingsFor(context, effectiveGardenId(context))
fun getRachioZoneMappingsFor(context: Context, gardenId: String): List<RachioZoneMapping> {
    val raw = gardenScopedString(context, "rachio_device_mapping", "", gardenIdOverride = gardenId)
    return raw.split("|").filter { it.contains("=") }.mapNotNull { entry ->
        val parts = entry.split("=", limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val (zone, rest) = parts
        val restParts = rest.split(":")
        val deviceId = restParts.getOrNull(0) ?: return@mapNotNull null
        val zoneId = restParts.getOrNull(1) ?: ""
        if (zone.isBlank() || deviceId.isBlank() || zoneId.isBlank()) null else RachioZoneMapping(zone, deviceId, zoneId)
    }
}

fun setRachioZoneMappings(context: Context, mappings: List<RachioZoneMapping>) {
    val raw = mappings.joinToString("|") { "${it.zone}=${it.deviceId}:${it.zoneId}" }
    setGardenScopedString(context, "rachio_device_mapping", raw)
}

/** Each user connects their own Rachio account via a personal API token — nothing is shared between installs. */
fun getRachioApiToken(context: Context): String = getRachioApiTokenFor(context, effectiveGardenId(context))
fun getRachioApiTokenFor(context: Context, gardenId: String): String = gardenScopedString(context, "rachio_api_token", "", credentialPrefs(context), gardenIdOverride = gardenId)

/** When [gardenId]'s watering history was last pulled from Tuya/Rachio without any zone failing (manual button or IrrigationHistorySyncWorker) — 0 if never. */
fun getLastIrrigationSyncAt(context: Context, gardenId: String): Long =
    gardenScopedString(context, "irrigation_last_sync_at", "", gardenIdOverride = gardenId).toLongOrNull() ?: 0L
fun setLastIrrigationSyncAt(context: Context, gardenId: String, millis: Long) =
    setGardenScopedString(context, "irrigation_last_sync_at", millis.toString(), gardenIdOverride = gardenId)
fun setRachioApiToken(context: Context, value: String) {
    setGardenScopedString(context, "rachio_api_token", value, credentialPrefs(context))
}
