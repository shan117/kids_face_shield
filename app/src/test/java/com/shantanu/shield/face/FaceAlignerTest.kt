package com.shantanu.shield.face

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Phase 2b: the pure similarity-transform fit used for 5-point alignment. */
class FaceAlignerTest {

    /** Apply the solved `[a,-b,tx,b,a,ty]` coeffs to a point. */
    private fun apply(c: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        (c[0] * x + c[1] * y + c[2]) to (c[3] * x + c[4] * y + c[5])

    @Test
    fun recoversKnownSimilarityTransform() {
        // Build src = (scale,rot,translate) applied to the template; solving src→template must invert it.
        val dst = FaceAligner.TEMPLATE_112
        val s = 1.7f; val theta = 0.30f; val tx = 12f; val ty = -8f
        val ca = s * cos(theta); val sa = s * sin(theta)
        val src = FloatArray(dst.size)
        for (i in 0 until dst.size / 2) {
            val x = dst[2 * i]; val y = dst[2 * i + 1]
            src[2 * i] = ca * x - sa * y + tx
            src[2 * i + 1] = sa * x + ca * y + ty
        }
        val coeffs = FaceAligner.solveSimilarity(src, dst)
        // Mapping each src landmark through the fit should land on the template (sub-pixel).
        for (i in 0 until dst.size / 2) {
            val (u, v) = apply(coeffs, src[2 * i], src[2 * i + 1])
            assertEquals(dst[2 * i].toDouble(), u.toDouble(), 0.05)
            assertEquals(dst[2 * i + 1].toDouble(), v.toDouble(), 0.05)
        }
    }

    @Test
    fun templateScalesLinearly() {
        val t224 = FaceAligner.template(224)
        // 224 = 2× the 112 template.
        for (i in FaceAligner.TEMPLATE_112.indices) {
            assertEquals((FaceAligner.TEMPLATE_112[i] * 2f).toDouble(), t224[i].toDouble(), 1e-3)
        }
    }
}
