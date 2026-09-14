package com.shantanu.shield.ui.stats

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import com.shantanu.shield.ui.coachTarget
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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

    // BASIC_STATS gate: if it's been converted to premium and the user isn't entitled, the whole
    // dashboard is locked behind a Plus prompt. Default-free, so normally this never triggers.
    val basicStatsUnlocked by viewModel.basicStatsUnlocked.collectAsState()
    if (!basicStatsUnlocked) {
        val openPaywall = com.shantanu.shield.ui.LocalRequestPaywall.current
        StatsEmptyState(
            icon = Icons.Default.Lock,
            title = "Stats is a Plus feature",
            body = "Upgrade to Plus to see screen time, trends, and insights.",
            actionLabel = "Unlock with Plus",
            onAction = openPaywall
        )
        return
    }

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

    // Multiple-kids mode replaces the Parent/Kid switcher with a per-kid dashboard — but only once
    // it's actually active (both kids enrolled). Otherwise the existing dashboard below is untouched.
    val multiKid by viewModel.multiKidActive.collectAsState(initial = false)
    if (multiKid) {
        MultiKidDashboard(viewModel)
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
            StatsViewMode.PARENT -> {
                val excluded by viewModel.excludedApps.collectAsState()
                ParentDashboard(
                    snap = snapshot,
                    excludedApps = excluded,
                    onSetExcluded = viewModel::setExcluded
                )
            }
            StatsViewMode.KID -> KidDashboard(snapshot)
        }
    }
}

@Composable
private fun MultiKidDashboard(viewModel: StatsViewModel) {
    val profiles by viewModel.kidProfiles.collectAsState(initial = emptyList())
    val profileSnap by viewModel.profileSnapshot.collectAsState()
    val family by viewModel.family.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }

    // Build the Family comparison on first entry; the per-kid drill-down loads on tap.
    LaunchedEffect(Unit) { viewModel.loadFamilyComparison() }

    val select: (String?) -> Unit = { id ->
        selectedId = id
        if (id != null) viewModel.loadProfileSnapshot(id) else viewModel.loadFamilyComparison()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = selectedId == null, onClick = { select(null) }, label = { Text("Family") })
            profiles.forEach { p ->
                FilterChip(
                    selected = selectedId == p.id,
                    onClick = { select(p.id) },
                    label = { Text(p.name) }
                )
            }
        }
        val sel = selectedId
        if (sel == null) {
            FamilyComparisonView(family, onSelect = { select(it) })
        } else {
            val profile = profiles.firstOrNull { it.id == sel }
            if (profile == null || profileSnap.loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // The same rich dashboard the single-kid device shows — budget ring, top apps,
                // 7-day chart, 4-week trends, monthly average, insights — scoped to this kid.
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp)
                    )
                    KidDashboard(profileSnap)
                }
            }
        }
    }
}

@Composable
private fun FamilyComparisonView(family: FamilyComparison, onSelect: (String) -> Unit) {
    if (family.loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val kids = family.kids
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item { StatsSectionLabel("Today") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                kids.forEach { k -> FamilyKidCard(k, onClick = { onSelect(k.id) }) }
            }
        }

        // Cross-kid comparisons need exactly two kids (multi-kid is capped at 2).
        if (kids.size >= 2) {
            val k1 = kids[0]
            val k2 = kids[1]
            item { StatsSectionLabel("This week") }
            item { WeekCompareCard(k1, k2) }
            item { StatsSectionLabel("Week at a glance") }
            item { GlanceCompareCard(k1, k2) }
            item { StatsSectionLabel("Top app today") }
            item { TopAppCompareCard(kids) }
        }
    }
}

@Composable
private fun FamilyKidCard(k: KidWeek, onClick: () -> Unit) {
    val usedMin = (k.usedTodayMs / 60_000L).toInt()
    val over = usedMin >= k.limitMin
    val progress = if (k.limitMin > 0) (usedMin.toFloat() / k.limitMin).coerceIn(0f, 1f) else 0f
    val kidColor = Color(k.color)
    val valueColor = if (over) MaterialTheme.colorScheme.error else kidColor
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KidDot(kidColor)
                Spacer(Modifier.width(10.dp))
                Text(k.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "$usedMin / ${k.limitMin} min",
                    color = valueColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = valueColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
            Spacer(Modifier.height(6.dp))
            val pct = if (k.limitMin > 0) (usedMin * 100 / k.limitMin) else 0
            Text(
                if (over) "Over budget" else "$pct% of budget used",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WeekCompareCard(k1: KidWeek, k2: KidWeek) {
    val c1 = Color(k1.color)
    val c2 = Color(k2.color)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Screen time split", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            ShareSplitBar(
                listOf(
                    SplitSegment(k1.name, k1.weekTotalMs, c1),
                    SplitSegment(k2.name, k2.weekTotalMs, c2)
                )
            )
            Spacer(Modifier.height(20.dp))
            Text("Daily totals", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            TwoKidWeekBars(k1.week, k2.week, c1, c2)
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(c1, "${k1.name} · ${formatHm(k1.weekTotalMs)}")
                LegendDot(c2, "${k2.name} · ${formatHm(k2.weekTotalMs)}")
            }
        }
    }
}

@Composable
private fun GlanceCompareCard(k1: KidWeek, k2: KidWeek) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1.2f))
                CompareHeaderCell(k1)
                CompareHeaderCell(k2)
            }
            Spacer(Modifier.height(12.dp))
            CompareRow("Daily average", formatHm(k1.dailyAvgMs), formatHm(k2.dailyAvgMs))
            CompareRow("Budget hit", "${k1.budgetHitDays}/${k1.week.size}", "${k2.budgetHitDays}/${k2.week.size}")
            CompareRow("Busiest day", k1.busiestDayMs.weekdayOrDash(), k2.busiestDayMs.weekdayOrDash())
        }
    }
}

@Composable
private fun RowScope.CompareHeaderCell(k: KidWeek) {
    Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        KidDot(Color(k.color), size = 8.dp)
        Spacer(Modifier.width(6.dp))
        Text(k.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun CompareRow(label: String, v1: String, v2: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.2f))
        Text(v1, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(v2, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TopAppCompareCard(kids: List<KidWeek>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            kids.forEach { k ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KidDot(Color(k.color))
                        Spacer(Modifier.width(10.dp))
                        Text(k.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(8.dp))
                    val app = k.topAppToday
                    if (app == null) {
                        Text(
                            "No activity yet today.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 22.dp)
                        )
                    } else {
                        TopAppLine(app, modifier = Modifier.padding(start = 22.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        KidDot(color)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun KidDot(color: Color, size: androidx.compose.ui.unit.Dp = 12.dp) {
    Box(modifier = Modifier.size(size).clip(CircleShape).background(color))
}

private fun Long?.weekdayOrDash(): String {
    val ms = this ?: return "—"
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
        java.util.Calendar.MONDAY -> "Mon"
        java.util.Calendar.TUESDAY -> "Tue"
        java.util.Calendar.WEDNESDAY -> "Wed"
        java.util.Calendar.THURSDAY -> "Thu"
        java.util.Calendar.FRIDAY -> "Fri"
        java.util.Calendar.SATURDAY -> "Sat"
        else -> "Sun"
    }
}

@Composable
private fun ParentDashboard(
    snap: StatsSnapshot,
    excludedApps: List<Pair<String, String>> = emptyList(),
    onSetExcluded: (String, Boolean) -> Unit = { _, _ -> }
) {
    // Long-press target for the "not screen time" override. Parent dashboard only — this composable
    // is never rendered on a kid-owned device (the view mode follows ownerType).
    var pendingExclude by remember { mutableStateOf<AppUsageBucket?>(null) }
    pendingExclude?.let { bucket ->
        AlertDialog(
            onDismissRequest = { pendingExclude = null },
            title = { Text("Not screen time?") },
            text = {
                // The consequence list is deliberately blunt: excluding an app also removes it from
                // enforcement, so a parent must not be able to open a loophole without reading it.
                Text(
                    "${bucket.name} will stop counting as screen time:\n\n" +
                        "•  Hidden from these charts and the weekly report\n" +
                        "•  Stops using up the daily budget\n" +
                        "•  No longer locked — not at night, not when the budget runs out, " +
                        "not by a per-app limit\n\n" +
                        "You can still face-lock it from Protect. Undo this at the bottom of " +
                        "this screen."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetExcluded(bucket.packageName, true)
                    pendingExclude = null
                }) { Text("Don't count it") }
            },
            dismissButton = {
                TextButton(onClick = { pendingExclude = null }) { Text("Cancel") }
            }
        )
    }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .coachTarget("stats-app-breakdown"),
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
                            AppBarRow(
                                bucket = bucket,
                                maxMs = total,
                                onLongPress = { pendingExclude = bucket }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Not screen time? Tap ⋮ next to an app to stop counting it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

        // Parent's "not screen time" overrides — the undo surface for the long-press above, and the
        // fallback for any OEM utility (wallpaper carousel, clock, …) our rules didn't catch.
        if (excludedApps.isNotEmpty()) {
            item { StatsSectionLabel("Not counted as screen time") }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "Hidden from the charts and the weekly report. These don't use up the " +
                                "daily budget — and aren't locked at night or over budget.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        excludedApps.forEach { (pkg, label) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { onSetExcluded(pkg, false) }) { Text("Count it") }
                            }
                        }
                    }
                }
            }
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
                    // Ring fills against the EFFECTIVE limit (budget + today's extension), so
                    // "over" matches the actual lock threshold and the Kid Mode tab card. The
                    // pill below names the extension that grew the denominator.
                    BudgetRing(
                        usedMs = snap.budgetUsedMs,
                        totalMs = snap.budgetMs + snap.extensionsTodayMs
                    )
                    val extMin = (snap.extensionsTodayMs / 60_000L).toInt()
                    if (extMin > 0) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                "Budget extended +$extMin min today",
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
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
