package com.shantanu.shield.webfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The claim this screen makes to a parent is "your child's web is filtered". These tests exist so that
 * claim can never be made when it isn't true — a false assurance about a child's safety is worse than
 * showing nothing at all.
 */
class PrivateDnsStatesTest {

    // ---- provider matching ----

    @Test
    fun `recognises each known filtering provider`() {
        for (provider in DnsFilterProvider.entries) {
            assertEquals(provider, PrivateDnsStates.providerFor(provider.hostname))
        }
    }

    @Test
    fun `matching is case-insensitive and tolerates whitespace and a trailing dot`() {
        val host = DnsFilterProvider.CLEANBROWSING_FAMILY.hostname
        assertEquals(DnsFilterProvider.CLEANBROWSING_FAMILY, PrivateDnsStates.providerFor(host.uppercase()))
        assertEquals(DnsFilterProvider.CLEANBROWSING_FAMILY, PrivateDnsStates.providerFor("  $host  "))
        // A fully-qualified name with the root dot is the same server.
        assertEquals(DnsFilterProvider.CLEANBROWSING_FAMILY, PrivateDnsStates.providerFor("$host."))
    }

    @Test
    fun `does not recognise non-filtering or unknown resolvers`() {
        assertNull(PrivateDnsStates.providerFor("dns.google"))
        assertNull(PrivateDnsStates.providerFor("one.one.one.one"))
        assertNull(PrivateDnsStates.providerFor("dns.adguard-dns.com"))  // AdGuard, but NOT the family one
        assertNull(PrivateDnsStates.providerFor(null))
        assertNull(PrivateDnsStates.providerFor(""))
        assertNull(PrivateDnsStates.providerFor("   "))
    }

    @Test
    fun `a lookalike hostname is not accepted`() {
        // Substring matching would be the tempting shortcut and would accept an attacker-ish or
        // simply mistyped host as genuine filtering.
        assertNull(PrivateDnsStates.providerFor("family-filter-dns.cleanbrowsing.org.evil.com"))
        assertNull(PrivateDnsStates.providerFor("notfamily.adguard-dns.com"))
    }

    // ---- state interpretation ----

    @Test
    fun `unsupported wins over everything`() {
        // On API < 29 we cannot read the setting, so we must not report either outcome.
        val state = PrivateDnsStates.of(
            supported = false,
            active = true,
            hostname = DnsFilterProvider.CLEANBROWSING_FAMILY.hostname,
        )
        assertEquals(PrivateDnsState.Unsupported, state)
        assertFalse(state.isFiltering)
    }

    @Test
    fun `off when private DNS is not active`() {
        val state = PrivateDnsStates.of(supported = true, active = false, hostname = null)
        assertEquals(PrivateDnsState.Off, state)
        assertFalse(state.isFiltering)
    }

    @Test
    fun `active with a known provider is filtering`() {
        val state = PrivateDnsStates.of(
            supported = true,
            active = true,
            hostname = DnsFilterProvider.ADGUARD_FAMILY.hostname,
        )
        assertEquals(PrivateDnsState.ActiveFiltered(DnsFilterProvider.ADGUARD_FAMILY), state)
        assertTrue(state.isFiltering)
    }

    @Test
    fun `active with an unrecognised resolver is NOT reported as filtering`() {
        // The whole point of ActiveUnfiltered. dns.google encrypts and answers everything honestly;
        // calling that "filtered" would tell a parent their child is protected when they are not.
        val state = PrivateDnsStates.of(supported = true, active = true, hostname = "dns.google")

        assertEquals(PrivateDnsState.ActiveUnfiltered("dns.google"), state)
        assertFalse("encrypted is not filtered", state.isFiltering)
    }

    @Test
    fun `automatic mode reports active with no hostname and is not filtering`() {
        // Android's "Automatic" (opportunistic) mode: encryption where available, no filtering.
        val state = PrivateDnsStates.of(supported = true, active = true, hostname = null)

        assertEquals(PrivateDnsState.ActiveUnfiltered("automatic"), state)
        assertFalse(state.isFiltering)
    }

    @Test
    fun `a blank hostname is treated as automatic, not as filtering`() {
        val state = PrivateDnsStates.of(supported = true, active = true, hostname = "   ")
        assertEquals(PrivateDnsState.ActiveUnfiltered("automatic"), state)
        assertFalse(state.isFiltering)
    }

    @Test
    fun `only ActiveFiltered ever reports isFiltering`() {
        val states = listOf(
            PrivateDnsState.Unsupported,
            PrivateDnsState.Off,
            PrivateDnsState.ActiveUnfiltered("dns.google"),
            PrivateDnsState.ActiveFiltered(DnsFilterProvider.CLOUDFLARE_FAMILY),
        )
        assertEquals(
            listOf(false, false, false, true),
            states.map { it.isFiltering },
        )
    }

    @Test
    fun `the recommended provider blocks bypass sites`() {
        // Not cosmetic: blocking proxy/VPN sites is why CleanBrowsing is the default rather than the
        // faster, more permissive options. If the default ever changes, this should be reconsidered.
        assertEquals(DnsFilterProvider.CLEANBROWSING_FAMILY, DnsFilterProvider.RECOMMENDED)
        assertTrue(DnsFilterProvider.RECOMMENDED.blurb.contains("VPN", ignoreCase = true))
    }
}
