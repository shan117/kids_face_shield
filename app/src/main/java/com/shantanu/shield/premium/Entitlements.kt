package com.shantanu.shield.premium

/**
 * The single, pure rule that decides whether a feature is on. Kept side-effect-free so it can be
 * unit-tested without Android, Billing, or Firebase. Everything funnels through here.
 */
object Entitlements {
    /**
     * @param isPremium this device owns an active subscription (Play Billing).
     * @param grantedPremium a paired PARENT's subscription covers this device.
     *
     * [grantedPremium] exists because every premium feature runs on the CHILD's phone, which made no
     * purchase — so without it a parent could pay and get almost nothing working. Defaulted to false so
     * existing callers are unaffected. See ENTITLEMENT_SHARING_PLAN.md.
     */
    fun isUnlocked(
        feature: Feature,
        config: PaywallConfig,
        isPremium: Boolean,
        grantedPremium: Boolean = false,
    ): Boolean {
        if (config.promoActive) return true                    // free-for-all period
        if (config.tierOf(feature) == Tier.FREE) return true   // free by config (default or override)
        return isPremium || grantedPremium                     // own purchase, or the family's
    }
}
