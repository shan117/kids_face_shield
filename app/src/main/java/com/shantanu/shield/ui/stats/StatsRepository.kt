package com.shantanu.shield.ui.stats

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.data.FreePlayRecord
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

    /** Average daily screen time across the budget-days of the current calendar month. */
    fun monthToDateAverageMs(include: (String) -> Boolean = { controlled(it) }): Long {
        val monthStartMs = (Calendar.getInstance().clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        var total = 0L
        var days = 0
        var daysAgo = 0
        // Walk back budget-days while their start is still inside this calendar month.
        while (daysAgo <= 31) {
            val (s, e) = dayWindow(daysAgo)
            if (s < monthStartMs) break
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
