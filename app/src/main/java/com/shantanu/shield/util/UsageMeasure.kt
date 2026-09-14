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

    // SCREEN_OFF is a device-level terminator (screen non-interactive / keyguard / shutdown). On
    // screen-off, no app is foreground, yet some apps never emit a per-app PAUSED/STOPPED then — so
    // without this their RESUMED session would run to the window end and wildly inflate the total
    // (e.g. the Clock showing ~2h because it was foreground when the screen went off).
    enum class Type { RESUMED, PAUSED_OR_STOPPED, SCREEN_OFF }

    data class Event(val pkg: String, val type: Type, val timeStamp: Long)

    /** Controlled-app session count (pickups) + longest single session, for the richer Remote Report. */
    data class SessionStats(val count: Int, val longestMs: Long)

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
                Type.SCREEN_OFF -> {
                    // Close EVERY open session at the screen-off timestamp — nothing is foreground
                    // once the screen is off, so no session may keep accruing past this point.
                    val it = sessionStart.entries.iterator()
                    while (it.hasNext()) {
                        val (pkg, start) = it.next()
                        totals[pkg] = (totals[pkg] ?: 0L) + (e.timeStamp - start).coerceAtLeast(0L)
                        it.remove()
                    }
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
    fun foregroundMsByPackage(usm: UsageStatsManager, startMs: Long, endMs: Long): Map<String, Long> =
        reduceForegroundMs(eventsIn(usm, startMs, endMs), endMs)

    /** Android adapter: pull the in-order Event stream for a window. Shared by the reducer above and the
     *  richer-report aggregations (hourly buckets + session stats) below. */
    fun eventsIn(usm: UsageStatsManager, startMs: Long, endMs: Long): List<Event> {
        val raw = usm.queryEvents(startMs, endMs)
        val events = ArrayList<Event>()
        val ev = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(ev)
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    ev.packageName?.let { events.add(Event(it, Type.RESUMED, ev.timeStamp)) }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    ev.packageName?.let { events.add(Event(it, Type.PAUSED_OR_STOPPED, ev.timeStamp)) }
                // Device-level terminators: screen off / locked / shutting down → close all sessions.
                // (Constants exist since API 28/30; harmlessly never emitted on older devices.)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN ->
                    events.add(Event("", Type.SCREEN_OFF, ev.timeStamp))
                else -> {}
            }
        }
        return events
    }

    /**
     * Pure: foreground ms bucketed by hour-of-day (0..23), summed across all sessions that pass [include].
     * Sessions are split at hour boundaries; SCREEN_OFF closes open sessions. Drives the report's
     * time-of-day pattern + night-usage view. No package is foreground after screen-off, so nothing leaks.
     */
    fun hourlyForegroundMs(events: List<Event>, windowEndMs: Long, include: (String) -> Boolean): LongArray {
        val buckets = LongArray(24)
        val sessionStart = HashMap<String, Long>()
        for (e in events) {
            when (e.type) {
                Type.RESUMED -> if (include(e.pkg)) sessionStart[e.pkg] = e.timeStamp
                Type.PAUSED_OR_STOPPED -> sessionStart.remove(e.pkg)?.let { addHourly(buckets, it, e.timeStamp) }
                Type.SCREEN_OFF -> {
                    val it = sessionStart.entries.iterator()
                    while (it.hasNext()) { val (_, st) = it.next(); addHourly(buckets, st, e.timeStamp); it.remove() }
                }
            }
        }
        for ((_, st) in sessionStart) addHourly(buckets, st, windowEndMs)
        return buckets
    }

    private fun addHourly(buckets: LongArray, start: Long, end: Long) {
        if (end <= start) return
        val cal = java.util.Calendar.getInstance()
        var s = start
        while (s < end) {
            cal.timeInMillis = s
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            cal.add(java.util.Calendar.HOUR_OF_DAY, 1)
            cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            val segEnd = minOf(end, cal.timeInMillis)
            buckets[hour] += (segEnd - s)
            s = segEnd
        }
    }

    /** Pure: controlled-app session count (pickups) + longest single session over the window. */
    fun sessionStats(events: List<Event>, windowEndMs: Long, include: (String) -> Boolean): SessionStats {
        var count = 0
        var longest = 0L
        val sessionStart = HashMap<String, Long>()
        fun close(st: Long, end: Long) {
            val d = (end - st).coerceAtLeast(0L)
            if (d > 0L) { count++; if (d > longest) longest = d }
        }
        for (e in events) {
            when (e.type) {
                Type.RESUMED -> if (include(e.pkg)) sessionStart[e.pkg] = e.timeStamp
                Type.PAUSED_OR_STOPPED -> sessionStart.remove(e.pkg)?.let { close(it, e.timeStamp) }
                Type.SCREEN_OFF -> {
                    val it = sessionStart.entries.iterator()
                    while (it.hasNext()) { val (_, st) = it.next(); close(st, e.timeStamp); it.remove() }
                }
            }
        }
        for ((_, st) in sessionStart) close(st, windowEndMs)
        return SessionStats(count, longest)
    }
}
