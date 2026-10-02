package com.shantanu.shield.remote

/**
 * A child asking their parent for more screen time. See UX_IMPROVEMENT_PLAN.md §1.
 *
 * Replaces a dialog that told the child to go ask in person — a button promising a request and
 * delivering instructions. That dialog arrived at the moment of maximum frustration, which is also the
 * moment that decides whether a child tolerates the app or campaigns to have it removed.
 */
data class TimeRequest(
    val requestedAtMs: Long,
    val minutes: Int,
)

/**
 * Request rules, kept pure.
 *
 * Both rules here exist to protect the parent from the child's frustration rather than the other way
 * round: a request must not survive long enough to be granted by accident, and a child tapping
 * repeatedly must not flood the parent.
 */
object TimeRequests {

    /** Minutes a child may ask for. Fixed options rather than free entry — one tap, nothing to argue. */
    val OPTIONS = listOf(15, 30, 60)

    /**
     * How long a request stays actionable.
     *
     * Four hours. A parent clearing notifications in the evening must not silently grant an ask from
     * breakfast — the child's situation has moved on, and time granted for a forgotten reason reads as
     * the app behaving randomly.
     */
    const val EXPIRY_MS = 4L * 60 * 60 * 1000

    fun isFresh(request: TimeRequest?, nowMs: Long): Boolean =
        request != null && nowMs - request.requestedAtMs < EXPIRY_MS

    /** Clamps to a sane ask, so a corrupt or hostile value cannot grant a day of screen time. */
    fun sanitize(minutes: Int): Int = minutes.coerceIn(1, 120)

    /**
     * The request to store when a child asks.
     *
     * Deliberately replaces any pending one rather than queueing. A child who taps three times should
     * produce one ask, not three notifications — and the newest ask is the only one that reflects what
     * they actually want.
     */
    fun create(minutes: Int, nowMs: Long): TimeRequest =
        TimeRequest(requestedAtMs = nowMs, minutes = sanitize(minutes))
}

/** Delimiter codec, matching the other wire formats in this package. Pure and tolerant. */
object TimeRequestCodec {
    private val US = Char(31)

    fun encode(request: TimeRequest): String =
        listOf(request.requestedAtMs.toString(), request.minutes.toString()).joinToString(US.toString())

    /** Null for anything unparseable — a corrupt request must read as "none", never as a grantable ask. */
    fun decode(raw: String): TimeRequest? {
        if (raw.isBlank()) return null
        val f = raw.split(US)
        if (f.size < 2) return null
        val at = f[0].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val minutes = f[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return TimeRequest(at, TimeRequests.sanitize(minutes))
    }
}
