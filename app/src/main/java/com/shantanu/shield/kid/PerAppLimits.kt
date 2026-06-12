package com.shantanu.shield.kid

/**
 * Pure decision for premium "Per-app limits": is a specific app past its own daily limit? Independent
 * of the overall budget. [usageMs] is per-package foreground ms today (from the budget poll), [limits]
 * is package -> minutes. Unit-testable.
 */
object PerAppLimits {
    fun isOver(pkg: String, usageMs: Map<String, Long>, limits: Map<String, Int>): Boolean {
        val limit = limits[pkg] ?: return false
        val usedMin = ((usageMs[pkg] ?: 0L) / 60_000L).toInt()
        return usedMin >= limit
    }
}
