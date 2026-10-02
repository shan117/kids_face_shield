package com.shantanu.shield.remote

/**
 * The aggregate-only report that crosses devices (end-to-end encrypted). It carries ONLY screen-time
 * totals & patterns — NEVER content, raw events, messages, location, or face data. The invariant that makes
 * this a privacy feature instead of spyware. See PARENT_REMOTE_REPORT_PLAN.md §1.
 */
data class RemoteReportPayload(
    val generatedAtMs: Long,
    val kids: List<KidReport>,
)

data class KidReport(
    val name: String,
    val limitMin: Int,
    val dailyAvgMs: Long,
    val week: List<Long>,            // up to 7 daily totals, oldest first
    val topApps: List<AppStat>,
    val budgetHitDays: Int,
    // --- B1 richer-report fields (all still aggregate). Default-empty so OLD 6-field docs keep decoding. ---
    val hourly: List<Long> = emptyList(),         // 24 hour-of-day buckets (ms) → time-of-day + night usage
    val categories: List<AppStat> = emptyList(),  // per-category totals (name = category label)
    val weeks: List<Long> = emptyList(),          // 4-week daily-average trend, oldest first
    val sessions: Int = 0,                        // controlled-app session count (pickups)
    val longestMs: Long = 0,                      // longest single session
    // --- Phase-2 current-config mirror (so the parent edits real values, not blind). -1 = not reported
    //     (old doc / not applicable). limitMin above already carries the daily budget. ---
    val allowedPreset: Int = -1,                  // 0 Phone+Msgs · 1 +WhatsApp · 2 Custom
    val autoBlockNewApps: Int = -1,               // 0 off · 1 on
    // --- Tier-2: so the parent can pick specific apps remotely. installedApps = the pickable user apps on the
    //     child (package + label); customAllowed = currently always-allowed packages; perAppLimits = current
    //     per-app caps (AppStat.name = package, AppStat.ms = minutes). All default-empty for old docs. ---
    val installedApps: List<AppEntry> = emptyList(),
    val customAllowed: List<String> = emptyList(),
    val perAppLimits: List<AppStat> = emptyList(),
    // Today's granted extra minutes — lets the parent confirm a GRANT_EXTRA_TIME actually applied. -1 = old doc.
    val extensionsMin: Int = -1,
    /**
     * Web-filter refusals, as COUNTS per category (AppStat.name = category name, AppStat.ms = count).
     *
     * Counts, deliberately never the domains. The payload's whole premise is that it carries patterns
     * and not content, and a list of the sites a child tried to reach is content — it would turn this
     * from a safety report into a browsing log. A parent needs to know whether it is happening far
     * more than they need the addresses. Empty on old docs. See WEB_FILTER_PLAN.md Phase 2.
     */
    val webBlocks: List<AppStat> = emptyList(),
)

data class AppStat(val name: String, val ms: Long)
data class AppEntry(val pkg: String, val label: String)

/**
 * Pure, dependency-free serialization so the privacy-critical payload is unit-testable without Android
 * or a JSON lib (mirrors the other codecs in `data/`). Uses ASCII control characters as delimiters
 * (US/RS/GS/FS) — they never occur in app labels, so no escaping is needed; we strip them defensively
 * on encode anyway. Positional + tolerant: extra trailing fields are optional, so the format is
 * forward/backward compatible (old 6-field docs decode with the new fields empty; new docs round-trip fully).
 */
object RemoteReportCodec {
    // Built from integer codes so no literal control char appears in source.
    private val US = Char(31)  // field separator (within a kid)
    private val RS = Char(30)  // record separator (between kids)
    private val GS = Char(29)  // group separator (list items)
    private val FS = Char(28)  // unit separator (app name <-> ms)

    private fun clean(s: String) = s.filterNot { it == US || it == RS || it == GS || it == FS }

    private fun longs(list: List<Long>) = list.joinToString(GS.toString())
    private fun apps(list: List<AppStat>) = list.joinToString(GS.toString()) { "${clean(it.name)}$FS${it.ms}" }
    private fun entries(list: List<AppEntry>) = list.joinToString(GS.toString()) { "${clean(it.pkg)}$FS${clean(it.label)}" }
    private fun strings(list: List<String>) = list.joinToString(GS.toString()) { clean(it) }

    fun encode(p: RemoteReportPayload): String {
        val kidsBlock = p.kids.joinToString(RS.toString()) { k ->
            listOf(
                clean(k.name),
                k.limitMin.toString(),
                k.dailyAvgMs.toString(),
                longs(k.week),
                apps(k.topApps),
                k.budgetHitDays.toString(),
                longs(k.hourly),
                apps(k.categories),
                longs(k.weeks),
                k.sessions.toString(),
                k.longestMs.toString(),
                k.allowedPreset.toString(),
                k.autoBlockNewApps.toString(),
                entries(k.installedApps),
                strings(k.customAllowed),
                apps(k.perAppLimits),
                k.extensionsMin.toString(),
                apps(k.webBlocks),
            ).joinToString(US.toString())
        }
        return p.generatedAtMs.toString() + US + kidsBlock
    }

    fun decode(raw: String): RemoteReportPayload? = runCatching {
        if (raw.isEmpty()) return null
        val header = raw.split(US, limit = 2)
        val generatedAt = header[0].toLong()
        val kidsBlock = header.getOrElse(1) { "" }
        val kids = if (kidsBlock.isEmpty()) emptyList()
                   else kidsBlock.split(RS).mapNotNull { decodeKid(it) }
        RemoteReportPayload(generatedAt, kids)
    }.getOrNull()

    private fun parseLongs(s: String): List<Long> =
        s.split(GS).filter { it.isNotEmpty() }.map { it.toLong() }

    private fun parseApps(s: String): List<AppStat> =
        s.split(GS).filter { it.isNotEmpty() }.mapNotNull { a ->
            val parts = a.split(FS)
            if (parts.size == 2) AppStat(parts[0], parts[1].toLong()) else null
        }

    private fun parseEntries(s: String): List<AppEntry> =
        s.split(GS).filter { it.isNotEmpty() }.mapNotNull { a ->
            val parts = a.split(FS)
            if (parts.size == 2) AppEntry(parts[0], parts[1]) else null
        }

    private fun parseStrings(s: String): List<String> =
        s.split(GS).filter { it.isNotEmpty() }

    private fun decodeKid(s: String): KidReport? = runCatching {
        val f = s.split(US)
        if (f.size < 6) return null   // the 6 original fields are mandatory; the rest are optional
        KidReport(
            name = f[0],
            limitMin = f[1].toInt(),
            dailyAvgMs = f[2].toLong(),
            week = parseLongs(f[3]),
            topApps = parseApps(f[4]),
            budgetHitDays = f[5].toInt(),
            hourly = f.getOrNull(6)?.let(::parseLongs) ?: emptyList(),
            categories = f.getOrNull(7)?.let(::parseApps) ?: emptyList(),
            weeks = f.getOrNull(8)?.let(::parseLongs) ?: emptyList(),
            sessions = f.getOrNull(9)?.toIntOrNull() ?: 0,
            longestMs = f.getOrNull(10)?.toLongOrNull() ?: 0,
            allowedPreset = f.getOrNull(11)?.toIntOrNull() ?: -1,
            autoBlockNewApps = f.getOrNull(12)?.toIntOrNull() ?: -1,
            installedApps = f.getOrNull(13)?.let(::parseEntries) ?: emptyList(),
            customAllowed = f.getOrNull(14)?.let(::parseStrings) ?: emptyList(),
            perAppLimits = f.getOrNull(15)?.let(::parseApps) ?: emptyList(),
            extensionsMin = f.getOrNull(16)?.toIntOrNull() ?: -1,
            webBlocks = f.getOrNull(17)?.let(::parseApps) ?: emptyList(),
        )
    }.getOrNull()
}
