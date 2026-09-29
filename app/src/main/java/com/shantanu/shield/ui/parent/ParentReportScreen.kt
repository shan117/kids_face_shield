package com.shantanu.shield.ui.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.shantanu.shield.remote.AppEntry
import com.shantanu.shield.remote.KidReport
import com.shantanu.shield.remote.RemoteCommandSender
import com.shantanu.shield.ui.stats.AppUsageBucket
import com.shantanu.shield.ui.stats.DailyAverageLine
import com.shantanu.shield.ui.stats.DayBucket
import com.shantanu.shield.ui.stats.formatHm
import com.shantanu.shield.ui.stats.SparkBars
import com.shantanu.shield.ui.stats.TopAppLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the decrypted child report on the parent's phone, reusing the on-device Stats components so the
 * remote view matches the local one. Stateful via [ParentReportViewModel]; this composable is pure render.
 */
@Composable
fun ParentReportPane(
    onUnpair: () -> Unit,
    onAddDevice: () -> Unit = {},
    onRescanDevice: (com.shantanu.shield.remote.PairedDevice) -> Unit = {},
    viewModel: ParentReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val controlUnlocked by viewModel.controlUnlocked.collectAsState()
    val commandStatus by viewModel.commandStatus.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val activeDevice by viewModel.activeDevice.collectAsState()

    // Snackbar on every remote action (fires per send, repeats included).
    val snackbar = com.shantanu.shield.LocalSnackbarHostState.current
    LaunchedEffect(Unit) {
        viewModel.commandEvent.collect { snackbar.showSnackbar(controlMessage(it)) }
    }

    // Renaming matters as soon as there are two children: "Child device 2" tells a parent nothing about
    // which phone they are about to lock.
    var renaming by remember { mutableStateOf<com.shantanu.shield.remote.PairedDevice?>(null) }
    renaming?.let { device ->
        var draft by remember(device.pairingId) { mutableStateOf(device.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name this device") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(30) },
                    singleLine = true,
                    label = { Text("Whose phone is this?") },
                    placeholder = { Text("e.g. Aarav's phone") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        viewModel.renameDevice(device.pairingId, draft)
                        renaming = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        // Device switcher. Hidden at one device so the single-child experience is untouched.
        if (devices.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                devices.forEach { device ->
                    FilterChip(
                        selected = device.pairingId == activeDevice?.pairingId,
                        onClick = { viewModel.selectDevice(device.pairingId) },
                        label = { Text(device.label, maxLines = 1) },
                    )
                }
            }
        }
        when (val s = state) {
            is ParentReportViewModel.State.Loading ->
                CenteredMessage { CircularProgressIndicator() }

            is ParentReportViewModel.State.Loaded ->
                LoadedReport(s.payload.generatedAtMs, s.payload.kids, onRefresh = viewModel::refresh)

            is ParentReportViewModel.State.Empty ->
                StatusBlock(
                    title = "Paired ✓",
                    body = "No report yet. It appears here once the child device turns on sharing and the " +
                        "first weekly report syncs.",
                    onRefresh = viewModel::refresh,
                )

            is ParentReportViewModel.State.Revoked -> {
                // The definitive case: the child told us it rotated. Not an error, not empty — the link
                // is simply dead, and the only fix is to scan the new code.
                val stale = activeDevice
                StatusBlock(
                    title = "${stale?.label ?: "This device"} needs reconnecting",
                    body = "This child's phone generated a new pairing code, so the old link no longer " +
                        "works. Scan the new code shown on their phone to start seeing reports again.",
                    onRefresh = viewModel::refresh,
                ) {
                    if (stale != null) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { onRescanDevice(stale) }) {
                            Text("Re-scan ${stale.label}'s code")
                        }
                    }
                }
            }

            is ParentReportViewModel.State.Error -> {
                // Name the child in the message. With several linked, "couldn't read the report" alone
                // doesn't say WHOSE report broke — and the fix (re-scan that child's new code) needs the
                // parent to know which phone to pick up (plan trap F).
                val broken = activeDevice
                StatusBlock(
                    title = "Couldn't read ${broken?.label ?: "the report"}",
                    body = "This data can't be unlocked with the code stored on this phone. That usually " +
                        "means the child's device generated a new code — scan it again to reconnect.",
                    onRefresh = viewModel::refresh,
                ) {
                    if (broken != null) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { onRescanDevice(broken) }) {
                            Text("Re-scan ${broken.label}'s code")
                        }
                    }
                }
            }

            is ParentReportViewModel.State.NotPaired ->
                StatusBlock(
                    title = "Not paired",
                    body = "Scan the child device's QR to start viewing their weekly report.",
                    onRefresh = viewModel::refresh,
                )
        }

        // Current child config (from the last decrypted report) so the parent edits real values, not blind.
        val currentKid = (state as? ParentReportViewModel.State.Loaded)?.payload?.kids?.firstOrNull()

        // Grant/limit button feedback: tapped button turns YELLOW (sent, waiting) then GREEN once the next
        // synced report shows the child actually applied it. Child confirms automatically — no tap on its end.
        //
        // Keyed on the active pairing id: this state is a claim about ONE child ("you just set 90 min on
        // Aarav's phone"). Carrying it across a device switch would show a green "applied" tick against a
        // sibling who was never sent anything (MULTI_DEVICE_PAIRING_PLAN.md trap B).
        val activeId = activeDevice?.pairingId
        var pendingLimit by remember(activeId) { mutableStateOf<Int?>(null) }   // last limit minutes tapped
        var pendingGrant by remember(activeId) { mutableStateOf<Int?>(null) }   // last grant minutes tapped
        var grantTarget by remember(activeId) { mutableStateOf<Int?>(null) }    // target today's extension minutes
        val limitConfirmed = pendingLimit != null && currentKid?.limitMin == pendingLimit
        val grantConfirmed = grantTarget != null && (currentKid?.extensionsMin ?: -1) >= grantTarget!!

        Spacer(Modifier.height(20.dp))
        RemoteControls(
            unlocked = controlUnlocked,
            status = commandStatus,
            currentLimit = currentKid?.limitMin ?: -1,
            currentPreset = currentKid?.allowedPreset ?: -1,
            currentAutoBlock = currentKid?.autoBlockNewApps ?: -1,
            pendingLimit = pendingLimit,
            limitConfirmed = limitConfirmed,
            pendingGrant = pendingGrant,
            grantConfirmed = grantConfirmed,
            onLock = viewModel::lockNow,
            onUnlock = viewModel::unlock,
            onGrant = { m -> pendingGrant = m; grantTarget = (currentKid?.extensionsMin?.coerceAtLeast(0) ?: 0) + m; viewModel.grantExtraTime(m) },
            onSetLimit = { m -> pendingLimit = m; viewModel.setDailyLimit(m) },
            onSetPreset = viewModel::setChildAllowedPreset,
            onSetAutoBlock = viewModel::setChildAutoBlockNewApps,
            appsForPicker = currentKid?.installedApps ?: emptyList(),
            customAllowedNow = currentKid?.customAllowed?.toSet() ?: emptySet(),
            perAppLimitsNow = currentKid?.perAppLimits?.associate { it.name to it.ms.toInt() } ?: emptyMap(),
            onApplyCustomAllowed = viewModel::setChildCustomAllowed,
            onSetPerAppLimit = viewModel::setChildPerAppLimit,
        )

        Spacer(Modifier.height(20.dp))
        activeDevice?.let { active ->
            TextButton(onClick = { renaming = active }, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (devices.size > 1) "Rename ${active.label}" else "Name this device")
            }
        }
        if (devices.size < com.shantanu.shield.remote.PairedDevices.MAX_DEVICES) {
            TextButton(onClick = onAddDevice, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Link another child device")
            }
        }
        // With one child, "Unpair" still means the whole feature — unchanged from before. With several,
        // removing one must leave the others working, so it routes through unpairDevice rather than the
        // global reset (plan trap A).
        if (devices.size > 1) {
            val active = activeDevice
            TextButton(
                onClick = { active?.let { viewModel.unpairDevice(it.pairingId) } },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Remove ${active?.label ?: "this device"}")
            }
        } else {
            TextButton(onClick = onUnpair, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Unpair")
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun LoadedReport(generatedAtMs: Long, kids: List<KidReport>, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "Decrypted on this device · updated ${formatWhen(generatedAtMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        Spacer(Modifier.height(8.dp))

        if (kids.isEmpty()) {
            Text(
                "The report has no children configured yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            kids.forEach { kid ->
                KidReportCard(kid)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun KidReportCard(kid: KidReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(kid.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            DailyAverageLine(kid.dailyAvgMs)

            if (kid.week.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SparkBars(
                    week = kid.week.map { DayBucket(0L, it, 0L) },
                    budgetMs = kid.limitMin * 60_000L,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                budgetSummary(kid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (kid.topApps.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Top apps this week", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                kid.topApps.forEach { app ->
                    TopAppLine(AppUsageBucket(packageName = "", name = app.name, icon = null, foregroundMs = app.ms))
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (kid.weeks.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("4-week trend (daily average)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                TrendBars(kid.weeks)
            }

            if (kid.categories.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("By category", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                kid.categories.forEach { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(c.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatHm(c.ms), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (kid.hourly.size == 24 && kid.hourly.any { it > 0 }) {
                Spacer(Modifier.height(16.dp))
                Text("Time of day", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                HourlyBars(kid.hourly)
            }

            if (kid.sessions > 0) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Sessions (pickups)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${kid.sessions} · longest ${formatHm(kid.longestMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun HourlyBars(hourly: List<Long>) {
    val max = (hourly.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        hourly.forEach { v ->
            val h = (v.toFloat() / max).coerceIn(0f, 1f) * 56f
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(h.dp.coerceAtLeast(2.dp))
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("12a", "6a", "12p", "6p", "11p").forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TrendBars(weeks: List<Long>) {
    val max = (weeks.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Row(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        weeks.forEach { v ->
            val h = (v.toFloat() / max).coerceIn(0f, 1f) * 64f
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(h.dp.coerceAtLeast(3.dp))
                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun StatusBlock(
    title: String,
    body: String,
    onRefresh: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Check again")
        }
        extra()
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun RemoteControls(
    unlocked: Boolean,
    status: RemoteCommandSender.Result?,
    currentLimit: Int = -1,
    currentPreset: Int = -1,
    currentAutoBlock: Int = -1,
    pendingLimit: Int? = null,
    limitConfirmed: Boolean = false,
    pendingGrant: Int? = null,
    grantConfirmed: Boolean = false,
    onLock: (Boolean) -> Unit,
    onUnlock: () -> Unit,
    onGrant: (Int) -> Unit,
    onSetLimit: (Int) -> Unit,
    onSetPreset: (Int) -> Unit,
    onSetAutoBlock: (Boolean) -> Unit,
    appsForPicker: List<AppEntry> = emptyList(),
    customAllowedNow: Set<String> = emptySet(),
    perAppLimitsNow: Map<String, Int> = emptyMap(),
    onApplyCustomAllowed: (Set<String>) -> Unit = {},
    onSetPerAppLimit: (String, Int) -> Unit = { _, _ -> },
) {
    var fullLock by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Remote controls", style = MaterialTheme.typography.titleSmall)
                if (!unlocked) {
                    Spacer(Modifier.size(8.dp))
                    PlusBadge()
                }
            }
            Spacer(Modifier.height(12.dp))

            Button(onClick = { onLock(fullLock) }, enabled = unlocked, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Lock child's device now")
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Switch(checked = fullLock, onCheckedChange = { fullLock = it }, enabled = unlocked)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Also block phone & messages", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (fullLock) "Full lock — only emergency calls work" else "Phone & messages stay usable",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onUnlock, enabled = unlocked, modifier = Modifier.fillMaxWidth()) {
                Text("Unlock child's device")
            }

            Spacer(Modifier.height(16.dp))
            Text("Grant extra time", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { m ->
                    StatusButton(
                        label = "+$m min", pending = m == pendingGrant, confirmed = grantConfirmed,
                        filled = false, enabled = unlocked, modifier = Modifier.weight(1f),
                    ) { onGrant(m) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Set daily limit (minutes)", style = MaterialTheme.typography.bodyMedium)
            if (currentLimit >= 0) Text(
                "Currently $currentLimit min on the child",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 90, 120).forEach { m ->
                    StatusButton(
                        label = "$m", pending = m == pendingLimit, confirmed = limitConfirmed,
                        filled = m == currentLimit, enabled = unlocked, modifier = Modifier.weight(1f),
                    ) { onSetLimit(m) }
                }
            }

            // ---- Kid Mode settings pushed to the child's phone (no in-person setup needed) ----
            Spacer(Modifier.height(20.dp))
            Text("Kid Mode settings", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                "Push these to the child's phone — they apply when it's online.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Text("Allowed apps (stay open at budget)", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Currently: " + when (currentPreset) {
                    0 -> "Phone + Messages"
                    1 -> "Phone + Messages + WhatsApp"
                    2 -> "Custom (set on the child)"
                    else -> "—"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currentPreset == 0) {
                    Button(onClick = { onSetPreset(0) }, enabled = unlocked, modifier = Modifier.weight(1f)) { Text("Phone + Messages") }
                } else {
                    OutlinedButton(onClick = { onSetPreset(0) }, enabled = unlocked, modifier = Modifier.weight(1f)) { Text("Phone + Messages") }
                }
                if (currentPreset == 1) {
                    Button(onClick = { onSetPreset(1) }, enabled = unlocked, modifier = Modifier.weight(1f)) { Text("+ WhatsApp") }
                } else {
                    OutlinedButton(onClick = { onSetPreset(1) }, enabled = unlocked, modifier = Modifier.weight(1f)) { Text("+ WhatsApp") }
                }
            }
            Text(
                "For specific apps, use “Manage child’s apps” below.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(16.dp))
            Text("Auto-block newly installed apps", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Currently: " + when (currentAutoBlock) { 1 -> "On"; 0 -> "Off"; else -> "—" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currentAutoBlock == 1) {
                    Button(onClick = { onSetAutoBlock(true) }, enabled = unlocked, modifier = Modifier.weight(1f)) { Text("Turn on") }
                } else {
                    OutlinedButton(onClick = { onSetAutoBlock(true) }, enabled = unlocked, modifier = Modifier.weight(1f)) { Text("Turn on") }
                }
                if (currentAutoBlock == 0) {
                    Button(onClick = { onSetAutoBlock(false) }, enabled = unlocked, modifier = Modifier.weight(1f)) { Text("Turn off") }
                } else {
                    OutlinedButton(onClick = { onSetAutoBlock(false) }, enabled = unlocked, modifier = Modifier.weight(1f)) { Text("Turn off") }
                }
            }

            // Tier-2: pick specific allowed apps + per-app limits from the child's synced app list.
            Spacer(Modifier.height(16.dp))
            Text("Specific apps (allow + per-app limits)", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showAppPicker = true },
                enabled = unlocked && appsForPicker.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (appsForPicker.isEmpty()) "Sync the child to manage its apps" else "Manage child's apps…")
            }
            if (appsForPicker.isNotEmpty()) Text(
                "${customAllowedNow.size} always-allowed · ${perAppLimitsNow.size} with a per-app limit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )

            status?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    controlMessage(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showAppPicker) {
        ManageChildAppsDialog(
            apps = appsForPicker,
            allowedNow = customAllowedNow,
            limitsNow = perAppLimitsNow,
            onApplyAllowed = onApplyCustomAllowed,
            onSetLimit = onSetPerAppLimit,
            onDismiss = { showAppPicker = false },
        )
    }
}

/**
 * Remote app picker: shows the child's synced app list. The parent toggles "always allowed" (checkbox; applied
 * as a set on Save) and cycles a per-app daily cap (None → 15 → 30 → 60 → None; each tap sends one command).
 */
@Composable
private fun ManageChildAppsDialog(
    apps: List<AppEntry>,
    allowedNow: Set<String>,
    limitsNow: Map<String, Int>,
    onApplyAllowed: (Set<String>) -> Unit,
    onSetLimit: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var allowed by remember { mutableStateOf(allowedNow) }
    var localLimits by remember { mutableStateOf(limitsNow) }
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Child's apps") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap the limit to cycle None → 15 → 30 → 60 min. Check apps to keep open at budget, then Save.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
                    shown.forEach { app ->
                        val limit = localLimits[app.pkg] ?: 0
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Checkbox(
                                checked = app.pkg in allowed,
                                onCheckedChange = { on -> allowed = if (on) allowed + app.pkg else allowed - app.pkg },
                            )
                            Text(app.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                val next = when (limit) { 0 -> 15; 15 -> 30; 30 -> 60; else -> 0 }
                                localLimits = if (next == 0) localLimits - app.pkg else localLimits + (app.pkg to next)
                                onSetLimit(app.pkg, next)
                            }) {
                                Text(if (limit > 0) "${limit}m" else "No limit")
                            }
                        }
                    }
                    if (shown.isEmpty()) Text(
                        "No apps match.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApplyAllowed(allowed); onDismiss() }) { Text("Save allowed apps") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// Grant/limit button. pending → YELLOW (sent, waiting child) → GREEN once confirmed. Else filled (= current
// value) or outlined. Child confirms automatically via its next synced report; no action on the child.
@Composable
private fun StatusButton(
    label: String, pending: Boolean, confirmed: Boolean, filled: Boolean, enabled: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    when {
        pending -> Button(
            onClick = onClick, enabled = enabled, modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (confirmed) androidx.compose.ui.graphics.Color(0xFF4CAF50) else androidx.compose.ui.graphics.Color(0xFFFFC107),
                contentColor = if (confirmed) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color(0xFF3E2C00),
            ),
        ) { Text(label) }
        filled -> Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
        else -> OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun PlusBadge() {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text("Plus", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
    }
}

private fun controlMessage(result: RemoteCommandSender.Result): String = when (result) {
    RemoteCommandSender.Result.SUCCESS -> "Command sent ✓ — applies when the child is online"
    RemoteCommandSender.Result.NOT_PARENT -> "Only the parent device can send commands"
    RemoteCommandSender.Result.NOT_PAIRED -> "Pair with the child device first"
    RemoteCommandSender.Result.FAILED -> "Couldn't send — check the connection"
}

private fun budgetSummary(kid: KidReport): String {
    val days = kid.week.size
    return when {
        kid.limitMin <= 0 -> "No daily limit set"
        kid.budgetHitDays == 0 -> "Within the ${kid.limitMin}-min budget all $days days"
        else -> "Over the ${kid.limitMin}-min budget on ${kid.budgetHitDays} of $days days"
    }
}

private fun formatWhen(epochMs: Long): String =
    SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(epochMs))
