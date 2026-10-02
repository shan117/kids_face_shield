package com.shantanu.shield.webfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules that decide whether web filtering works at all: suffix matching and precedence.
 *
 * Both fail *quietly* when wrong — `notexample.com` slipping through a block on `example.com` looks
 * exactly like a filter that works, and a parent's ignored exception looks exactly like a broken app.
 * Hence the emphasis here on the near-misses rather than the happy path.
 */
class DomainBlocklistTest {

    private val lists = mapOf(
        WebFilterCategory.ADULT to setOf("adult-example.com"),
        WebFilterCategory.GAMBLING to setOf("bet-example.com"),
        WebFilterCategory.SOCIAL to setOf("social-example.com"),
    )
    private val allOn = WebFilterCategory.entries.toSet()

    // ---- scheme gate: the escape hatch that matters most ----

    @Test
    fun `only http and https are web schemes`() {
        assertTrue(DomainBlocklist.isWebScheme("http"))
        assertTrue(DomainBlocklist.isWebScheme("https"))
        assertTrue("case-insensitive", DomainBlocklist.isWebScheme("HTTPS"))
    }

    @Test
    fun `intent and app schemes are refused`() {
        // `intent://…#Intent;package=com.android.chrome;end` is how a page walks the child out of the
        // filtered browser into an unfiltered one. If this test fails, the whole feature is theatre.
        for (scheme in listOf("intent", "market", "tel", "sms", "file", "content", "javascript", "data", "")) {
            assertFalse(scheme, DomainBlocklist.isWebScheme(scheme))
        }
        assertFalse(DomainBlocklist.isWebScheme(null))
    }

    // ---- host normalisation ----

    @Test
    fun `normalises case, port and trailing dot`() {
        assertEquals("example.com", DomainBlocklist.normalizeHost("EXAMPLE.com"))
        assertEquals("example.com", DomainBlocklist.normalizeHost("example.com:8443"))
        assertEquals("example.com", DomainBlocklist.normalizeHost("example.com."))
        assertEquals("example.com", DomainBlocklist.normalizeHost("  example.com  "))
    }

    @Test
    fun `keeps the www label`() {
        // Stripping it would make an allowlist entry for www.example.com quietly cover the whole domain.
        assertEquals("www.example.com", DomainBlocklist.normalizeHost("www.example.com"))
    }

    @Test
    fun `blank and null hosts normalise to null`() {
        assertNull(DomainBlocklist.normalizeHost(null))
        assertNull(DomainBlocklist.normalizeHost(""))
        assertNull(DomainBlocklist.normalizeHost("   "))
    }

    // ---- suffix matching, and the near-misses ----

    @Test
    fun `matches the domain itself and any subdomain`() {
        assertTrue(DomainBlocklist.hostMatches("example.com", "example.com"))
        assertTrue(DomainBlocklist.hostMatches("ads.example.com", "example.com"))
        assertTrue(DomainBlocklist.hostMatches("a.b.c.example.com", "example.com"))
    }

    @Test
    fun `does NOT match a domain that merely ends with the same letters`() {
        // The classic endsWith bug.
        assertFalse(DomainBlocklist.hostMatches("notexample.com", "example.com"))
        assertFalse(DomainBlocklist.hostMatches("myexample.com", "example.com"))
    }

    @Test
    fun `does NOT match when the blocked domain is only a prefix of the host`() {
        // example.com.evil.net is a completely different site controlled by someone else.
        assertFalse(DomainBlocklist.hostMatches("example.com.evil.net", "example.com"))
    }

    @Test
    fun `does not match a parent domain from a subdomain entry`() {
        // Blocking ads.example.com must not take out example.com.
        assertFalse(DomainBlocklist.hostMatches("example.com", "ads.example.com"))
    }

    // ---- precedence ----

    @Test
    fun `an enabled category blocks, and names itself`() {
        val decision = DomainBlocklist.decide("adult-example.com", allOn, lists)
        assertEquals(FilterDecision.BlockedByCategory(WebFilterCategory.ADULT), decision)
        assertTrue(decision.isBlocked)
    }

    @Test
    fun `a disabled category does not block`() {
        val decision = DomainBlocklist.decide(
            host = "social-example.com",
            enabledCategories = allOn - WebFilterCategory.SOCIAL,
            categoryDomains = lists,
        )
        assertEquals(FilterDecision.Allowed, decision)
    }

    @Test
    fun `the parent allowlist beats an enabled category`() {
        // The point of allow-over-block: a parent can unblock one school site without switching a whole
        // category off. The other order makes exceptions look broken.
        val decision = DomainBlocklist.decide(
            host = "adult-example.com",
            enabledCategories = allOn,
            categoryDomains = lists,
            parentAllowed = setOf("adult-example.com"),
        )
        assertEquals(FilterDecision.Allowed, decision)
    }

    @Test
    fun `an allowlisted subdomain does not unblock its parent domain`() {
        val allowed = setOf("safe.adult-example.com")
        assertEquals(
            FilterDecision.Allowed,
            DomainBlocklist.decide("safe.adult-example.com", allOn, lists, parentAllowed = allowed),
        )
        assertEquals(
            FilterDecision.BlockedByCategory(WebFilterCategory.ADULT),
            DomainBlocklist.decide("other.adult-example.com", allOn, lists, parentAllowed = allowed),
        )
    }

    @Test
    fun `the parent blocklist blocks a site no category covers`() {
        val decision = DomainBlocklist.decide(
            host = "timewaster.com",
            enabledCategories = allOn,
            categoryDomains = lists,
            parentBlocked = setOf("timewaster.com"),
        )
        assertEquals(FilterDecision.BlockedByParent, decision)
    }

    @Test
    fun `the allowlist also beats the parent blocklist`() {
        val decision = DomainBlocklist.decide(
            host = "example.com",
            enabledCategories = allOn,
            categoryDomains = lists,
            parentAllowed = setOf("example.com"),
            parentBlocked = setOf("example.com"),
        )
        assertEquals("allow is the more specific, later intent", FilterDecision.Allowed, decision)
    }

    @Test
    fun `a host in two categories reports the same one every time`() {
        val overlapping = mapOf(
            WebFilterCategory.ADULT to setOf("both.com"),
            WebFilterCategory.GAMBLING to setOf("both.com"),
        )
        val results = (1..5).map { DomainBlocklist.decide("both.com", allOn, overlapping) }
        assertTrue(results.all { it == FilterDecision.BlockedByCategory(WebFilterCategory.ADULT) })
    }

    @Test
    fun `an unknown host with nothing configured is allowed`() {
        assertEquals(FilterDecision.Allowed, DomainBlocklist.decide("example.com", allOn, lists))
        assertEquals(FilterDecision.Allowed, DomainBlocklist.decide(null, allOn, lists))
    }

    @Test
    fun `no categories enabled blocks nothing`() {
        assertEquals(
            FilterDecision.Allowed,
            DomainBlocklist.decide("adult-example.com", emptySet(), lists),
        )
    }

    // ---- category defaults ----

    @Test
    fun `bypass-prevention is on by default`() {
        // Proxy/VPN sites protect every other category; defaulting them off would let a child undo the
        // whole filter with one visit.
        assertTrue(WebFilterCategory.PROXY_VPN in WebFilterCategory.DEFAULT_ON)
        assertTrue(WebFilterCategory.ADULT in WebFilterCategory.DEFAULT_ON)
    }

    @Test
    fun `judgement-call categories are off by default`() {
        // Over-blocking out of the box gets the whole filter switched off, which protects nobody.
        assertFalse(WebFilterCategory.SOCIAL in WebFilterCategory.DEFAULT_ON)
        assertFalse(WebFilterCategory.GAMING in WebFilterCategory.DEFAULT_ON)
    }

    @Test
    fun `category names round-trip tolerantly for the remote command`() {
        assertEquals(WebFilterCategory.ADULT, WebFilterCategory.fromName("ADULT"))
        assertEquals(WebFilterCategory.ADULT, WebFilterCategory.fromName(" adult "))
        assertNull(WebFilterCategory.fromName("NOT_A_CATEGORY"))
    }
}
