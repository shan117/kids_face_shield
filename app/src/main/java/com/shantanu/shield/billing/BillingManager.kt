package com.shantanu.shield.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.premium.PremiumSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real Google Play Billing implementation of [PremiumSource]. Replaces DormantPremiumSource.
 *
 * Dormant-safe: while the promo is on (`promo_active=true`), `Entitlements.isUnlocked` returns true
 * regardless of [isPremium], so this engine running changes nothing user-visible until the flip.
 * Everything is wrapped defensively — if Play/Billing is unavailable it keeps the cached value and
 * NEVER crashes or blocks protection (the service reads only the DataStore cache this writes).
 *
 * Premium = an active, acknowledged, signature-valid `premium` SUBS purchase. Re-reconciled on every
 * app launch ([start]); the cache is never treated as authoritative on its own.
 */
@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStoreManager: DataStoreManager,
) : PremiumSource, PurchasesUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isPremium = MutableStateFlow(false)
    override val isPremium: Flow<Boolean> = _isPremium.asStateFlow()

    // Exposed for the paywall UI (Phase B2): the live `premium` subscription's offers/prices.
    private val _products = MutableStateFlow<List<ProductDetails>>(emptyList())
    val products: StateFlow<List<ProductDetails>> = _products.asStateFlow()

    /** Result of a manual "Restore purchases" tap, for user feedback. */
    enum class RestoreResult { RESTORED, NOTHING_FOUND, UNAVAILABLE }

    private val _restoreEvents = MutableSharedFlow<RestoreResult>(extraBufferCapacity = 1)
    val restoreEvents: SharedFlow<RestoreResult> = _restoreEvents.asSharedFlow()

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    @Volatile private var started = false

    override fun start() {
        if (started) return
        started = true
        // Seed from the cache so there's an immediate value before Play responds.
        scope.launch { runCatching { _isPremium.value = dataStoreManager.cachedIsPremium.first() } }
        connect()
    }

    private fun connect() {
        runCatching {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        scope.launch { queryProducts(); reconcile() }
                    } else {
                        Log.w(TAG, "Billing setup failed: ${result.responseCode} ${result.debugMessage}")
                    }
                }

                override fun onBillingServiceDisconnected() {
                    // Simple bounded retry; never throw.
                    scope.launch { delay(RETRY_DELAY_MS); runCatching { connect() } }
                }
            })
        }.onFailure { Log.w(TAG, "startConnection failed", it) }
    }

    /** Load the `premium` subscription's ProductDetails (offers + prices) for the paywall. */
    private suspend fun queryProducts() {
        runCatching {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(PREMIUM_PRODUCT_ID)
                            .setProductType(BillingClient.ProductType.SUBS)
                            .build()
                    )
                ).build()
            val result = billingClient.queryProductDetails(params)
            if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                _products.value = result.productDetailsList ?: emptyList()
            }
        }.onFailure { Log.w(TAG, "queryProducts failed", it) }
    }

    /** Re-derive premium from Play's record of truth and write through to the cache. */
    private suspend fun reconcile() {
        runCatching {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS).build()
            val result = billingClient.queryPurchasesAsync(params)
            val purchases = result.purchasesList
            purchases.forEach { acknowledgeIfNeeded(it) }
            updatePremium(BillingLogic.isPremium(purchases.map { it.toInfo() }, PREMIUM_PRODUCT_ID))
        }.onFailure {
            // Keep the last cached value; never overwrite entitlement with a transient failure.
            Log.w(TAG, "reconcile failed; keeping cached premium", it)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) return
        scope.launch {
            purchases.forEach { acknowledgeIfNeeded(it) }
            if (BillingLogic.isPremium(purchases.map { it.toInfo() }, PREMIUM_PRODUCT_ID)) {
                updatePremium(true)
            }
        }
    }

    private suspend fun acknowledgeIfNeeded(p: Purchase) {
        if (p.purchaseState != Purchase.PurchaseState.PURCHASED || p.isAcknowledged) return
        if (!Security.verifyPurchase(PUBLIC_KEY, p.originalJson, p.signature)) return
        runCatching {
            billingClient.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
            )
        }.onFailure { Log.w(TAG, "acknowledge failed", it) }
    }

    private suspend fun updatePremium(value: Boolean) {
        _isPremium.value = value
        runCatching { dataStoreManager.setCachedIsPremium(value) }
    }

    private fun Purchase.toInfo() = BillingLogic.PurchaseInfo(
        productIds = products.toSet(),
        purchased = purchaseState == Purchase.PurchaseState.PURCHASED,
        signatureValid = Security.verifyPurchase(PUBLIC_KEY, originalJson, signature),
    )

    /**
     * Launch the Play purchase sheet for a chosen base plan/offer. Called from the paywall (B2).
     * No-ops safely if billing isn't ready.
     */
    fun launchPurchase(activity: Activity, productDetails: ProductDetails, offerToken: String) {
        runCatching {
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .setOfferToken(offerToken)
                            .build()
                    )
                )
                // Obfuscated, stable per-install id — lets Play flag fraud/abuse and ties the purchase
                // to this install without exposing any real account identifier. Server-side receipt
                // verification (deferred) can cross-check this later.
                .setObfuscatedAccountId(obfuscatedAccountId())
                .build()
            billingClient.launchBillingFlow(activity, params)
        }.onFailure { Log.w(TAG, "launchPurchase failed", it) }
    }

    /** Manual reconcile, e.g. a "Restore purchases" button. Emits a [RestoreResult] for UI feedback. */
    fun restore() {
        scope.launch {
            if (!billingClient.isReady) {
                connect()
                // Give the connection a beat, then check readiness for an honest message.
                delay(RETRY_DELAY_MS)
                if (!billingClient.isReady) {
                    _restoreEvents.emit(RestoreResult.UNAVAILABLE)
                    return@launch
                }
            }
            runCatching {
                val params = QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS).build()
                val purchases = billingClient.queryPurchasesAsync(params).purchasesList
                purchases.forEach { acknowledgeIfNeeded(it) }
                val premium = BillingLogic.isPremium(purchases.map { it.toInfo() }, PREMIUM_PRODUCT_ID)
                updatePremium(premium)
                _restoreEvents.emit(if (premium) RestoreResult.RESTORED else RestoreResult.NOTHING_FOUND)
            }.onFailure {
                Log.w(TAG, "restore failed", it)
                _restoreEvents.emit(RestoreResult.UNAVAILABLE)
            }
        }
    }

    /** Stable, non-PII per-install id (SHA-256 of ANDROID_ID) for Play's obfuscatedAccountId. */
    private fun obfuscatedAccountId(): String = runCatching {
        @Suppress("HardwareIds")
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ).orEmpty()
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(androidId.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(64)
    }.getOrDefault("unknown")

    companion object {
        const val PREMIUM_PRODUCT_ID = "premium"
        private const val TAG = "BillingManager"
        private const val RETRY_DELAY_MS = 2_000L

        // TODO(before charging): paste the app's Base64 RSA public key from
        // Play Console -> Monetization setup -> Licensing. Empty = signature verification disabled
        // (fine for the promo / internal-track testing; set it before the go-paid flip).
        private const val PUBLIC_KEY = ""
    }
}
