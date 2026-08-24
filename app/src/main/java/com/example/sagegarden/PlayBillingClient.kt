package com.example.sagegarden

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Product/base-plan IDs must exactly match what's created in Play Console (Monetization →
 * Subscriptions) once the Merchant account is verified — one subscription product with two base
 * plans (monthly/yearly) is the current Play Billing model, rather than two separate products.
 * These are placeholders; update them here once the real product is created in Play Console.
 */
const val PLAY_BILLING_PRODUCT_ID = "sage_garden_pro"

data class ProOffer(val basePlanId: String, val offerToken: String, val formattedPrice: String, val billingPeriodIso8601: String)

/** "P1M" -> "month", "P1Y" -> "year", "P3M" -> "3 months" — covers the periods a subscription base plan can actually have. */
fun readableBillingPeriod(iso8601: String): String {
    val match = Regex("""P(\d+)([DWMY])""").find(iso8601) ?: return iso8601
    val (countStr, unitChar) = match.destructured
    val count = countStr.toIntOrNull() ?: return iso8601
    val unit = when (unitChar) {
        "D" -> "day"; "W" -> "week"; "M" -> "month"; "Y" -> "year"
        else -> return iso8601
    }
    return if (count == 1) unit else "$count ${unit}s"
}

/**
 * Thin wrapper around Google Play's BillingClient. This class never grants entitlement itself —
 * it only drives the on-device purchase UI and hands the resulting [Purchase] to the caller, which
 * must always verify it server-side (see [SageClient.verifyPurchase]) before trusting it. A purchase
 * reported here (even one Play itself reports as PURCHASED) is not proof of payment on its own —
 * the client and this whole library run on a device an attacker could control.
 */
object PlayBillingClient {
    private var billingClient: BillingClient? = null
    private var onPurchaseUpdated: ((Purchase) -> Unit)? = null
    private var cachedProductDetails: ProductDetails? = null
    private var connected = CompletableDeferred<Unit>()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach { onPurchaseUpdated?.invoke(it) }
        }
    }

    /**
     * Call once at app startup (see MainActivity.onCreate). [onPurchase] fires for every purchase
     * Play reports — both right after a fresh purchase and for existing ones found on reconnect —
     * always re-verify server-side before trusting it, never grant entitlement directly from this.
     */
    fun init(context: Context, onPurchase: (Purchase) -> Unit) {
        onPurchaseUpdated = onPurchase
        if (billingClient?.isReady == true) return
        val client = BillingClient.newBuilder(context.applicationContext)
            .setListener(purchasesUpdatedListener)
            // enableOneTimeProducts() is required by PendingPurchasesParams.Builder.build() in
            // Billing Library 9 even though this app only sells a subscription — omitting it
            // throws IllegalArgumentException("Pending purchases for one-time products must be
            // supported") at BillingClient construction, crashing the app on launch.
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
        billingClient = client
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) connected.complete(Unit)
            }
            override fun onBillingServiceDisconnected() {} // enableAutoServiceReconnection() handles retrying
        })
    }

    /**
     * Fetches the Pro subscription's current base-plan offers with live, localized pricing —
     * never hardcode a price, Play Console controls it. Null if not yet connected, or if the
     * product doesn't exist in Play Console yet (e.g. before the Merchant account is verified).
     */
    suspend fun queryProOffers(): List<ProOffer>? {
        // Same wait-for-connection as queryExistingPurchases() — without it, opening the upgrade
        // dialog shortly after app launch (startConnection() is async and can still be in flight)
        // returned null immediately and got stuck forever, since the caller treats null the same
        // as "hasn't loaded yet". Confirmed live: tapping Upgrade to Pro showed "Loading
        // subscription options…" indefinitely.
        if (billingClient?.isReady != true) withTimeoutOrNull(5000) { connected.await() }
        val client = billingClient?.takeIf { it.isReady } ?: return null
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PLAY_BILLING_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return null
        val productDetails = result.productDetailsList?.firstOrNull() ?: return null
        cachedProductDetails = productDetails
        return productDetails.subscriptionOfferDetails?.map { offer ->
            val pricingPhase = offer.pricingPhases.pricingPhaseList.firstOrNull()
            ProOffer(
                basePlanId = offer.basePlanId,
                offerToken = offer.offerToken,
                formattedPrice = pricingPhase?.formattedPrice ?: "",
                billingPeriodIso8601 = pricingPhase?.billingPeriod ?: ""
            )
        } ?: emptyList()
    }

    /** Launches Play's purchase UI for the given offer (from [queryProOffers], which must be called first in this session so the ProductDetails is cached). Returns false if the client isn't ready or the offer's product wasn't queried. */
    fun launchPurchase(activity: Activity, offerToken: String): Boolean {
        val client = billingClient?.takeIf { it.isReady } ?: return false
        val productDetails = cachedProductDetails ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    /** Acknowledges a purchase after it's been verified server-side — required within 3 days of purchase or Play auto-refunds it. Safe to call on an already-acknowledged purchase (checked first). */
    suspend fun acknowledge(purchase: Purchase): Boolean {
        val client = billingClient?.takeIf { it.isReady } ?: return false
        if (purchase.isAcknowledged) return true
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        return client.acknowledgePurchase(params).responseCode == BillingClient.BillingResponseCode.OK
    }

    /**
     * Re-checks for an existing active subscription — call at app startup/resume so a purchase
     * made on another device, or a renewal/cancellation Play processed while the app wasn't open,
     * gets re-verified against the server rather than relying solely on the purchase-time callback.
     */
    suspend fun queryExistingPurchases(): List<Purchase> {
        // On a cold launch, onResume can run before startConnection()'s async callback fires —
        // without this wait, the very first re-verification of the app's life would silently see
        // no purchases at all, and with no server-side RTDN handler yet, that's the only mechanism
        // that catches a cancellation/lapse that happened while the app was closed.
        if (billingClient?.isReady != true) withTimeoutOrNull(5000) { connected.await() }
        val client = billingClient?.takeIf { it.isReady } ?: return emptyList()
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        return client.queryPurchasesAsync(params).purchasesList
    }
}
