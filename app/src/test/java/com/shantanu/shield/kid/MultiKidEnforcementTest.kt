package com.shantanu.shield.kid

import com.shantanu.shield.data.ProfileSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiKidEnforcementTest {

    private val now = 1_000_000L

    @Test
    fun `active profile is the latest session within grace`() {
        val sessions = listOf(
            ProfileSession("p1", now - 600_000, now - 500_000),
            ProfileSession("p2", now - 60_000, now - 30_000)   // ended 30s ago
        )
        assertEquals("p2", MultiKidEnforcement.activeProfileId(sessions, now))
    }

    @Test
    fun `stale identity (beyond grace) returns null`() {
        val sessions = listOf(ProfileSession("p1", now - 600_000, now - 300_000)) // ended 5 min ago
        assertNull(MultiKidEnforcement.activeProfileId(sessions, now))
    }

    @Test
    fun `no sessions returns null`() {
        assertNull(MultiKidEnforcement.activeProfileId(emptyList(), now))
    }

    @Test
    fun `night always locks regardless of budget`() {
        assertTrue(MultiKidEnforcement.shouldLock(usedMs = 0L, dailyLimitMinutes = 60, extensionsMs = 0L, isNight = true))
    }

    @Test
    fun `under budget in the day does not lock`() {
        assertFalse(MultiKidEnforcement.shouldLock(usedMs = 30 * 60_000L, dailyLimitMinutes = 60, extensionsMs = 0L, isNight = false))
    }

    @Test
    fun `at or over budget locks, and extension raises the bar`() {
        assertTrue(MultiKidEnforcement.shouldLock(60 * 60_000L, 60, 0L, isNight = false))
        // +30 min extension → 60 used is back under the 90 effective limit
        assertFalse(MultiKidEnforcement.shouldLock(60 * 60_000L, 60, 30 * 60_000L, isNight = false))
    }
}
