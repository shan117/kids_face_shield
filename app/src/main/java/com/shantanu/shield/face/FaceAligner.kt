package com.shantanu.shield.face

import android.graphics.Bitmap
import android.graphics.Matrix

/**
 * 5-point face alignment (SFace migration, Phase 2b). Warps a detected face onto the canonical
 * 112×112 template SFace/ArcFace are trained on, using a 2D *similarity* transform (uniform scale +
 * rotation + translation — no shear) fit from five ML Kit landmarks: left eye, right eye, nose tip,
 * left mouth corner, right mouth corner.
 *
 * Behind a flag: only used when [FaceModelConfig.active] has `requiresAlignment = true` (SFace). FaceNet
 * tolerates a raw box crop, so this is OFF until Phase 2c — no behavior change before then.
 *
 * The transform math ([solveSimilarity]) is pure and unit-tested; [align] applies it to a Bitmap.
 */
object FaceAligner {

    /**
     * Canonical 5-point template for a 112×112 crop (the standard ArcFace/insightface reference
     * landmarks). Order: left eye, right eye, nose, left mouth, right mouth.
     */
    val TEMPLATE_112 = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f,
    )

    /** Template scaled to an arbitrary square [size]. */
    fun template(size: Int): FloatArray {
        val s = size / 112f
        return FloatArray(TEMPLATE_112.size) { TEMPLATE_112[it] * s }
    }

    /**
     * Least-squares fit of a 2D similarity transform mapping source points → destination points.
     * Returns the 6 affine coefficients `[a, -b, tx, b, a, ty]` (Android `Matrix` MSCALE/MSKEW row
     * order), where `x' = a*x - b*y + tx`, `y' = b*x + a*y + ty`.
     *
     * @param src flattened source points `[x0,y0, x1,y1, …]` (the detected landmarks).
     * @param dst flattened destination points, same length (the template).
     */
    fun solveSimilarity(src: FloatArray, dst: FloatArray): FloatArray {
        require(src.size == dst.size && src.size % 2 == 0 && src.size >= 4) {
            "need matching, even, ≥2 point pairs"
        }
        val n = src.size / 2
        // Normal equations for u = a*x - b*y + tx ; v = b*x + a*y + ty.
        // Unknowns: a, b, tx, ty. Accumulate the closed-form sums.
        var sxx = 0f // Σ(x²+y²)
        var sx = 0f
        var sy = 0f
        var sau = 0f // Σ(x*u + y*v)
        var sbu = 0f // Σ(x*v - y*u)
        var su = 0f
        var sv = 0f
        for (i in 0 until n) {
            val x = src[2 * i]; val y = src[2 * i + 1]
            val u = dst[2 * i]; val v = dst[2 * i + 1]
            sxx += x * x + y * y
            sx += x; sy += y
            sau += x * u + y * v
            sbu += x * v - y * u
            su += u; sv += v
        }
        val nf = n.toFloat()
        // Solve the 4×4 symmetric system:
        //   [ sxx   0   sx   sy ][a ]   [sau]
        //   [  0   sxx -sy   sx ][b ] = [sbu]
        //   [ sx  -sy    n    0 ][tx]   [su ]
        //   [ sy   sx    0    n ][ty]   [sv ]
        val m = arrayOf(
            floatArrayOf(sxx, 0f, sx, sy, sau),
            floatArrayOf(0f, sxx, -sy, sx, sbu),
            floatArrayOf(sx, -sy, nf, 0f, su),
            floatArrayOf(sy, sx, 0f, nf, sv),
        )
        val sol = solve4(m)
        val a = sol[0]; val b = sol[1]; val tx = sol[2]; val ty = sol[3]
        return floatArrayOf(a, -b, tx, b, a, ty)
    }

    /** Gaussian elimination with partial pivoting on a 4×5 augmented matrix; returns the 4 unknowns. */
    private fun solve4(m: Array<FloatArray>): FloatArray {
        val n = 4
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (kotlin.math.abs(m[r][col]) > kotlin.math.abs(m[piv][col])) piv = r
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            val d = m[col][col]
            if (kotlin.math.abs(d) < 1e-9f) continue // singular-ish; leave as-is (caller falls back to crop)
            for (c in col..n) m[col][c] /= d
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col]
                for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        return floatArrayOf(m[0][4], m[1][4], m[2][4], m[3][4])
    }

    /**
     * Align [bitmap] to a [size]×[size] canonical crop using the detected [landmarks] (flattened
     * `[lx,ly, rx,ry, nx,ny, mlx,mly, mrx,mry]`). Returns null if landmarks are unusable so the caller
     * can fall back to a box crop for that frame (never drop the session — see plan §10).
     */
    fun align(bitmap: Bitmap, landmarks: FloatArray, size: Int): Bitmap? {
        if (landmarks.size != TEMPLATE_112.size) return null
        val coeffs = solveSimilarity(landmarks, template(size))
        if (coeffs.any { it.isNaN() || it.isInfinite() }) return null
        val matrix = Matrix().apply {
            setValues(
                floatArrayOf(
                    coeffs[0], coeffs[1], coeffs[2],
                    coeffs[3], coeffs[4], coeffs[5],
                    0f, 0f, 1f,
                )
            )
        }
        return runCatching {
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).drawBitmap(bitmap, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            out
        }.getOrNull()
    }
}
