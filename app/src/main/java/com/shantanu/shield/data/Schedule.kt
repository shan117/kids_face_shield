package com.shantanu.shield.data

/** A saved kid-mode rule-set the parent can apply with one tap (premium "Schedules"):
 *  School / Weekend / Exam, etc. Applying it writes these values into the live kid-mode config. */
data class Schedule(
    val id: String,
    val name: String,
    val dailyLimitMinutes: Int,
    val allowedPreset: Int,
    val customAllowed: Set<String> = emptySet()
)

/** Pure, dependency-free serialization: `id|name|limit|preset|pkg,pkg,...` per line. */
object ScheduleCodec {
    private fun sanitize(s: String): String = s.replace('|', ' ').replace('\n', ' ')

    fun encode(schedules: List<Schedule>): String =
        schedules.joinToString("\n") { s ->
            listOf(
                s.id, sanitize(s.name), s.dailyLimitMinutes.toString(),
                s.allowedPreset.toString(), s.customAllowed.joinToString(",")
            ).joinToString("|")
        }

    fun decode(raw: String): List<Schedule> {
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val f = line.split("|")
            if (f.size != 5) return@mapNotNull null
            runCatching {
                Schedule(
                    id = f[0],
                    name = f[1],
                    dailyLimitMinutes = f[2].toInt(),
                    allowedPreset = f[3].toInt(),
                    customAllowed = f[4].split(",").filter { it.isNotBlank() }.toSet()
                )
            }.getOrNull()
        }
    }
}
