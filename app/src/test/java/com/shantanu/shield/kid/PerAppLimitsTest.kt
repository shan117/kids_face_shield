package com.shantanu.shield.kid

import com.shantanu.shield.data.PerAppLimitCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppLimitsTest {

    @Test
    fun `over when used minutes reach the app's limit`() {
        val usage = mapOf("com.instagram.android" to 30 * 60_000L)
        val limits = mapOf("com.instagram.android" to 30)
        assertTrue(PerAppLimits.isOver("com.instagram.android", usage, limits))
    }

    @Test
    fun `under the limit is not over`() {
        val usage = mapOf("com.instagram.android" to 20 * 60_000L)
        assertFalse(PerAppLimits.isOver("com.instagram.android", usage, mapOf("com.instagram.android" to 30)))
    }

    @Test
    fun `no limit set for the app is never over`() {
        assertFalse(PerAppLimits.isOver("com.foo", mapOf("com.foo" to 999 * 60_000L), emptyMap()))
    }

    @Test
    fun `codec round-trips and drops non-positive`() {
        val map = mapOf("a" to 30, "b" to 60)
        assertEquals(map, PerAppLimitCodec.decode(PerAppLimitCodec.encode(map)))
        assertTrue(PerAppLimitCodec.decode("").isEmpty())
        assertTrue(PerAppLimitCodec.decode("a:0").isEmpty())
    }
}
