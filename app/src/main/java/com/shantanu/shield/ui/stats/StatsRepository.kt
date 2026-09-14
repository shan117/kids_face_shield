package com.shantanu.shield.ui.stats

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.data.FreePlayRecord
import com.shantanu.shield.data.ProfileSession
import com.shantanu.shield.util.AppCategorizer
import com.shantanu.shield.util.AppCategory
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

data class AppUsageBucket(
    val packageName: String,
    val name: String,
    val icon: Drawable?,
    val foregroundMs: Long
)

data class DayBucket(
    val dateMs: Long,
    val totalMs: Long,
    val freePlayMs: Long
)

@Singleton
class StatsRepository @Inject constructor(
    private val context: Context,
    private val dataStoreManager: DataStoreManager
) {

    private val pm: PackageManager get() = context.packageManager
    private val usm: UsageStatsManager
        get() = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    // Default app filter for all usage queries: the controlled set (kid budget basis).
    // The parent view passes AllowedApps.isVisibleInParentStats instead.
    private fun controlled(pkg: String): Boolean =
        com.shantanu.shield.util.AllowedApps.isControlledPackage(context, pkg)

    /** Per-package foreground time within the given window, sorted desc by time.
     *  [include] decides which packages are counted — controlled apps (kid view) or
     *  parent-visible apps (parent view). Measurement is shared with the Kid Mode budget
     *  via UsageMeasure, so the charts and the budget ring count usage identically. */
    fun usageInWindow(
        startMs: Long,
        endMs: Long,
        include: (String) -> Boolean = { controlled(it) }
    ): List<AppUsageBucket> {
        return com.shantanu.shield.util.UsageMeasure.foregroundMsByPackage(usm, startMs, endMs)
            .entries
            .filter { it.value > 0L && include(it.key) }
            .mapNotNull { (pkg, ms) -> resolveBucket(pkg, ms) }
            .sortedByDescending { it.foregroundMs }
    }

    // Budget-day windows are anchored at 07:00 — the same boundary the service uses to
    // reset the budget — so the dashboard's "today", 7-day chart, and trends line up with
    // the enforced budget instead of the calendar midnight. A budget-day labelled e.g.
    // "Tue" runs Tue 07:00 → Wed 07:00; usage at 02:00 Wed still belongs to Tue.

    fun todayWindow(): Pair<Long, Long> = dayWindow(0)

    fun dayWindow(daysAgo: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        // Step back to the start (07:00) of the current budget-day.
        if (cal.get(Calendar.HOUR_OF_DAY) < 7) cal.add(Calendar.DAY_OF_YEAR, -1)
        cal.set(Calendar.HOUR_OF_DAY, 7); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        // …then back [daysAgo] whole budget-days. DAY_OF_YEAR arithmetic is DST-safe.
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        val start = cal.timeInMillis
        val end = if (daysAgo == 0) System.currentTimeMillis()
                  else (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        return start to end
    }

    fun usageToday(include: (String) -> Boolean = { controlled(it) }): List<AppUsageBucket> {
        val (s, e) = todayWindow()
        return usageInWindow(s, e, include)
    }

    fun totalMsToday(include: (String) -> Boolean = { controlled(it) }): Long =
        usageToday(include).sumOf { it.foregroundMs }

    fun totalMsForDay(daysAgo: Int, include: (String) -> Boolean = { controlled(it) }): Long {
        val (s, e) = dayWindow(daysAgo)
        return usageInWindow(s, e, include).sumOf { it.foregroundMs }
    }

    /** Last [days] budget-days, oldest first; index 0 = today. */
    suspend fun dailyTotals(days: Int, include: (String) -> Boolean = { controlled(it) }): List<DayBucket> {
        val history = dataStoreManager.freePlayHistory.first()
        val out = ArrayList<DayBucket>(days)
        for (i in (days - 1) downTo 0) {
            val (s, e) = dayWindow(i)
            val total = totalMsForWindow(s, e, include)
            val fp = freePlayMsInWindow(history, s, e)
            out.add(DayBucket(s, total, fp))
        }
        return out
    }

    /** Daily averages for the last [weeks] weeks. Oldest first. */
    fun weeklyDailyAverages(weeks: Int = 4, include: (String) -> Boolean = { controlled(it) }): List<Long> {
        if (weeks <= 0) return emptyList()
        val out = ArrayList<Long>(weeks)
        for (w in (weeks - 1) downTo 0) {
            var weekTotal = 0L
            for (d in 0 until 7) {
                val daysAgo = w * 7 + d
                val (s, e) = dayWindow(daysAgo)
                weekTotal += totalMsForWindow(s, e, include)
            }
            out.add(weekTotal / 7)
        }
        return out
    }

    /** Start-of-month timestamp (calendar midnight on the 1st), shared by the device-wide and
     *  per-kid month-to-date averages. */
    private fun monthStartMs(): Long = (Calendar.getInstance().clone() as Calendar).apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Average daily screen time across the budget-days of the current calendar month. */
    fun monthToDateAverageMs(include: (String) -> Boolean = { controlled(it) }): Long {
        val monthStart = monthStartMs()
        var total = 0L
        var days = 0
        var daysAgo = 0
        // Walk back budget-days while their start is still inside this calendar month.
        while (daysAgo <= 31) {
            val (s, e) = dayWindow(daysAgo)
            if (s < monthStart) break
            total += totalMsForWindow(s, e, include)
            days++
            daysAgo++
        }
        return if (days == 0) 0L else total / days
    }

    private fun totalMsForWindow(s: Long, e: Long, include: (String) -> Boolean = { controlled(it) }): Long {
        // queryUsageStats's totalTimeInForeground is unreliable for week-level rollups
        // (it returns cumulative values that can span beyond the bucket). Falling back
        // to event-based aggregation keeps numbers honest at the cost of a few extra
        // event scans per refresh.
        return usageInWindow(s, e, include).sumOf { it.foregroundMs }
    }

    suspend fun freePlayRecords(): List<FreePlayRecord> = dataStoreManager.freePlayHistory.first()

    suspend fun freePlayRecordsToday(): List<FreePlayRecord> {
        val (s, e) = todayWindow()
        return freePlayRecords().filter { it.endMs >= s && it.startMs <= e }
    }

    fun freePlayMsInWindow(history: List<FreePlayRecord>, s: Long, e: Long): Long =
        history.sumOf { rec ->
            val overlapStart = maxOf(rec.startMs, s)
            val overlapEnd = minOf(rec.endMs, e)
            (overlapEnd - overlapStart).coerceAtLeast(0L)
        }

    /** Per-app foreground time during any Free Play window today. */
    suspend fun freePlayUsageToday(include: (String) -> Boolean = { controlled(it) }): List<AppUsageBucket> {
        val (todayStart, todayEnd) = todayWindow()
        val sessions = freePlayRecordsToday()
        if (sessions.isEmpty()) return emptyList()
        val merged = mutableMapOf<String, Long>()
        for (session in sessions) {
            val s = maxOf(session.startMs, todayStart)
            val e = minOf(session.endMs, todayEnd)
            if (e <= s) continue
            for (bucket in usageInWindow(s, e, include)) {
                merged[bucket.packageName] = (merged[bucket.packageName] ?: 0L) + bucket.foregroundMs
            }
        }
        return merged.entries
            .filter { it.value > 0L }
            .mapNotNull { (pkg, ms) -> resolveBucket(pkg, ms) }
            .sortedByDescending { it.foregroundMs }
    }

    // ---- Per-kid (Multiple-kids) usage ----
    // Every per-kid number is the same window math the parent/kid views use, but intersected
    // with that kid's ProfileSessions so it only counts time the kid was the identified user.

    /** All recorded sessions for [profileId]. Fetched once and reused across the per-kid windows
     *  (today / 7-day / 4-week / month) so we don't re-read DataStore for every window. */
    suspend fun profileSessionsFor(profileId: String): List<ProfileSession> =
        dataStoreManager.profileSessions.first().filter { it.profileId == profileId }

    /** Per-app foreground time during the [sessions] that overlap [windowStart, windowEnd].
     *  The shared primitive behind every per-kid figure: clip each session to the window, measure
     *  controlled-app usage in the overlap (same UsageMeasure as the budget), merge by package. */
    private fun profileUsageInWindow(
        sessions: List<ProfileSession>,
        windowStart: Long,
        windowEnd: Long,
        include: (String) -> Boolean
    ): List<AppUsageBucket> {
        val merged = mutableMapOf<String, Long>()
        for (session in sessions) {
            val s = maxOf(session.startMs, windowStart)
            val e = minOf(session.endMs, windowEnd)
            if (e <= s) continue
            for (bucket in usageInWindow(s, e, include)) {
                merged[bucket.packageName] = (merged[bucket.packageName] ?: 0L) + bucket.foregroundMs
            }
        }
        return merged.entries
            .filter { it.value > 0L }
            .mapNotNull { (pkg, ms) -> resolveBucket(pkg, ms) }
            .sortedByDescending { it.foregroundMs }
    }

    /** Top apps for [profileId] today (07:00 window). Convenience over [profileSessionsFor]. */
    suspend fun profileUsageToday(
        profileId: String,
        include: (String) -> Boolean = { controlled(it) }
    ): List<AppUsageBucket> = profileUsageToday(profileSessionsFor(profileId), include)

    /** Top apps within today's window for already-fetched [sessions]. */
    fun profileUsageToday(
        sessions: List<ProfileSession>,
        include: (String) -> Boolean = { controlled(it) }
    ): List<AppUsageBucket> {
        val (s, e) = todayWindow()
        return profileUsageInWindow(sessions, s, e, include)
    }

    /** Total controlled-app ms for [sessions] within the budget-day [daysAgo] days back. */
    fun profileTotalForDay(
        sessions: List<ProfileSession>,
        daysAgo: Int,
        include: (String) -> Boolean = { controlled(it) }
    ): Long {
        val (s, e) = dayWindow(daysAgo)
        return profileUsageInWindow(sessions, s, e, include).sumOf { it.foregroundMs }
    }

    /** Last [days] budget-days for [sessions], oldest first; index 0 = today. Free-Play ms is left
     *  at 0 — Free Play is a device-level grant, not attributed to an individual kid. */
    fun profileDailyTotals(
        sessions: List<ProfileSession>,
        days: Int,
        include: (String) -> Boolean = { controlled(it) }
    ): List<DayBucket> {
        val out = ArrayList<DayBucket>(days)
        for (i in (days - 1) downTo 0) {
            val (s, e) = dayWindow(i)
            out.add(DayBucket(s, profileUsageInWindow(sessions, s, e, include).sumOf { it.foregroundMs }, 0L))
        }
        return out
    }

    /** Daily averages for the last [weeks] weeks for [sessions]. Oldest first. */
    fun profileWeeklyDailyAverages(
        sessions: List<ProfileSession>,
        weeks: Int = 4,
        include: (String) -> Boolean = { controlled(it) }
    ): List<Long> {
        if (weeks <= 0) return emptyList()
        val out = ArrayList<Long>(weeks)
        for (w in (weeks - 1) downTo 0) {
            var weekTotal = 0L
            for (d in 0 until 7) {
                val (s, e) = dayWindow(w * 7 + d)
                weekTotal += profileUsageInWindow(sessions, s, e, include).sumOf { it.foregroundMs }
            }
            out.add(weekTotal / 7)
        }
        return out
    }

    /** Month-to-date daily average for [sessions] across the current calendar month. */
    fun profileMonthToDateAverage(
        sessions: List<ProfileSession>,
        include: (String) -> Boolean = { controlled(it) }
    ): Long {
        val monthStart = monthStartMs()
        var total = 0L
        var days = 0
        var daysAgo = 0
        while (daysAgo <= 31) {
            val (s, e) = dayWindow(daysAgo)
            if (s < monthStart) break
            total += profileUsageInWindow(sessions, s, e, include).sumOf { it.foregroundMs }
            days++
            daysAgo++
        }
        return if (days == 0) 0L else total / days
    }

    /** Per-app foreground time across the last [days] budget-days (device-wide), merged + sorted desc.
     *  Used to build the weekly Remote Report's top-apps list in single-kid mode. */
    fun usageForDays(days: Int, include: (String) -> Boolean = { controlled(it) }): List<AppUsageBucket> {
        val merged = mutableMapOf<String, Long>()
        for (i in 0 until days) {
            val (s, e) = dayWindow(i)
            for (b in usageInWindow(s, e, include)) {
                merged[b.packageName] = (merged[b.packageName] ?: 0L) + b.foregroundMs
            }
        }
        return merged.entries
            .filter { it.value > 0L }
            .mapNotNull { (pkg, ms) -> resolveBucket(pkg, ms) }
            .sortedByDescending { it.foregroundMs }
    }

    /** Per-app foreground time for [sessions] across the last [days] budget-days, merged + sorted desc.
     *  The per-kid counterpart of [usageForDays] for the multi-kid Remote Report. */
    fun profileUsageForDays(
        sessions: List<ProfileSession>,
        days: Int,
        include: (String) -> Boolean = { controlled(it) }
    ): List<AppUsageBucket> {
        val merged = mutableMapOf<String, Long>()
        for (i in 0 until days) {
            val (s, e) = dayWindow(i)
            for (b in profileUsageInWindow(sessions, s, e, include)) {
                merged[b.packageName] = (merged[b.packageName] ?: 0L) + b.foregroundMs
            }
        }
        return merged.entries
            .filter { it.value > 0L }
            .mapNotNull { (pkg, ms) -> resolveBucket(pkg, ms) }
            .sortedByDescending { it.foregroundMs }
    }

    /** Aggregate already-measured usage buckets into per-category totals (sorted by category order),
     *  for the richer Remote Report. Returns (categoryLabel, ms), positive totals only. */
    fun categoryTotals(buckets: List<AppUsageBucket>): List<Pair<String, Long>> {
        val totals = HashMap<AppCategory, Long>()
        for (b in buckets) {
            val cat = runCatching {
                AppCategorizer.categoryOf(pm.getApplicationInfo(b.packageName, 0), b.name)
            }.getOrDefault(AppCategory.OTHER)
            totals[cat] = (totals[cat] ?: 0L) + b.foregroundMs
        }
        return totals.entries
            .filter { it.value > 0L }
            .sortedBy { it.key.order }
            .map { it.key.label to it.value }
    }

    /** 24 hour-of-day buckets of controlled-app usage over the last [days] budget-days (device-wide). */
    fun hourlyControlledMs(days: Int, include: (String) -> Boolean = { controlled(it) }): LongArray {
        val (start, _) = dayWindow(days - 1)
        val end = System.currentTimeMillis()
        return com.shantanu.shield.util.UsageMeasure.hourlyForegroundMs(
            com.shantanu.shield.util.UsageMeasure.eventsIn(usm, start, end), end, include)
    }

    /** Controlled-app session count + longest session over the last [days] budget-days (device-wide). */
    fun sessionStatsForDays(
        days: Int,
        include: (String) -> Boolean = { controlled(it) }
    ): com.shantanu.shield.util.UsageMeasure.SessionStats {
        val (start, _) = dayWindow(days - 1)
        val end = System.currentTimeMillis()
        return com.shantanu.shield.util.UsageMeasure.sessionStats(
            com.shantanu.shield.util.UsageMeasure.eventsIn(usm, start, end), end, include)
    }

    // Resolve a package's label + icon into a bucket. App-set filtering happens at the
    // call site (via the `include` predicate), so this only returns null when the package
    // can't be resolved (uninstalled since the usage event was recorded).
    private fun resolveBucket(pkg: String, ms: Long): AppUsageBucket? {
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            AppUsageBucket(
                packageName = pkg,
                name = pm.getApplicationLabel(info).toString(),
                icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                foregroundMs = ms
            )
        } catch (e: Exception) {
            null
        }
    }
}
