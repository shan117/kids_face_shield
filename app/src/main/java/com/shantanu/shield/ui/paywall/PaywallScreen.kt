package com.shantanu.shield.ui.paywall

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.shantanu.shield.R
import com.shantanu.shield.premium.PaywallConfig
import com.shantanu.shield.ui.theme.AppShieldTheme

// Premium value props shown on the screen (display copy; gating lives in the Feature flags).
private val PLUS_FEATURES = listOf(
    "Multiple kid profiles",
    "Full screen-time history & trends",
    "Unlimited app locks",
    "Schedules & advanced controls"
)

/**
 * Early-access / paywall screen. Two modes driven by the runtime config:
 *  - promo mode (`promoActive`): "Plus — free for now", CTA just continues (no purchase).
 *  - paywall mode (`!promoActive && paywallEnabled`): plan + purchase CTA (Billing wired in Phase 1b).
 * Right now the default config has the promo on, so it renders the free early-access mode.
 */
@Composable
fun PaywallScreen(
    onClose: () -> Unit,
    viewModel: PaywallViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsState()
    PaywallContent(config = config, onClose = onClose, onContinue = onClose)
}

@Composable
private fun PaywallContent(
    config: PaywallConfig,
    onClose: () -> Unit,
    onContinue: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val daysLeft = remember(config.freeUntilEpochMs) {
        val now = System.currentTimeMillis()
        if (config.freeUntilEpochMs > now) ((config.freeUntilEpochMs - now) / 86_400_000L).toInt() else -1
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Hero
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cs.primary)
                .padding(horizontal = 22.dp)
                .padding(top = 16.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = cs.onPrimary.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.size(34.dp)
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = cs.onPrimary)
                    }
                }
                Spacer(Modifier.weight(1f))
            }

            Surface(
                color = cs.onPrimary,
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(116.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            // "FREE EARLY ACCESS" ribbon (promo) or "PLUS" (paywall)
            Surface(color = cs.tertiaryContainer, shape = RoundedCornerShape(50)) {
                Text(
                    if (config.promoActive) "★ FREE EARLY ACCESS" else "★ KIDS SHIELD PLUS",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = cs.onTertiaryContainer
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Unlock the full Shield",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = cs.onPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Everything you need to keep them safe.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onPrimary.copy(alpha = 0.92f),
                textAlign = TextAlign.Center
            )
        }

        // Body
        Column(modifier = Modifier.padding(22.dp)) {
            PLUS_FEATURES.forEach { feature ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(color = cs.secondaryContainer, shape = RoundedCornerShape(50), modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Check, contentDescription = null,
                            tint = cs.onSecondaryContainer, modifier = Modifier.padding(5.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(feature, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(20.dp))

            if (config.promoActive) {
                // Free early-access mode
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Continue — it's free", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    buildString {
                        append("All Plus features are included free while we're new.")
                        if (daysLeft >= 0) append("  Free for $daysLeft more days.")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // Paywall mode (purchase wired in Phase 1b — dormant for now)
                Button(
                    onClick = onContinue,
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Start 30-day free trial", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Pricing arrives with billing (coming soon).",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Entry card shown in Settings that opens the early-access screen. */
@Composable
fun PlusEntryCard(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.primaryContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = cs.primary, shape = RoundedCornerShape(50), modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Star, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.padding(9.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Kids Shield Plus", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
                Text("Free for now — see what's included", style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer.copy(alpha = 0.8f))
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = cs.onPrimaryContainer)
        }
    }
}

@Preview
@Composable
private fun PaywallPreview() {
    AppShieldTheme {
        PaywallContent(config = PaywallConfig.DEFAULT, onClose = {}, onContinue = {})
    }
}
