package com.shantanu.shield.premium

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the entitlement rule that the whole monetization model hinges on. If this drifts, features
 * get wrongly paywalled (or wrongly free), so the truth table is pinned explicitly.
 */
class EntitlementsTest {

    private val promoOn = PaywallConfig(promoActive = true)
    private val promoOff = PaywallConfig(promoActive = false)

    @Test
    fun `core features are always free`() {
        for (f in Feature.CORE_ALWAYS_FREE) {
            assertTrue("expected core $f free off-promo, non-premium",
                Entitlements.isUnlocked(f, promoOff, isPremium = false))
        }
    }

    @Test
    fun `during the promo everything is unlocked`() {
        assertTrue(Entitlements.isUnlocked(Feature.MULTI_KID_PROFILES, promoOn, isPremium = false))
        assertTrue(Entitlements.isUnlocked(Feature.THEMES, promoOn, isPremium = false))
    }

    @Test
    fun `premium feature is locked when promo off and user not premium`() {
        assertFalse(Entitlements.isUnlocked(Feature.MULTI_KID_PROFILES, promoOff, isPremium = false))
    }

    @Test
    fun `premium feature is unlocked when the user owns premium`() {
        assertTrue(Entitlements.isUnlocked(Feature.MULTI_KID_PROFILES, promoOff, isPremium = true))
    }

    @Test
    fun `a feature mapped free by config is unlocked for free users off-promo`() {
        val cfg = PaywallConfig(promoActive = false, premiumFeatures = emptySet())
        assertTrue(Entitlements.isUnlocked(Feature.SCHEDULES, cfg, isPremium = false))
    }

    @Test
    fun `core stays free even if config wrongly marks it premium`() {
        // Safety guard: config can never paywall child-safety features.
        val cfg = PaywallConfig(promoActive = false, premiumFeatures = setOf(Feature.APP_LOCK))
        assertTrue(Entitlements.isUnlocked(Feature.APP_LOCK, cfg, isPremium = false))
    }

    @Test
    fun `default config has the promo on so the app ships free`() {
        assertTrue(PaywallConfig.DEFAULT.promoActive)
        assertFalse(PaywallConfig.DEFAULT.paywallEnabled)
    }
}
