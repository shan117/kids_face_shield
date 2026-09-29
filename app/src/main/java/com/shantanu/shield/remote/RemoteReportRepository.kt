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
/**
 * What the relay currently holds for a pairing.
 *
 * [Revoked] exists because a key rotation does NOT invalidate the old document: the child mints a new
 * id and starts uploading to `reports/{newId}`, leaving `reports/{oldId}` intact — still perfectly
 * decryptable with the key the parent already has. Without an explicit marker the parent would go on
 * showing a real, valid, permanently-frozen report and never learn that it had been superseded.
 */
sealed interface ReportDoc {
    /** No document — a fresh pairing that has not uploaded yet. */
    data object Missing : ReportDoc
    /** The child rotated its key; this pairing is dead and the parent must re-scan. */
    data object Revoked : ReportDoc
    data class Data(val sealed: Sealed) : ReportDoc
}

@Singleton
class RemoteReportRepository @Inject constructor() {

    private fun doc(pairingId: String) =
        FirebaseFirestore.getInstance().collection(COLLECTION).document(pairingId)

    /** Read one snapshot into a [ReportDoc]. Revoked wins over any leftover ciphertext. */
    private fun parse(snapshot: com.google.firebase.firestore.DocumentSnapshot?): ReportDoc {
        if (snapshot == null || !snapshot.exists()) return ReportDoc.Missing
        if (snapshot.getBoolean(FIELD_REVOKED) == true) return ReportDoc.Revoked
        val iv = snapshot.getBlob("iv")?.toBytes()
        val ct = snapshot.getBlob("ciphertext")?.toBytes()
        return if (iv != null && ct != null) ReportDoc.Data(Sealed(iv, ct)) else ReportDoc.Missing
    }

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

    /**
     * Replace this pairing's report with a revocation marker, so a parent still watching the old
     * document learns it has been superseded instead of showing frozen data forever.
     *
     * Overwrites rather than deletes: an absent document is indistinguishable from a pairing that has
     * simply never uploaded, and the parent needs to tell those apart. The ciphertext is dropped in the
     * same write, which also means the stale report stops being readable at all. Never throws.
     */
    suspend fun revoke(pairingId: String): Boolean = runCatching {
        suspendCancellableCoroutine { cont ->
            val data = hashMapOf(
                FIELD_REVOKED to true,
                "updatedAt" to System.currentTimeMillis(),
                "schemaVersion" to SCHEMA_VERSION,
            )
            doc(pairingId).set(data)
                .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                .addOnFailureListener { if (cont.isActive) cont.resume(false) }
        }
    }.getOrDefault(false)

    /** Fetch the current state of the report document. Never throws. */
    suspend fun read(pairingId: String): ReportDoc = runCatching {
        suspendCancellableCoroutine<ReportDoc> { cont ->
            doc(pairingId).get()
                .addOnSuccessListener { snap -> if (cont.isActive) cont.resume(parse(snap)) }
                .addOnFailureListener { if (cont.isActive) cont.resume(ReportDoc.Missing) }
        }
    }.getOrDefault(ReportDoc.Missing)

    /**
     * Parent: subscribe to the report doc for **live auto-refresh**. [onChange] fires once on attach with the
     * current value and again on every change — with the ciphertext, or null when the doc is absent/unreadable
     * (so the caller can show the "no report yet" state). Returns a [ListenerRegistration] the caller MUST
     * `remove()` when done. Errors are swallowed (a dropped listener just stops updating until re-attached).
     */
    fun listen(pairingId: String, onChange: (ReportDoc) -> Unit): ListenerRegistration =
        doc(pairingId).addSnapshotListener { snapshot, error ->
            if (error != null) return@addSnapshotListener
            onChange(parse(snapshot))
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
        /** Set by the child when it rotates its key, retiring this pairing. */
        private const val FIELD_REVOKED = "revoked"
    }
}
