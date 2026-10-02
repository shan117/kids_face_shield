package com.shantanu.shield.webfilter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Parent-facing setup for Phase 0 web filtering.
 *
 * The honest shape of this screen: **we cannot switch filtering on.** A normal app has no way to write
 * Private DNS. So the screen does the three things that are possible — hand over the exact hostname,
 * open the right settings screen, and then *prove* whether it worked — instead of pretending to a level
 * of control the platform doesn't allow.
 */
@Composable
fun WebFilterSection(viewModel: WebFilterViewModel = hiltViewModel()) {
    val state by viewModel.dnsState.collectAsState()
    val settingsLocked by viewModel.settingsLocked.collectAsState()
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(DnsFilterProvider.RECOMMENDED) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // ---- Layer 1: the in-app browser. Primary, because it needs nothing from the parent and
        // blocks pages rather than only whole sites. ----
        BrowserFilterBlock(viewModel)

        Spacer(Modifier.height(28.dp))
        androidx.compose.material3.HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        // ---- Layer 2: device-wide Private DNS. Secondary, and honest about why it still matters:
        // it is the only layer that reaches links opened INSIDE other apps. ----
        Text(
            "Also filter other apps",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "The browser above can't see links your child opens inside other apps — Instagram's " +
                "built-in browser, a link in a game. Android's own Private DNS covers those, across " +
                "every app on the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        StatusCard(state, settingsLocked)

        Spacer(Modifier.height(18.dp))
        Text(
            "How it works",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Android only lets you turn this on yourself — no app is allowed to change it. It takes " +
                "about 30 seconds, and Kids Shield will confirm it worked.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))
        Text("1 · Pick a filter", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        DnsFilterProvider.entries.forEach { provider ->
            ProviderRow(
                provider = provider,
                selected = provider == chosen,
                onSelect = { chosen = provider },
            )
        }

        Spacer(Modifier.height(12.dp))
        PrivacyDisclosure(chosen)

        Spacer(Modifier.height(16.dp))
        Text("2 · Copy the address", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                chosen.hostname,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { copyToClipboard(context, chosen.hostname) },
            shape = RoundedCornerShape(12.dp),
        ) { Text("Copy address") }

        Spacer(Modifier.height(16.dp))
        Text("3 · Paste it into Android", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Open network settings, then find Private DNS — usually under \"Private DNS\" or inside " +
                "\"More connection settings\", depending on your phone. Choose Private DNS provider " +
                "hostname, paste the address, and save.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            // There is no public Intent that opens the Private DNS screen directly, so this lands on
            // network settings and the text above covers the last hop. Wrapped because some OEM builds
            // don't resolve even this action.
            onClick = { openNetworkSettings(context) },
            shape = RoundedCornerShape(12.dp),
        ) { Text("Open network settings") }

        Spacer(Modifier.height(18.dp))
        Text("4 · Lock it down", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (settingsLocked) {
                "Lock System Settings is already on, so your child can't reach this setting to undo it."
            } else {
                "Turn on Lock System Settings in Tamper Protection — otherwise your child can open " +
                    "Settings and switch the filter off in seconds."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (settingsLocked) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
        )

        Spacer(Modifier.height(18.dp))
        LimitsCard()
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * The in-app browser controls: master switch, categories, parent overrides, and what got blocked.
 *
 * The master switch is off by default and says what it actually does, because "safe browsing" sounds
 * additive while the real effect is that every other browser on the phone stops opening. A parent
 * surprised by that turns the whole feature off.
 */
@Composable
private fun BrowserFilterBlock(viewModel: WebFilterViewModel) {
    val context = LocalContext.current
    val unlocked by viewModel.unlocked.collectAsState()
    val enabled by viewModel.enabled.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val allowed by viewModel.allowedDomains.collectAsState()
    val blocked by viewModel.blockedDomains.collectAsState()
    val counts by viewModel.blockCounts.collectAsState()

    Text(
        "Kids Shield Browser",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Blocks sites, individual pages and unsafe searches. Turning it on also blocks every other " +
            "browser on this phone, so your child browses here instead. Links from other apps open " +
            "here automatically.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    com.shantanu.shield.ui.PremiumGate(
        unlocked = unlocked,
        featureName = "Safe browsing",
        description = "Block adult sites, with a browser you control.",
    ) {
        Column {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Use Kids Shield Browser only",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (enabled) "On — Chrome and other browsers are blocked"
                            else "Off — your child can use any browser",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = viewModel::setEnabled)
                }
            }

            Spacer(Modifier.height(10.dp))
            // A parent needs to see what their child will actually get before trusting it — and this
            // is also the second way in, alongside the browser's own home-screen icon.
            OutlinedButton(
                onClick = { openShieldBrowser(context) },
                shape = RoundedCornerShape(12.dp),
            ) { Text("Open the browser") }
            Spacer(Modifier.height(2.dp))
            Text(
                "It also has its own icon on the home screen, called Kids Shield Browser.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            if (enabled) {
                Spacer(Modifier.height(16.dp))
                Text("Block these", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                WebFilterCategory.entries.forEach { category ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(category.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${category.description} · ${WebFilterLists.sizeOf(category)} sites",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = category in categories,
                            onCheckedChange = { viewModel.toggleCategory(category, it) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                OverrideEditor(
                    allowed = allowed,
                    blocked = blocked,
                    onAdd = viewModel::addOverride,
                    onRemove = viewModel::removeOverride,
                )

                if (counts.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    BlockCountsCard(counts, viewModel::clearCounts)
                }
            }
        }
    }
}

/** Parent exceptions in both directions. Allow wins over block — see DomainBlocklist.decide. */
@Composable
private fun OverrideEditor(
    allowed: Set<String>,
    blocked: Set<String>,
    onAdd: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    Text("Your own exceptions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(2.dp))
    Text(
        "The lists above can't cover every site. Add one here — allowing a site always wins over a " +
            "blocked category, so you can let one school site through without switching a category off.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    androidx.compose.material3.OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Site address") },
        placeholder = { Text("example.com") },
    )
    Spacer(Modifier.height(8.dp))
    Row {
        Button(
            onClick = { onAdd(draft, true); draft = "" },
            enabled = draft.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
        ) { Text("Always allow") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = { onAdd(draft, false); draft = "" },
            enabled = draft.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
        ) { Text("Always block") }
    }

    if (allowed.isNotEmpty() || blocked.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        (allowed.sorted().map { it to true } + blocked.sorted().map { it to false }).forEach { (domain, isAllow) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (isAllow) "Allowed" else "Blocked",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isAllow) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                    modifier = Modifier.width(62.dp),
                )
                Text(domain, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { onRemove(domain) }) { Text("Remove") }
            }
        }
    }
}

/**
 * What the filter refused, by category.
 *
 * Counts, never the sites. A list of what a child tried to reach turns a safety tool into
 * surveillance, and a parent needs to know *whether* it is happening far more than they need the URLs.
 * This is also why only counts can reach the weekly report.
 */
@Composable
private fun BlockCountsCard(counts: Map<WebFilterCategory, Int>, onClear: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Blocked so far: ${WebBlockCountCodec.total(counts)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            counts.entries.sortedByDescending { it.value }.forEach { (category, count) ->
                Text(
                    "${category.label}: $count",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Kids Shield counts these but never records which sites — not on this phone, and not " +
                    "in your weekly report.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            androidx.compose.material3.TextButton(onClick = onClear) { Text("Reset counts") }
        }
    }
}

@Composable
private fun StatusCard(state: PrivateDnsState, settingsLocked: Boolean) {
    val cs = MaterialTheme.colorScheme
    // Colour follows the real protection level, so a parent can read this in one glance without
    // parsing the sentence. "On but not filtering" is deliberately a warning, not a success.
    val (container, onContainer, icon, title, body) = when (state) {
        is PrivateDnsState.ActiveFiltered -> Quint(
            cs.secondaryContainer, cs.onSecondaryContainer, Icons.Default.Check,
            "Web filtering is on",
            "Filtering through ${state.provider.label}." +
                if (settingsLocked) " Your child can't turn it off."
                else " Turn on Lock System Settings so it can't be switched off.",
        )
        is PrivateDnsState.ActiveUnfiltered -> Quint(
            cs.errorContainer, cs.onErrorContainer, Icons.Default.Warning,
            "Private DNS is on, but it isn't filtering",
            "This phone is using \"${state.hostname}\", which encrypts lookups but doesn't block " +
                "anything. Replace it with one of the filters below.",
        )
        is PrivateDnsState.Off -> Quint(
            cs.errorContainer, cs.onErrorContainer, Icons.Default.Warning,
            "Web filtering is off",
            "This phone can reach any site. Follow the steps below to turn filtering on.",
        )
        is PrivateDnsState.Unsupported -> Quint(
            cs.surfaceContainerHigh, cs.onSurface, Icons.Default.Info,
            "Can't check on this Android version",
            "Kids Shield can't read this setting on Android 9 or older, so it can't confirm whether " +
                "filtering is on. The steps below still work — you'll just need to check manually.",
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = onContainer, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = onContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer.copy(alpha = 0.9f),
                )
            }
        }
    }
}

@Composable
private fun ProviderRow(provider: DnsFilterProvider, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    provider.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (provider == DnsFilterProvider.RECOMMENDED) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            "Recommended",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            Text(
                provider.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Says who gets to see the child's browsing, before the parent commits to it.
 *
 * Turning this on routes every domain lookup on the phone to an outside company. That is a real
 * privacy decision, and it is not this app's to make quietly on a family's behalf — the same standard
 * applied to telling a child when their location is shared. Placed between choosing a provider and
 * acting on it, so it cannot be scrolled past.
 */
@Composable
private fun PrivacyDisclosure(provider: DnsFilterProvider) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.Info,
                null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "What ${provider.label} can see",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "To decide what to block, ${provider.label} has to be asked about every site this " +
                        "phone opens. They see the site names — not the pages, not messages, and not " +
                        "what your child searches for.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.9f),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    // Context, not an excuse: the same list is already visible to the carrier today,
                    // and unencrypted. A parent weighing this deserves the comparison, not just the cost.
                    "Your mobile network already sees this list today, without encryption. This moves " +
                        "it to ${provider.label} and encrypts it on the way. Their privacy policy " +
                        "covers how long they keep it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/** States the limits plainly. A filter a parent over-trusts is worse than one they understand. */
@Composable
private fun LimitsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "What this does and doesn't do",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "• Blocks whole sites, not single pages or searches\n" +
                    "• You won't see a report of what was blocked\n" +
                    "• Chrome has its own \"Use secure DNS\" setting that can get around this — " +
                    "locking Android's Settings doesn't cover it\n" +
                    "• A determined teenager can still find ways around it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class Quint(
    val container: androidx.compose.ui.graphics.Color,
    val onContainer: androidx.compose.ui.graphics.Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val body: String,
)

private fun openShieldBrowser(context: Context) {
    runCatching {
        context.startActivity(
            Intent(context, ShieldBrowserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Private DNS", text))
    }
}

private fun openNetworkSettings(context: Context) {
    // Fall back through progressively broader screens: OEM builds vary in what they resolve.
    val candidates = listOf(
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        val ok = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        }.getOrDefault(false)
        if (ok) return
    }
}
