package com.shantanu.shield.ui.stats

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private fun isUsageAccessGranted(context: android.content.Context): Boolean {
    val appOps = context.getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
    val mode = appOps.checkOpNoThrow(
        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )
    return mode == android.app.AppOpsManager.MODE_ALLOWED
}

@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val snapshot by viewModel.snapshot.collectAsState()
    val mode by viewModel.viewMode.collectAsState()
    var usageGranted by remember { mutableStateOf(isUsageAccessGranted(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageGranted = isUsageAccessGranted(context)
                if (usageGranted) viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!usageGranted) {
        StatsEmptyState(
            icon = Icons.Default.Warning,
            title = "Usage Access required",
            body = "Grant Usage Access permission to see screen time, Free Play usage, and insights.",
            actionLabel = "Open Settings",
            onAction = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Switcher is shown but the non-owner tab is disabled. Owner is driven by ownerType.
        val segColors = SegmentedButtonDefaults.colors(
            disabledInactiveContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            disabledInactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            disabledInactiveBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                SegmentedButton(
                    selected = mode == StatsViewMode.PARENT,
                    onClick = {},
                    enabled = mode == StatsViewMode.PARENT,
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    colors = segColors,
                    icon = {
                        if (mode == StatsViewMode.PARENT) {
                            SegmentedButtonDefaults.Icon(active = true)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Disabled",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    },
                    label = { Text("Parent") }
                )
                SegmentedButton(
                    selected = mode == StatsViewMode.KID,
                    onClick = {},
                    enabled = mode == StatsViewMode.KID,
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    colors = segColors,
                    icon = {
                        if (mode == StatsViewMode.KID) {
                            SegmentedButtonDefaults.Icon(active = true)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Disabled",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    },
                    label = { Text("Kid") }
                )
            }
        }

        if (snapshot.loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        when (mode) {
            StatsViewMode.PARENT -> ParentDashboard(snapshot)
            StatsViewMode.KID -> KidDashboard(snapshot)
        }
    }
}

@Composable
private fun ParentDashboard(snap: StatsSnapshot) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Hero — today
        item {
            val pct = if (snap.totalYesterdayMs > 0) ((snap.totalTodayMs - snap.totalYesterdayMs) * 100 / snap.totalYesterdayMs).toInt() else null
            val deltaText = pct?.let { "${if (it >= 0) "↑" else "↓"} ${kotlin.math.abs(it)}% vs yesterday" }
            HeroMetricCard(
                label = "Today's screen time",
                value = formatHm(snap.totalTodayMs),
                delta = deltaText,
                deltaPositive = (pct ?: 0) > 0
            )
        }

        // Free Play today section
        item {
            StatsSectionLabel("Free Play today")
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    if (snap.freePlayGrantedMsToday == 0L) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "No Free Play sessions today",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Donut(
                                slices = freePlaySlices(snap.freePlayApps),
                                centerLabel = formatHm(snap.freePlayUsedMsToday),
                                centerSubLabel = "of ${formatHm(snap.freePlayGrantedMsToday)}",
                                modifier = Modifier.size(150.dp)
                            )
                            Spacer(Modifier.width(20.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                LegendList(snap.freePlayApps.take(4))
                            }
                        }
                        if (snap.freePlayApps.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            val total = snap.freePlayApps.sumOf { it.foregroundMs }
                            snap.freePlayApps.take(5).forEach { bucket ->
                                AppBarRow(bucket = bucket, maxMs = total, barColor = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                }
            }
        }

        // Your screen time today
        item {
            StatsSectionLabel("Your screen time today")
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    val parentOnly = snap.parentTopApps
                    if (parentOnly.isEmpty()) {
                        Text(
                            "No app activity yet today.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Donut(
                                slices = parentSlices(parentOnly),
                                centerLabel = formatHm(snap.totalTodayMs),
                                centerSubLabel = "today",
                                modifier = Modifier.size(150.dp)
                            )
                            Spacer(Modifier.width(20.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                LegendList(parentOnly.take(4))
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        val total = parentOnly.sumOf { it.foregroundMs }
                        parentOnly.take(5).forEach { bucket ->
                            AppBarRow(bucket = bucket, maxMs = total)
                        }
                    }
                }
            }
        }

        // 7-day trend
        item {
            StatsSectionLabel("This week")
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    SparkBars(week = snap.week)
                    if (snap.week.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        val avg = snap.week.sumOf { it.totalMs } / snap.week.size.coerceAtLeast(1)
                        DailyAverageLine(avgMs = avg)
                    }
                }
            }
        }

        // Trends — 4-week + month-to-date
        item { StatsSectionLabel("Trends") }
        item {
            WeeklyTrendCard(
                weeklyAverages = snap.weeklyDailyAverages,
                monthToDateAvgMs = snap.monthToDateAvgMs,
                trendDeltaPct = snap.trendDeltaPct
            )
        }

        // Insights
        if (snap.insights.isNotEmpty()) {
            item { StatsSectionLabel("Insights") }
            items(snap)
        }
    }
}

@Composable
private fun KidDashboard(snap: StatsSnapshot) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Budget ring
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Today's budget",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    BudgetRing(usedMs = snap.budgetUsedMs, totalMs = snap.budgetMs)
                }
            }
        }

        // Top apps today
        item { StatsSectionLabel("Where the time went today") }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    val apps = snap.kidTopApps
                    if (apps.isEmpty()) {
                        Text(
                            "No app activity yet today.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val total = apps.sumOf { it.foregroundMs }
                        apps.take(6).forEach { bucket ->
                            AppBarRow(bucket = bucket, maxMs = total)
                        }
                    }
                }
            }
        }

        // 7-day trend with budget threshold
        item { StatsSectionLabel("This week") }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    SparkBars(week = snap.week, budgetMs = snap.budgetMs)
                    if (snap.week.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        val avg = snap.week.sumOf { it.totalMs } / snap.week.size.coerceAtLeast(1)
                        DailyAverageLine(avgMs = avg)
                        Spacer(Modifier.height(6.dp))
                        val hit = snap.week.count { snap.budgetMs > 0 && it.totalMs >= snap.budgetMs }
                        Text(
                            "Budget hit on $hit of ${snap.week.size} days",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item { StatsSectionLabel("Trends") }
        item {
            WeeklyTrendCard(
                weeklyAverages = snap.weeklyDailyAverages,
                monthToDateAvgMs = snap.monthToDateAvgMs,
                trendDeltaPct = snap.trendDeltaPct
            )
        }

        if (snap.insights.isNotEmpty()) {
            item { StatsSectionLabel("Insights") }
            items(snap)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.items(snap: StatsSnapshot) {
    snap.insights.forEach { line ->
        item { InsightCard(text = line) }
    }
}

@Composable
private fun LegendList(apps: List<AppUsageBucket>) {
    Column {
        apps.forEachIndexed { i, app ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(legendColor(i))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    app.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatHm(app.foregroundMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun parentSlices(apps: List<AppUsageBucket>): List<DonutSlice> {
    val top = apps.take(4)
    val others = apps.drop(4).sumOf { it.foregroundMs }
    val out = top.mapIndexed { i, b ->
        DonutSlice(b.name, b.foregroundMs.toFloat(), legendColor(i))
    }.toMutableList()
    if (others > 0) {
        out += DonutSlice("Others", others.toFloat(), MaterialTheme.colorScheme.outline)
    }
    return out
}

@Composable
private fun freePlaySlices(apps: List<AppUsageBucket>): List<DonutSlice> {
    val top = apps.take(4)
    val others = apps.drop(4).sumOf { it.foregroundMs }
    val out = top.mapIndexed { i, b ->
        DonutSlice(b.name, b.foregroundMs.toFloat(), legendColor(i))
    }.toMutableList()
    if (others > 0) {
        out += DonutSlice("Others", others.toFloat(), MaterialTheme.colorScheme.outline)
    }
    return out
}

@Composable
private fun legendColor(i: Int): Color = when (i % 5) {
    0 -> MaterialTheme.colorScheme.primary
    1 -> MaterialTheme.colorScheme.tertiary
    2 -> MaterialTheme.colorScheme.secondary
    3 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun StatsEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(icon, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = onAction, shape = RoundedCornerShape(14.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}
