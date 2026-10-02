package com.shantanu.shield.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The child's "ask for more time" request.
 *
 * Both rules pinned here protect the PARENT from the child's frustration rather than the reverse: a
 * stale ask must not be grantable hours later, and a child tapping repeatedly must not flood them.
 * Those are the two ways a well-meant feature turns into a reason to uninstall the app.
 */
class TimeRequestTest {

    private val now = 1_700_000_000_000L

    // ---- freshness ----

    @Test
    fun `a new request is fresh`() {
        assertTrue(TimeRequests.isFresh(TimeRequests.create(15, now), now))
    }

    @Test
    fun `a request expires after the window`() {
        val old = TimeRequests.create(15, now)
        assertTrue(TimeRequests.isFresh(old, now + TimeRequests.EXPIRY_MS - 1))
        assertFalse(TimeRequests.isFresh(old, now + TimeRequests.EXPIRY_MS))
        assertFalse(TimeRequests.isFresh(old, now + TimeRequests.EXPIRY_MS + 1))
    }

    @Test
    fun `no request is never fresh`() {
        assertFalse(TimeRequests.isFresh(null, now))
    }

    @Test
    fun `the window is hours, not minutes or days`() {
        // Minutes would expire an ask before a parent could reach their phone. Days would let a parent
        // clearing notifications in the evening grant time asked for at breakfast — time granted for a
        // forgotten reason reads as the app behaving randomly.
        val hours = TimeRequests.EXPIRY_MS / (60 * 60 * 1000)
        assertTrue("window is ${hours}h", hours in 1..12)
    }

    // ---- the ask itself ----

    @Test
    fun `asking twice replaces rather than queues`() {
        // One document, one pending request. A frustrated child tapping three times must produce one
        // ask — and the newest is the only one reflecting what they actually want.
        val first = TimeRequests.create(15, now)
        val second = TimeRequests.create(60, now + 5_000)

        assertEquals(60, second.minutes)
        assertTrue("the later ask is the live one", second.requestedAtMs > first.requestedAtMs)
    }

    @Test
    fun `minutes are clamped to something sane`() {
        // A corrupt or hostile value must not grant a day of screen time.
        assertEquals(1, TimeRequests.sanitize(0))
        assertEquals(1, TimeRequests.sanitize(-99))
        assertEquals(120, TimeRequests.sanitize(100_000))
        assertEquals(30, TimeRequests.sanitize(30))
    }

    @Test
    fun `the options are one-tap choices, not free entry`() {
        // Fixed options mean nothing for a child to negotiate up, and no keyboard at the moment of
        // frustration.
        assertEquals(listOf(15, 30, 60), TimeRequests.OPTIONS)
        assertTrue(TimeRequests.OPTIONS.all { it == TimeRequests.sanitize(it) })
    }

    // ---- wire format ----

    @Test
    fun `round-trips a request`() {
        val request = TimeRequests.create(30, now)
        assertEquals(request, TimeRequestCodec.decode(TimeRequestCodec.encode(request)))
    }

    @Test
    fun `an unparseable request decodes to null, never to a grantable ask`() {
        assertNull(TimeRequestCodec.decode(""))
        assertNull(TimeRequestCodec.decode("   "))
        assertNull(TimeRequestCodec.decode("garbage"))
        assertNull(TimeRequestCodec.decode("123"))                       // missing minutes
        assertNull(TimeRequestCodec.decode("0${Char(31)}15"))            // no timestamp
        assertNull(TimeRequestCodec.decode("$now${Char(31)}0"))          // no minutes
        assertNull(TimeRequestCodec.decode("$now${Char(31)}notanumber"))
    }

    @Test
    fun `a decoded request is clamped too`() {
        // The clamp has to survive the wire, not just the UI — the relay document is the untrusted path.
        val raw = "$now${Char(31)}99999"
        assertEquals(120, TimeRequestCodec.decode(raw)?.minutes)
    }

    @Test
    fun `extra trailing fields still decode - forward compatible`() {
        val raw = listOf(now.toString(), "15", "future-field").joinToString(Char(31).toString())
        val out = TimeRequestCodec.decode(raw)
        assertEquals(now, out?.requestedAtMs)
        assertEquals(15, out?.minutes)
    }
}
