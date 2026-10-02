package com.shantanu.shield.remote

import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The entitlement grant relay: `grants/{pairingId}`. Parent writes, child listens.
 *
 * Encrypted like everything else here — not because an expiry timestamp is secret, but for
 * **authenticity**. A plaintext grant would let anyone who learned a pairing id write themselves free
 * premium; one that must decrypt under the pairing key can only have come from the real parent.
 *
 * Never throws: a grant that fails to arrive must degrade to "not granted", never crash the service.
 */
@Singleton
class GrantRepository @Inject constructor() {
    private fun doc(pairingId: String) =
        FirebaseFirestore.getInstance().collection(COLLECTION).document(pairingId)

    private fun parse(snapshot: com.google.firebase.firestore.DocumentSnapshot?): Sealed? {
        if (snapshot == null || !snapshot.exists()) return null
        val iv = snapshot.getBlob("iv")?.toBytes() ?: return null
        val ct = snapshot.getBlob("ciphertext")?.toBytes() ?: return null
        return Sealed(iv, ct)
    }

    /** Parent: publish the grant for one paired child. Returns false on any failure. */
    suspend fun write(pairingId: String, sealed: Sealed): Boolean = runCatching {
        suspendCancellableCoroutine { cont ->
            val data = hashMapOf<String, Any>(
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

    /** Child: one-shot read, for picking up a grant issued while this device was offline. */
    suspend fun read(pairingId: String): Sealed? = runCatching {
        suspendCancellableCoroutine<Sealed?> { cont ->
            doc(pairingId).get()
                .addOnSuccessListener { snap -> if (cont.isActive) cont.resume(parse(snap)) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
    }.getOrNull()

    /** Child: subscribe, so a fresh grant takes effect without waiting for the next poll. */
    fun listen(pairingId: String, onChange: (Sealed?) -> Unit): ListenerRegistration =
        doc(pairingId).addSnapshotListener { snapshot, error ->
            if (error != null) return@addSnapshotListener
            onChange(parse(snapshot))
        }

    /** Remove the grant. Called when a pairing is torn down, alongside the other three collections. */
    suspend fun delete(pairingId: String): Boolean = runCatching {
        suspendCancellableCoroutine { cont ->
            doc(pairingId).delete()
                .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                .addOnFailureListener { if (cont.isActive) cont.resume(false) }
        }
    }.getOrDefault(false)

    companion object {
        private const val COLLECTION = "grants"
        private const val SCHEMA_VERSION = 1L
    }
}
