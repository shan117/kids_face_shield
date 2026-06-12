package com.shantanu.shield.data

/**
 * Per-kid per-app daily limits for Multiple-kids mode: `profileId -> (package -> minutes)`.
 * Stored in its own DataStore key (NOT inside KidProfile) so the profile codec stays unchanged and
 * a revert is non-destructive — see PREMIUM_FEATURE_PLAN.md. Pure / unit-testable.
 *
 * Wire format: top-level entries joined by `|`, each `profileId=<PerAppLimitCodec payload>`.
 * Profile ids are `pN` and packages are `[a-zA-Z0-9._]`, so `=`/`|` never appear inside a field;
 * the payload itself uses `;`/`:` (PerAppLimitCodec), all distinct delimiters.
 */
object KidPerAppLimitCodec {

    fun encode(map: Map<String, Map<String, Int>>): String =
        map.entries
            .mapNotNull { (id, limits) ->
                val payload = PerAppLimitCodec.encode(limits)
                if (payload.isBlank()) null else "$id=$payload"
            }
            .joinToString("|")

    fun decode(raw: String): Map<String, Map<String, Int>> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, Map<String, Int>>()
        for (entry in raw.split("|")) {
            val idx = entry.indexOf('=')
            if (idx <= 0) continue
            val id = entry.substring(0, idx)
            val limits = PerAppLimitCodec.decode(entry.substring(idx + 1))
            if (limits.isNotEmpty()) out[id] = limits
        }
        return out
    }
}
