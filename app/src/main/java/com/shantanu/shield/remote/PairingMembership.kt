package com.shantanu.shield.remote

/**
 * Who is allowed to touch a pairing's relay documents. See PHASE7_AUTH_PLAN.md.
 *
 * Pure, because this is the rule that decides whether a stolen pairing id is useful to anyone. The
 * Firestore rules enforce the same logic server-side; this is the client's half, and keeping it free of
 * Firebase means the window arithmetic and the "exactly one more UID" constraint are unit-testable
 * rather than only observable by trying to break production.
 */
object PairingMembership {

    /** Firestore field holding the device UIDs allowed to read and write this pairing. */
    const val FIELD_UIDS = "uids"

    /** Firestore field holding the epoch-ms instant after which no further UID may be claimed. */
    const val FIELD_CLAIM_DEADLINE = "claimDeadline"

    /** A pairing is two devices: the child that minted it and the parent that scanned it. */
    const val MAX_MEMBERS = 2

    /**
     * How long a second device has to claim membership.
     *
     * Thirty minutes, and **refreshed every time the child displays its QR** (see
     * `PairingRepository.ensureMembership`). A fixed window from creation was the wrong model: a child
     * can mint a pairing, put the phone down, and be scanned an hour later — and a window that had
     * silently closed would leave the pairing permanently unclaimable.
     *
     * Refreshing while the QR is on screen means the window is always open exactly when someone is
     * actually pairing, and shut the rest of the time. Once two devices are recorded it stops being
     * extended at all, so a photographed QR is worthless from that moment on.
     */
    const val CLAIM_WINDOW_MS = 30 * 60 * 1000L

    /** True when [uid] already holds membership. */
    fun isMember(uids: List<String>, uid: String?): Boolean =
        uid != null && uid.isNotBlank() && uid in uids

    /** True when the claim window is still open at [nowMs]. */
    fun isClaimWindowOpen(uids: List<String>, claimDeadlineMs: Long, nowMs: Long): Boolean =
        uids.size < MAX_MEMBERS && nowMs < claimDeadlineMs

    /**
     * The UID list after [uid] claims membership, or the list unchanged when it may not.
     *
     * Refuses to grow past [MAX_MEMBERS], refuses after the deadline, and is a no-op for a UID that is
     * already a member — so a device re-scanning its own QR does not consume the second slot and lock
     * the real partner out.
     */
    fun claim(
        uids: List<String>,
        uid: String?,
        claimDeadlineMs: Long,
        nowMs: Long,
    ): List<String> {
        if (uid == null || uid.isBlank()) return uids
        if (isMember(uids, uid)) return uids
        if (!isClaimWindowOpen(uids, claimDeadlineMs, nowMs)) return uids
        return uids + uid
    }

}
