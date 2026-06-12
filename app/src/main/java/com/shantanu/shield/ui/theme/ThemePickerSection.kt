package com.shantanu.shield.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shantanu.shield.MainViewModel

/**
 * Premium "Themes": pick an accent colour for the app. Gated behind Feature.THEMES; hidden when
 * locked. The choice is read at the app root and applied via AppShieldTheme.
 */
@Composable
fun ThemePickerSection(viewModel: MainViewModel) {
    val unlocked by viewModel.themesUnlocked.collectAsState(initial = false)
    if (!unlocked) return
    val current by viewModel.themeAccent.collectAsState(initial = "teal")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Pick an accent colour for the app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AppAccent.values().forEach { accent ->
                    val selected = current == accent.key
                    Surface(
                        onClick = { viewModel.setThemeAccent(accent.key) },
                        shape = RoundedCornerShape(50),
                        color = Color(accent.primaryLight),
                        modifier = Modifier.size(46.dp)
                    ) {
                        if (selected) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Check, contentDescription = accent.label, tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
