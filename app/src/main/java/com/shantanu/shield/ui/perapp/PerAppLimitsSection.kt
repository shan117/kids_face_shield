package com.shantanu.shield.ui.perapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.shantanu.shield.AppInfo
import com.shantanu.shield.MainViewModel

/**
 * Premium "Per-app limits": set a daily limit on specific apps (e.g. 30 min Instagram), enforced
 * independently of the overall budget. Gated behind Feature.PER_APP_LIMITS. The dialog has search;
 * apps that already have a limit sort to the top.
 */
@Composable
fun PerAppLimitsSection(viewModel: MainViewModel) {
    val unlocked by viewModel.perAppLimitsUnlocked.collectAsState(initial = false)
    if (!unlocked) return
    val limits by viewModel.perAppLimits.collectAsState(initial = emptyMap())
    val installedApps by viewModel.installedApps.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Per-app limits", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (limits.isEmpty()) "Set a daily limit on specific apps (e.g. 30 min Instagram), separate from the overall budget."
                    else "${limits.size} app(s) limited.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = { showDialog = true }, shape = RoundedCornerShape(14.dp)) { Text("Manage") }
        }
    }

    if (showDialog) {
        PerAppLimitsManageDialog(
            title = "Per-app limits",
            installedApps = installedApps,
            limits = limits,
            onSet = { pkg, m -> viewModel.setPerAppLimit(pkg, m) },
            onClose = { showDialog = false }
        )
    }
}

/**
 * Shared searchable per-app-limit dialog — used by the global [PerAppLimitsSection] and by the
 * per-kid cards in Multiple-kids mode. [limits] is package -> minutes; [onSet] gets (pkg, minutes)
 * with 0 = no limit. Apps that already have a limit sort to the top.
 */
@Composable
fun PerAppLimitsManageDialog(
    title: String,
    installedApps: List<AppInfo>,
    limits: Map<String, Int>,
    onSet: (pkg: String, minutes: Int) -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = installedApps
        .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        .sortedWith(compareByDescending<AppInfo> { it.packageName in limits.keys }.thenBy { it.name.lowercase() })
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("‹ Done") }
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search apps...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear") }
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true
                )
                LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    items(filtered) { app ->
                        AppLimitRow(
                            app = app,
                            limit = limits[app.packageName] ?: 0,
                            onSet = { onSet(app.packageName, it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppLimitRow(app: AppInfo, limit: Int, onSet: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                bitmap = app.icon.toBitmap().asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.SemiBold)
                Text(
                    if (limit > 0) "$limit min/day" else "No limit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Box {
                TextButton(onClick = { expanded = true }) { Text(if (limit > 0) "${limit}m" else "Off") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf(0, 15, 30, 45, 60, 90, 120).forEach { m ->
                        DropdownMenuItem(
                            text = { Text(if (m == 0) "No limit" else "$m min") },
                            onClick = { onSet(m); expanded = false }
                        )
                    }
                }
            }
        }
    }
}
