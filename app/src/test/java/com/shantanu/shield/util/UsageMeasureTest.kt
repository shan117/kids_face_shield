package com.shantanu.shield.util

import com.shantanu.shield.util.UsageMeasure.Event
import com.shantanu.shield.util.UsageMeasure.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the event-pairing math now used by BOTH the Stats charts and the Kid Mode
 * budget counter. A regression here would directly mis-measure the budget that locks the
 * kid, so the edge cases (unclosed sessions, dangling pauses, out-of-order events) are
 * pinned explicitly.
 */
class UsageMeasureTest {

    private fun resumed(pkg: String, ts: Long) = Event(pkg, Type.RESUMED, ts)
    private fun paused(pkg: String, ts: Long) = Event(pkg, Type.PAUSED_OR_STOPPED, ts)

    @Test
    fun `a closed session counts its duration`() {
        val r = UsageMeasure.reduceForegroundMs(listOf(resumed("a", 0), paused("a", 1_000)), windowEndMs = 5_000)
        assertEquals(1_000L, r["a"])
    }

    @Test
    fun `an unclosed session counts up to the window end`() {
        val r = UsageMeasure.reduceForegroundMs(listOf(resumed("a", 500)), windowEndMs = 2_000)
        assertEquals(1_500L, r["a"])
    }

    @Test
    fun `multiple sessions of the same package sum`() {
        val r = UsageMeasure.reduceForegroundMs(
            listOf(resumed("a", 0), paused("a", 100), resumed("a", 200), paused("a", 350)),
            windowEndMs = 9_999
        )
        assertEquals(250L, r["a"])
    }

    @Test
    fun `a pause with no matching resume is ignored`() {
        val r = UsageMeasure.reduceForegroundMs(listOf(paused("a", 100)), windowEndMs = 9_999)
        assertTrue(r.isEmpty())
    }

    @Test
    fun `interleaved packages are tracked independently`() {
        val r = UsageMeasure.reduceForegroundMs(
            listOf(resumed("a", 0), resumed("b", 100), paused("a", 200), paused("b", 450)),
            windowEndMs = 9_999
        )
        assertEquals(200L, r["a"])
        assertEquals(350L, r["b"])
    }

    @Test
    fun `out-of-order timestamps clamp the delta to zero, never negative`() {
        val r = UsageMeasure.reduceForegroundMs(listOf(resumed("a", 1_000), paused("a", 500)), windowEndMs = 9_999)
        // 0-length session is dropped (only positive totals are returned).
        assertTrue(r.isEmpty())
    }

    @Test
    fun `a re-resume without a pause keeps only the later session start`() {
        // RESUMED, RESUMED (no pause), then PAUSED → counts from the second resume.
        val r = UsageMeasure.reduceForegroundMs(
            listOf(resumed("a", 0), resumed("a", 300), paused("a", 500)),
            windowEndMs = 9_999
        )
        assertEquals(200L, r["a"])
    }
}
