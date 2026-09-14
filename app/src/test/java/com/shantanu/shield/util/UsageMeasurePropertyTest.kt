package com.shantanu.shield.util

import com.shantanu.shield.util.UsageMeasure.Event
import com.shantanu.shield.util.UsageMeasure.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Property/fuzz tests for the usage reducer — guards the whole "phantom usage" class of bugs (the Clock
 * ~1h59m regression) over generated event streams, not just hand-picked cases.
 */
class UsageMeasurePropertyTest {

    private val rnd = Random(20260615)
    private val pkgs = listOf("a", "b", "c")

    /** A non-decreasing (in-order, like real UsageEvents) random event stream + a window end. */
    private fun randomStream(): Pair<List<Event>, Long> {
        val n = rnd.nextInt(0, 30)
        var t = rnd.nextLong(0, 1_000_000)
        val events = ArrayList<Event>(n)
        repeat(n) {
            t += rnd.nextLong(0, 100_000)
            val type = Type.entries[rnd.nextInt(Type.entries.size)]
            val pkg = if (type == Type.SCREEN_OFF) "" else pkgs[rnd.nextInt(pkgs.size)]
            events.add(Event(pkg, type, t))
        }
        return events to (t + rnd.nextLong(0, 1_000_000))
    }

    @Test
    fun `totals are positive and never exceed the window span`() {
        repeat(2000) {
            val (events, windowEnd) = randomStream()
            val totals = UsageMeasure.reduceForegroundMs(events, windowEnd)
            if (events.isEmpty()) {
                assertTrue(totals.isEmpty())
                return@repeat
            }
            val span = windowEnd - events.minOf { it.timeStamp }
            for ((pkg, ms) in totals) {
                assertTrue("negative total for $pkg", ms > 0L)
                assertTrue("total $ms exceeds span $span for $pkg", ms <= span)
            }
        }
    }

    @Test
    fun `a trailing screen-off pins totals — extending the window adds no phantom time`() {
        // The exact phantom-usage invariant: once the screen goes off, nothing accrues past it, so
        // pushing the window end far into the future must not change any total.
        repeat(1000) {
            val (events, _) = randomStream()
            val lastTs = events.lastOrNull()?.timeStamp ?: rnd.nextLong(0, 1_000_000)
            val screenOffAt = lastTs + rnd.nextLong(1, 10_000)
            val stream = events + Event("", Type.SCREEN_OFF, screenOffAt)

            val pinned = UsageMeasure.reduceForegroundMs(stream, screenOffAt)
            val extended = UsageMeasure.reduceForegroundMs(stream, screenOffAt + 5_000_000L)
            assertEquals(pinned, extended)
        }
    }

    @Test
    fun `sessionStats counts controlled sessions and the longest`() {
        val events = listOf(
            Event("a", Type.RESUMED, 0L),
            Event("a", Type.PAUSED_OR_STOPPED, 1000L),     // 1000 ms
            Event("b", Type.RESUMED, 2000L),
            Event("b", Type.PAUSED_OR_STOPPED, 5000L),     // 3000 ms (longest)
        )
        val s = UsageMeasure.sessionStats(events, 6000L, include = { true })
        assertEquals(2, s.count)
        assertEquals(3000L, s.longestMs)
    }

    @Test
    fun `sessionStats respects the include filter`() {
        val events = listOf(
            Event("a", Type.RESUMED, 0L), Event("a", Type.PAUSED_OR_STOPPED, 1000L),
            Event("b", Type.RESUMED, 2000L), Event("b", Type.PAUSED_OR_STOPPED, 3000L),
        )
        val s = UsageMeasure.sessionStats(events, 4000L, include = { it == "a" })
        assertEquals(1, s.count)
        assertEquals(1000L, s.longestMs)
    }

    @Test
    fun `hourly buckets sum to the total foreground time (no ms lost in hour-splitting)`() {
        repeat(1000) {
            val (events, end) = randomStream()
            val hourlySum = UsageMeasure.hourlyForegroundMs(events, end, include = { true }).sum()
            val total = UsageMeasure.reduceForegroundMs(events, end).values.sum()
            assertEquals(total, hourlySum)
        }
    }
}
