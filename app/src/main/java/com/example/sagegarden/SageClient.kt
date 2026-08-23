package com.example.sagegarden

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class EntitlementSnapshot(
    val isPro: Boolean,
    val source: EntitlementSource,
    val trialExpiresAt: Long?,
    val promoCode: String?,
    val sagePromptsUsed: Int,
    val sagePromptLimit: Int
)

data class FrequencySuggestion(
    val wateringFrequencyDays: Int?,
    val fertiliseFrequencyDays: Int?,
    val pruneFrequencyDays: Int?,
    val feedFrequencyDays: Int?
)

sealed class EntitlementSyncResult {
    data class Success(val snapshot: EntitlementSnapshot) : EntitlementSyncResult()
    data object NetworkError : EntitlementSyncResult()
    data object ServerError : EntitlementSyncResult()
}

sealed class PromoRedeemResult {
    data class Success(val snapshot: EntitlementSnapshot) : PromoRedeemResult()
    data object InvalidCode : PromoRedeemResult()
    data object Expired : PromoRedeemResult()
    data object RedemptionCapReached : PromoRedeemResult()
    data object NetworkError : PromoRedeemResult()
}

sealed class SageChatResult {
    data class Success(val reply: String, val promptsRemaining: Int?) : SageChatResult()
    data object FreeLimitReached : SageChatResult()
    data object DailyLimitReached : SageChatResult()
    data object NetworkError : SageChatResult()
    data object ServerError : SageChatResult()
}

sealed class SageAutoFillResult {
    data class Success(val suggestion: FrequencySuggestion, val promptsRemaining: Int?) : SageAutoFillResult()
    data object FreeLimitReached : SageAutoFillResult()
    data object DailyLimitReached : SageAutoFillResult()
    data object NetworkError : SageAutoFillResult()
    data object ServerError : SageAutoFillResult()
}

/** Thin proxy to the Sage Cloud Functions backend — same OkHttp + org.json shape as WeatherHelper/TuyaClient. The Anthropic key never appears here; it lives only server-side. */
object SageClient {
    // Cloud Functions Gen2 cold-starts (first call after a deploy, or after being idle) can take
    // longer than OkHttp's 10s default read timeout, especially with Firebase Admin + the Anthropic
    // SDK to load — surfaces to the user as a spurious "couldn't reach Sage" NetworkError.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private const val BASE_URL = BuildConfig.SAGE_API_BASE_URL

    private fun jsonBody(json: JSONObject) =
        json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    /** Attaches an App Check attestation token when one's available — see AppCheckClient. Requests still go out without it on failure; the server treats a missing/invalid token as unverified rather than rejecting outright while App Check is being rolled out (see APP_CHECK_ENFORCED in each Function). */
    private suspend fun requestBuilder(path: String, body: JSONObject): Request.Builder {
        val builder = Request.Builder().url("$BASE_URL$path").post(jsonBody(body))
        AppCheckClient.token()?.let { builder.header("X-Firebase-AppCheck", it) }
        return builder
    }

    private fun parseSnapshot(json: JSONObject): EntitlementSnapshot {
        val source = when (json.optString("source", "none")) {
            "trial" -> EntitlementSource.TRIAL
            "promo" -> EntitlementSource.PROMO_CODE
            "override" -> EntitlementSource.OVERRIDE
            else -> EntitlementSource.NONE
        }
        return EntitlementSnapshot(
            isPro = json.optBoolean("isPro", false),
            source = source,
            trialExpiresAt = if (json.isNull("trialExpiresAt")) null else json.optLong("trialExpiresAt"),
            promoCode = if (json.isNull("promoCode")) null else json.optString("promoCode"),
            sagePromptsUsed = json.optInt("sagePromptCount", 0),
            sagePromptLimit = json.optInt("sagePromptLimit", EntitlementManager.FREE_SAGE_PROMPT_LIMIT)
        )
    }

    suspend fun syncEntitlement(context: Context): EntitlementSyncResult = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("deviceId", getOrCreateInstallId(context))
            val request = requestBuilder("/syncEntitlement", body).build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful || text == null) return@withContext EntitlementSyncResult.ServerError
                EntitlementSyncResult.Success(parseSnapshot(JSONObject(text)))
            }
        } catch (_: Exception) {
            EntitlementSyncResult.NetworkError
        }
    }

    suspend fun redeemPromoCode(context: Context, code: String): PromoRedeemResult = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("deviceId", getOrCreateInstallId(context)).put("code", code)
            val request = requestBuilder("/redeemPromoCode", body).build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: return@withContext PromoRedeemResult.NetworkError
                if (response.isSuccessful) {
                    return@withContext PromoRedeemResult.Success(parseSnapshot(JSONObject(text)))
                }
                return@withContext when (JSONObject(text).optString("error", "")) {
                    "not_found", "inactive" -> PromoRedeemResult.InvalidCode
                    "expired" -> PromoRedeemResult.Expired
                    "redemption_cap_reached" -> PromoRedeemResult.RedemptionCapReached
                    else -> PromoRedeemResult.InvalidCode
                }
            }
        } catch (_: Exception) {
            PromoRedeemResult.NetworkError
        }
    }

    suspend fun chat(context: Context, message: String): SageChatResult = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("deviceId", getOrCreateInstallId(context)).put("message", message)
            val request = requestBuilder("/sageChat", body).build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: return@withContext SageChatResult.NetworkError
                val json = JSONObject(text)
                if (response.isSuccessful) {
                    val remaining = if (json.isNull("promptsRemaining")) null else json.optInt("promptsRemaining")
                    return@withContext SageChatResult.Success(json.optString("reply", ""), remaining)
                }
                return@withContext when (json.optString("error", "")) {
                    "free_limit_reached" -> SageChatResult.FreeLimitReached
                    "daily_limit_reached" -> SageChatResult.DailyLimitReached
                    else -> SageChatResult.ServerError
                }
            }
        } catch (_: Exception) {
            SageChatResult.NetworkError
        }
    }

    suspend fun autoFillFrequencies(context: Context, sciName: String): SageAutoFillResult = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("deviceId", getOrCreateInstallId(context)).put("sciName", sciName)
            val request = requestBuilder("/sageAutoFill", body).build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: return@withContext SageAutoFillResult.NetworkError
                val json = JSONObject(text)
                if (response.isSuccessful) {
                    val s = json.optJSONObject("suggestion") ?: return@withContext SageAutoFillResult.ServerError
                    val suggestion = FrequencySuggestion(
                        wateringFrequencyDays = if (s.isNull("wateringFrequencyDays")) null else s.optInt("wateringFrequencyDays"),
                        fertiliseFrequencyDays = if (s.isNull("fertiliseFrequencyDays")) null else s.optInt("fertiliseFrequencyDays"),
                        pruneFrequencyDays = if (s.isNull("pruneFrequencyDays")) null else s.optInt("pruneFrequencyDays"),
                        feedFrequencyDays = if (s.isNull("feedFrequencyDays")) null else s.optInt("feedFrequencyDays")
                    )
                    val remaining = if (json.isNull("promptsRemaining")) null else json.optInt("promptsRemaining")
                    return@withContext SageAutoFillResult.Success(suggestion, remaining)
                }
                return@withContext when (json.optString("error", "")) {
                    "free_limit_reached" -> SageAutoFillResult.FreeLimitReached
                    "daily_limit_reached" -> SageAutoFillResult.DailyLimitReached
                    else -> SageAutoFillResult.ServerError
                }
            }
        } catch (_: Exception) {
            SageAutoFillResult.NetworkError
        }
    }
}
