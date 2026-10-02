package com.shantanu.shield.webfilter

import com.shantanu.shield.remote.AppStat
import com.shantanu.shield.remote.KidReport
import com.shantanu.shield.remote.RemoteReportCodec
import com.shantanu.shield.remote.RemoteReportPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The counts that reach a parent, and the guarantee that only counts do.
 *
 * The report payload's premise is that it carries patterns and never content. A list of the sites a
 * child tried to open is content — it would turn a safety report into a browsing log. So the wire
 * format carries category names and integers, and these tests keep it that way.
 */
class WebBlockCountCodecTest {

    @Test
    fun `round-trips counts`() {
        val counts = mapOf(
            WebFilterCategory.ADULT to 9,
            WebFilterCategory.GAMBLING to 3,
        )
        assertEquals(counts, WebBlockCountCodec.decode(WebBlockCountCodec.encode(counts)))
    }

    @Test
    fun `round-trips an empty map`() {
        assertTrue(WebBlockCountCodec.encode(emptyMap()).isEmpty())
        assertTrue(WebBlockCountCodec.decode("").isEmpty())
    }

    @Test
    fun `zero and negative counts are dropped`() {
        val encoded = WebBlockCountCodec.encode(
            mapOf(WebFilterCategory.ADULT to 0, WebFilterCategory.DATING to -4, WebFilterCategory.DRUGS to 2)
        )
        assertEquals(mapOf(WebFilterCategory.DRUGS to 2), WebBlockCountCodec.decode(encoded))
    }

    @Test
    fun `an unknown category from a newer child is ignored, not fatal`() {
        val record = "SOME_FUTURE_CATEGORY${Char(31)}5"
        assertTrue(WebBlockCountCodec.decode(record).isEmpty())
    }

    @Test
    fun `a malformed record is dropped without losing the rest`() {
        val good = WebBlockCountCodec.encode(mapOf(WebFilterCategory.ADULT to 4))
        assertEquals(
            mapOf(WebFilterCategory.ADULT to 4),
            WebBlockCountCodec.decode(good + Char(30) + "garbage"),
        )
    }

    @Test
    fun `total sums the categories`() {
        assertEquals(
            12,
            WebBlockCountCodec.total(
                mapOf(WebFilterCategory.ADULT to 9, WebFilterCategory.GAMBLING to 3)
            ),
        )
        assertEquals(0, WebBlockCountCodec.total(emptyMap()))
    }

    // ---- the report payload ----

    @Test
    fun `webBlocks survives a report round-trip`() {
        val payload = RemoteReportPayload(
            generatedAtMs = 1L,
            kids = listOf(
                KidReport(
                    name = "Child",
                    limitMin = 120,
                    dailyAvgMs = 1000L,
                    week = listOf(1L),
                    topApps = emptyList(),
                    budgetHitDays = 0,
                    webBlocks = listOf(AppStat("ADULT", 9), AppStat("GAMBLING", 3)),
                )
            ),
        )
        val decoded = RemoteReportCodec.decode(RemoteReportCodec.encode(payload))
        assertEquals(payload.kids.first().webBlocks, decoded?.kids?.first()?.webBlocks)
    }

    @Test
    fun `an older report without webBlocks still decodes`() {
        // Backward compatibility: a child on a build that predates web filtering sends 17 fields, and a
        // newer parent must still read their screen-time report rather than failing the whole document.
        val old = RemoteReportPayload(
            generatedAtMs = 1L,
            kids = listOf(
                KidReport("Child", 120, 1000L, listOf(1L), emptyList(), 0)
            ),
        )
        val encoded = RemoteReportCodec.encode(old)
        val decoded = RemoteReportCodec.decode(encoded)

        assertEquals("Child", decoded?.kids?.first()?.name)
        assertTrue(decoded?.kids?.first()?.webBlocks?.isEmpty() == true)
    }

    @Test
    fun `the wire format carries only category names and integers`() {
        // A guard against someone later "improving" this by adding the domain. If a hostname ever
        // appears in the encoded payload, this fails.
        val counts = mapOf(WebFilterCategory.ADULT to 7)
        val encoded = WebBlockCountCodec.encode(counts)

        assertTrue("category name present", encoded.contains("ADULT"))
        assertTrue("count present", encoded.contains("7"))
        assertTrue("no dots, so no hostname could be encoded here", !encoded.contains("."))
    }
}
