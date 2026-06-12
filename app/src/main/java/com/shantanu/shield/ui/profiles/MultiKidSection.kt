package com.shantanu.shield.ui.profiles

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shantanu.shield.AllowedAppRow
import com.shantanu.shield.FaceEnrollmentScreen
import com.shantanu.shield.MainViewModel
import com.shantanu.shield.data.KidProfile

/**
 * Premium "Multiple kids" setup — its own Settings entry, for a shared phone used by two kids.
 * Toggle migrates the single-kid settings into two profiles, each with its own name, daily limit,
 * allowed-apps list, per-app limits, and enrolled face. It only ENFORCES once both kids' faces are
 * enrolled (see the service's isMultiKidActive).
 */
@Composable
fun MultiKidSection(viewModel: MainViewModel) {
    val unlocked by viewModel.multiKidUnlocked.collectAsState(initial = false)
    val enabled by viewModel.multiKidEnabled.collectAsState(initial = false)
    val profiles by viewModel.kidProfiles.collectAsState(initial = emptyList())
    val embeddings by viewModel.kidFaceEmbeddings.collectAsState(initial = emptyMap())
    val installedApps by viewModel.installedApps.collectAsState()

    var enrollingId by remember { mutableStateOf<String?>(null) }
    var customAppsForId by remember { mutableStateOf<String?>(null) }
    var perAppForId by remember { mutableStateOf<String?>(null) }
    val kidPerAppLimits by viewModel.kidPerAppLimits.collectAsState(initial = emptyMap())

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Multiple kids", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Give each child their own face, budget and allowed apps. Each child unlocks with their own face.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = enabled, enabled = unlocked, onCheckedChange = { viewModel.setMultiKidEnabled(it) })
            }

            if (!unlocked) {
                Spacer(Modifier.height(8.dp))
                Text("Premium feature", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }

            if (enabled && embeddings.size < 2) {
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Enrol both kids' faces below to turn this on. Until then, the device runs as single-kid.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            if (enabled) {
                Spacer(Modifier.height(12.dp))
                profiles.forEach { profile ->
                    KidProfileRow(
                        profile = profile,
                        enrolled = embeddings.containsKey(profile.id),
                        perAppCount = kidPerAppLimits[profile.id]?.size ?: 0,
                        onName = { viewModel.setKidProfileName(profile.id, it) },
                        onLimit = { viewModel.setKidProfileLimit(profile.id, it) },
                        onPreset = { viewModel.setKidProfilePreset(profile.id, it) },
                        onPickApps = { customAppsForId = profile.id },
                        onPerAppLimits = { perAppForId = profile.id },
                        onEnroll = { enrollingId = profile.id }
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }

    // Full-screen face enrolment for the chosen kid.
    val enrolling = enrollingId
    if (enrolling != null) {
        val profile = profiles.firstOrNull { it.id == enrolling }
        Dialog(onDismissRequest = { enrollingId = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { enrollingId = null }) { Text("‹ Back") }
                    }
                    FaceEnrollmentScreen(
                        viewModel = viewModel,
                        title = "Enrol ${profile?.name ?: "Kid"}",
                        isEnrolled = embeddings.containsKey(enrolling),
                        onEmbedding = { emb -> viewModel.saveKidFaceEmbedding(enrolling, emb); enrollingId = null }
                    )
                }
            }
        }
    }

    // Per-kid custom allowed-apps picker (with search, like the Protect tab).
    val customId = customAppsForId
    if (customId != null) {
        val profile = profiles.firstOrNull { it.id == customId }
        var appQuery by remember { mutableStateOf("") }
        val allowedSet = profile?.customAllowed ?: emptySet()
        // Allowed (toggled-on) apps float to the top, then alphabetical — like the Protect tab.
        val filteredApps = installedApps
            .filter { appQuery.isBlank() || it.name.contains(appQuery, ignoreCase = true) }
            .sortedWith(
                compareByDescending<com.shantanu.shield.AppInfo> { it.packageName in allowedSet }
                    .thenBy { it.name.lowercase() }
            )
        Dialog(onDismissRequest = { customAppsForId = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { customAppsForId = null }) { Text("‹ Done") }
                        Text("${profile?.name ?: "Kid"} — allowed apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Apps switched on are always allowed for this child (free, no time limit).",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    OutlinedTextField(
                        value = appQuery,
                        onValueChange = { appQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search apps...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (appQuery.isNotEmpty()) {
                                IconButton(onClick = { appQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear") }
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true
                    )
                    if (filteredApps.isEmpty()) {
                        Text(
                            if (appQuery.isBlank()) "Loading apps…" else "No apps match \"$appQuery\".",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        items(filteredApps) { app ->
                            AllowedAppRow(
                                app = app,
                                isAllowed = profile?.customAllowed?.contains(app.packageName) == true,
                                onToggle = { viewModel.toggleKidProfileCustomAllowed(customId, app.packageName) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Per-kid per-app daily limits dialog (reuses the global manager dialog).
    val perAppId = perAppForId
    if (perAppId != null) {
        val profile = profiles.firstOrNull { it.id == perAppId }
        com.shantanu.shield.ui.perapp.PerAppLimitsManageDialog(
            title = "${profile?.name ?: "Kid"} — app limits",
            installedApps = installedApps,
            limits = kidPerAppLimits[perAppId] ?: emptyMap(),
            onSet = { pkg, m -> viewModel.setKidProfilePerAppLimit(perAppId, pkg, m) },
            onClose = { perAppForId = null }
        )
    }
}

@Composable
private fun KidProfileRow(
    profile: KidProfile,
    enrolled: Boolean,
    perAppCount: Int,
    onName: (String) -> Unit,
    onLimit: (Int) -> Unit,
    onPreset: (Int) -> Unit,
    onPickApps: () -> Unit,
    onPerAppLimits: () -> Unit,
    onEnroll: () -> Unit
) {
    var nameField by remember(profile.id) { mutableStateOf(profile.name) }

    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = nameField,
                onValueChange = { nameField = it; onName(it) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text("Daily limit: ${profile.dailyLimitMinutes} min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Slider(
                value = profile.dailyLimitMinutes.toFloat(),
                onValueChange = { onLimit(it.toInt()) },
                valueRange = 15f..240f,
                steps = (240 - 15) / 15 - 1
            )

            // Per-kid allowed apps
            Text("Allowed apps (free, no limit)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = profile.allowedPreset == 0, onClick = { onPreset(0) }, label = { Text("Phone & SMS") })
                FilterChip(selected = profile.allowedPreset == 1, onClick = { onPreset(1) }, label = { Text("+ WhatsApp") })
                FilterChip(selected = profile.allowedPreset == 2, onClick = { onPreset(2) }, label = { Text("Custom") })
            }
            if (profile.allowedPreset == 2) {
                TextButton(onClick = onPickApps) { Text("Choose apps (${profile.customAllowed.size} selected)") }
            }

            // Per-kid per-app daily limits (e.g. 30 min YouTube for this child only).
            TextButton(onClick = onPerAppLimits) {
                Text(if (perAppCount > 0) "Per-app limits ($perAppCount set)" else "Per-app limits")
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (enrolled) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Face enrolled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onEnroll) { Text("Re-enrol") }
                } else {
                    Text("No face yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = onEnroll, shape = RoundedCornerShape(12.dp)) { Text("Enrol face") }
                }
            }
        }
    }
}
