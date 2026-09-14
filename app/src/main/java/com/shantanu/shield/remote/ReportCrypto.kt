package com.shantanu.shield.remote

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure AES-GCM (256-bit) for the end-to-end-encrypted remote report. No Android and no Base64 — it
 * operates on raw bytes so it is fully unit-testable on the JVM. Base64 is applied only at the
 * Android/Firestore/QR edges in later phases. This is the privacy core: the relay (Firestore) ever only
 * holds [Sealed] ciphertext; the symmetric key is shared device-to-device via QR at pairing and never
 * leaves either phone. See PARENT_REMOTE_REPORT_PLAN.md §1–§2.
 */
object ReportCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val IV_BYTES = 12      // 96-bit nonce — the GCM-recommended size
    private const val TAG_BITS = 128     // authentication tag length

    /** A fresh 256-bit AES key as raw bytes (Base64-encoded only when it crosses the QR/DataStore edge). */
    fun generateKey(): ByteArray =
        KeyGenerator.getInstance("AES").apply { init(KEY_BITS) }.generateKey().encoded

    /** Encrypts [plaintext] (UTF-8) under [key], producing a fresh random IV + ciphertext-with-tag. */
    fun encrypt(plaintext: String, key: ByteArray): Sealed {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        }
        return Sealed(iv, cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Decrypts under [key], returning null on ANY failure — wrong key, tampered ciphertext, or bad IV.
     * GCM authentication makes "wrong key" and "modified data" indistinguishable, and the caller should
     * treat both the same way: ignore the doc. Never throws.
     */
    fun decrypt(sealed: Sealed, key: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed.iv))
        }
        String(cipher.doFinal(sealed.ciphertext), Charsets.UTF_8)
    }.getOrNull()
}

/**
 * Encryption output: a fresh [iv] (nonce) and [ciphertext] (which already includes the GCM auth tag).
 * Raw bytes only; the two are stored as separate Firestore fields in later phases. Intentionally a plain
 * class (not a data class) so equality is by reference — content comparison is the caller's job and a
 * data class's array-by-reference equals would be a silent footgun.
 */
class Sealed(val iv: ByteArray, val ciphertext: ByteArray)
