package com.shantanu.shield.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager

/**
 * Single source of truth for "how much foreground time did each package get in a window".
 *
 * Both the Kid Mode budget (AppLockForegroundService.pollScreenTime) and the Stats
 * dashboard (StatsRepository) measure usage through here, so the budget ring and the
 * charts always use the SAME method over the SAME window — no aggregate-vs-event drift.
 *
 * Method: replay UsageEvents, pair each ACTIVITY_RESUMED with the next
 * ACTIVITY_PAUSED/STOPPED, and sum the deltas. This is precise to the window bounds —
 * unlike UsageStatsManager.queryAndAggregateUsageStats, whose per-package
 * totalTimeInForeground is reported against midnight-aligned daily buckets and therefore
 * leaks usage from before the window start (e.g. midnight→07:00 bleeding into a 07:00
 * budget day).
 */
object UsageMeasure {

    enum class Type { RESUMED, PAUSED_OR_STOPPED }

    data class Event(val pkg: String, val type: Type, val timeStamp: Long)

    /**
     * Pure reduction over an in-order event stream — no Android dependencies, so it is
     * unit-testable. Sessions still open at [windowEndMs] are counted up to that bound.
     * Negative deltas (out-of-order timestamps) are clamped to zero. Returns only
     * packages with a positive total.
     */
    fun reduceForegroundMs(events: List<Event>, windowEndMs: Long): Map<String, Long> {
        val sessionStart = HashMap<String, Long>()
        val totals = HashMap<String, Long>()
        for (e in events) {
            when (e.type) {
                Type.RESUMED -> sessionStart[e.pkg] = e.timeStamp
                Type.PAUSED_OR_STOPPED -> {
                    val start = sessionStart.remove(e.pkg) ?: continue
                    val delta = (e.timeStamp - start).coerceAtLeast(0L)
                    totals[e.pkg] = (totals[e.pkg] ?: 0L) + delta
                }
            }
        }
        // Any app still in the foreground at the window end counts up to that end.
        for ((pkg, start) in sessionStart) {
            val delta = (windowEndMs - start).coerceAtLeast(0L)
            totals[pkg] = (totals[pkg] ?: 0L) + delta
        }
        return totals.filterValues { it > 0L }
    }

    /** Android adapter: query events in [startMs, endMs] and reduce them. */
    fun foregroundMsByPackage(usm: UsageStatsManager, startMs: Long, endMs: Long): Map<String, Long> {
        val raw = usm.queryEvents(startMs, endMs)
        val events = ArrayList<Event>()
        val ev = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(ev)
            val pkg = ev.packageName ?: continue
            val type = when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> Type.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> Type.PAUSED_OR_STOPPED
                else -> continue
            }
            events.add(Event(pkg, type, ev.timeStamp))
        }
        return reduceForegroundMs(events, endMs)
    }
}
