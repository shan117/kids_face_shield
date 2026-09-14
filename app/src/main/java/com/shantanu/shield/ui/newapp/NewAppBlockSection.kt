package com.shantanu.shield.ui.newapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shantanu.shield.MainViewModel

/**
 * Premium "Auto-lock new apps": when a new app is installed, the foreground service locks it (adds it
 * to the protected set) and notifies the parent, who can tap "Allow" to unlock. Gated behind
 * Feature.NEW_APP_AUTO_BLOCK; hidden when locked.
 */
@Composable
fun NewAppBlockSection(viewModel: MainViewModel) {
    val unlocked by viewModel.autoBlockUnlocked.collectAsState(initial = false)
    com.shantanu.shield.ui.PremiumGate(unlocked, "Auto-block new apps") { NewAppBlockBody(viewModel) }
}

@Composable
private fun NewAppBlockBody(viewModel: MainViewModel) {
    val enabled by viewModel.autoBlockNewApps.collectAsState(initial = false)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-lock new apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "When a new app is installed, lock it and notify you — it stays locked until you tap Allow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = { viewModel.setAutoBlockNewApps(it) })
        }
    }
}
