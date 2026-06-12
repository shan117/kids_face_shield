package com.shantanu.shield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KidPerAppLimitCodecTest {

    @Test
    fun roundTrip_twoProfiles() {
        val map = mapOf(
            "p1" to mapOf("com.youtube" to 30, "com.insta" to 45),
            "p2" to mapOf("com.youtube" to 60)
        )
        val decoded = KidPerAppLimitCodec.decode(KidPerAppLimitCodec.encode(map))
        assertEquals(map, decoded)
    }

    @Test
    fun emptyMap_encodesBlank_andDecodesEmpty() {
        assertEquals("", KidPerAppLimitCodec.encode(emptyMap()))
        assertTrue(KidPerAppLimitCodec.decode("").isEmpty())
    }

    @Test
    fun zeroOrNegativeMinutes_areDropped() {
        val map = mapOf("p1" to mapOf("a" to 0, "b" to -5, "c" to 20))
        val decoded = KidPerAppLimitCodec.decode(KidPerAppLimitCodec.encode(map))
        assertEquals(mapOf("p1" to mapOf("c" to 20)), decoded)
    }

    @Test
    fun profileWithNoPositiveLimits_isOmitted() {
        val map = mapOf("p1" to mapOf("a" to 0), "p2" to mapOf("b" to 15))
        val decoded = KidPerAppLimitCodec.decode(KidPerAppLimitCodec.encode(map))
        assertEquals(mapOf("p2" to mapOf("b" to 15)), decoded)
    }

    @Test
    fun garbageAndEmptyIds_areIgnored() {
        assertTrue(KidPerAppLimitCodec.decode("nonsense").isEmpty())
        assertEquals(
            mapOf("p1" to mapOf("a" to 10)),
            KidPerAppLimitCodec.decode("p1=a:10|garbage|=x:5")
        )
    }
}
