package com.shantanu.shield.kid

import com.shantanu.shield.data.ProfileSession

/**
 * Pure decision kernel for Multiple-kids enforcement (the "hybrid" model). No Android deps, so it
 * is unit-testable. The service wires these decisions to the live camera/identify + lock flow
 * (Phase 5b), but the rules themselves live here so they can be pinned by tests.
 */
object MultiKidEnforcement {

    /** How long an identification stays valid without re-scanning (the "grace window"). */
    const val DEFAULT_GRACE_MS = 2 * 60 * 1000L

    /**
     * The profile considered active *right now*: the most recently-ended session, if it ended within
     * [graceMs] of [nowMs] (covers an ongoing session, whose end is kept at ~now). Returns null when
     * identity is stale → the caller must re-identify (face scan) before attributing/enforcing.
     */
    fun activeProfileId(
        sessions: List<ProfileSession>,
        nowMs: Long,
        graceMs: Long = DEFAULT_GRACE_MS
    ): String? {
        val latest = sessions.maxByOrNull { it.endMs } ?: return null
        return if (nowMs - latest.endMs <= graceMs) latest.profileId else null
    }

    /**
     * Per-profile budget / night-window lock decision — the single-kid rule applied to one profile's
     * own counters. Night always locks; otherwise lock once used minutes reach the profile's limit
     * plus any granted extension.
     */
    fun shouldLock(
        usedMs: Long,
        dailyLimitMinutes: Int,
        extensionsMs: Long,
        isNight: Boolean
    ): Boolean {
        if (isNight) return true
        val usedMin = (usedMs / 60_000L).toInt()
        val extensionMin = (extensionsMs / 60_000L).toInt()
        return usedMin >= dailyLimitMinutes + extensionMin
    }
}
