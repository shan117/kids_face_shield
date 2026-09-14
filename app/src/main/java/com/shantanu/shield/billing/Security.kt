package com.shantanu.shield.billing

import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Local signature verification of Play purchases (Google's documented client-side check).
 *
 * NOTE: client-side verification is spoofable on rooted devices — it's a deterrent, not a guarantee.
 * Server-side verification is the future hardening (PREMIUM_FEATURE_PLAN §13). We only ever gate
 * premium *extras* on this, never child-safety features.
 *
 * Until the app's Base64 RSA public key (Play Console -> Monetization setup -> Licensing) is pasted
 * into [BillingManager.PUBLIC_KEY], verification is **disabled** (returns true) so test purchases on
 * the internal track aren't rejected. Set the key before charging real users.
 */
object Security {

    private const val KEY_FACTORY_ALGORITHM = "RSA"
    private const val SIGNATURE_ALGORITHM = "SHA1withRSA"

    fun verifyPurchase(base64PublicKey: String, signedData: String, signature: String): Boolean {
        if (base64PublicKey.isBlank()) return true            // verification disabled until key set
        if (signedData.isBlank() || signature.isBlank()) return false
        return try {
            verify(generatePublicKey(base64PublicKey), signedData, signature)
        } catch (e: Exception) {
            false
        }
    }

    private fun generatePublicKey(base64: String): PublicKey {
        val decoded = Base64.decode(base64, Base64.DEFAULT)
        return KeyFactory.getInstance(KEY_FACTORY_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(decoded))
    }

    private fun verify(publicKey: PublicKey, signedData: String, signature: String): Boolean {
        val sig = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initVerify(publicKey)
            update(signedData.toByteArray())
        }
        return sig.verify(Base64.decode(signature, Base64.DEFAULT))
    }
}
