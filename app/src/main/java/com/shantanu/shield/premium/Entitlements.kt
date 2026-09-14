package com.shantanu.shield.premium

/**
 * The single, pure rule that decides whether a feature is on. Kept side-effect-free so it can be
 * unit-tested without Android, Billing, or Firebase. Everything funnels through here.
 */
object Entitlements {
    fun isUnlocked(feature: Feature, config: PaywallConfig, isPremium: Boolean): Boolean {
        if (config.promoActive) return true                    // free-for-all period
        if (config.tierOf(feature) == Tier.FREE) return true   // free by config (default or override)
        return isPremium                                       // otherwise needs premium
    }
}
