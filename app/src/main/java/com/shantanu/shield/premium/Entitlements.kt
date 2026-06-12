package com.shantanu.shield.premium

/**
 * The single, pure rule that decides whether a feature is on. Kept side-effect-free so it can be
 * unit-tested without Android, Billing, or Firebase. Everything funnels through here.
 */
object Entitlements {
    fun isUnlocked(feature: Feature, config: PaywallConfig, isPremium: Boolean): Boolean {
        if (feature in Feature.CORE_ALWAYS_FREE) return true   // never gated — child safety
        if (config.promoActive) return true                    // free-for-all period
        if (config.tierOf(feature) == Tier.FREE) return true   // mapped free by config
        return isPremium                                       // otherwise needs premium
    }
}
