package com.shantanu.shield.data

/**
 * Serializes per-profile face embeddings for Multiple-kids mode as
 * `p1:f,f,...;p2:f,f,...`. Pure / dependency-free / unit-testable. Floats never contain `:` or `;`,
 * so the delimiters are safe.
 */
object FaceGalleryCodec {

    fun encode(gallery: Map<String, FloatArray>): String =
        gallery.entries.joinToString(";") { (id, emb) -> "$id:" + emb.joinToString(",") }

    fun decode(raw: String): Map<String, FloatArray> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, FloatArray>()
        for (entry in raw.split(";")) {
            val idx = entry.indexOf(':')
            if (idx <= 0) continue
            val id = entry.substring(0, idx)
            val floats = entry.substring(idx + 1)
                .split(",")
                .mapNotNull { it.toFloatOrNull() }
                .toFloatArray()
            if (floats.isNotEmpty()) out[id] = floats
        }
        return out
    }
}
