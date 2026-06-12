package com.shantanu.shield.data

/** A parent-defined task that grants bonus screen-time when completed (premium "Earn time"). */
data class EarnedTask(val id: String, val title: String, val minutes: Int)

/** Pure, dependency-free serialization: one `id|title|minutes` record per line. Title is sanitized
 *  of the delimiter/newline so a record always parses. */
object EarnedTaskCodec {
    private fun sanitize(s: String): String = s.replace('|', ' ').replace('\n', ' ')

    fun encode(tasks: List<EarnedTask>): String =
        tasks.joinToString("\n") { "${it.id}|${sanitize(it.title)}|${it.minutes}" }

    fun decode(raw: String): List<EarnedTask> {
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val f = line.split("|")
            if (f.size != 3) return@mapNotNull null
            runCatching { EarnedTask(f[0], f[1], f[2].toInt()) }.getOrNull()
        }
    }
}
