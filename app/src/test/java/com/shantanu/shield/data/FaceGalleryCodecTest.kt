package com.shantanu.shield.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the per-profile face-embedding serialization (kid identity gallery). */
class FaceGalleryCodecTest {

    @Test
    fun `gallery round-trips per profile`() {
        val gallery = mapOf(
            "p1" to floatArrayOf(0.1f, -0.2f, 0.3f),
            "p2" to floatArrayOf(0.9f, 0.05f, -0.44f)
        )
        val decoded = FaceGalleryCodec.decode(FaceGalleryCodec.encode(gallery))
        assertEquals(setOf("p1", "p2"), decoded.keys)
        assertArrayEquals(gallery["p1"], decoded["p1"], 1e-6f)
        assertArrayEquals(gallery["p2"], decoded["p2"], 1e-6f)
    }

    @Test
    fun `empty input decodes to empty map`() {
        assertTrue(FaceGalleryCodec.decode("").isEmpty())
        assertTrue(FaceGalleryCodec.decode("   ").isEmpty())
    }

    @Test
    fun `malformed entries are skipped`() {
        val good = FaceGalleryCodec.encode(mapOf("p1" to floatArrayOf(1f, 2f)))
        val decoded = FaceGalleryCodec.decode("no-colon-here;$good;p2:")
        assertEquals(setOf("p1"), decoded.keys)
    }
}
