package com.shantanu.shield.face

/**
 * Single source of truth for the face model: which one the build embeds with, how to preprocess for
 * it, and which version the stored embeddings belong to. See FACE_MODEL_MIGRATION_PLAN.md.
 *
 * Embeddings are model-specific — a vector from one model is meaningless to another. So every
 * (re)enrol stamps [CURRENT_FACE_MODEL_VERSION], and any stored embedding whose tag isn't current is
 * treated as NOT enrolled (forcing a one-time re-enrol). This prevents cross-model comparisons that
 * would yield wrong unlocks/lockouts.
 *
 * Phase 2a: [FaceRecognitionManager] reads [active] instead of hardcoding 112 / 127.5 / 128. Still on
 * FaceNet — no behavior change. Phase 2c flips [active] to [SFACE] (+ alignment) in one version bump.
 */
object FaceModelConfig {

    /**
     * Everything needed to preprocess a face crop and embed it. The ONE place a model is described.
     *
     * @param assetName      tflite file under app/src/main/assets.
     * @param inputSize      square input edge in px (model is inputSize × inputSize).
     * @param normMean       per-channel mean subtracted in NormalizeOp ((x - mean) / std).
     * @param normStd        per-channel std for NormalizeOp.
     * @param swapRB         true if the model expects BGR (Android bitmaps are RGB → swap R↔B). SFace is BGR.
     * @param outputDim      embedding length (introspected at load too; this is the expected value).
     * @param requiresAlignment  true if the model needs 5-point aligned input (SFace does; FaceNet tolerates a box crop).
     * @param version        tag stamped onto embeddings produced by this model.
     */
    data class ModelSpec(
        val assetName: String,
        val inputSize: Int,
        val normMean: Float,
        val normStd: Float,
        val swapRB: Boolean,
        val outputDim: Int,
        val requiresAlignment: Boolean,
        val version: String,
    )

    /** Legacy MobileFaceNet ("facenet.tflite"): RGB, (x-127.5)/127.5, 128-d, box crop OK. */
    val FACENET = ModelSpec(
        assetName = "facenet.tflite",
        inputSize = 112,
        normMean = 127.5f,
        normStd = 127.5f,
        swapRB = false,
        outputDim = 128,
        requiresAlignment = false,
        version = "facenet-v1",
    )

    /**
     * OpenCV Zoo SFace (Apache-2.0). NOT YET ACTIVE — wired in at Phase 2c after the Phase 0 conversion
     * + on-device sanity check confirm these values. SFace is BGR and uses raw 0-255 input (no mean/std
     * scaling in sface.py's blobFromImage, scale=1.0) — TODO(2c): confirm normMean/normStd + outputDim
     * against the converted model and a same-face/different-face cosine sanity check before activating.
     */
    val SFACE = ModelSpec(
        assetName = "face_recognition_sface_2021dec_float16.tflite",
        inputSize = 112,
        normMean = 0f,
        normStd = 1f,
        swapRB = true,
        outputDim = 128,
        requiresAlignment = true,
        version = "sface-v1",
    )

    /** The model this build actually uses. Phase 2c: change to [SFACE]. */
    val active: ModelSpec = FACENET

    /** Convenience aliases used by the versioning helpers. */
    const val FACENET_MODEL_VERSION = "facenet-v1"
    const val SFACE_MODEL_VERSION = "sface-v1"

    /** The version stamped on every (re)enrol in this build = the active model's tag. */
    val CURRENT_FACE_MODEL_VERSION: String get() = active.version

    /**
     * True if an embedding stamped [stored] belongs to the current model. Legacy unstamped ("")
     * embeddings count as current ONLY while the build is still on FaceNet — so Phase 1/2a introduce
     * no behavior change, but the Phase 2c flip to SFace correctly marks them stale.
     */
    fun isVersionCurrent(stored: String): Boolean =
        stored == CURRENT_FACE_MODEL_VERSION ||
            (CURRENT_FACE_MODEL_VERSION == FACENET_MODEL_VERSION && stored.isEmpty())
}
