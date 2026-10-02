package com.shantanu.shield.remote

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The pairing's membership record: `pairings/{pairingId}` holding `uids` and `claimDeadline`.
 *
 * **Why one shared record instead of per-document membership.** The first design stamped the creating
 * device's uid onto each relay document. That cannot work, because every collection has exactly one
 * writer and one reader:
 *
 * | collection | created by | read by |
 * |---|---|---|
 * | `reports`, `locations` | child | parent |
 * | `commands`, `grants` | parent | child |
 *
 * So the reader was never in the document's own `uids`, and a reader cannot add itself — claiming is a
 * write. Publishing strict rules on that model denied all four directions at once. `grants` made the
 * flaw plain: that document may not exist until weeks after pairing, long after any per-document claim
 * window could have closed.
 *
 * Membership is therefore established **once, at pairing**, when both devices genuinely are present, and
 * every collection's rule consults this one record.
 *
 * Never throws: a membership write failing must not break pairing under the current permissive rules.
 */
@Singleton
class PairingRepository @Inject constructor(
    private val identity: DeviceIdentity,
) {
    private fun doc(pairingId: String) =
        FirebaseFirestore.getInstance().collection(COLLECTION).document(pairingId)

    /** The recorded members, or an empty list when there is no record. */
    suspend fun members(pairingId: String): List<String> = runCatching {
        suspendCancellableCoroutine<List<String>> { cont ->
            doc(pairingId).get()
                .addOnSuccessListener { snap ->
                    @Suppress("UNCHECKED_CAST")
                    val uids = (snap.get(PairingMembership.FIELD_UIDS) as? List<String>).orEmpty()
                    if (cont.isActive) cont.resume(uids)
                }
                .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
        }
    }.getOrDefault(emptyList())

    /**
     * Child side: make sure the record exists with this device as first member, and keep the claim
     * window open while pairing is actually possible.
     *
     * Called when a pairing is minted AND every time the QR is displayed, so a child who mints now and
     * is scanned an hour later still pairs. Idempotent, and deliberately stops extending once two
     * devices are recorded — otherwise the window would never shut and a photographed QR would stay
     * usable forever.
     */
    suspend fun ensureMembership(pairingId: String): Boolean {
        if (pairingId.isBlank()) return false
        val uid = identity.uid() ?: return false

        val existing = members(pairingId)
        if (existing.size >= PairingMembership.MAX_MEMBERS) return true   // sealed; leave it alone

        val deadline = System.currentTimeMillis() + PairingMembership.CLAIM_WINDOW_MS
        // `set` with merge so re-running this never drops the other member's uid if one has just
        // claimed, and never clobbers an unrelated field.
        val data = mapOf(
            PairingMembership.FIELD_UIDS to FieldValue.arrayUnion(uid),
            PairingMembership.FIELD_CLAIM_DEADLINE to deadline,
        )
        return write(pairingId, data, merge = true).also {
            if (it) Log.i(TAG, "membership ready for ${pairingId.take(8)}… window ${PairingMembership.CLAIM_WINDOW_MS / 60_000}min")
            else Log.w(TAG, "membership write failed for ${pairingId.take(8)}…")
        }
    }

    /**
     * Parent side: add this device as the second member.
     *
     * Uses `arrayUnion`, which is applied server-side — so it is atomic and idempotent. No read-then-
     * write, therefore no lost update if anything else touches the record at the same moment, and
     * re-scanning the same QR cannot add a duplicate or consume the remaining slot twice.
     *
     * The *limits* — at most two members, only inside the window — are enforced by the security rules,
     * which is where they belong: a client-side check could simply be skipped.
     */
    suspend fun claim(pairingId: String): Boolean {
        if (pairingId.isBlank()) return false
        val uid = identity.uid() ?: return false

        val data = mapOf(PairingMembership.FIELD_UIDS to FieldValue.arrayUnion(uid))
        return write(pairingId, data, merge = true).also {
            if (it) Log.i(TAG, "claimed membership of ${pairingId.take(8)}…")
            else Log.w(TAG, "claim failed for ${pairingId.take(8)}… (window closed, or offline)")
        }
    }

    /** Remove the record. Called wherever a pairing is torn down, with the other four collections. */
    suspend fun delete(pairingId: String): Boolean = runCatching {
        suspendCancellableCoroutine { cont ->
            doc(pairingId).delete()
                .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                .addOnFailureListener { if (cont.isActive) cont.resume(false) }
        }
    }.getOrDefault(false)

    private suspend fun write(
        pairingId: String,
        data: Map<String, Any>,
        merge: Boolean,
    ): Boolean = runCatching {
        suspendCancellableCoroutine { cont ->
            val task = if (merge) {
                doc(pairingId).set(data, com.google.firebase.firestore.SetOptions.merge())
            } else {
                doc(pairingId).set(data)
            }
            task.addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                .addOnFailureListener { if (cont.isActive) cont.resume(false) }
        }
    }.getOrDefault(false)

    private companion object {
        const val COLLECTION = "pairings"
        const val TAG = "ShieldAuth"
    }
}
