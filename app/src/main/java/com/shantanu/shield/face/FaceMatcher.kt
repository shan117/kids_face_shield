package com.shantanu.shield.face

/**
 * Pure 1:N face identification for Multiple-kids mode. No Android / TFLite dependencies, so it is
 * unit-testable. Embeddings are L2-normalized 128-d vectors (as produced by FaceRecognitionManager),
 * so cosine similarity == dot product.
 *
 * This is separate from FaceRecognitionManager's existing 1:1 verify path, which is left untouched —
 * the parent face-unlock keeps working exactly as before.
 */
object FaceMatcher {

    data class Result(val profileId: String?, val score: Float, val runnerUp: Float)

    fun similarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) dot += a[i] * b[i]
        return dot
    }

    /**
     * Returns the best-matching profile id — but only if it clears [threshold] AND beats the
     * runner-up by [margin], so two similar-looking siblings aren't confidently confused. If neither
     * holds, profileId is null ("unknown" → the caller applies the strict fallback).
     */
    fun identify(
        probe: FloatArray,
        gallery: Map<String, FloatArray>,
        threshold: Float = 0.6f,
        margin: Float = 0.08f
    ): Result {
        if (gallery.isEmpty()) return Result(null, 0f, 0f)
        val scored = gallery.entries
            .map { it.key to similarity(probe, it.value) }
            .sortedByDescending { it.second }
        val (bestId, bestScore) = scored[0]
        val runnerUp = if (scored.size > 1) scored[1].second else 0f
        val confident = bestScore >= threshold && (bestScore - runnerUp) >= margin
        return Result(if (confident) bestId else null, bestScore, runnerUp)
    }
}
