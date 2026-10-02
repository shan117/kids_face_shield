package com.shantanu.shield.remote

import com.shantanu.shield.premium.Entitlements
import com.shantanu.shield.premium.Feature
import com.shantanu.shield.premium.PaywallConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lease that carries one payment to every device in a family.
 *
 * The failure directions are not symmetric, which is why these are worth pinning: over-granting costs a
 * little revenue, while wrongly dropping a grant leaves a child's phone unfiltered and unlimited with
 * nobody aware of it. Several tests below exist to make sure the second never happens quietly.
 */
class PremiumGrantTest {

    private val now = 1_700_000_000_000L

    // ---- lease arithmetic ----

    @Test
    fun `renew grants exactly the lease window`() {
        assertEquals(
            now + PremiumGrants.LEASE_MS,
            PremiumGrants.renew(now).premiumUntilMs,
        )
    }

    @Test
    fun `a fresh grant is valid`() {
        assertTrue(PremiumGrants.isValid(PremiumGrants.renew(now), now))
    }

    @Test
    fun `a grant is valid right up to its expiry and not at it`() {
        val grant = PremiumGrant(now + 1000)
        assertTrue(PremiumGrants.isValid(grant, now + 999))
        assertFalse("expiry is exclusive", PremiumGrants.isValid(grant, now + 1000))
        assertFalse(PremiumGrants.isValid(grant, now + 1001))
    }

    @Test
    fun `no grant is not valid`() {
        assertFalse(PremiumGrants.isValid(null, now))
        assertFalse(PremiumGrants.isValid(0L, now))
    }

    @Test
    fun `the lease is a week, not a day and not a month`() {
        // The one dial in this design: it is BOTH the offline tolerance and the free-premium window
        // after a cancellation. A day would strand an offline child; a month would gift a free month.
        val days = PremiumGrants.LEASE_MS / (24 * 60 * 60 * 1000)
        assertEquals(7L, days)
    }

    // ---- wire format ----

    @Test
    fun `round-trips a grant`() {
        val grant = PremiumGrants.renew(now)
        assertEquals(grant, PremiumGrantCodec.decode(PremiumGrantCodec.encode(grant)))
    }

    @Test
    fun `an unparseable grant decodes to null, never to valid`() {
        // A corrupt document must read as "no grant". Decoding it as 0 or as "now" would either strand a
        // paying family or hand out premium for free.
        assertNull(PremiumGrantCodec.decode(""))
        assertNull(PremiumGrantCodec.decode("   "))
        assertNull(PremiumGrantCodec.decode("not-a-number"))
        assertNull(PremiumGrantCodec.decode("-5"))
        assertNull(PremiumGrantCodec.decode("0"))
    }

    @Test
    fun `extra trailing fields still decode - forward compatible`() {
        val raw = listOf((now + 1000).toString(), "future-field").joinToString(Char(31).toString())
        assertEquals(now + 1000, PremiumGrantCodec.decode(raw)?.premiumUntilMs)
    }

    // ---- how it reaches the entitlement decision ----

    private val paidConfig = PaywallConfig.DEFAULT.copy(promoActive = false)

    @Test
    fun `a granted device unlocks premium features without owning a purchase`() {
        // The whole point: every premium feature runs on the child's phone, which never bought anything.
        assertTrue(
            Entitlements.isUnlocked(
                Feature.PER_APP_LIMITS, paidConfig, isPremium = false, grantedPremium = true,
            )
        )
        assertTrue(
            Entitlements.isUnlocked(
                Feature.REMOTE_CONTROL, paidConfig, isPremium = false, grantedPremium = true,
            )
        )
    }

    @Test
    fun `without a grant and without a purchase, premium features stay locked`() {
        assertFalse(
            Entitlements.isUnlocked(
                Feature.PER_APP_LIMITS, paidConfig, isPremium = false, grantedPremium = false,
            )
        )
    }

    @Test
    fun `an own purchase still works with no grant`() {
        // The single-device family: parent's phone, kid has no phone. Nothing to propagate.
        assertFalse(
            Entitlements.isUnlocked(Feature.PER_APP_LIMITS, paidConfig, isPremium = false)
        )
        assertTrue(
            Entitlements.isUnlocked(Feature.PER_APP_LIMITS, paidConfig, isPremium = true)
        )
    }

    @Test
    fun `web filtering needs no grant, because it ships default-free`() {
        // Worth pinning rather than assuming: WEB_FILTER is in DEFAULT_FREE, so a kid device filters
        // the web whether or not a grant ever arrives. If that tier is ever flipped to premium, this
        // fails and forces the consequence to be considered deliberately.
        assertTrue(Feature.WEB_FILTER in Feature.DEFAULT_FREE)
        assertTrue(
            Entitlements.isUnlocked(
                Feature.WEB_FILTER, paidConfig, isPremium = false, grantedPremium = false,
            )
        )
    }

    @Test
    fun `a grant cannot lock a FREE feature`() {
        // Free features must stay free regardless — a grant only ever adds.
        assertTrue(
            Entitlements.isUnlocked(
                Feature.KID_BUDGET, paidConfig, isPremium = false, grantedPremium = false,
            )
        )
    }

    @Test
    fun `the promo still overrides everything`() {
        assertTrue(
            Entitlements.isUnlocked(
                Feature.WEB_FILTER,
                PaywallConfig.DEFAULT,              // promoActive = true
                isPremium = false,
                grantedPremium = false,
            )
        )
    }

    @Test
    fun `the default keeps existing callers unaffected`() {
        // grantedPremium is defaulted, so the three-argument form must behave exactly as before.
        assertEquals(
            Entitlements.isUnlocked(Feature.THEMES, paidConfig, isPremium = true),
            Entitlements.isUnlocked(Feature.THEMES, paidConfig, isPremium = true, grantedPremium = false),
        )
    }
}
