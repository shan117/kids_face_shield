package com.shantanu.shield.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the 1:N identify rules that drive per-kid attribution. The "ambiguous siblings" case is the
 * important one — two close matches must resolve to "unknown", not a confident wrong pick.
 */
class FaceMatcherTest {

    @Test
    fun `clear best match above threshold is identified`() {
        val gallery = mapOf("p1" to floatArrayOf(1f, 0f), "p2" to floatArrayOf(0f, 1f))
        assertEquals("p1", FaceMatcher.identify(floatArrayOf(1f, 0f), gallery).profileId)
    }

    @Test
    fun `ambiguous match (two close scores) resolves to unknown`() {
        // Probe is equidistant from both → margin 0 < 0.08 → null.
        val gallery = mapOf("p1" to floatArrayOf(1f, 0f), "p2" to floatArrayOf(0f, 1f))
        assertNull(FaceMatcher.identify(floatArrayOf(0.7f, 0.7f), gallery).profileId)
    }

    @Test
    fun `best below threshold is unknown`() {
        val gallery = mapOf("p1" to floatArrayOf(1f, 0f))
        assertNull(FaceMatcher.identify(floatArrayOf(0.5f, 0f), gallery, threshold = 0.6f).profileId)
    }

    @Test
    fun `single enrolled face above threshold is identified (no runner-up)`() {
        val gallery = mapOf("p1" to floatArrayOf(1f, 0f))
        val r = FaceMatcher.identify(floatArrayOf(1f, 0f), gallery)
        assertEquals("p1", r.profileId)
        assertEquals(0f, r.runnerUp, 1e-6f)
    }

    @Test
    fun `empty gallery is unknown`() {
        assertNull(FaceMatcher.identify(floatArrayOf(1f, 0f), emptyMap()).profileId)
    }
}
