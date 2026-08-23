package com.example.sagegarden

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val KEY_UI_MODE_ADVANCED = "ui_mode_advanced"
private const val KEY_SAGE_CHAT_ENABLED = "sage_chat_enabled"
private const val KEY_SAGE_FAB_OFFSET_Y_DP = "sage_fab_offset_y_dp"

/**
 * Live, Compose-observable mirror of the "Sage enabled" preference. Reading raw SharedPreferences
 * only reflects a change once *something else* happens to trigger recomposition (e.g. navigation) —
 * this makes the toggle propagate immediately to the FAB regardless of which screen it's toggled
 * from. Synced from persisted prefs once at app startup (see GardenMapperApp's startup effect);
 * [FeatureVisibility.setSageChatEnabled] keeps it in sync on every change after that.
 */
object SageEnabledState {
    var enabled by mutableStateOf(true)
}

/**
 * Live, Compose-observable mirror of the Basic/Advanced UI mode preference — same rationale as
 * [SageEnabledState]. Without this, toggling the mode in the Help screen only reached other
 * composables (dashboard, map, list, audit, irrigation tabs) once navigation happened to remount
 * them, since reading raw SharedPreferences doesn't itself trigger recomposition elsewhere.
 * Synced from persisted prefs once at app startup; [FeatureVisibility.setAdvancedModeEnabled]
 * keeps it in sync on every change after that.
 */
object AdvancedModeState {
    var enabled by mutableStateOf(false)
}

enum class Hemisphere { SOUTHERN, NORTHERN }

/**
 * Live, Compose-observable mirror of the garden's hemisphere setting — same rationale as
 * [SageEnabledState] and [AdvancedModeState]. Composables that show "due" status (dashboard,
 * widget preview inside the app, audit) need to recompute the moment this changes in Help, not
 * just on next navigation. Background call sites with no running Compose tree (the reminder
 * worker) should read [getHemisphere] directly from prefs instead of this, since the singleton
 * may not have been synced yet in a cold-started worker process.
 */
object HemisphereState {
    var value by mutableStateOf(Hemisphere.SOUTHERN)
}

enum class Feature {
    SUN_MAP,
    TUYA_INTEGRATION,
    AUDIT_SCREEN,
    COST_WATER_TRACKING,
    GROWTH_TIMELINES,
    WATERING_HISTORY,
    WEATHER_AWARE_REMINDERS,
    DROPBOX_BACKUP,
    SAGE_ASSISTANT
}

/**
 * Combines the two independent visibility axes — Basic/Advanced UI mode, and Pro entitlement —
 * into a single check so call sites never duplicate boolean logic. This gates *rendering only*;
 * it must never be used to condition a read or write of the underlying data (Tuya credentials,
 * sun zones, watering history, etc.), so switching modes never loses anything — see the plan's
 * "gating never touches data" guarantee. This means once a trial lapses, everything a user
 * entered into a now-hidden feature is still sitting there untouched, ready to reappear the
 * moment they're Pro again (renewed trial, promo code, or a future purchase).
 *
 * Lives in the general "garden_mapper_prefs" file (existing convention) since the Basic/Advanced
 * toggle is a UI preference, not an entitlement fact — entitlement itself is cached separately by
 * EntitlementManager in its own prefs file.
 */
object FeatureVisibility {
    private fun generalPrefs(context: Context) =
        context.getSharedPreferences("garden_mapper_prefs", Context.MODE_PRIVATE)

    fun isAdvancedModeEnabled(context: Context): Boolean =
        generalPrefs(context).getBoolean(KEY_UI_MODE_ADVANCED, false)

    fun setAdvancedModeEnabled(context: Context, enabled: Boolean) {
        generalPrefs(context).edit().putBoolean(KEY_UI_MODE_ADVANCED, enabled).apply()
        AdvancedModeState.enabled = enabled
    }

    /** User-level "do I want Sage at all" preference — independent of, and checked in addition to, Pro entitlement. Defaults on. */
    fun isSageChatEnabled(context: Context): Boolean =
        generalPrefs(context).getBoolean(KEY_SAGE_CHAT_ENABLED, true)

    fun setSageChatEnabled(context: Context, enabled: Boolean) {
        generalPrefs(context).edit().putBoolean(KEY_SAGE_CHAT_ENABLED, enabled).apply()
        SageEnabledState.enabled = enabled
    }

    /**
     * Vertical drag offset (dp) for the Sage FAB, so it can be moved out of the way of a button
     * underneath it. Not clamped here — the caller clamps against the actual screen height (which
     * varies per device), so store whatever it's given.
     */
    fun getSageFabOffsetDp(context: Context): Float =
        generalPrefs(context).getFloat(KEY_SAGE_FAB_OFFSET_Y_DP, 0f)

    fun setSageFabOffsetDp(context: Context, offsetDp: Float) {
        generalPrefs(context).edit().putFloat(KEY_SAGE_FAB_OFFSET_Y_DP, offsetDp).apply()
    }

    /**
     * True when the feature should render. Sage is intentionally independent of the Basic/Advanced
     * toggle — it's gated on Pro status (trial or promo code) AND the user's own "Sage enabled"
     * preference, so it stays available in Basic mode as soon as a device is entitled, but can be
     * turned off entirely from Help regardless of mode. Weather-aware reminders is similarly
     * independent of the toggle — it's a Pro perk available in Basic mode too, not something Advanced
     * mode unlocks. Every other feature requires Advanced mode AND Pro: once a trial lapses, these
     * features (and the toggle itself) become unavailable — see [setAdvancedModeEnabled]'s doc comment
     * for why nothing entered while they were visible gets lost. TUYA_INTEGRATION also gates the whole
     * Irrigation section (Rachio included) — it's really "irrigation integration enabled", not
     * Tuya-specific; which vendor's controls show within that section is a separate,
     * non-gating IrrigationSystem choice.
     */
    fun shouldShow(context: Context, feature: Feature): Boolean {
        if (feature == Feature.SAGE_ASSISTANT) {
            return SageEnabledState.enabled && EntitlementManager.getCached(context).isPro
        }
        if (feature == Feature.WEATHER_AWARE_REMINDERS) {
            return EntitlementManager.getCached(context).isPro
        }
        if (!AdvancedModeState.enabled) return false
        return when (feature) {
            Feature.TUYA_INTEGRATION -> EntitlementManager.getCached(context).tuyaEnabled
            Feature.SUN_MAP, Feature.AUDIT_SCREEN, Feature.COST_WATER_TRACKING,
            Feature.GROWTH_TIMELINES, Feature.WATERING_HISTORY -> EntitlementManager.getCached(context).isPro
            else -> true
        }
    }
}
