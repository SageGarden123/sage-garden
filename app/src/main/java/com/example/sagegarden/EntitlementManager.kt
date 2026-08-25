package com.example.sagegarden

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val ENTITLEMENT_PREFS = "garden_mapper_entitlement_prefs"
private const val KEY_IS_PRO = "entitlement_is_pro"
private const val KEY_SOURCE = "entitlement_source"
private const val KEY_PROMO_CODE = "entitlement_promo_code"
private const val KEY_SAGE_PROMPTS_USED = "entitlement_sage_prompts_used"
private const val KEY_LAST_SYNCED_AT = "entitlement_last_synced_at"

enum class EntitlementSource { PROMO_CODE, OVERRIDE, NONE }

data class EntitlementState(
    val isPro: Boolean,
    val source: EntitlementSource,
    val sagePromptsUsed: Int,
    val sagePromptLimit: Int
)

/**
 * Live, Compose-observable mirror of [EntitlementState] — same rationale/pattern as SageEnabledState,
 * AdvancedModeState, HemisphereState (see feedback-compose-reactive-staleness in project memory).
 * Without this, a promo-code redemption only reached FeatureVisibility.shouldShow() once something
 * else forced recomposition (tab switch, app restart) — EntitlementManager.getCached(context) reads
 * raw SharedPreferences fresh on every call, but that alone doesn't make Compose re-run the
 * composables that already read it. Synced at app startup (MainActivity.onCreate, alongside the
 * other *State singletons) and on every [EntitlementManager.writeSnapshot] call (i.e. every
 * sync()/redeemPromoCode()).
 */
object EntitlementLiveState {
    var value by mutableStateOf(
        EntitlementState(
            isPro = false, source = EntitlementSource.NONE,
            sagePromptsUsed = 0, sagePromptLimit = EntitlementManager.FREE_SAGE_PROMPT_LIMIT
        )
    )
}

sealed class PromoRedemptionResult {
    data class Success(val state: EntitlementState) : PromoRedemptionResult()
    data object InvalidCode : PromoRedemptionResult()
    data object Expired : PromoRedemptionResult()
    data object RedemptionCapReached : PromoRedemptionResult()
    data object NetworkError : PromoRedemptionResult()
}

/**
 * Resolves entitlement (whether this device gets unlimited Sage/AI-ID access via a promo code)
 * from a local cache synced periodically from the Sage backend. [getCached] is synchronous and
 * safe to call from any Compose call site — it never blocks on network.
 */
object EntitlementManager {
    const val FREE_SAGE_PROMPT_LIMIT = 5

    private fun prefs(context: Context) = context.getSharedPreferences(ENTITLEMENT_PREFS, Context.MODE_PRIVATE)

    fun getCached(context: Context): EntitlementState {
        val p = prefs(context)
        val isPro = p.getBoolean(KEY_IS_PRO, false)
        val sourceName = p.getString(KEY_SOURCE, EntitlementSource.NONE.name) ?: EntitlementSource.NONE.name
        val source = runCatching { EntitlementSource.valueOf(sourceName) }.getOrDefault(EntitlementSource.NONE)
        val sagePromptsUsed = p.getInt(KEY_SAGE_PROMPTS_USED, 0)

        return EntitlementState(
            isPro = isPro,
            source = source,
            sagePromptsUsed = sagePromptsUsed,
            sagePromptLimit = FREE_SAGE_PROMPT_LIMIT
        )
    }

    fun getLastSyncedAt(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNCED_AT, 0L)

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
            .putString(KEY_PROMO_CODE, snapshot.promoCode)
            .putInt(KEY_SAGE_PROMPTS_USED, snapshot.sagePromptsUsed)
            .putLong(KEY_LAST_SYNCED_AT, System.currentTimeMillis())
            .apply()
        EntitlementLiveState.value = getCached(context)
    }
}
