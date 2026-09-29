package com.shantanu.shield.ui.paywall

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.shantanu.shield.R
import com.shantanu.shield.premium.PaywallConfig
import com.shantanu.shield.ui.theme.AppShieldTheme

// TODO(before charging): your hosted policy URLs (also required for the Play store listing).
private const val TERMS_URL = ""
private const val PRIVACY_URL = ""

// Deep link into the Play subscription-management screen for this product.
private const val MANAGE_SUBS_URL =
    "https://play.google.com/store/account/subscriptions?sku=premium&package=com.appsecure.shield"

// Premium value props shown on the screen (display copy; gating lives in the Feature flags).
// NOTE: the feature list is no longer hardcoded here. It is rendered from `config.premiumFeatures`
// through FeatureCopy, so what the paywall sells is always exactly what is currently locked.

/**
 * Early-access / paywall screen. Two modes driven by the runtime config:
 *  - promo mode (`promoActive`): "free for now", CTA just continues (no purchase).
 *  - paywall mode (`!promoActive && paywallEnabled`): real monthly/annual plans from Play Billing.
 * Default config has the promo on, so it renders the free early-access mode (paywall mode dormant).
 */
@Composable
fun PaywallScreen(
    onClose: () -> Unit,
    viewModel: PaywallViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsState()
    val plans by viewModel.plans.collectAsState()
    val isPremium by viewModel.isPremium.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // Restore feedback — a plain "Restore" tap is otherwise silent, so surface the result.
    val snackbar = com.shantanu.shield.LocalSnackbarHostState.current
    LaunchedEffect(Unit) {
        viewModel.restoreEvents.collect { result ->
            val msg = when (result) {
                com.shantanu.shield.billing.BillingManager.RestoreResult.RESTORED -> "Purchases restored — Plus is active."
                com.shantanu.shield.billing.BillingManager.RestoreResult.NOTHING_FOUND -> "No previous purchases found on this Google account."
                com.shantanu.shield.billing.BillingManager.RestoreResult.UNAVAILABLE -> "Couldn't reach Google Play. Check your connection and try again."
            }
            snackbar.showSnackbar(msg)
        }
    }

    PaywallContent(
        config = config,
        plans = plans,
        isPremium = isPremium,
        onClose = onClose,
        onContinue = onClose,
        onPurchase = { plan -> activity?.let { viewModel.purchase(it, plan) } },
        onRestore = viewModel::restore,
    )
}

@Composable
private fun PaywallContent(
    config: PaywallConfig,
    plans: List<PlanUi>,
    isPremium: Boolean,
    onClose: () -> Unit,
    onContinue: () -> Unit,
    onPurchase: (PlanUi) -> Unit,
    onRestore: () -> Unit,
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
            // Exactly what is locked right now, straight from the runtime config.
            val locked = com.shantanu.shield.premium.FeatureCopy.ordered(config.premiumFeatures)

            Text(
                if (config.promoActive) "Free for everyone right now" else "What Plus unlocks",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = cs.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (config.promoActive) {
                    "Every feature below is included at no cost during early access."
                } else {
                    "${locked.size} feature${if (locked.size == 1) "" else "s"} — everything else stays free."
                },
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))

            if (locked.isEmpty()) {
                Text(
                    "Everything in Kids Shield is currently free.",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                )
            }
            locked.forEach { feature ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Surface(color = cs.secondaryContainer, shape = RoundedCornerShape(50), modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Check, contentDescription = null,
                            tint = cs.onSecondaryContainer, modifier = Modifier.padding(5.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            com.shantanu.shield.premium.FeatureCopy.title(feature),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            com.shantanu.shield.premium.FeatureCopy.description(feature),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            when {
                config.promoActive -> PromoCta(daysLeft = daysLeft, onContinue = onContinue)
                isPremium -> PremiumActive(onContinue = onContinue)
                else -> PaywallPlans(plans = plans, onPurchase = onPurchase, onRestore = onRestore)
            }
        }
    }
}

@Composable
private fun PromoCta(daysLeft: Int, onContinue: () -> Unit) {
    val cs = MaterialTheme.colorScheme
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
}

@Composable
private fun PremiumActive(onContinue: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("✓ You're on Plus", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.primary)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Continue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PaywallPlans(plans: List<PlanUi>, onPurchase: (PlanUi) -> Unit, onRestore: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (plans.isEmpty()) {
        Text(
            "Plans will appear here once pricing is set up.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text("Restore purchases") }
        return
    }

    var selectedId by remember(plans) { mutableStateOf(plans.first().basePlanId) }
    plans.forEach { plan ->
        PlanCard(plan = plan, selected = plan.basePlanId == selectedId, onClick = { selectedId = plan.basePlanId })
        Spacer(Modifier.height(10.dp))
    }
    val selected = plans.firstOrNull { it.basePlanId == selectedId } ?: plans.first()

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { onPurchase(selected) },
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            if (selected.trialText != null) "Start ${selected.trialText}"
            else "Subscribe — ${selected.price}${selected.periodSuffix}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Auto-renews until cancelled. Manage or cancel anytime in Google Play.",
        style = MaterialTheme.typography.bodySmall,
        color = cs.outline,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text("Restore purchases") }
    LegalLinks()
}

@Composable
private fun PlanCard(plan: PlanUi, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) cs.primaryContainer else cs.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(plan.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (plan.trialText != null) {
                    Text(plan.trialText, style = MaterialTheme.typography.labelMedium, color = cs.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(plan.price, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(plan.periodSuffix, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LegalLinks() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { openUrl(context, MANAGE_SUBS_URL) }) { Text("Manage subscription", style = MaterialTheme.typography.labelMedium) }
        // Terms/Privacy only shown once URLs are configured — hidden while blank so we don't ship dead links.
        if (TERMS_URL.isNotBlank()) {
            Text("·", color = cs.outline)
            TextButton(onClick = { openUrl(context, TERMS_URL) }) { Text("Terms", style = MaterialTheme.typography.labelMedium) }
        }
        if (PRIVACY_URL.isNotBlank()) {
            Text("·", color = cs.outline)
            TextButton(onClick = { openUrl(context, PRIVACY_URL) }) { Text("Privacy", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** Entry card shown in Settings that opens the early-access screen. */
@Composable
fun PlusEntryCard(onClick: () -> Unit, promoActive: Boolean = true) {
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
                Text(
                    if (promoActive) "Free for now — see what's included" else "Subscribe to unlock premium features",
                    style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = cs.onPrimaryContainer)
        }
    }
}

private fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Preview
@Composable
private fun PaywallPromoPreview() {
    AppShieldTheme {
        PaywallContent(PaywallConfig.DEFAULT, emptyList(), false, {}, {}, {}, {})
    }
}

@Preview
@Composable
private fun PaywallPaidPreview() {
    AppShieldTheme {
        PaywallContent(
            config = PaywallConfig.DEFAULT.copy(promoActive = false, paywallEnabled = true),
            plans = listOf(
                PlanUi("monthly", "Monthly", "₹149", "/month", "30-day free trial", ""),
                PlanUi("annual", "Annual", "₹1,199", "/year", "30-day free trial", ""),
            ),
            isPremium = false, onClose = {}, onContinue = {}, onPurchase = {}, onRestore = {}
        )
    }
}
