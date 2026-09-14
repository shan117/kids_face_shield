package com.shantanu.shield.ui.schedules

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shantanu.shield.LocalSnackbarHostState
import com.shantanu.shield.MainViewModel
import kotlinx.coroutines.launch

/**
 * Premium "Schedules": save the current kid-mode config (limit + allowed apps) as a named rule-set
 * (School / Weekend / Exam) and re-apply it in one tap. Applying writes the values into the live
 * config — no enforcement change, so this is low-risk. Gated behind Feature.SCHEDULES.
 */
@Composable
fun SchedulesSection(viewModel: MainViewModel) {
    val unlocked by viewModel.schedulesUnlocked.collectAsState(initial = false)
    com.shantanu.shield.ui.PremiumGate(unlocked, "Schedules") { SchedulesBody(viewModel) }
}

@Composable
private fun SchedulesBody(viewModel: MainViewModel) {
    val schedules by viewModel.schedules.collectAsState(initial = emptyList())
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Schedules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Save the current limit + allowed apps as a rule-set (School, Weekend, Exam) and apply it in one tap.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))

            schedules.forEach { s ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.SemiBold)
                            Text("${s.dailyLimitMinutes} min · ${presetLabel(s.allowedPreset)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        TextButton(onClick = {
                            viewModel.applySchedule(s)
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar("Applied \"${s.name}\"")
                            }
                        }) { Text("Apply") }
                        IconButton(onClick = { viewModel.deleteSchedule(s.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete schedule", tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                placeholder = { Text("Name (e.g. School)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = newName.isNotBlank(),
                    onClick = { viewModel.addSchedule(newName); newName = "" },
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Save current settings") }
            }
        }
    }
}

private fun presetLabel(preset: Int): String = when (preset) {
    0 -> "Phone & SMS"
    1 -> "+ WhatsApp"
    else -> "Custom"
}
