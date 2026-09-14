package com.shantanu.shield.ui.stats

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * "What counts as screen time" — the full manager behind Settings.
 *
 * Every package with recorded usage is listed (not just the dashboard's top 5), so an OEM utility
 * burning three minutes a day is still reachable. Switch ON = counted; OFF = excluded.
 *
 * This composable does NOT gate itself. The caller wraps it in `ParentGate`, which demands a parent
 * face scan on a kid-owned device.
 */
@Composable
fun ScreenTimeAppsSection(viewModel: StatsViewModel = hiltViewModel()) {
    val apps by viewModel.manageableApps.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadManageableApps() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Turn an app off to stop counting it as screen time. It leaves the charts and the " +
                "weekly report, stops using up the daily budget, and is no longer locked at night " +
                "or over budget.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Best used for phone utilities the system counts but nobody \"uses\" — a clock, a " +
                "wallpaper carousel, a launcher.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(16.dp))

        if (apps.isEmpty()) {
            Text(
                "No app activity recorded yet. Once the phone has been used for a while, every app " +
                    "that shows up in your charts will be listed here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                apps.forEach { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = app.icon
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
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                if (app.excluded) "Not counted" else "${formatHm(app.ms)} this week",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = !app.excluded,
                            onCheckedChange = { counted ->
                                viewModel.setExcluded(app.packageName, !counted)
                            }
                        )
                    }
                }
            }
        }
    }
}
