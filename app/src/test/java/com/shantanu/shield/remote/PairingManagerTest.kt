package com.shantanu.shield.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the QR pairing wire format and the secrecy/round-trip guarantees of a minted pairing. */
class PairingManagerTest {

    @Test
    fun `newPairing mints a 128-bit id and a 256-bit key in hex`() {
        val p = PairingManager.newPairing()
        assertEquals(32, p.pairingIdHex.length)   // 16 bytes
        assertEquals(64, p.keyHex.length)          // 32 bytes
        assertTrue(p.pairingIdHex.all { it in "0123456789abcdef" })
        assertTrue(p.keyHex.all { it in "0123456789abcdef" })
        assertEquals(32, p.keyBytes().size)
    }

    @Test
    fun `each pairing is unique`() {
        val a = PairingManager.newPairing()
        val b = PairingManager.newPairing()
        assertNotEquals(a.pairingIdHex, b.pairingIdHex)
        assertNotEquals(a.keyHex, b.keyHex)
    }

    @Test
    fun `encodeQr then parseQr round-trips`() {
        val p = PairingManager.newPairing()
        val parsed = PairingManager.parseQr(PairingManager.encodeQr(p))
        assertEquals(p, parsed)
    }

    @Test
    fun `parseQr tolerates surrounding whitespace`() {
        val p = PairingManager.newPairing()
        val parsed = PairingManager.parseQr("  ${PairingManager.encodeQr(p)}\n")
        assertEquals(p, parsed)
    }

    @Test
    fun `parseQr normalizes uppercase hex to lowercase`() {
        val p = PairingManager.newPairing()
        val parsed = PairingManager.parseQr(PairingManager.encodeQr(p).uppercase())
        // SCHEME/VERSION uppercased too, but they compare to the literals... so uppercase whole string must
        // still fail on scheme. Guard the real intent: only the HEX should be case-insensitive.
        assertNull(parsed)
        val mixed = "shieldpair:1:${p.pairingIdHex.uppercase()}:${p.keyHex.uppercase()}"
        assertEquals(p, PairingManager.parseQr(mixed))
    }

    @Test
    fun `parseQr rejects a foreign or malformed QR`() {
        assertNull(PairingManager.parseQr("https://example.com"))
        assertNull(PairingManager.parseQr(""))
        assertNull(PairingManager.parseQr("shieldpair:1:tooShort:alsoShort"))
        assertNull(PairingManager.parseQr("wrongscheme:1:${"a".repeat(32)}:${"b".repeat(64)}"))
        assertNull(PairingManager.parseQr("shieldpair:2:${"a".repeat(32)}:${"b".repeat(64)}")) // future version
        assertNull(PairingManager.parseQr("shieldpair:1:${"z".repeat(32)}:${"b".repeat(64)}")) // non-hex id
    }

    @Test
    fun `a paired key actually decrypts what it encrypts`() {
        // PairingManager + ReportCrypto compose: the key that crosses the QR is a usable E2E key, and a
        // different pairing's key cannot read it.
        val pairing = PairingManager.newPairing()
        val sealed = ReportCrypto.encrypt("totals only", pairing.keyBytes())
        assertEquals("totals only", ReportCrypto.decrypt(sealed, pairing.keyBytes()))
        assertNull(ReportCrypto.decrypt(sealed, PairingManager.newPairing().keyBytes()))
    }
}
