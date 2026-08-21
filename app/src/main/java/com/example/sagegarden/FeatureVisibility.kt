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

enum class Feature {
    SUN_MAP,
    TUYA_INTEGRATION,
    AUDIT_SCREEN,
    COST_WATER_TRACKING,
    GROWTH_TIMELINES,
    WATERING_HISTORY,
    DROPBOX_BACKUP,
    SAGE_ASSISTANT
}

/**
 * Combines the two independent visibility axes — Basic/Advanced UI mode, and Pro entitlement —
 * into a single check so call sites never duplicate boolean logic. This gates *rendering only*;
 * it must never be used to condition a read or write of the underlying data (Tuya credentials,
 * sun zones, watering history, etc.), so switching modes never loses anything — see the plan's
 * "gating never touches data" guarantee.
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
     * turned off entirely from Help regardless of mode. Every other feature requires Advanced mode,
     * with Tuya additionally requiring Pro.
     */
    fun shouldShow(context: Context, feature: Feature): Boolean {
        if (feature == Feature.SAGE_ASSISTANT) {
            return SageEnabledState.enabled && EntitlementManager.getCached(context).isPro
        }
        if (!isAdvancedModeEnabled(context)) return false
        return when (feature) {
            Feature.TUYA_INTEGRATION -> EntitlementManager.getCached(context).tuyaEnabled
            else -> true
        }
    }
}
