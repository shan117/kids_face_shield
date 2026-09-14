package com.shantanu.shield.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteReportBuilderTest {

    @Test
    fun `daily average is the mean of the week`() {
        assertEquals(2000L, RemoteReportBuilder.dailyAvgMs(listOf(1000L, 2000L, 3000L)))
    }

    @Test
    fun `empty week averages to zero`() {
        assertEquals(0L, RemoteReportBuilder.dailyAvgMs(emptyList()))
    }

    @Test
    fun `budget-hit days counts days at or over the limit`() {
        val limitMin = 60                      // 3_600_000 ms
        val week = listOf(3_600_000L, 3_600_001L, 1_000_000L, 0L)
        assertEquals(2, RemoteReportBuilder.budgetHitDays(week, limitMin))
    }

    @Test
    fun `no limit means zero budget-hit days`() {
        assertEquals(0, RemoteReportBuilder.budgetHitDays(listOf(9_999_999L), 0))
    }

    @Test
    fun `kidReport derives average and budget-hit days`() {
        val week = listOf(3_600_000L, 0L)
        val report = RemoteReportBuilder.kidReport("Aarav", 60, week, listOf(AppStat("YouTube", 5L)))
        assertEquals(1_800_000L, report.dailyAvgMs)
        assertEquals(1, report.budgetHitDays)
        assertEquals(week, report.week)
        assertEquals("Aarav", report.name)
    }
}
