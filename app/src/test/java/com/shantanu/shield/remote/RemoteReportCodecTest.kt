package com.shantanu.shield.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the cross-device report serialization so the encrypted payload survives encode/decode intact. */
class RemoteReportCodecTest {

    private fun sample() = RemoteReportPayload(
        generatedAtMs = 1_700_000_000_000L,
        kids = listOf(
            KidReport(
                name = "Aarav",
                limitMin = 60,
                dailyAvgMs = 3_600_000L,
                week = listOf(1000L, 2000L, 0L, 4000L, 5000L, 6000L, 7000L),
                topApps = listOf(AppStat("YouTube", 1_800_000L), AppStat("Roblox", 900_000L)),
                budgetHitDays = 3,
            ),
            KidReport(
                name = "Diya",
                limitMin = 90,
                dailyAvgMs = 5_400_000L,
                week = listOf(0L, 0L, 0L),
                topApps = emptyList(),
                budgetHitDays = 0,
            ),
        ),
    )

    @Test
    fun `payload round-trips through encode then decode`() {
        val original = sample()
        val decoded = RemoteReportCodec.decode(RemoteReportCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `payload with no kids round-trips to empty list`() {
        val original = RemoteReportPayload(generatedAtMs = 42L, kids = emptyList())
        val decoded = RemoteReportCodec.decode(RemoteReportCodec.encode(original))
        assertNotNull(decoded)
        assertEquals(42L, decoded!!.generatedAtMs)
        assertTrue(decoded.kids.isEmpty())
    }

    @Test
    fun `delimiter characters in an app or kid name are stripped, record stays intact`() {
        // The codec's delimiters are US/RS/GS/FS (control chars 31/30/29/28). Built here from int codes —
        // never literal control chars in source — and they must be scrubbed on encode so an injected
        // delimiter can neither survive into the value nor corrupt the record structure.
        val us = Char(31); val rs = Char(30); val gs = Char(29); val fs = Char(28)
        val dirty = RemoteReportPayload(
            generatedAtMs = 1L,
            kids = listOf(
                KidReport(
                    name = "Aa${rs}rav",
                    limitMin = 30,
                    dailyAvgMs = 0L,
                    week = listOf(10L),
                    topApps = listOf(AppStat("You${us}Tu${gs}b${fs}e", 5L)),
                    budgetHitDays = 1,
                ),
            ),
        )
        val decoded = RemoteReportCodec.decode(RemoteReportCodec.encode(dirty))
        assertNotNull(decoded)
        // Still exactly one kid with one app — no delimiter leaked through to split the structure.
        assertEquals(1, decoded!!.kids.size)
        assertEquals("Aarav", decoded.kids[0].name)
        assertEquals(1, decoded.kids[0].topApps.size)
        assertEquals("YouTube", decoded.kids[0].topApps[0].name)
        assertEquals(5L, decoded.kids[0].topApps[0].ms)
    }

    @Test
    fun `empty input decodes to null`() {
        assertNull(RemoteReportCodec.decode(""))
    }

    @Test
    fun `garbage input decodes to null instead of throwing`() {
        assertNull(RemoteReportCodec.decode("not-a-number-header"))
    }

    @Test
    fun `payload with rich B1 fields round-trips`() {
        val p = RemoteReportPayload(
            generatedAtMs = 1L,
            kids = listOf(
                KidReport(
                    name = "Aarav", limitMin = 60, dailyAvgMs = 100L,
                    week = listOf(1L, 2L), topApps = listOf(AppStat("YouTube", 9L)), budgetHitDays = 1,
                    hourly = List(24) { it.toLong() * 1000 },
                    categories = listOf(AppStat("games", 50L), AppStat("social", 30L)),
                    weeks = listOf(10L, 20L, 30L, 40L),
                    sessions = 12, longestMs = 999L,
                ),
            ),
        )
        assertEquals(p, RemoteReportCodec.decode(RemoteReportCodec.encode(p)))
    }

    @Test
    fun `old 6-field encoding still decodes with empty new fields (back-compat)`() {
        val us = Char(31).toString(); val gs = Char(29).toString(); val fs = Char(28).toString()
        val oldKid = listOf("Aarav", "60", "100", "1${gs}2", "YouTube${fs}9", "3").joinToString(us)
        val decoded = RemoteReportCodec.decode("1700000000000" + us + oldKid)
        assertNotNull(decoded)
        val k = decoded!!.kids.single()
        assertEquals("Aarav", k.name)
        assertEquals(60, k.limitMin)
        assertEquals(3, k.budgetHitDays)
        assertTrue(k.hourly.isEmpty())
        assertTrue(k.categories.isEmpty())
        assertTrue(k.weeks.isEmpty())
        assertEquals(0, k.sessions)
        assertEquals(0L, k.longestMs)
        // Phase-2 config mirror absent in an old doc → "not reported" sentinel.
        assertEquals(-1, k.allowedPreset)
        assertEquals(-1, k.autoBlockNewApps)
        // Tier-2 app-list fields absent in an old doc → empty.
        assertTrue(k.installedApps.isEmpty())
        assertTrue(k.customAllowed.isEmpty())
        assertTrue(k.perAppLimits.isEmpty())
    }

    @Test
    fun `Tier-2 app-list fields round-trip`() {
        val p = RemoteReportPayload(
            generatedAtMs = 1L,
            kids = listOf(
                KidReport(
                    name = "Aarav", limitMin = 60, dailyAvgMs = 0L,
                    week = listOf(1L), topApps = emptyList(), budgetHitDays = 0,
                    installedApps = listOf(AppEntry("com.x", "X App"), AppEntry("com.y", "Y")),
                    customAllowed = listOf("com.x"),
                    perAppLimits = listOf(AppStat("com.y", 30L)),
                ),
            ),
        )
        val decoded = RemoteReportCodec.decode(RemoteReportCodec.encode(p))
        assertEquals(p, decoded)
        val k = decoded!!.kids.single()
        assertEquals(2, k.installedApps.size)
        assertEquals("X App", k.installedApps[0].label)
        assertEquals(listOf("com.x"), k.customAllowed)
        assertEquals(30L, k.perAppLimits.single().ms)
    }

    @Test
    fun `Phase-2 config-mirror fields round-trip`() {
        val p = RemoteReportPayload(
            generatedAtMs = 1L,
            kids = listOf(
                KidReport(
                    name = "Aarav", limitMin = 120, dailyAvgMs = 0L,
                    week = listOf(1L), topApps = emptyList(), budgetHitDays = 0,
                    allowedPreset = 1, autoBlockNewApps = 1,
                ),
            ),
        )
        val decoded = RemoteReportCodec.decode(RemoteReportCodec.encode(p))
        assertEquals(p, decoded)
        assertEquals(1, decoded!!.kids.single().allowedPreset)
        assertEquals(1, decoded.kids.single().autoBlockNewApps)
    }
}
