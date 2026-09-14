package com.shantanu.shield.remote

/**
 * Pure, dependency-free (de)serialization for [RemoteCommand] (mirrors `RemoteReportCodec`). No Android, so
 * it is JVM-testable. The command is encrypted under the pairing key with [ReportCrypto] before it ever
 * reaches the relay — so a successful decrypt is itself the authorization (PARENT_REMOTE_CONTROL_PLAN.md D2),
 * and this codec only runs on already-authenticated bytes.
 */
object RemoteCommandCodec {
    private val US = Char(31)   // field separator — never occurs in our ids, stripped defensively anyway

    private fun clean(s: String) = s.filterNot { it == US }

    fun encode(c: RemoteCommand): String {
        // Append [payload] as a 5th field ONLY when present, so int-only commands stay 4-field and an older
        // child (which rejects anything but 4 fields) still decodes them. Tier-2 commands are 5-field.
        val base = listOf(clean(c.commandId), c.issuedAtMs.toString(), c.type.name, c.arg.toString())
        val fields = if (c.payload.isEmpty()) base else base + clean(c.payload)
        return fields.joinToString(US.toString())
    }

    fun decode(raw: String): RemoteCommand? = runCatching {
        val f = raw.split(US)
        if (f.size != 4 && f.size != 5) return null
        RemoteCommand(
            commandId = f[0],
            issuedAtMs = f[1].toLong(),
            type = enumValueOf<CommandType>(f[2]),   // unknown type → throws → null
            arg = f[3].toInt(),
            payload = f.getOrElse(4) { "" },
        )
    }.getOrNull()
}

/**
 * Idempotency + freshness gate for an already-decrypted (i.e. authenticated) command. Keeps the child from
 * re-applying the current command on every reconnect/restart, and from acting on commands that are too old
 * to still reflect the parent's intent. See plan D4.
 */
object RemoteCommandGate {
    /** Apply only if the command has an id, isn't the last one applied, and isn't older than [maxAgeMs]. */
    fun shouldApply(command: RemoteCommand, lastAppliedId: String, nowMs: Long, maxAgeMs: Long): Boolean {
        if (command.commandId.isBlank()) return false
        if (command.commandId == lastAppliedId) return false
        if (nowMs - command.issuedAtMs > maxAgeMs) return false
        return true
    }
}
