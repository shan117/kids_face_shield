package com.shantanu.shield.remote

import java.security.SecureRandom

/**
 * Pure pairing core for the remote report: mints the secret that ties a child device to a parent viewer,
 * and defines the QR wire format that carries it. No Android, no crypto provider beyond [ReportCrypto] —
 * fully unit-testable on the JVM.
 *
 * A [Pairing] is two secrets:
 *  - [pairingIdHex] — a random 128-bit id. It is the Firestore doc key AND a bearer token (whoever holds
 *    it can fetch the ciphertext), so it must be unguessable. See PARENT_REMOTE_REPORT_PLAN.md §8.
 *  - [keyHex] — the AES-256 E2E key. The relay never sees it; it travels only device→device via the QR.
 *
 * Both are hex (not Base64) so the wire format and DataStore values stay dependency-free and safe on
 * minSdk 24 (`java.util.Base64` is API 26+). A QR carries ~110 ASCII chars — comfortably within capacity.
 */
object PairingManager {
    private const val SCHEME = "shieldpair"   // distinguishes our QR from any other the camera might see
    private const val VERSION = "1"
    private const val ID_BYTES = 16           // 128-bit pairing id / bearer token
    private const val KEY_BYTES = 32          // 256-bit AES key
    private const val SEP = ':'

    data class Pairing(val pairingIdHex: String, val keyHex: String) {
        /** Raw key bytes for [ReportCrypto]. */
        fun keyBytes(): ByteArray = hexToBytes(keyHex)
    }

    /** Mints a fresh pairing: a random bearer id + a fresh AES-256 key. Call once, on the child device. */
    fun newPairing(): Pairing {
        val id = ByteArray(ID_BYTES).also { SecureRandom().nextBytes(it) }
        return Pairing(bytesToHex(id), bytesToHex(ReportCrypto.generateKey()))
    }

    /** The string to render as a QR on the child device: `shieldpair:1:<idHex>:<keyHex>`. */
    fun encodeQr(p: Pairing): String =
        listOf(SCHEME, VERSION, p.pairingIdHex, p.keyHex).joinToString(SEP.toString())

    /**
     * Parses a scanned QR back into a [Pairing], or null for anything that isn't a well-formed Shield
     * pairing code (wrong scheme/version, bad lengths, non-hex). Lets the scanner safely ignore foreign QRs.
     */
    fun parseQr(text: String): Pairing? = runCatching {
        val parts = text.trim().split(SEP)
        if (parts.size != 4) return null
        if (parts[0] != SCHEME || parts[1] != VERSION) return null
        val idHex = parts[2].lowercase()
        val keyHex = parts[3].lowercase()
        if (!isHex(idHex, ID_BYTES) || !isHex(keyHex, KEY_BYTES)) return null
        Pairing(idHex, keyHex)
    }.getOrNull()

    // --- pure hex helpers (lowercase, fixed-length validated) ---

    private const val HEX = "0123456789abcdef"

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = HEX.indexOf(hex[i * 2])
            val lo = HEX.indexOf(hex[i * 2 + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun isHex(s: String, expectedBytes: Int): Boolean =
        s.length == expectedBytes * 2 && s.all { it in HEX }
}
