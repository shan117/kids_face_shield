package com.shantanu.shield.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Hero metric. Big number + optional delta vs yesterday. */
@Composable
fun HeroMetricCard(
    label: String,
    value: String,
    delta: String? = null,
    deltaPositive: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (delta != null) {
                    Spacer(Modifier.width(12.dp))
                    val pillColor = if (deltaPositive)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.tertiaryContainer
                    val pillText = if (deltaPositive)
                        MaterialTheme.colorScheme.onErrorContainer
                    else
                        MaterialTheme.colorScheme.onTertiaryContainer
                    Box(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .background(pillColor, shape = RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            delta,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = pillText
                        )
                    }
                }
            }
        }
    }
}

/** Top-N app list row: icon + name + horizontal bar + minutes + percentage of total. */
@Composable
fun AppBarRow(
    bucket: AppUsageBucket,
    maxMs: Long,
    barColor: Color = MaterialTheme.colorScheme.primary
) {
    val fraction = if (maxMs <= 0) 0f else (bucket.foregroundMs.toFloat() / maxMs).coerceIn(0f, 1f)
    val pct = (fraction * 100).toInt()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = bucket.icon
        if (icon != null) {
            Image(
                bitmap = icon.toBitmap(width = 80, height = 80).asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Star, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                bucket.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(barColor)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = buildAnnotatedString {
                append(formatHm(bucket.foregroundMs))
                append(" ")
                withStyle(
                    SpanStyle(
                        baselineShift = BaselineShift.Superscript,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    append("($pct%)")
                }
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Donut chart with the total label rendered inside. */
@Composable
fun Donut(
    slices: List<DonutSlice>,
    centerLabel: String,
    centerSubLabel: String? = null,
    modifier: Modifier = Modifier.size(160.dp)
) {
    val total = slices.sumOf { it.value.toDouble() }.coerceAtLeast(0.0001)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            val stroke = 22f * density
            val pad = stroke / 2f
            val rect = Rect(
                Offset(pad, pad),
                Size(size.width - stroke, size.height - stroke)
            )
            // Background ring
            drawArc(
                color = Color(0xFFE0E0E0),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = rect.topLeft,
                size = rect.size,
                style = Stroke(width = stroke)
            )
            var start = -90f
            for (slice in slices) {
                val sweep = (slice.value / total * 360.0).toFloat()
                drawArc(
                    color = slice.color,
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    style = Stroke(width = stroke)
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                centerLabel,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (centerSubLabel != null) {
                Text(
                    centerSubLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

data class DonutSlice(val label: String, val value: Float, val color: Color)

/** Budget ring (donut variant). Used vs total; turns errorContainer color when over. */
@Composable
fun BudgetRing(
    usedMs: Long,
    totalMs: Long,
    modifier: Modifier = Modifier.size(200.dp)
) {
    val pct = if (totalMs <= 0) 0f else (usedMs.toFloat() / totalMs).coerceAtLeast(0f)
    val over = pct >= 1f
    val ringColor = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val bgColor = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            val stroke = 26f * density
            val pad = stroke / 2f
            val rect = Rect(
                Offset(pad, pad),
                Size(size.width - stroke, size.height - stroke)
            )
            drawArc(
                color = bgColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = rect.topLeft,
                size = rect.size,
                style = Stroke(width = stroke)
            )
            val sweep = (pct.coerceAtMost(1f) * 360f)
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = rect.topLeft,
                size = rect.size,
                style = Stroke(width = stroke)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                formatHm(usedMs),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(
                "today",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (totalMs > 0) {
                Spacer(Modifier.height(6.dp))
                val statusText = if (over) {
                    "Over by ${formatHm(usedMs - totalMs)}"
                } else {
                    "${formatHm(totalMs - usedMs)} left of ${formatHm(totalMs)}"
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** 7-day vertical bars. Each day = total time. Optional dashed line = budget. */
@Composable
fun SparkBars(
    week: List<DayBucket>,
    budgetMs: Long = 0,
    barColor: Color = MaterialTheme.colorScheme.primary
) {
    if (week.isEmpty()) return
    val max = (week.maxOf { it.totalMs }).coerceAtLeast(budgetMs).coerceAtLeast(1L)
    val labels = listOf("M", "T", "W", "T", "F", "S", "S")
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            week.forEach { day ->
                val h = (day.totalMs.toFloat() / max).coerceIn(0f, 1f) * 100f
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .height(h.dp.coerceAtLeast(2.dp))
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(barColor)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            week.forEachIndexed { i, _ ->
                Text(
                    text = dayLabel(week.size, i),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun dayLabel(total: Int, idx: Int): String {
    val cal = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, -(total - 1 - idx))
    }
    val day = cal.get(java.util.Calendar.DAY_OF_WEEK)
    return when (day) {
        java.util.Calendar.MONDAY -> "M"
        java.util.Calendar.TUESDAY -> "T"
        java.util.Calendar.WEDNESDAY -> "W"
        java.util.Calendar.THURSDAY -> "T"
        java.util.Calendar.FRIDAY -> "F"
        java.util.Calendar.SATURDAY -> "S"
        else -> "S"
    }
}

/** Plain-language insight card. */
@Composable
fun InsightCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Section label used throughout dashboards. */
@Composable
fun StatsSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

enum class UsageLevel { LOW, MEDIUM, HIGH }

/** Bucket a daily-average duration into a "good / medium / high" band. Uses absolute
 * thresholds so the tone stays consistent across Parent and Kid views — what feels
 * low for a parent is still low for a kid; what feels high is still high. */
fun classifyDailyAverage(ms: Long): UsageLevel {
    val hours = ms / (60_000.0 * 60)
    return when {
        hours < 2.0 -> UsageLevel.LOW
        hours < 4.0 -> UsageLevel.MEDIUM
        else -> UsageLevel.HIGH
    }
}

@Composable
fun usageLevelColor(level: UsageLevel): Color = when (level) {
    UsageLevel.LOW -> MaterialTheme.colorScheme.tertiary
    UsageLevel.MEDIUM -> MaterialTheme.colorScheme.primary
    UsageLevel.HIGH -> MaterialTheme.colorScheme.error
}

fun usageLevelLabel(level: UsageLevel): String = when (level) {
    UsageLevel.LOW -> "low"
    UsageLevel.MEDIUM -> "medium"
    UsageLevel.HIGH -> "high"
}

/** Trend section: 4-week daily averages + monthly average. */
@Composable
fun WeeklyTrendCard(
    weeklyAverages: List<Long>,
    monthToDateAvgMs: Long,
    trendDeltaPct: Int?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Last 4 weeks",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (weeklyAverages.isEmpty() || weeklyAverages.all { it == 0L }) {
                TrendInfoCallout("Use the app for 4–5 weeks to see a meaningful trend.")
            } else {
                FourWeekBars(weeklyAverages)
                Spacer(Modifier.height(12.dp))
                TrendPill(trendDeltaPct)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Monthly average so far",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            if (monthToDateAvgMs > 0) {
                DailyAverageLine(avgMs = monthToDateAvgMs, labelOverride = "Avg per day:")
            } else {
                Text(
                    "No data yet this month.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun FourWeekBars(weekly: List<Long>) {
    val max = weekly.max().coerceAtLeast(1L)
    val labels = buildList {
        val n = weekly.size
        for (i in 0 until n) {
            add(if (i == n - 1) "Now" else "W-${n - 1 - i}")
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(110.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        weekly.forEachIndexed { i, avg ->
            val level = classifyDailyAverage(avg)
            val color = usageLevelColor(level)
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    formatHm(avg),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                val barHeight = ((avg.toFloat() / max) * 70f).coerceAtLeast(4f)
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(barHeight.dp)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(color)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    labels[i],
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TrendInfoCallout(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TrendPill(deltaPct: Int?) {
    if (deltaPct == null) {
        TrendInfoCallout("Use the app for 4–5 weeks to see a meaningful trend.")
        return
    }
    val abs = kotlin.math.abs(deltaPct)
    val text: String
    val color: Color
    when {
        abs < 5 -> {
            text = "→ Steady vs 4 weeks ago"
            color = MaterialTheme.colorScheme.primary
        }
        deltaPct < 0 -> {
            text = "↓ Improving $abs% vs 4 weeks ago"
            color = MaterialTheme.colorScheme.tertiary
        }
        else -> {
            text = "↑ Rising $abs% vs 4 weeks ago"
            color = MaterialTheme.colorScheme.error
        }
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

/** Pill-styled daily average that colors itself by [classifyDailyAverage]. */
@Composable
fun DailyAverageLine(avgMs: Long, labelOverride: String? = null) {
    val level = classifyDailyAverage(avgMs)
    val color = usageLevelColor(level)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            labelOverride ?: "Daily average:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .background(color.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatHm(avgMs),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "· ${usageLevelLabel(level)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = color
                )
            }
        }
    }
}

