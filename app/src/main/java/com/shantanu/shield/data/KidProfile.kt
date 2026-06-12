package com.shantanu.shield.data

/**
 * One child's configuration + running counters, used only when Multiple-kids mode is on.
 * Single-kid mode keeps using the flat DataStore keys; the first profile ("p1") is migrated from
 * those. See PREMIUM_FEATURE_PLAN.md §3/§6.
 */
data class KidProfile(
    val id: String,
    val name: String,
    val avatarColor: Long,                 // ARGB, e.g. 0xFF006C7F
    val dailyLimitMinutes: Int = 60,
    val allowedPreset: Int = 0,
    val customAllowed: Set<String> = emptySet(),
    val usedMs: Long = 0L,
    val extensionsMs: Long = 0L,
    val lastResetDate: String = ""
)

/** A window during which a given profile was the identified, active user (for usage attribution). */
data class ProfileSession(val profileId: String, val startMs: Long, val endMs: Long)

/**
 * Pure, dependency-free (and unit-testable) serialization for profiles + sessions.
 *
 * Profiles: one record per line (`\n`), fields pipe-delimited. The only free-text field (name) is
 * sanitized of `|`/newlines on encode. Package names (customAllowed) are `[a-zA-Z0-9._]`, so a
 * comma sub-delimiter is safe.
 * Sessions: `profileId,startMs,endMs` joined by `;` (profile ids are `pN`, delimiter-safe).
 */
object KidProfileCodec {

    private fun sanitize(s: String): String = s.replace('|', ' ').replace('\n', ' ')

    fun encode(profiles: List<KidProfile>): String =
        profiles.joinToString("\n") { p ->
            listOf(
                p.id,
                sanitize(p.name),
                p.avatarColor.toString(),
                p.dailyLimitMinutes.toString(),
                p.allowedPreset.toString(),
                p.usedMs.toString(),
                p.extensionsMs.toString(),
                p.lastResetDate,
                p.customAllowed.joinToString(",")
            ).joinToString("|")
        }

    fun decode(raw: String): List<KidProfile> {
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val f = line.split("|")
            if (f.size != 9) return@mapNotNull null
            runCatching {
                KidProfile(
                    id = f[0],
                    name = f[1],
                    avatarColor = f[2].toLong(),
                    dailyLimitMinutes = f[3].toInt(),
                    allowedPreset = f[4].toInt(),
                    usedMs = f[5].toLong(),
                    extensionsMs = f[6].toLong(),
                    lastResetDate = f[7],
                    customAllowed = f[8].split(",").filter { it.isNotBlank() }.toSet()
                )
            }.getOrNull()
        }
    }

    fun encodeSessions(sessions: List<ProfileSession>): String =
        sessions.joinToString(";") { "${it.profileId},${it.startMs},${it.endMs}" }

    fun decodeSessions(raw: String): List<ProfileSession> {
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val p = entry.split(",")
            if (p.size != 3) return@mapNotNull null
            runCatching { ProfileSession(p[0], p[1].toLong(), p[2].toLong()) }.getOrNull()
        }
    }
}
