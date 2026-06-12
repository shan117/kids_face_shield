package com.shantanu.shield.ui.earn

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
 * Premium "Earn screen-time": the parent defines tasks (homework, chores) worth bonus minutes; when
 * a child finishes one, the parent taps Give and those minutes are added to today's budget (reuses
 * the existing extension mechanism). Gated behind Feature.EARNED_TIME; hidden when locked.
 *
 * v1 grants to the single-kid budget; multi-kid earned-time is a later add.
 */
@Composable
fun EarnedTimeSection(viewModel: MainViewModel) {
    val unlocked by viewModel.earnedUnlocked.collectAsState(initial = false)
    if (!unlocked) return

    val tasks by viewModel.earnedTasks.collectAsState(initial = emptyList())
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    var newTitle by remember { mutableStateOf("") }
    var newMinutes by remember { mutableStateOf(15) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Earn screen-time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Set tasks worth bonus minutes. When your child finishes one, tap Give to add the minutes to today.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))

            tasks.forEach { task ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(task.title, fontWeight = FontWeight.SemiBold)
                            Text("+${task.minutes} min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        TextButton(onClick = {
                            viewModel.grantExtension(task.minutes)
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar("Added +${task.minutes} min to today")
                            }
                        }) { Text("Give") }
                        IconButton(onClick = { viewModel.removeEarnedTask(task.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove task", tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Add a task
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                placeholder = { Text("New task (e.g. Homework)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 15, 30).forEach { m ->
                    FilterChip(selected = newMinutes == m, onClick = { newMinutes = m }, label = { Text("$m min") })
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = newTitle.isNotBlank(),
                    onClick = { viewModel.addEarnedTask(newTitle, newMinutes); newTitle = "" },
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Add") }
            }
        }
    }
}
