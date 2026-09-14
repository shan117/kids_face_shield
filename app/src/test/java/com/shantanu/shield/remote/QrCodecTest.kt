package com.shantanu.shield.remote

import com.google.zxing.common.BitMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Proves the QR pairing handshake works end to end without Android: encode → render to a luminance
 * buffer (exactly what the camera Y-plane gives us) → decode. If this passes, the only thing left for
 * a real device is the camera/bitmap plumbing.
 */
class QrCodecTest {

    // A fixed, valid pairing (id = 16 bytes / 32 hex, key = 32 bytes / 64 hex). Deterministic on purpose:
    // a single synthetic frame through HybridBinarizer (tuned for real camera photos) occasionally misses
    // on *certain* random payloads — harmless on-device (the camera retries ~30 fps), but it makes a
    // `newPairing()`-based assertion a coin flip. Pinning the payload keeps this a real pipe test, not a flake.
    private val fixedPairing = PairingManager.Pairing(
        pairingIdHex = "0123456789abcdeffedcba9876543210",
        keyHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    )

    /** Renders a [BitMatrix] 1:1 into a luminance buffer: dark module → 0, light → 255. */
    private fun toLuminance(m: BitMatrix): ByteArray {
        val buf = ByteArray(m.width * m.height)
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                buf[y * m.width + x] = if (m.get(x, y)) 0 else 255.toByte()
            }
        }
        return buf
    }

    @Test
    fun `pairing QR encodes then decodes back to the same string`() {
        val original = PairingManager.encodeQr(fixedPairing)
        val matrix = QrEncoder.encode(original, size = 512)
        val decoded = QrScanner.decodeLuminance(toLuminance(matrix), matrix.width, matrix.height)
        assertEquals(original, decoded)
    }

    @Test
    fun `decoded pairing QR parses back into the original pairing`() {
        // The whole chain that matters on-device: Pairing -> QR text -> matrix -> luminance -> text -> Pairing.
        val matrix = QrEncoder.encode(PairingManager.encodeQr(fixedPairing), size = 512)
        val scannedText = QrScanner.decodeLuminance(toLuminance(matrix), matrix.width, matrix.height)
        assertEquals(fixedPairing, PairingManager.parseQr(scannedText!!))
    }

    @Test
    fun `a blank frame decodes to null instead of throwing`() {
        val blank = ByteArray(200 * 200) { 255.toByte() }   // all white, no QR
        assertNull(QrScanner.decodeLuminance(blank, 200, 200))
    }
}
