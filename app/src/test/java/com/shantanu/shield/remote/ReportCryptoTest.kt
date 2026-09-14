package com.shantanu.shield.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Guards the E2E core: only the holder of the key can read the report, and tampering is rejected. */
class ReportCryptoTest {

    @Test
    fun `generateKey returns a 256-bit key`() {
        assertEquals(32, ReportCrypto.generateKey().size)
    }

    @Test
    fun `encrypt then decrypt with the same key round-trips`() {
        val key = ReportCrypto.generateKey()
        val plaintext = "weekly report payload — totals only"
        val sealed = ReportCrypto.encrypt(plaintext, key)
        assertEquals(plaintext, ReportCrypto.decrypt(sealed, key))
    }

    @Test
    fun `decrypt with the wrong key returns null`() {
        val sealed = ReportCrypto.encrypt("secret", ReportCrypto.generateKey())
        assertNull(ReportCrypto.decrypt(sealed, ReportCrypto.generateKey()))
    }

    @Test
    fun `each encryption uses a fresh IV and produces different ciphertext`() {
        val key = ReportCrypto.generateKey()
        val a = ReportCrypto.encrypt("same plaintext", key)
        val b = ReportCrypto.encrypt("same plaintext", key)
        assertFalse("IV must be random per message", a.iv.contentEquals(b.iv))
        assertFalse("ciphertext must differ when the IV differs", a.ciphertext.contentEquals(b.ciphertext))
        // ...yet both still decrypt back to the original under the key.
        assertEquals("same plaintext", ReportCrypto.decrypt(a, key))
        assertEquals("same plaintext", ReportCrypto.decrypt(b, key))
    }

    @Test
    fun `tampered ciphertext fails authentication and returns null`() {
        val key = ReportCrypto.generateKey()
        val sealed = ReportCrypto.encrypt("trustworthy totals", key)
        val flipped = sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertNull(ReportCrypto.decrypt(Sealed(sealed.iv, flipped), key))
    }

    @Test
    fun `empty plaintext round-trips`() {
        val key = ReportCrypto.generateKey()
        val sealed = ReportCrypto.encrypt("", key)
        assertEquals("", ReportCrypto.decrypt(sealed, key))
    }

    @Test
    fun `a full encoded report survives encrypt then decrypt then decode`() {
        // The two Phase-1 pieces composed: codec -> crypto -> wire -> crypto -> codec.
        val key = ReportCrypto.generateKey()
        val report = RemoteReportPayload(
            generatedAtMs = 1_700_000_000_000L,
            kids = listOf(
                KidReport("Aarav", 60, 3_600_000L, listOf(1L, 2L, 3L), listOf(AppStat("YouTube", 9L)), 2),
            ),
        )
        val wire = ReportCrypto.encrypt(RemoteReportCodec.encode(report), key)
        val decodedPlain = ReportCrypto.decrypt(wire, key)
        assertEquals(report, RemoteReportCodec.decode(decodedPlain!!))
    }
}
