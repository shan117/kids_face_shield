package com.shantanu.shield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the profile/session serialization so multi-kid data survives encode/decode intact. */
class KidProfileCodecTest {

    @Test
    fun `profiles round-trip including custom allow-list`() {
        val profiles = listOf(
            KidProfile("p1", "Aarav", 0xFF006C7F, 60, 0, setOf("com.whatsapp"), 1000L, 0L, "2026-06-06"),
            KidProfile("p2", "Diya", 0xFFFF9E7A, 90, 2, setOf("com.android.chrome", "com.foo.bar"), 5000L, 1800000L, "2026-06-06")
        )
        assertEquals(profiles, KidProfileCodec.decode(KidProfileCodec.encode(profiles)))
    }

    @Test
    fun `empty input decodes to empty list`() {
        assertTrue(KidProfileCodec.decode("").isEmpty())
        assertTrue(KidProfileCodec.decode("   ").isEmpty())
    }

    @Test
    fun `name with delimiter characters is sanitized, not corrupting the record`() {
        val p = KidProfile("p1", "weird|name\nhere", 0xFF000000, 30, 1, emptySet())
        val decoded = KidProfileCodec.decode(KidProfileCodec.encode(listOf(p)))
        assertEquals(1, decoded.size)
        // delimiters replaced with spaces; record still parses to exactly one profile
        assertEquals("weird name here", decoded[0].name)
    }

    @Test
    fun `malformed lines are skipped, valid ones kept`() {
        val good = KidProfileCodec.encode(listOf(KidProfile("p1", "Kid", 0xFF006C7F, 60, 0)))
        val mixed = "garbage|too|few\n$good\nalso-bad"
        val decoded = KidProfileCodec.decode(mixed)
        assertEquals(1, decoded.size)
        assertEquals("p1", decoded[0].id)
    }

    @Test
    fun `sessions round-trip`() {
        val sessions = listOf(
            ProfileSession("p1", 1000L, 2000L),
            ProfileSession("p2", 3000L, 4500L)
        )
        assertEquals(sessions, KidProfileCodec.decodeSessions(KidProfileCodec.encodeSessions(sessions)))
    }
}
