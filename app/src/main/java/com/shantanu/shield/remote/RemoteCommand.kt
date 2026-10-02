package com.shantanu.shield.remote

/** The bounded set of parent→child actions — no arbitrary/admin commands, so the blast radius is limited
 *  by design (PARENT_REMOTE_CONTROL_PLAN.md D1). New types are appended (the codec encodes the name, and an
 *  older child decodes an unknown name → null → safely ignores it). */
enum class CommandType {
    LOCK_NOW, UNLOCK, GRANT_EXTRA_TIME, SET_DAILY_LIMIT,
    // Phase-1 remote Kid Mode config: arg = preset (0/1) / 0|1 boolean.
    SET_ALLOWED_PRESET, SET_AUTO_BLOCK,
    // Tier-2 (carry a [payload]): SET_CUSTOM_ALLOWED = comma-joined packages; SET_PER_APP_LIMIT = one package
    // in [payload] + minutes in [arg] (0 clears it).
    SET_CUSTOM_ALLOWED, SET_PER_APP_LIMIT,
    /** Ask the child for one location fix. Carries no arg or payload — the answer travels back on the
     *  separate `locations/{pairingId}` channel, never inside the screen-time report. The child answers
     *  with a status even when it refuses, so the parent never waits forever.
     *  See LOCATION_FEATURE_PLAN.md. */
    REQUEST_LOCATION,
    /** Set the child's web-filter categories. [RemoteCommand.payload] = comma-joined category names;
     *  [RemoteCommand.arg] 1 enables the filter, 0 disables it. Same wire shape as
     *  SET_CUSTOM_ALLOWED. See WEB_FILTER_PLAN.md Phase 2. */
    SET_WEB_CATEGORIES,
}

/**
 * Which consent a command needs before the child will act on it.
 *
 * Pure, so the property that actually matters — that switching off "Allow remote control" really does
 * stop every controlling command — is pinned by tests rather than by reading the ViewModel.
 *
 * Location is deliberately NOT bound to the remote-control switch. The child gives a separate,
 * specific consent for location, and making it depend on an unrelated toggle would recreate exactly
 * the bundled-consent problem this feature was designed to avoid: one "yes" quietly covering something
 * the person never agreed to. See LOCATION_FEATURE_PLAN.md §10.
 */
object CommandConsent {

    /**
     * True when [type] may be delivered to the responder.
     *
     * Note what this does NOT decide: whether a location is actually shared. `REQUEST_LOCATION` is
     * allowed through whenever either consent is present so the child can ANSWER — including
     * answering "no". `LocationResponder` makes the real decision and replies `NOT_CONSENTED`, which
     * is why a parent gets a reason instead of an unexplained timeout.
     */
    fun isPermitted(
        type: CommandType,
        remoteControlEnabled: Boolean,
        locationSharingEnabled: Boolean,
    ): Boolean = when (type) {
        CommandType.REQUEST_LOCATION -> remoteControlEnabled || locationSharingEnabled
        // Everything else controls the device, so it needs the control consent and nothing else can
        // substitute for it. Location sharing must never unlock the ability to lock a child's phone.
        else -> remoteControlEnabled
    }
}

/**
 * A single parent→child command. [arg] is minutes for GRANT_EXTRA_TIME / SET_DAILY_LIMIT and ignored for
 * LOCK_NOW / UNLOCK. [commandId] makes application idempotent (a re-delivered command is applied once);
 * [issuedAtMs] lets the child drop stale commands it receives after being offline. See the plan §4.
 */
data class RemoteCommand(
    val commandId: String,
    val issuedAtMs: Long,
    val type: CommandType,
    val arg: Int = 0,
    /** Optional richer payload (Tier-2). Empty for the int-only commands, which stay 4-field on the wire so an
     *  older child still decodes them. */
    val payload: String = "",
) {
    companion object {
        /** [arg] for LOCK_NOW meaning "full lock" — also block Phone & Messages (default keeps them usable). */
        const val LOCK_FLAG_FULL = 1
    }
}
