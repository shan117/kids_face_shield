package com.shantanu.shield.remote

import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The only component that talks to the relay. Firestore holds ONLY ciphertext — a [Sealed] iv+ciphertext
 * pair (as Blobs) plus housekeeping. It never sees the key, so it cannot read the report. Doc path:
 * `reports/{pairingId}`. See PARENT_REMOTE_REPORT_PLAN.md §2 / §4.
 */
@Singleton
class RemoteReportRepository @Inject constructor() {

    private fun doc(pairingId: String) =
        FirebaseFirestore.getInstance().collection(COLLECTION).document(pairingId)

    /** Upload the encrypted report. Returns false on any failure (offline, rules, init) — never throws. */
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

    /** Fetch + reassemble the latest ciphertext, or null if absent/unreadable. Never throws. */
    suspend fun read(pairingId: String): Sealed? = runCatching {
        suspendCancellableCoroutine<Sealed?> { cont ->
            doc(pairingId).get()
                .addOnSuccessListener { snap ->
                    val iv = snap.getBlob("iv")?.toBytes()
                    val ct = snap.getBlob("ciphertext")?.toBytes()
                    if (cont.isActive) cont.resume(if (iv != null && ct != null) Sealed(iv, ct) else null)
                }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
    }.getOrNull()

    /**
     * Parent: subscribe to the report doc for **live auto-refresh**. [onChange] fires once on attach with the
     * current value and again on every change — with the ciphertext, or null when the doc is absent/unreadable
     * (so the caller can show the "no report yet" state). Returns a [ListenerRegistration] the caller MUST
     * `remove()` when done. Errors are swallowed (a dropped listener just stops updating until re-attached).
     */
    fun listen(pairingId: String, onChange: (Sealed?) -> Unit): ListenerRegistration =
        doc(pairingId).addSnapshotListener { snapshot, error ->
            if (error != null) return@addSnapshotListener
            if (snapshot == null || !snapshot.exists()) { onChange(null); return@addSnapshotListener }
            val iv = snapshot.getBlob("iv")?.toBytes()
            val ct = snapshot.getBlob("ciphertext")?.toBytes()
            onChange(if (iv != null && ct != null) Sealed(iv, ct) else null)
        }

    /** Delete the report doc (unpair / revoke), so the stale ciphertext doesn't linger. Never throws. */
    suspend fun delete(pairingId: String): Boolean = runCatching {
        suspendCancellableCoroutine { cont ->
            doc(pairingId).delete()
                .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                .addOnFailureListener { if (cont.isActive) cont.resume(false) }
        }
    }.getOrDefault(false)

    companion object {
        private const val COLLECTION = "reports"
        private const val SCHEMA_VERSION = 1L
    }
}
