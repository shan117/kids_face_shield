package com.shantanu.shield.ui.stats

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shantanu.shield.data.DataStoreManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class StatsViewMode { PARENT, KID }

data class StatsSnapshot(
    val mode: StatsViewMode = StatsViewMode.PARENT,
    val loading: Boolean = true,

    // Today (parent-mode)
    val totalTodayMs: Long = 0,
    val totalYesterdayMs: Long = 0,
    val parentTopApps: List<AppUsageBucket> = emptyList(),

    // Free Play (parent-mode)
    val freePlayGrantedMsToday: Long = 0,
    val freePlayUsedMsToday: Long = 0,
    val freePlayApps: List<AppUsageBucket> = emptyList(),

    // Kid-mode
    val budgetMs: Long = 0,
    val budgetUsedMs: Long = 0,
    // Parent-granted budget extension for today (ms). Shown in the Kid view.
    val extensionsTodayMs: Long = 0,
    val kidTopApps: List<AppUsageBucket> = emptyList(),

    // Weekly
    val week: List<DayBucket> = emptyList(),

    // Trends — oldest first; 4 entries each one week's daily average.
    val weeklyDailyAverages: List<Long> = emptyList(),
    // Month-to-date average across the current calendar month.
    val monthToDateAvgMs: Long = 0,
    // Trend delta in percent (positive = rising, negative = improving) computed from
    // weeklyDailyAverages.first() vs weeklyDailyAverages.last().
    val trendDeltaPct: Int? = null,

    // Insights
    val insights: List<String> = emptyList()
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: StatsRepository,
    private val dataStoreManager: DataStoreManager
) : ViewModel() {

    private val _snapshot = MutableStateFlow(StatsSnapshot())
    val snapshot: StateFlow<StatsSnapshot> = _snapshot.asStateFlow()

    private val _viewMode = MutableStateFlow(StatsViewMode.PARENT)
    val viewMode: StateFlow<StatsViewMode> = _viewMode.asStateFlow()

    init {
        // Observe ownerType so the dashboard auto-flips between Parent and Kid views
        // when the parent toggles Kid Mode on or off from Settings. No manual switcher.
        viewModelScope.launch {
            dataStoreManager.ownerType.collect { owner ->
                val next = if (owner == "kid") StatsViewMode.KID else StatsViewMode.PARENT
                if (_viewMode.value != next) {
                    _viewMode.value = next
                    refresh()
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _snapshot.value = _snapshot.value.copy(loading = true)
            val data = withContext(Dispatchers.IO) { computeSnapshot() }
            _snapshot.value = data
        }
    }

    private suspend fun computeSnapshot(): StatsSnapshot {
        val mode = _viewMode.value
        // App-set filter for this view. Kid view mirrors the budget (controlled apps only);
        // parent view is broader (everyday apps like Gmail/Photos) but still drops pure
        // utilities like Maps. All usage windows below are 07:00-anchored budget-days.
        val include: (String) -> Boolean = if (mode == StatsViewMode.KID) {
            { com.shantanu.shield.util.AllowedApps.isControlledPackage(context, it) }
        } else {
            { com.shantanu.shield.util.AllowedApps.isVisibleInParentStats(context, it) }
        }

        val totalToday = repo.totalMsToday(include)
        val totalYesterday = repo.totalMsForDay(1, include)
        val parentTop = repo.usageToday(include).take(6)
        val fpRecordsToday = repo.freePlayRecordsToday()
        val grantedMs = fpRecordsToday.sumOf {
            val today = repo.todayWindow()
            val s = maxOf(it.startMs, today.first)
            val e = minOf(it.endMs, today.second)
            (e - s).coerceAtLeast(0L).let { ov ->
                if (ov == 0L) 0L else it.grantedMs
            }
        }
        val fpAppUsage = repo.freePlayUsageToday(include)
        val fpUsedMs = fpAppUsage.sumOf { it.foregroundMs }

        val budget = dataStoreManager.dailyLimitMinutes.first() * 60_000L
        // Read the SAME persisted counter the service ticks and the Kid Mode tab shows,
        // so the budget ring here matches the Kid Mode card and the actual lock decision
        // exactly (anchored at 07:00, controlled-apps-only). The Kid view is only rendered
        // when ownerType == "kid", i.e. when that counter is live, so it is never stale.
        val budgetUsed = dataStoreManager.screenTimeUsedMs.first()
        val extensionsToday = dataStoreManager.extensionsTodayMs.first()

        val week = repo.dailyTotals(7, include)
        val weeklyAvgs = repo.weeklyDailyAverages(4, include)
        val monthAvg = repo.monthToDateAverageMs(include)
        val trendDelta = computeTrendDelta(weeklyAvgs)
        val insights = buildInsights(mode, totalToday, totalYesterday, parentTop, fpAppUsage, budget, budgetUsed, week, weeklyAvgs, monthAvg, trendDelta)

        return StatsSnapshot(
            mode = mode,
            loading = false,
            totalTodayMs = totalToday,
            totalYesterdayMs = totalYesterday,
            parentTopApps = parentTop,
            freePlayGrantedMsToday = grantedMs,
            freePlayUsedMsToday = fpUsedMs,
            freePlayApps = fpAppUsage,
            budgetMs = budget,
            budgetUsedMs = budgetUsed,
            extensionsTodayMs = extensionsToday,
            kidTopApps = parentTop,
            week = week,
            weeklyDailyAverages = weeklyAvgs,
            monthToDateAvgMs = monthAvg,
            trendDeltaPct = trendDelta,
            insights = insights
        )
    }

    private fun computeTrendDelta(weeks: List<Long>): Int? {
        if (weeks.size < 2) return null
        val first = weeks.first()
        val last = weeks.last()
        if (first <= 0L) return null
        return ((last - first) * 100 / first).toInt()
    }

    private fun buildInsights(
        mode: StatsViewMode,
        todayMs: Long,
        yesterdayMs: Long,
        parentTop: List<AppUsageBucket>,
        fpApps: List<AppUsageBucket>,
        budgetMs: Long,
        budgetUsedMs: Long,
        week: List<DayBucket>,
        weeklyAvgs: List<Long>,
        monthAvg: Long,
        trendDelta: Int?
    ): List<String> {
        val out = mutableListOf<String>()

        if (yesterdayMs > 0) {
            val deltaPct = ((todayMs - yesterdayMs) * 100.0 / yesterdayMs).toInt()
            if (kotlin.math.abs(deltaPct) >= 10) {
                val dir = if (deltaPct > 0) "up" else "down"
                out += "Your screen time is $dir ${kotlin.math.abs(deltaPct)}% vs yesterday."
            }
        }

        if (mode == StatsViewMode.PARENT && fpApps.isNotEmpty()) {
            val top = fpApps.first()
            out += "${top.name} is your kid's top Free Play app today."
        }

        if (mode == StatsViewMode.KID && budgetMs > 0) {
            val pct = (budgetUsedMs * 100 / budgetMs).toInt()
            when {
                pct >= 100 -> out += "Budget reached for today."
                pct >= 75 -> out += "You've used $pct% of today's budget."
            }
        }

        if (parentTop.isNotEmpty()) {
            val topApp = parentTop.first()
            val total = parentTop.sumOf { it.foregroundMs }
            if (total > 0) {
                val share = (topApp.foregroundMs * 100 / total).toInt()
                if (share >= 30) {
                    out += "${topApp.name} is $share% of your screen time today."
                }
            }
        }

        if (week.size >= 7) {
            val days = week.count { it.totalMs > 0 }
            if (days >= 7) {
                val avg = week.sumOf { it.totalMs } / 7
                out += "Average ${formatHm(avg)} per day this week."
            }
        }

        if (trendDelta != null && kotlin.math.abs(trendDelta) >= 5) {
            val direction = if (trendDelta < 0) "improving" else "rising"
            out += "Daily average is $direction ${kotlin.math.abs(trendDelta)}% vs 4 weeks ago."
        }

        if (monthAvg > 0) {
            out += "This month so far: ${formatHm(monthAvg)} per day."
        }

        return out
    }
}

internal fun formatHm(ms: Long): String {
    val totalMinutes = (ms / 60_000L).toInt()
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}
