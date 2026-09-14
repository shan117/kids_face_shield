package com.shantanu.shield.ui.paywall

/**
 * Pure formatting of ISO-8601 subscription billing periods (e.g. "P1M", "P1Y", "P30D") into human
 * labels for the paywall. Dependency-free so it can be unit-tested without the Billing SDK.
 */
object SubscriptionFormat {

    private fun isYearly(period: String) = period.contains("Y") || period == "P12M"

    /** "Annual" / "Monthly" / "Weekly" from the recurring billing period. */
    fun planTitle(period: String): String = when {
        isYearly(period) -> "Annual"
        period.contains("W") -> "Weekly"
        period.contains("M") -> "Monthly"
        else -> period
    }

    /** "/year" / "/month" / "/week" suffix shown next to the price. */
    fun periodSuffix(period: String): String = when {
        isYearly(period) -> "/year"
        period.contains("W") -> "/week"
        period.contains("M") -> "/month"
        else -> ""
    }

    /** "30-day free trial" from a trial phase period like "P30D" / "P1W" / "P1M". */
    fun trialLabel(period: String): String {
        val n = period.filter { it.isDigit() }.toIntOrNull() ?: return "Free trial"
        val unit = when {
            period.contains("D") -> "day"
            period.contains("W") -> "week"
            period.contains("Y") -> "year"
            period.contains("M") -> "month"
            else -> "day"
        }
        return "$n-$unit free trial"
    }
}
