package com.shantanu.shield.premium

/**
 * Runtime configuration snapshot. Shipped with [DEFAULT] (promo on → everything free) and later
 * overridden by Firebase Remote Config without an app update.
 *
 * NOTE: gating is driven by [promoActive], not by [freeUntilEpochMs]. The promo ends only when the
 * owner sets `promo_active=false` — the date is for the countdown/messaging UI, so there is no
 * surprise auto-cutoff.
 */
data class PaywallConfig(
    val promoActive: Boolean = true,
    val freeUntilEpochMs: Long = 0L,          // display/countdown only
    val paywallEnabled: Boolean = false,
    val priceTier: String = "default",
    val premiumFeatures: Set<Feature> = Feature.DEFAULT_PREMIUM,
    val paywallVariant: String = "a"
) {
    /** Effective tier of a feature. Core features are FREE regardless of [premiumFeatures]. */
    fun tierOf(feature: Feature): Tier = when {
        feature in Feature.CORE_ALWAYS_FREE -> Tier.FREE
        feature in premiumFeatures -> Tier.PREMIUM
        else -> Tier.FREE
    }

    companion object {
        val DEFAULT = PaywallConfig()
    }
}
