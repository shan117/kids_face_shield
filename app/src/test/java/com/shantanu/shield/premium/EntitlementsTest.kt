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
    fun `default-free features are free under the default config off-promo`() {
        // promoOff uses the default premiumFeatures (= DEFAULT_PREMIUM), so the default-free set
        // isn't paywalled unless the config explicitly adds it.
        for (f in Feature.DEFAULT_FREE) {
            assertTrue("expected default-free $f free off-promo, non-premium",
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
    fun `any feature including a default-free one can be converted to premium by config`() {
        // Full flexibility: config can convert any feature to premium (no app update). It locks for
        // non-premium users off-promo, and unlocks once they own premium.
        val cfg = PaywallConfig(promoActive = false, premiumFeatures = setOf(Feature.APP_LOCK))
        assertFalse(Entitlements.isUnlocked(Feature.APP_LOCK, cfg, isPremium = false))
        assertTrue(Entitlements.isUnlocked(Feature.APP_LOCK, cfg, isPremium = true))
    }

    @Test
    fun `default config has the promo on so the app ships free`() {
        assertTrue(PaywallConfig.DEFAULT.promoActive)
        assertFalse(PaywallConfig.DEFAULT.paywallEnabled)
    }

    @Test
    fun `remote report ships premium-by-default but free during the promo`() {
        assertTrue(Feature.REMOTE_REPORT in Feature.DEFAULT_PREMIUM)
        assertTrue(Entitlements.isUnlocked(Feature.REMOTE_REPORT, promoOn, isPremium = false))
        assertFalse(Entitlements.isUnlocked(Feature.REMOTE_REPORT, promoOff, isPremium = false))
        assertTrue(Entitlements.isUnlocked(Feature.REMOTE_REPORT, promoOff, isPremium = true))
    }

    @Test
    fun `remote control ships premium-by-default but free during the promo`() {
        assertTrue(Feature.REMOTE_CONTROL in Feature.DEFAULT_PREMIUM)
        assertTrue(Entitlements.isUnlocked(Feature.REMOTE_CONTROL, promoOn, isPremium = false))
        assertFalse(Entitlements.isUnlocked(Feature.REMOTE_CONTROL, promoOff, isPremium = false))
        assertTrue(Entitlements.isUnlocked(Feature.REMOTE_CONTROL, promoOff, isPremium = true))
    }
}
