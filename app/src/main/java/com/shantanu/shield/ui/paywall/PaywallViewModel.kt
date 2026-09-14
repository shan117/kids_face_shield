package com.shantanu.shield.ui.paywall

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.ProductDetails
import com.shantanu.shield.billing.BillingManager
import com.shantanu.shield.premium.EntitlementRepository
import com.shantanu.shield.premium.PaywallConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** A purchasable plan as the paywall renders it (one base plan + its best offer). */
data class PlanUi(
    val basePlanId: String,
    val title: String,            // "Monthly" / "Annual"
    val price: String,            // formatted, e.g. "₹149"
    val periodSuffix: String,     // "/month" / "/year"
    val trialText: String?,       // "30-day free trial" or null
    val offerToken: String,
    val productDetails: ProductDetails? = null, // null only in @Preview
)

/**
 * Backs the early-access / paywall screen. Reads the runtime [PaywallConfig] (promo vs paywall mode)
 * and, in paywall mode, the live `premium` plans + purchase actions from [BillingManager].
 *
 * Dormant-safe: while the promo is on the paywall mode never renders, so these stay unused.
 */
@HiltViewModel
class PaywallViewModel @Inject constructor(
    entitlements: EntitlementRepository,
    private val billingManager: BillingManager,
) : ViewModel() {

    val config: StateFlow<PaywallConfig> =
        entitlements.config.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), PaywallConfig.DEFAULT
        )

    val isPremium: StateFlow<Boolean> =
        entitlements.isPremium.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), false
        )

    val plans: StateFlow<List<PlanUi>> =
        billingManager.products.map { toPlans(it) }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
        )

    fun purchase(activity: Activity, plan: PlanUi) {
        plan.productDetails?.let { billingManager.launchPurchase(activity, it, plan.offerToken) }
    }

    val restoreEvents = billingManager.restoreEvents

    fun restore() = billingManager.restore()

    // Map the `premium` subscription's offers → one display plan per base plan, preferring the offer
    // that carries a free trial. Monthly first, annual second.
    private fun toPlans(products: List<ProductDetails>): List<PlanUi> {
        val premium = products.firstOrNull { it.productId == BillingManager.PREMIUM_PRODUCT_ID }
            ?: return emptyList()
        val offers = premium.subscriptionOfferDetails ?: return emptyList()
        return offers.groupBy { it.basePlanId }.mapNotNull { (basePlanId, planOffers) ->
            val offer = planOffers.firstOrNull { o ->
                o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
            } ?: planOffers.firstOrNull() ?: return@mapNotNull null
            val phases = offer.pricingPhases.pricingPhaseList
            val recurring = phases.lastOrNull { it.priceAmountMicros > 0L }
                ?: phases.lastOrNull() ?: return@mapNotNull null
            val trial = phases.firstOrNull { it.priceAmountMicros == 0L }
            PlanUi(
                basePlanId = basePlanId,
                title = SubscriptionFormat.planTitle(recurring.billingPeriod),
                price = recurring.formattedPrice,
                periodSuffix = SubscriptionFormat.periodSuffix(recurring.billingPeriod),
                trialText = trial?.let { SubscriptionFormat.trialLabel(it.billingPeriod) },
                offerToken = offer.offerToken,
                productDetails = premium,
            )
        }.sortedBy { it.periodSuffix } // "/month" before "/year"
    }
}
