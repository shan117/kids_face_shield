package com.shantanu.shield.remote

import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Relay access for parent→child commands. Like reports, Firestore holds ONLY ciphertext (a [Sealed]
 * iv+ciphertext blob). The parent [write]s the latest command; the child [listen]s with a real-time
 * snapshot listener — which is the delivery mechanism, no Cloud Function/Blaze needed (plan D3).
 * Doc path: `commands/{pairingId}`.
 */
@Singleton
class RemoteCommandRepository @Inject constructor() {

    private fun doc(pairingId: String) =
        FirebaseFirestore.getInstance().collection(COLLECTION).document(pairingId)

    /** Parent: upload the encrypted command. Returns false on any failure — never throws. */
    suspend fun write(pairingId: String, sealed: Sealed): Boolean = runCatching {
        suspendCancellableCoroutine { cont ->
            val data = hashMapOf(
                "iv" to Blob.fromBytes(sealed.iv),
                "ciphertext" to Blob.fromBytes(sealed.ciphertext),
                "updatedAt" to System.currentTimeMillis(),
                "schemaVersion" to SCHEMA_VERSION,
            )
            doc(pairingId).set(data)
                .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                .addOnFailureListener { if (cont.isActive) cont.resume(false) }
        }
    }.getOrDefault(false)

    /**
     * Child: subscribe to the command doc. [onCommand] fires with the ciphertext whenever it changes (and
     * once on attach with the current value). Returns a [ListenerRegistration] the caller MUST `remove()`
     * when done. Errors are swallowed — a dropped listener just means no command until it re-attaches.
     */
    fun listen(pairingId: String, onCommand: (Sealed) -> Unit): ListenerRegistration =
        doc(pairingId).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
            val iv = snapshot.getBlob("iv")?.toBytes()
            val ct = snapshot.getBlob("ciphertext")?.toBytes()
            if (iv != null && ct != null) onCommand(Sealed(iv, ct))
        }

    /** Delete the command doc (unpair / revoke). Never throws. */
    suspend fun delete(pairingId: String): Boolean = runCatching {
        suspendCancellableCoroutine { cont ->
            doc(pairingId).delete()
                .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                .addOnFailureListener { if (cont.isActive) cont.resume(false) }
        }
    }.getOrDefault(false)

    companion object {
        private const val COLLECTION = "commands"
        private const val SCHEMA_VERSION = 1L
    }
}
