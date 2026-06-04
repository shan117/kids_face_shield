package com.shantanu.shield.ui.stats

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
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

    /** Per-package foreground time within the given window, sorted desc by time. */
    fun usageInWindow(startMs: Long, endMs: Long): List<AppUsageBucket> {
        val events = usm.queryEvents(startMs, endMs)
        val sessionStart = mutableMapOf<String, Long>()
        val totals = mutableMapOf<String, Long>()
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val pkg = ev.packageName ?: continue
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> sessionStart[pkg] = ev.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val start = sessionStart.remove(pkg) ?: continue
                    val delta = (ev.timeStamp - start).coerceAtLeast(0L)
                    totals[pkg] = (totals[pkg] ?: 0L) + delta
                }
            }
        }
        // Any unclosed sessions count up to the window end.
        sessionStart.forEach { (pkg, start) ->
            val delta = (endMs - start).coerceAtLeast(0L)
            totals[pkg] = (totals[pkg] ?: 0L) + delta
        }
        return totals.entries
            .filter { it.value > 0L }
            .mapNotNull { (pkg, ms) -> buildBucket(pkg, ms) }
            .sortedByDescending { it.foregroundMs }
    }

    fun todayWindow(): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        return start to System.currentTimeMillis()
    }

    fun dayWindow(daysAgo: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end = if (daysAgo == 0) System.currentTimeMillis() else start + 24L * 60 * 60 * 1000 - 1
        return start to end
    }

    fun usageToday(): List<AppUsageBucket> {
        val (s, e) = todayWindow()
        return usageInWindow(s, e)
    }

    fun totalMsToday(): Long = usageToday().sumOf { it.foregroundMs }

    fun totalMsForDay(daysAgo: Int): Long {
        val (s, e) = dayWindow(daysAgo)
        return usageInWindow(s, e).sumOf { it.foregroundMs }
    }

    /** Last [days] days, oldest first; index 0 = today. */
    suspend fun dailyTotals(days: Int): List<DayBucket> {
        val history = dataStoreManager.freePlayHistory.first()
        val out = ArrayList<DayBucket>(days)
        for (i in (days - 1) downTo 0) {
            val (s, e) = dayWindow(i)
            val total = totalMsForWindow(s, e)
            val fp = freePlayMsInWindow(history, s, e)
            out.add(DayBucket(s, total, fp))
        }
        return out
    }

    /** Daily averages for the last [weeks] weeks. Oldest first. */
    fun weeklyDailyAverages(weeks: Int = 4): List<Long> {
        if (weeks <= 0) return emptyList()
        val out = ArrayList<Long>(weeks)
        for (w in (weeks - 1) downTo 0) {
            var weekTotal = 0L
            for (d in 0 until 7) {
                val daysAgo = w * 7 + d
                val (s, e) = dayWindow(daysAgo)
                weekTotal += totalMsForWindow(s, e)
            }
            out.add(weekTotal / 7)
        }
        return out
    }

    /** Average daily screen time across the current calendar month so far. */
    fun monthToDateAverageMs(): Long {
        val now = Calendar.getInstance()
        val monthStart = (now.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val dayOfMonth = now.get(Calendar.DAY_OF_MONTH)
        var monthTotal = 0L
        for (d in 0 until dayOfMonth) {
            val cal = (monthStart.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, d) }
            val start = cal.timeInMillis
            val end = if (d == dayOfMonth - 1) System.currentTimeMillis() else start + 24L * 60 * 60 * 1000 - 1
            monthTotal += totalMsForWindow(start, end)
        }
        return if (dayOfMonth == 0) 0L else monthTotal / dayOfMonth
    }

    private fun totalMsForWindow(s: Long, e: Long): Long {
        // queryUsageStats's totalTimeInForeground is unreliable for week-level rollups
        // (it returns cumulative values that can span beyond the bucket). Falling back
        // to event-based aggregation keeps numbers honest at the cost of a few extra
        // event scans per refresh.
        return usageInWindow(s, e).sumOf { it.foregroundMs }
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
    suspend fun freePlayUsageToday(): List<AppUsageBucket> {
        val (todayStart, todayEnd) = todayWindow()
        val sessions = freePlayRecordsToday()
        if (sessions.isEmpty()) return emptyList()
        val merged = mutableMapOf<String, Long>()
        for (session in sessions) {
            val s = maxOf(session.startMs, todayStart)
            val e = minOf(session.endMs, todayEnd)
            if (e <= s) continue
            for (bucket in usageInWindow(s, e)) {
                merged[bucket.packageName] = (merged[bucket.packageName] ?: 0L) + bucket.foregroundMs
            }
        }
        return merged.entries
            .filter { it.value > 0L }
            .mapNotNull { (pkg, ms) -> buildBucket(pkg, ms) }
            .sortedByDescending { it.foregroundMs }
    }

    private fun buildBucket(pkg: String, ms: Long): AppUsageBucket? {
        // Hide the launcher, our own app, and pure system apps the user never
        // installed. Updated-system apps (Chrome, Maps, etc.) are kept because they
        // behave like normal user apps from the parent's perspective.
        if (pkg == context.packageName) return null
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !isUpdatedSystem) return null
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
