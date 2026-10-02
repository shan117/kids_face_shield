package com.shantanu.shield.remote

import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * `requests/{pairingId}` — the only CHILD → PARENT channel in the app.
 *
 * Every other collection flows the other way or carries reports; this one exists so a child can ask for
 * more time and the parent actually hears it. Encrypted under the pairing key for authenticity: without
 * that, anyone holding a pairing id could spam a parent with fake requests from their child.
 *
 * One document, overwritten — a child who taps three times produces one ask, not three notifications.
 *
 * Never throws: a failed request must leave the child's local "waiting" state intact rather than crash.
 */
@Singleton
class RequestRepository @Inject constructor() {

    private fun doc(pairingId: String) =
        FirebaseFirestore.getInstance().collection(COLLECTION).document(pairingId)

    private fun parse(snapshot: com.google.firebase.firestore.DocumentSnapshot?): Sealed? {
        if (snapshot == null || !snapshot.exists()) return null
        val iv = snapshot.getBlob("iv")?.toBytes() ?: return null
        val ct = snapshot.getBlob("ciphertext")?.toBytes() ?: return null
        return Sealed(iv, ct)
    }

    /** Child: publish the ask. */
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

    /** Parent: subscribe, so an ask surfaces while the child is still waiting for it. */
    fun listen(pairingId: String, onChange: (Sealed?) -> Unit): ListenerRegistration =
        doc(pairingId).addSnapshotListener { snapshot, error ->
            if (error != null) return@addSnapshotListener
            onChange(parse(snapshot))
        }

    suspend fun read(pairingId: String): Sealed? = runCatching {
        suspendCancellableCoroutine<Sealed?> { cont ->
            doc(pairingId).get()
                .addOnSuccessListener { snap -> if (cont.isActive) cont.resume(parse(snap)) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
    }.getOrNull()

    /**
     * Clear the ask — after granting, after dismissing, and on teardown.
     *
     * Deleting rather than marking handled keeps the parent's "is something pending" check a plain
     * existence test, with no state to get out of step between the two devices.
     */
    suspend fun delete(pairingId: String): Boolean = runCatching {
        suspendCancellableCoroutine { cont ->
            doc(pairingId).delete()
                .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                .addOnFailureListener { if (cont.isActive) cont.resume(false) }
        }
    }.getOrDefault(false)

    companion object {
        private const val COLLECTION = "requests"
        private const val SCHEMA_VERSION = 1L
    }
}
