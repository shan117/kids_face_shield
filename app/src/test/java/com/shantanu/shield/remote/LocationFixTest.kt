package com.shantanu.shield.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the retention and wire rules from LOCATION_FEATURE_PLAN.md §3. These are the parts that can
 * silently lose a parent's data or leak a stale coordinate, so they live in pure code and are tested
 * without Android, Firestore or a clock.
 */
class LocationFixTest {

    private fun fix(at: Long, lat: Double = 12.9716, lon: Double = 77.5946) = LocationFix(
        requestedAtMs = at,
        fixedAtMs = at + 3_000,
        status = FixStatus.OK,
        lat = lat,
        lon = lon,
        accuracyM = 12.5f,
        batteryPct = 63,
    )

    // ---- LocationLog ----

    @Test
    fun `append puts the newest fix first`() {
        val log = LocationLog.append(LocationLog.append(emptyList(), fix(100)), fix(200))
        assertEquals(listOf(200L, 100L), log.map { it.requestedAtMs })
    }

    @Test
    fun `latest is the newest entry whatever its status`() {
        val withFailure = LocationLog.append(
            LocationLog.append(emptyList(), fix(100)),
            LocationFix.failure(FixStatus.TIMEOUT, requestedAtMs = 200),
        )
        assertEquals(200L, LocationLog.latest(withFailure)?.requestedAtMs)
        assertEquals(FixStatus.TIMEOUT, LocationLog.latest(withFailure)?.status)
    }

    @Test
    fun `latest of an empty log is null`() {
        assertNull(LocationLog.latest(emptyList()))
    }

    @Test
    fun `append caps the log and drops only the oldest`() {
        var log = emptyList<LocationFix>()
        for (i in 1..LocationLog.MAX_ENTRIES + 10) log = LocationLog.append(log, fix(i.toLong()))

        assertEquals(LocationLog.MAX_ENTRIES, log.size)
        assertEquals("newest kept", (LocationLog.MAX_ENTRIES + 10).toLong(), log.first().requestedAtMs)
        assertEquals("oldest survivor is exactly at the cap boundary", 11L, log.last().requestedAtMs)
    }

    @Test
    fun `ordered re-sorts a log that arrived out of order`() {
        val jumbled = listOf(fix(100), fix(300), fix(200))
        assertEquals(listOf(300L, 200L, 100L), LocationLog.ordered(jumbled).map { it.requestedAtMs })
    }

    // ---- failure construction ----

    @Test
    fun `a failure carries no coordinates`() {
        val f = LocationFix.failure(FixStatus.PERMISSION_DENIED, requestedAtMs = 500, batteryPct = 20)

        assertEquals(FixStatus.PERMISSION_DENIED, f.status)
        assertEquals(0.0, f.lat, 0.0)
        assertEquals(0.0, f.lon, 0.0)
        assertEquals(0L, f.fixedAtMs)
        assertEquals(20, f.batteryPct)
        assertFalse(f.isUsable)
    }

    @Test
    fun `only OK is usable`() {
        assertTrue(fix(1).isUsable)
        for (status in FixStatus.entries.filter { it != FixStatus.OK }) {
            assertFalse(status.name, LocationFix.failure(status, 1).isUsable)
        }
    }

    // ---- codec ----

    @Test
    fun `round-trips a log`() {
        val log = listOf(fix(300), fix(200), LocationFix.failure(FixStatus.LOCATION_DISABLED, 100))
        assertEquals(log, LocationFixCodec.decode(LocationFixCodec.encode(log)))
    }

    @Test
    fun `round-trips an empty log`() {
        assertTrue(LocationFixCodec.encode(emptyList()).isEmpty())
        assertTrue(LocationFixCodec.decode("").isEmpty())
        assertTrue(LocationFixCodec.decode("   ").isEmpty())
    }

    @Test
    fun `round-trips southern and western hemisphere coordinates`() {
        // Negative lat/lon are the easy thing to lose to a sloppy parser.
        val log = listOf(fix(1, lat = -33.8688, lon = -151.2093))
        val out = LocationFixCodec.decode(LocationFixCodec.encode(log)).single()
        assertEquals(-33.8688, out.lat, 0.000001)
        assertEquals(-151.2093, out.lon, 0.000001)
    }

    @Test
    fun `round-trips every status`() {
        val log = FixStatus.entries.mapIndexed { i, s -> LocationFix.failure(s, i.toLong()) }
        assertEquals(
            FixStatus.entries.toList(),
            LocationFixCodec.decode(LocationFixCodec.encode(log)).map { it.status },
        )
    }

    @Test
    fun `a malformed record is dropped without losing the others`() {
        val good = LocationFixCodec.encode(listOf(fix(300), fix(200)))
        val corrupted = good + Char(30) + "not-a-valid-record"

        assertEquals(listOf(300L, 200L), LocationFixCodec.decode(corrupted).map { it.requestedAtMs })
    }

    @Test
    fun `a record with a non-numeric timestamp is dropped`() {
        val record = listOf("not-a-number", "0", "OK", "1.0", "2.0", "5.0", "50")
            .joinToString(Char(31).toString())
        assertTrue(LocationFixCodec.decode(record).isEmpty())
    }

    @Test
    fun `extra trailing fields still decode - forward compatible`() {
        val record = listOf("100", "200", "OK", "1.5", "2.5", "9.0", "77", "future-field")
            .joinToString(Char(31).toString())
        val out = LocationFixCodec.decode(record).single()

        assertEquals(100L, out.requestedAtMs)
        assertEquals(77, out.batteryPct)
    }

    @Test
    fun `an unknown status from a newer child decodes to TIMEOUT`() {
        // Forward compatibility: "couldn't get a fix" is the honest reading of an outcome we can't name,
        // and is non-alarming — unlike defaulting to PERMISSION_DENIED, which would accuse the child.
        val record = listOf("100", "0", "SOME_FUTURE_STATUS", "0.0", "0.0", "0.0", "-1")
            .joinToString(Char(31).toString())
        assertEquals(FixStatus.TIMEOUT, LocationFixCodec.decode(record).single().status)
    }
}
