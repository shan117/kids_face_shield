package com.shantanu.shield.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1/2a of the SFace migration: version-gating rules that decide "is this embedding current?". */
class FaceModelConfigTest {

    @Test
    fun activeIsFaceNetForNow() {
        // Until Phase 2c flips it, the build embeds with FaceNet.
        assertEquals(FaceModelConfig.FACENET, FaceModelConfig.active)
        assertEquals("facenet-v1", FaceModelConfig.CURRENT_FACE_MODEL_VERSION)
    }

    @Test
    fun legacyUnstampedCountsAsCurrentWhileOnFaceNet() {
        // Existing users (no version key) must NOT be forced to re-enrol during Phase 1.
        assertTrue(FaceModelConfig.isVersionCurrent(""))
    }

    @Test
    fun currentTagIsCurrent() {
        assertTrue(FaceModelConfig.isVersionCurrent("facenet-v1"))
    }

    @Test
    fun foreignTagIsStale() {
        // An SFace-stamped embedding is stale while the build is still FaceNet (and vice-versa at 2c).
        assertFalse(FaceModelConfig.isVersionCurrent("sface-v1"))
        assertFalse(FaceModelConfig.isVersionCurrent("garbage"))
    }
}
