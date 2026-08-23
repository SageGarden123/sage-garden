package com.example.sagegarden

import android.content.Context

private const val ENTITLEMENT_PREFS = "garden_mapper_entitlement_prefs"
private const val KEY_IS_PRO = "entitlement_is_pro"
private const val KEY_SOURCE = "entitlement_source"
private const val KEY_TRIAL_EXPIRES_AT = "entitlement_trial_expires_at"
private const val KEY_PROMO_CODE = "entitlement_promo_code"
private const val KEY_SAGE_PROMPTS_USED = "entitlement_sage_prompts_used"
private const val KEY_LAST_SYNCED_AT = "entitlement_last_synced_at"
private const val KEY_DAY7_NUDGE_SHOWN = "entitlement_day7_nudge_shown"
private const val KEY_TRIAL_ENDED_NUDGE_SHOWN = "entitlement_trial_ended_nudge_shown"

enum class EntitlementSource { TRIAL, PROMO_CODE, PURCHASE, OVERRIDE, NONE }

/** A one-time trial-status message to surface to the user, per [EntitlementManager.checkTrialNudge]. */
enum class TrialNudge { DAY_SEVEN, TRIAL_ENDED }

data class EntitlementState(
    val isPro: Boolean,
    val source: EntitlementSource,
    val trialExpiresAt: Long?,
    val plantLimit: Int,
    val tuyaEnabled: Boolean,
    val logHistoryLimit: Int,
    val sagePromptsUsed: Int,
    val sagePromptLimit: Int
)

sealed class PromoRedemptionResult {
    data class Success(val state: EntitlementState) : PromoRedemptionResult()
    data object InvalidCode : PromoRedemptionResult()
    data object Expired : PromoRedemptionResult()
    data object RedemptionCapReached : PromoRedemptionResult()
    data object NetworkError : PromoRedemptionResult()
}

/**
 * Resolves entitlement (Pro vs free-tier limits) from a local cache synced periodically from the
 * Sage backend. [getCached] is synchronous and safe to call from any Compose call site — it never
 * blocks on network. Free-tier numeric limits below are placeholders (not yet specified by the
 * product owner) and are easy to tune.
 */
object EntitlementManager {
    const val FREE_PLANT_LIMIT = 25
    const val FREE_LOG_HISTORY_LIMIT = 20
    const val FREE_SAGE_PROMPT_LIMIT = 5
    const val FREE_WIDGET_PLANT_LIMIT = 10

    private fun prefs(context: Context) = context.getSharedPreferences(ENTITLEMENT_PREFS, Context.MODE_PRIVATE)

    fun getCached(context: Context): EntitlementState {
        val p = prefs(context)
        val isPro = p.getBoolean(KEY_IS_PRO, false)
        val sourceName = p.getString(KEY_SOURCE, EntitlementSource.NONE.name) ?: EntitlementSource.NONE.name
        val source = runCatching { EntitlementSource.valueOf(sourceName) }.getOrDefault(EntitlementSource.NONE)
        val trialExpiresAtRaw = p.getLong(KEY_TRIAL_EXPIRES_AT, -1L)
        val sagePromptsUsed = p.getInt(KEY_SAGE_PROMPTS_USED, 0)

        return EntitlementState(
            isPro = isPro,
            source = source,
            trialExpiresAt = if (trialExpiresAtRaw <= 0L) null else trialExpiresAtRaw,
            plantLimit = FREE_PLANT_LIMIT,
            tuyaEnabled = isPro,
            logHistoryLimit = FREE_LOG_HISTORY_LIMIT,
            sagePromptsUsed = sagePromptsUsed,
            sagePromptLimit = FREE_SAGE_PROMPT_LIMIT
        )
    }

    fun getLastSyncedAt(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNCED_AT, 0L)

    /**
     * Returns a one-time nudge to show the user, or null if none is due. Purely derived from
     * [state]'s trialExpiresAt (no separate TRIAL_DAYS constant needed client-side) plus a
     * "already shown" flag per nudge so each fires at most once per install. Call after every
     * [sync] (e.g. the app-startup sync) with the state it returned.
     */
    fun checkTrialNudge(context: Context, state: EntitlementState): TrialNudge? {
        val trialExpiresAt = state.trialExpiresAt ?: return null
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val day7Threshold = trialExpiresAt - 7L * 24 * 60 * 60 * 1000

        if (state.source == EntitlementSource.TRIAL && now in day7Threshold until trialExpiresAt) {
            if (p.getBoolean(KEY_DAY7_NUDGE_SHOWN, false)) return null
            p.edit().putBoolean(KEY_DAY7_NUDGE_SHOWN, true).apply()
            return TrialNudge.DAY_SEVEN
        }

        // Trial genuinely ended with no promo/override picking up entitlement — not shown to
        // PROMO_CODE/OVERRIDE devices, since those are still Pro despite the trial having lapsed.
        if (state.source == EntitlementSource.NONE && now >= trialExpiresAt) {
            if (p.getBoolean(KEY_TRIAL_ENDED_NUDGE_SHOWN, false)) return null
            p.edit().putBoolean(KEY_TRIAL_ENDED_NUDGE_SHOWN, true).apply()
            return TrialNudge.TRIAL_ENDED
        }

        return null
    }

    /** Cheap local write-back after a successful Sage call — the chat/auto-fill response already carries the authoritative post-call count, so this avoids a full [sync] round-trip just to refresh the displayed "X of 5 left" counter. No-op for Pro devices (promptsRemaining is null). */
    fun updateSagePromptsRemaining(context: Context, promptsRemaining: Int?) {
        if (promptsRemaining == null) return
        val limit = FREE_SAGE_PROMPT_LIMIT
        val used = (limit - promptsRemaining).coerceIn(0, limit)
        prefs(context).edit().putInt(KEY_SAGE_PROMPTS_USED, used).apply()
    }

    /** Calls the Sage backend to refresh entitlement state, caching the result locally. Call once at app startup, after promo redemption, and from the "Refresh status" button in Help. Falls back to the last-known local state on network failure — never blocks the UI on connectivity. */
    suspend fun sync(context: Context): EntitlementState {
        val result = SageClient.syncEntitlement(context)
        if (result is EntitlementSyncResult.Success) {
            writeSnapshot(context, result.snapshot)
        }
        return getCached(context)
    }

    /** Persists a snapshot already obtained from a successful [SageClient.verifyPurchase] call — same effect as [sync] without a redundant network round-trip. */
    fun applySnapshot(context: Context, snapshot: EntitlementSnapshot): EntitlementState {
        writeSnapshot(context, snapshot)
        return getCached(context)
    }

    suspend fun redeemPromoCode(context: Context, code: String): PromoRedemptionResult {
        return when (val result = SageClient.redeemPromoCode(context, code)) {
            is PromoRedeemResult.Success -> {
                writeSnapshot(context, result.snapshot)
                PromoRedemptionResult.Success(getCached(context))
            }
            is PromoRedeemResult.InvalidCode -> PromoRedemptionResult.InvalidCode
            is PromoRedeemResult.Expired -> PromoRedemptionResult.Expired
            is PromoRedeemResult.RedemptionCapReached -> PromoRedemptionResult.RedemptionCapReached
            is PromoRedeemResult.NetworkError -> PromoRedemptionResult.NetworkError
        }
    }

    private fun writeSnapshot(context: Context, snapshot: EntitlementSnapshot) {
        prefs(context).edit()
            .putBoolean(KEY_IS_PRO, snapshot.isPro)
            .putString(KEY_SOURCE, snapshot.source.name)
            .putLong(KEY_TRIAL_EXPIRES_AT, snapshot.trialExpiresAt ?: -1L)
            .putString(KEY_PROMO_CODE, snapshot.promoCode)
            .putInt(KEY_SAGE_PROMPTS_USED, snapshot.sagePromptsUsed)
            .putLong(KEY_LAST_SYNCED_AT, System.currentTimeMillis())
            .apply()
    }
}
