package com.shantanu.shield.data

/** Serializes per-app daily limits (`package:minutes;package:minutes`). Pure / unit-testable.
 *  Package names never contain ':' or ';', so the delimiters are safe; only positive limits kept. */
object PerAppLimitCodec {

    fun encode(limits: Map<String, Int>): String =
        limits.entries.filter { it.value > 0 }.joinToString(";") { "${it.key}:${it.value}" }

    fun decode(raw: String): Map<String, Int> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (entry in raw.split(";")) {
            val idx = entry.lastIndexOf(':')
            if (idx <= 0) continue
            val pkg = entry.substring(0, idx)
            val mins = entry.substring(idx + 1).toIntOrNull() ?: continue
            if (mins > 0) out[pkg] = mins
        }
        return out
    }
}
