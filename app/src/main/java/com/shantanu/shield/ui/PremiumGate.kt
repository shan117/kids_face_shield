package com.shantanu.shield.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Open the paywall. Provided by SettingsScreen; no-op elsewhere. */
val LocalRequestPaywall = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Hard premium gate. [unlocked] true → [content]; false → a lock card with an "Unlock with Plus" CTA that
 * opens the paywall. Replaces the old soft pattern (badge + still-usable, or `if(!unlocked) return` = silently
 * hidden) so a locked feature is both visible (upsell) AND unusable.
 */
@Composable
fun PremiumGate(
    unlocked: Boolean,
    featureName: String,
    description: String = "Subscribe to Kids Shield Plus to use this.",
    content: @Composable () -> Unit,
) {
    if (unlocked) { content(); return }
    val openPaywall = LocalRequestPaywall.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("$featureName — Plus", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = openPaywall) { Text("Unlock with Plus") }
        }
    }
}
