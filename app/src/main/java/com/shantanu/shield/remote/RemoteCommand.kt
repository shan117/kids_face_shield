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
