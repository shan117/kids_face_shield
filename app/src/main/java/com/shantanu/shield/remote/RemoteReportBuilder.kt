package com.shantanu.shield.remote

/**
 * Pure derivations for a [KidReport] — the parts worth pinning with tests (daily average, budget-hit
 * count) — kept separate from the Android-side gathering in `RemoteReportGatherer`. No Android, so it is
 * JVM-testable.
 */
object RemoteReportBuilder {

    /** Mean of the daily totals; 0 for an empty week. */
    fun dailyAvgMs(week: List<Long>): Long = if (week.isEmpty()) 0L else week.sum() / week.size

    /** Number of days whose usage met or exceeded the daily budget. 0 when no limit is set. */
    fun budgetHitDays(week: List<Long>, limitMin: Int): Int {
        if (limitMin <= 0) return 0
        val limitMs = limitMin * 60_000L
        return week.count { it >= limitMs }
    }

    /** Assemble a kid's weekly report from its raw weekly totals + top apps (+ optional richer fields). */
    fun kidReport(
        name: String,
        limitMin: Int,
        week: List<Long>,
        topApps: List<AppStat>,
        weeks: List<Long> = emptyList(),
        categories: List<AppStat> = emptyList(),
        hourly: List<Long> = emptyList(),
        sessions: Int = 0,
        longestMs: Long = 0,
        allowedPreset: Int = -1,
        autoBlockNewApps: Int = -1,
        installedApps: List<AppEntry> = emptyList(),
        customAllowed: List<String> = emptyList(),
        perAppLimits: List<AppStat> = emptyList(),
        extensionsMin: Int = -1,
    ): KidReport =
        KidReport(
            name = name,
            limitMin = limitMin,
            dailyAvgMs = dailyAvgMs(week),
            week = week,
            topApps = topApps,
            budgetHitDays = budgetHitDays(week, limitMin),
            weeks = weeks,
            categories = categories,
            hourly = hourly,
            sessions = sessions,
            longestMs = longestMs,
            allowedPreset = allowedPreset,
            autoBlockNewApps = autoBlockNewApps,
            installedApps = installedApps,
            customAllowed = customAllowed,
            perAppLimits = perAppLimits,
            extensionsMin = extensionsMin,
        )
}
