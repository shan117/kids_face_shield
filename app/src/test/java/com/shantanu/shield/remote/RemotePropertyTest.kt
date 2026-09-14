package com.shantanu.shield.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * Property/fuzz tests (Test Strategy L2): generated-input round-trips + "never crash" invariants for the
 * privacy-critical parsers and crypto. Seeded for reproducibility. No new dependency — a JUnit4 loop.
 */
class RemotePropertyTest {

    private val rnd = Random(20260615)

    // Clean = no delimiter/control chars, so codec round-trips are exact.
    private val alphabet = (('a'..'z') + ('A'..'Z') + ('0'..'9') + ' ').joinToString("")
    private fun cleanStr(maxLen: Int = 16): String {
        val n = rnd.nextInt(0, maxLen + 1)
        return buildString { repeat(n) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
    }

    // Arbitrary = includes the control chars (28–31) the codecs use as delimiters — to fuzz parsing.
    private fun randomStr(maxLen: Int = 32): String {
        val n = rnd.nextInt(0, maxLen + 1)
        return buildString { repeat(n) { append(rnd.nextInt(0, 128).toChar()) } }
    }

    private fun randomPayload(): RemoteReportPayload {
        val kids = List(rnd.nextInt(0, 4)) {
            KidReport(
                name = cleanStr(),
                limitMin = rnd.nextInt(0, 600),
                dailyAvgMs = rnd.nextLong(0, 100_000_000),
                week = List(rnd.nextInt(0, 8)) { rnd.nextLong(0, 50_000_000) },
                topApps = List(rnd.nextInt(0, 6)) { AppStat(cleanStr(), rnd.nextLong(0, 50_000_000)) },
                budgetHitDays = rnd.nextInt(0, 8),
                hourly = List(rnd.nextInt(0, 25)) { rnd.nextLong(0, 10_000_000) },
                categories = List(rnd.nextInt(0, 5)) { AppStat(cleanStr(), rnd.nextLong(0, 50_000_000)) },
                weeks = List(rnd.nextInt(0, 5)) { rnd.nextLong(0, 50_000_000) },
                sessions = rnd.nextInt(0, 100),
                longestMs = rnd.nextLong(0, 50_000_000),
            )
        }
        return RemoteReportPayload(rnd.nextLong(0, Long.MAX_VALUE / 2), kids)
    }

    private fun randomCommand() = RemoteCommand(
        commandId = cleanStr(),
        issuedAtMs = rnd.nextLong(0, Long.MAX_VALUE / 2),
        type = CommandType.entries[rnd.nextInt(CommandType.entries.size)],
        arg = rnd.nextInt(-1000, 1000),
    )

    @Test
    fun `report codec round-trips for arbitrary clean payloads`() {
        repeat(800) {
            val p = randomPayload()
            assertEquals(p, RemoteReportCodec.decode(RemoteReportCodec.encode(p)))
        }
    }

    @Test
    fun `command codec round-trips for arbitrary clean commands`() {
        repeat(800) {
            val c = randomCommand()
            assertEquals(c, RemoteCommandCodec.decode(RemoteCommandCodec.encode(c)))
        }
    }

    @Test
    fun `codecs never throw on arbitrary garbage input`() {
        repeat(2000) {
            RemoteReportCodec.decode(randomStr())   // must not throw (returns value or null)
            RemoteCommandCodec.decode(randomStr())
        }
    }

    @Test
    fun `crypto round-trips and rejects the wrong key`() {
        repeat(300) {
            val key = ReportCrypto.generateKey()
            val plaintext = randomStr(80)
            val sealed = ReportCrypto.encrypt(plaintext, key)
            assertEquals(plaintext, ReportCrypto.decrypt(sealed, key))
            assertNull(ReportCrypto.decrypt(sealed, ReportCrypto.generateKey()))
        }
    }

    @Test
    fun `crypto returns null (never throws) on random ciphertext`() {
        repeat(300) {
            val iv = ByteArray(12).also { rnd.nextBytes(it) }
            val ct = ByteArray(rnd.nextInt(0, 64)).also { rnd.nextBytes(it) }
            assertNull(ReportCrypto.decrypt(Sealed(iv, ct), ReportCrypto.generateKey()))
        }
    }

    @Test
    fun `pairing QR round-trips and parses garbage to null without throwing`() {
        repeat(500) {
            val p = PairingManager.newPairing()
            assertEquals(p, PairingManager.parseQr(PairingManager.encodeQr(p)))
        }
        repeat(1000) {
            PairingManager.parseQr(randomStr(48))   // must not throw
        }
    }

    @Test
    fun `qr scanner never throws on random luminance buffers`() {
        repeat(40) {
            val w = rnd.nextInt(8, 40)
            val h = rnd.nextInt(8, 40)
            val lum = ByteArray(w * h).also { rnd.nextBytes(it) }
            QrScanner.decodeLuminance(lum, w, h)     // result null-or-string; must not throw
        }
    }
}
