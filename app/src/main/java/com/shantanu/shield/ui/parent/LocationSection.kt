package com.shantanu.shield.ui.parent

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shantanu.shield.location.AddressResolver
import com.shantanu.shield.remote.FixStatus
import com.shantanu.shield.remote.LocationFix
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Where are they?" for the child device the parent is viewing.
 *
 * The newest entry is the current location; the rest is history, behind the LOCATION_HISTORY gate.
 * Every failure is rendered with its own reason — a parent must be able to tell "they switched this
 * off" from "no signal" from "their phone is off", because those call for completely different
 * responses. See LOCATION_FEATURE_PLAN.md §4.
 */
@Composable
fun LocationSection(
    state: ParentReportViewModel.LocationState,
    childLabel: String,
    historyUnlocked: Boolean,
    resolver: AddressResolver,
    onRequest: () -> Unit,
    onClearHistory: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Where is $childLabel?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))

            when (state) {
                is ParentReportViewModel.LocationState.Requesting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Asking their phone…", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "This can take up to a minute if they're indoors.",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }

                is ParentReportViewModel.LocationState.SendFailed -> {
                    Text(
                        "Couldn't send the request. Check your own connection and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    RequestButton(onRequest, first = false)
                }

                is ParentReportViewModel.LocationState.Unreachable -> {
                    // NOT "couldn't get a fix". Nothing came back at all, which points at the phone
                    // being off/offline rather than at signal — a different thing for a parent to do.
                    Text(
                        "Couldn't reach their phone",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "No answer came back. Their phone may be switched off, out of data, or Kids " +
                            "Shield may not be running on it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    val previous = state.log.firstOrNull()
                    if (previous != null && previous.isUsable) {
                        // A stale location is still worth something; hiding it would throw away real
                        // information because the newest attempt went unanswered.
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Last known",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = cs.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        CurrentLocation(previous, resolver)
                    }
                    Spacer(Modifier.height(14.dp))
                    RequestButton(onRequest, first = false)
                }

                is ParentReportViewModel.LocationState.Idle -> {
                    Text(
                        "You haven't asked yet. Their phone answers only when you do — there's no " +
                            "background tracking, and they're told each time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    RequestButton(onRequest, first = true)
                }

                is ParentReportViewModel.LocationState.Ready -> {
                    val latest = state.log.first()
                    CurrentLocation(latest, resolver)
                    Spacer(Modifier.height(14.dp))
                    RequestButton(onRequest, first = false)

                    val history = state.log.drop(1)
                    if (history.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "Earlier",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = cs.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        com.shantanu.shield.ui.PremiumGate(
                            unlocked = historyUnlocked,
                            featureName = "Location history",
                            description = "See every place you've looked them up before.",
                        ) {
                            Column {
                                history.take(20).forEach { fix ->
                                    HistoryRow(fix, resolver)
                                }
                                Spacer(Modifier.height(4.dp))
                                TextButton(onClick = onClearHistory) { Text("Clear history") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestButton(onRequest: () -> Unit, first: Boolean) {
    Button(onClick = onRequest, shape = RoundedCornerShape(12.dp)) {
        Icon(
            if (first) Icons.Default.LocationOn else Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(if (first) "Find their phone" else "Update location", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CurrentLocation(fix: LocationFix, resolver: AddressResolver) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current

    if (!fix.isUsable) {
        FailureBlock(fix)
        return
    }

    // Resolved off the main thread, keyed by coordinates so a repeat fix at the same place is not
    // re-geocoded. A null result is normal (no geocoder, offline, no street address) — coordinates
    // are shown instead, never a blank.
    var address by remember(fix.lat, fix.lon) { mutableStateOf<String?>(null) }
    LaunchedEffect(fix.lat, fix.lon) { address = resolver.resolve(fix) }

    Text(
        address ?: resolver.coordinates(fix),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = cs.onSurface,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        buildString {
            append(relativeTime(fix.fixedAtMs))
            if (fix.accuracyM > 0f) append(" · ±${fix.accuracyM.toInt()} m")
            if (fix.batteryPct >= 0) append(" · battery ${fix.batteryPct}%")
        },
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant,
    )
    if (fix.accuracyM > LOW_ACCURACY_M) {
        Spacer(Modifier.height(2.dp))
        Text(
            "Approximate — their phone couldn't get a precise fix.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(resolver.mapsUri(fix)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        },
        shape = RoundedCornerShape(12.dp),
    ) { Text("Open in Maps") }
}

@Composable
private fun FailureBlock(fix: LocationFix) {
    val cs = MaterialTheme.colorScheme
    val (headline, detail) = failureCopy(fix.status)
    Text(headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    Spacer(Modifier.height(2.dp))
    Text(
        "Asked ${relativeTime(fix.requestedAtMs)}" +
            if (fix.batteryPct >= 0) " · battery ${fix.batteryPct}%" else "",
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant,
    )
}

@Composable
private fun HistoryRow(fix: LocationFix, resolver: AddressResolver) {
    val cs = MaterialTheme.colorScheme
    var address by remember(fix.lat, fix.lon, fix.status) { mutableStateOf<String?>(null) }
    LaunchedEffect(fix.lat, fix.lon, fix.status) { address = resolver.resolve(fix) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (fix.isUsable) address ?: resolver.coordinates(fix) else failureCopy(fix.status).first,
                style = MaterialTheme.typography.bodyMedium,
                color = if (fix.isUsable) cs.onSurface else cs.onSurfaceVariant,
                maxLines = 2,
            )
            Text(
                absoluteTime(fix.requestedAtMs),
                style = MaterialTheme.typography.bodySmall,
                color = cs.outline,
            )
        }
    }
}

/** One headline + one explanation per failure. Each points at a different thing to actually do. */
private fun failureCopy(status: FixStatus): Pair<String, String> = when (status) {
    FixStatus.OK -> "Located" to ""
    FixStatus.NOT_CONSENTED ->
        "Location sharing is off on their phone" to
            "They need to turn on location sharing in Kids Shield on their own device. You can't " +
                "switch it on from here."
    FixStatus.PERMISSION_DENIED ->
        "Kids Shield can't access location on their phone" to
            "The location permission was denied or revoked there. It has to be granted on their device."
    FixStatus.LOCATION_DISABLED ->
        "Location is turned off on their phone" to
            "Their device's location setting is off, so no app can find it."
    FixStatus.TIMEOUT ->
        "Couldn't get a fix" to
            "Their phone may be indoors, out of signal, switched off, or offline. Try again in a moment."
}

private const val LOW_ACCURACY_M = 500f

private fun relativeTime(ms: Long): String {
    if (ms <= 0L) return "just now"
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000L} min ago"
        diff < 86_400_000L -> "${diff / 3_600_000L} h ago"
        else -> absoluteTime(ms)
    }
}

private fun absoluteTime(ms: Long): String =
    SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(ms))
