package com.shantanu.shield.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The safety property that matters most once a parent can link several children: a command or report
 * meant for one child must be unreadable by another, even though every document sits in the same
 * Firestore collection and the parent phone holds all the keys at once.
 *
 * This is guaranteed by construction — each [PairedDevice] carries its own AES-256 key, and
 * [RemoteCommandSender] encrypts with the key of the device it was handed. These tests pin that the
 * per-device key really is what gets used, so a future refactor cannot quietly collapse back to a
 * single shared key. See MULTI_DEVICE_PAIRING_PLAN.md §2.
 */
class MultiDeviceIsolationTest {

    private fun freshDevice(label: String): PairedDevice {
        val p = PairingManager.newPairing()
        return PairedDevice(p.pairingIdHex, p.keyHex, label, addedAtMs = 1L)
    }

    @Test
    fun `two linked devices get different ids and different keys`() {
        val a = freshDevice("Aarav's phone")
        val b = freshDevice("Diya's phone")

        assertNotEquals(a.pairingId, b.pairingId)
        assertNotEquals(a.keyHex, b.keyHex)
    }

    @Test
    fun `a command encrypted for one child cannot be read by the other`() {
        val a = freshDevice("Aarav's phone")
        val b = freshDevice("Diya's phone")

        val command = RemoteCommand(
            commandId = "cmd-1",
            issuedAtMs = 1_000L,
            type = CommandType.SET_DAILY_LIMIT,
            arg = 90,
            payload = "",
        )
        val sealed = ReportCrypto.encrypt(RemoteCommandCodec.encode(command), a.pairing().keyBytes())

        // The intended child decodes it.
        val forA = ReportCrypto.decrypt(sealed, a.pairing().keyBytes())?.let { RemoteCommandCodec.decode(it) }
        assertEquals(90, forA?.arg)
        assertEquals(CommandType.SET_DAILY_LIMIT, forA?.type)

        // The sibling cannot. Decryption IS authorization — knowing the doc id is not enough.
        assertNull("a sibling's key must not open this command", ReportCrypto.decrypt(sealed, b.pairing().keyBytes()))
    }

    @Test
    fun `a report encrypted for one child cannot be read by the other`() {
        val a = freshDevice("Aarav's phone")
        val b = freshDevice("Diya's phone")

        val payload = RemoteReportPayload(
            generatedAtMs = 42L,
            kids = listOf(
                KidReport(
                    name = "Aarav",
                    limitMin = 120,
                    dailyAvgMs = 3_600_000L,
                    week = listOf(1L, 2L, 3L),
                    topApps = emptyList(),
                    budgetHitDays = 1,
                )
            ),
        )
        val sealed = ReportCrypto.encrypt(RemoteReportCodec.encode(payload), a.pairing().keyBytes())

        val decodedForA = ReportCrypto.decrypt(sealed, a.pairing().keyBytes())?.let { RemoteReportCodec.decode(it) }
        assertEquals("Aarav", decodedForA?.kids?.firstOrNull()?.name)

        assertNull("a sibling's key must not open this report", ReportCrypto.decrypt(sealed, b.pairing().keyBytes()))
    }

    @Test
    fun `pairing() round-trips the stored hex back into the original secrets`() {
        val minted = PairingManager.newPairing()
        val device = PairedDevice(minted.pairingIdHex, minted.keyHex, "Child", 1L)

        assertEquals(minted.pairingIdHex, device.pairing().pairingIdHex)
        assertEquals(
            minted.keyBytes().toList(),
            device.pairing().keyBytes().toList(),
        )
    }

    @Test
    fun `a device surviving an encode-decode cycle still decrypts its own traffic`() {
        val a = freshDevice("Aarav's phone")
        val sealed = ReportCrypto.encrypt("hello", a.pairing().keyBytes())

        val restored = PairedDeviceCodec.decode(PairedDeviceCodec.encode(listOf(a))).single()

        assertEquals("hello", ReportCrypto.decrypt(sealed, restored.pairing().keyBytes()))
    }
}
