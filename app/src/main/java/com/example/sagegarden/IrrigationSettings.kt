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
 * until the user force-restarted the app. Refreshed on a garden switch and after a restore — not
 * by the tuyaZoneMappings setter, which the editor's own Save uses and would reset its in-progress rows.
 */
object TuyaZoneMappingState {
    var mappings by mutableStateOf<List<TuyaZoneMapping>>(emptyList())
}

// ============================================================================
// RACHIO ZONE MAPPING
// ============================================================================
// Unlike Tuya, a Rachio zone already carries its own vendor-assigned name and
// id — a "zone" here is just that zone on a given device (deviceId + zoneId).
// Stored as: "zoneA=deviceId:zoneId|zoneB=deviceId:zoneId|..."

data class RachioZoneMapping(val zone: String, val deviceId: String, val zoneId: String)

