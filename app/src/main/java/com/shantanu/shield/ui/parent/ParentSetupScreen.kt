package com.shantanu.shield.ui.parent

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.shantanu.shield.remote.RemoteReportSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

/**
 * Role choice + QR pairing for the Parent Remote Report. The privacy posture is visible here on purpose
 * (D8): the child screen states that sharing is OFF until enabled, so this reads as parental control, not
 * covert tracking. See PARENT_REMOTE_REPORT_PLAN.md §2.
 */
@Composable
fun ParentSetupScreen(
    onBack: () -> Unit,
    viewModel: ParentSetupViewModel = hiltViewModel(),
) {
    val role by viewModel.role.collectAsState()
    val childQr by viewModel.childQr.collectAsState()
    val shareEnabled by viewModel.shareEnabled.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val unlocked by viewModel.remoteReportUnlocked.collectAsState()
    val controlEnabled by viewModel.remoteControlEnabled.collectAsState()
    val locationSharingEnabled by viewModel.locationSharingEnabled.collectAsState()
    val controlUnlocked by viewModel.remoteControlUnlocked.collectAsState()
    val shareCadence by viewModel.shareCadence.collectAsState()
    val lockedDown by viewModel.deviceLockedDown.collectAsState()
    val faceEnrolled by viewModel.faceEnrolled.collectAsState()
    val appLocked by viewModel.appLocked.collectAsState()
    val settingsLocked by viewModel.settingsLocked.collectAsState()
    val kidModeOn by viewModel.kidModeOn.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    // Non-null when this scan is re-linking an existing child (their device minted a new code) rather
    // than adding a new one — so the old entry is retired instead of leaving a dead duplicate.
    var replaceTarget by remember { mutableStateOf<com.shantanu.shield.remote.PairedDevice?>(null) }
    var showShareConsent by remember { mutableStateOf(false) }

    // A valid scan flips the role to "parent" via DataStore — leave the camera when that lands.
    LaunchedEffect(role) { if (role == "parent") scanning = false }

    // Linking a SECOND child leaves the role already "parent", so the effect above never re-fires and the
    // scanner would stay open. The scan outcome closes it instead, and reports what happened.
    val scanResult by viewModel.scanResult.collectAsState()
    val setupSnackbar = com.shantanu.shield.LocalSnackbarHostState.current
    LaunchedEffect(scanResult) {
        val outcome = scanResult ?: return@LaunchedEffect
        scanning = false
        replaceTarget = null
        val message = when (outcome) {
            is ParentSetupViewModel.ScanResult.Added -> "Linked ${outcome.label}"
            is ParentSetupViewModel.ScanResult.Updated -> "Updated ${outcome.label}'s code"
            is ParentSetupViewModel.ScanResult.AtCapacity ->
                "You can link up to ${com.shantanu.shield.remote.PairedDevices.MAX_DEVICES} child devices. " +
                    "Remove one first."
        }
        setupSnackbar.showSnackbar(message)
        viewModel.consumeScanResult()
    }

    // A2 — re-gate "weakening" actions (unpair / disable share·control / disable lockdown / rotate) behind a
    // fresh parent face when one is enrolled. Defense in depth: even with the app already open, a child
    // can't undo the protection without the parent's face.
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val guard: (() -> Unit) -> Unit = { action -> if (faceEnrolled) pendingAction = action else action() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (scanning) scanning = false else onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Remote report", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!unlocked) {
                com.shantanu.shield.ui.PremiumGate(false, "Remote report",
                    "Subscribe to Kids Shield Plus to see your child's screen-time report on your phone.") {}
            } else
            when {
                scanning -> QrScanPane(
                    onScanned = { text ->
                        viewModel.onParentScanned(text, replacing = replaceTarget?.pairingId)
                    },
                    onCancel = { scanning = false; replaceTarget = null },
                )
                role == "child" -> {
                  // Refresh the pairing's claim window while the QR is actually visible. A child can
                  // mint a pairing, put the phone down, and be scanned much later — a window fixed at
                  // creation would have closed and left the pairing unclaimable.
                  LaunchedEffect(childQr) { if (childQr != null) viewModel.ensurePairingMembership() }
                  Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  ChildQrPane(
                    qr = childQr,
                    shareEnabled = shareEnabled,
                    unlocked = unlocked,
                    controlEnabled = controlEnabled,
                    locationSharingEnabled = locationSharingEnabled,
                    controlUnlocked = controlUnlocked,
                    cadence = shareCadence,
                    lockedDown = lockedDown,
                    faceEnrolled = faceEnrolled,
                    appLocked = appLocked,
                    settingsLocked = settingsLocked,
                    syncStatus = syncStatus,
                    onToggleShare = { on -> if (on) showShareConsent = true else guard { viewModel.setSharing(false) } },
                    onToggleControl = { on -> if (on) viewModel.setRemoteControl(true) else guard { viewModel.setRemoteControl(false) } },
                    // Turning location sharing OFF is not a "weakening" action needing the parent face:
                    // the child must always be able to withdraw this particular consent themselves.
                    onToggleLocation = viewModel::setLocationSharing,
                    onSetCadence = viewModel::setCadence,
                    onSetLockdown = { on -> if (on) viewModel.setDeviceLockdown(true) else guard { viewModel.setDeviceLockdown(false) } },
                    onSyncNow = viewModel::syncNow,
                    onRotateKey = { guard { viewModel.rotateKey() } },
                    onUnpair = { guard { viewModel.reset() } },
                  )
                  }
                }
                role == "parent" -> ParentReportPane(
                    onUnpair = viewModel::reset,
                    onAddDevice = { replaceTarget = null; viewModel.beginScan(); scanning = true },
                    onRescanDevice = { device ->
                        replaceTarget = device; viewModel.beginScan(); scanning = true
                    },
                )
                else -> RoleChoicePane(
                    kidModeOn = kidModeOn,
                    onChild = viewModel::becomeChild,
                    onParent = { viewModel.beginScan(); scanning = true },
                )
            }
        }
    }
        // A2 — face challenge overlay: covers everything until the parent face authenticates (or cancels).
        if (pendingAction != null) {
            Box(Modifier.fillMaxSize()) {
                com.shantanu.shield.AppFaceGate(onAuthenticated = {
                    pendingAction?.invoke()
                    pendingAction = null
                })
                TextButton(
                    onClick = { pendingAction = null },
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                ) { Text("Cancel") }
            }
        }

        // Consent before the first upload: turning sharing on also uploads the list of installed app names
        // (so you can pick allowed apps / per-app limits remotely), refreshed whenever apps change.
        if (showShareConsent) {
            AlertDialog(
                onDismissRequest = { showShareConsent = false },
                title = { Text("Turn on sharing?") },
                text = {
                    Text(
                        "This uploads your child's screen-time totals AND the list of app names installed on " +
                            "their phone — end-to-end encrypted, so only your paired device can read it. The app " +
                            "list refreshes automatically whenever an app is installed or removed, and updates on " +
                            "your phone on its own. No messages, content, or location are ever shared.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showShareConsent = false; viewModel.setSharing(true) }) {
                        Text("Turn on")
                    }
                },
                dismissButton = { TextButton(onClick = { showShareConsent = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun RoleChoicePane(kidModeOn: Boolean, onChild: () -> Unit, onParent: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    Text(
        "See a private weekly screen-time report on your own phone. Only encrypted totals are shared — " +
            "never messages, content, or location.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))

    // A device can only become a managed child once Kid Mode is on (it must already be enforcing a budget).
    RoleCard(
        title = "This is the child's device",
        body = if (kidModeOn)
            "Shows a pairing QR for the parent to scan. Sharing stays OFF until you turn it on."
        else
            "Turn on Kid Mode first (Settings → Kid Mode). A device becomes a managed child only once it's enforcing a daily budget.",
        cta = if (kidModeOn) "Set up as child" else "Kid Mode required",
        enabled = kidModeOn,
        onClick = onChild,
    )
    Spacer(Modifier.height(12.dp))
    RoleCard(
        title = "This is a parent's viewer",
        body = "Scan the child device's QR to pair, then view their weekly report here.",
        cta = "Scan child's QR",
        onClick = onParent,
    )
}

@Composable
private fun RoleCard(title: String, body: String, cta: String, onClick: () -> Unit, enabled: Boolean = true) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(cta) }
        }
    }
}

/**
 * A3 — the cage-status checklist. Reflects the *real* state of every protection (face / app-lock /
 * settings-lock / anti-uninstall), so the parent can tell at a glance whether the cage is sealed and fix
 * whatever isn't. Device-Admin is a system state read live from [TamperProtection] and refreshed on resume
 * (so it updates after the parent returns from the system "add device admin" dialog).
 */
@Composable
private fun CageChecklist(
    faceEnrolled: Boolean,
    appLocked: Boolean,
    settingsLocked: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var adminActive by remember { mutableStateOf(com.shantanu.shield.util.TamperProtection.isAdminActive(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                adminActive = com.shantanu.shield.util.TamperProtection.isAdminActive(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val done = listOf(faceEnrolled, appLocked, settingsLocked, adminActive).count { it }
    val sealed = done == 4

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                if (sealed) "Cage sealed — all protections active" else "Cage status — $done of 4 active",
                style = MaterialTheme.typography.titleSmall,
                color = if (sealed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            ChecklistRow("Parent face enrolled", faceEnrolled, if (faceEnrolled) null else "Enrol in the Face tab")
            ChecklistRow("App locked (parent face to open Shield)", appLocked, if (appLocked) null else "Turn on “Lock down this device”")
            ChecklistRow("System Settings locked", settingsLocked, if (settingsLocked) null else "Turn on “Lock down this device”")
            ChecklistRow(
                "Uninstall blocked (Device Admin)",
                adminActive,
                hint = if (adminActive) null else "Not active",
                action = if (adminActive) null else "Enable" to {
                    runCatching { context.startActivity(com.shantanu.shield.util.TamperProtection.enableAdminIntent(context)) }
                },
            )
            if (!sealed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Note: even fully sealed, force-stop and Safe Mode can still pause protection without Device-Owner setup.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A5 — the honesty banner. States the real protection ceiling rather than implying the cage is
 * unbreakable: without Device-Owner ("Strict") provisioning, a determined child can still force-stop the
 * app or boot Safe Mode. Reads the live Device-Owner state so it flips to "Strict mode active" if the
 * device was ever provisioned (future A6 / dedicated kid device).
 */
@Composable
private fun StrictModeBanner() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var strict by remember { mutableStateOf(com.shantanu.shield.util.TamperProtection.isDeviceOwner(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                strict = com.shantanu.shield.util.TamperProtection.isDeviceOwner(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val container =
        if (strict) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (strict) Icons.Filled.CheckCircle else Icons.Filled.Info,
                contentDescription = null,
                tint = if (strict) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (strict) "Strict mode active" else "Standard protection",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (strict) {
                        "This device is provisioned as Device Owner — force-stop and Safe Mode are blocked."
                    } else {
                        "Strong, but not unbreakable: a determined child can still force-stop Shield or boot " +
                            "into Safe Mode, which pauses protection until the next normal restart. An " +
                            "unbreakable “Strict” lock needs Device-Owner setup (for dedicated kid devices)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChecklistRow(
    label: String,
    done: Boolean,
    hint: String?,
    action: Pair<String, () -> Unit>? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = if (done) "Done" else "Not done",
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (!done && hint != null) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (action != null) {
            TextButton(onClick = action.second) { Text(action.first) }
        }
    }
}

@Composable
private fun ChildQrPane(
    qr: String?,
    shareEnabled: Boolean,
    unlocked: Boolean,
    controlEnabled: Boolean,
    locationSharingEnabled: Boolean,
    controlUnlocked: Boolean,
    cadence: String,
    lockedDown: Boolean,
    faceEnrolled: Boolean,
    appLocked: Boolean,
    settingsLocked: Boolean,
    syncStatus: RemoteReportSync.Result?,
    onToggleShare: (Boolean) -> Unit,
    onToggleControl: (Boolean) -> Unit,
    onToggleLocation: (Boolean) -> Unit,
    onSetCadence: (String) -> Unit,
    onSetLockdown: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onRotateKey: () -> Unit,
    onUnpair: () -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Text("Have the parent scan this", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))

    if (qr == null) {
        CircularProgressIndicator()
    } else {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
        ) {
            QrImage(text = qr, modifier = Modifier.padding(16.dp).size(240.dp))
        }
    }

    Spacer(Modifier.height(20.dp))

    // A1 — child-device lockdown (the cage): reuses Tamper Protection's app-lock + settings-lock so the
    // child can't open Shield to unpair/disable, nor reach system Settings to undo it. Needs a parent face.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = if (lockedDown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Lock down this device", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        !faceEnrolled -> "Enrol your face (Face tab) first to lock this device down."
                        lockedDown -> "On — only your face opens Shield or system Settings here."
                        else -> "Off — a child could open Shield and unpair or disable this."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = lockedDown, onCheckedChange = onSetLockdown, enabled = faceEnrolled)
        }
    }

    Spacer(Modifier.height(12.dp))

    // A3 — cage-status checklist: shows the real state of each protection so the parent can see, at a
    // glance, whether the cage is fully sealed (and fix what isn't).
    CageChecklist(
        faceEnrolled = faceEnrolled,
        appLocked = appLocked,
        settingsLocked = settingsLocked,
    )

    Spacer(Modifier.height(12.dp))

    // A5 — protection-level honesty: state the real ceiling (force-stop / Safe Mode survive without
    // Device-Owner "Strict" mode) instead of implying the lock is unbreakable. Play-policy + trust.
    StrictModeBanner()

    Spacer(Modifier.height(12.dp))

    // Locked rows show a Plus badge AND open the paywall when tapped (A3 consistency).
    val openPaywall = com.shantanu.shield.ui.LocalRequestPaywall.current

    // Opt-in switch — OFF by default; nothing syncs until the user turns this on (D7). Premium-gated:
    // disabled (with a Plus badge) when the feature is locked. Unlocked for everyone during the promo.
    Card(
        modifier = Modifier.fillMaxWidth()
            .then(if (!unlocked) Modifier.clickable { openPaywall() } else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Share weekly report", style = MaterialTheme.typography.titleSmall)
                    if (!unlocked) {
                        Spacer(Modifier.size(8.dp))
                        PlusBadge()
                    }
                }
                Text(
                    when {
                        !unlocked -> "Premium feature — subscribe to enable"
                        shareEnabled -> "On — encrypted totals + app names sync automatically"
                        else -> "Off — nothing leaves this device"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = shareEnabled, onCheckedChange = onToggleShare, enabled = unlocked)
        }
    }

    Spacer(Modifier.height(12.dp))
    // Second, independent opt-in: accept parent→child commands (lock / grant time / set limit).
    Card(
        modifier = Modifier.fillMaxWidth()
            .then(if (!controlUnlocked) Modifier.clickable { openPaywall() } else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow remote control", style = MaterialTheme.typography.titleSmall)
                    if (!controlUnlocked) {
                        Spacer(Modifier.size(8.dp))
                        PlusBadge()
                    }
                }
                Text(
                    when {
                        !controlUnlocked -> "Premium feature — subscribe to enable"
                        controlEnabled -> "On — parent can lock, grant time, set limits"
                        else -> "Off — this device ignores remote commands"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = controlEnabled, onCheckedChange = onToggleControl, enabled = controlUnlocked)
        }
    }

    Spacer(Modifier.height(10.dp))
    // Location is a SEPARATE consent from screen-time sharing and from remote control. Bundling it
    // into either would mean one "yes" silently covering something far more sensitive.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Share location when asked", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (locationSharingEnabled) {
                        "On — this phone answers when your parent asks where it is. You'll get a " +
                            "notification each time. This works on its own; it doesn't switch on " +
                            "remote control."
                    } else {
                        "Off — your parent is told the request was declined."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Switching ON must also secure the runtime permission, or the child would see "On" while
            // every request came back PERMISSION_DENIED — a setting that lies about itself.
            val locationPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
            ) { granted ->
                onToggleLocation(granted.values.any { it })
            }
            Switch(
                checked = locationSharingEnabled,
                onCheckedChange = { on ->
                    if (!on) onToggleLocation(false)
                    else locationPermLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    PrivacyNote(
        if (shareEnabled)
            "Only encrypted weekly screen-time totals are shared — never content, messages, or location."
        else
            "Sharing is OFF. Nothing leaves this device until you enable it.",
    )

    if (shareEnabled) {
        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Sync",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilterChip(selected = cadence == "daily", onClick = { onSetCadence("daily") }, label = { Text("Daily") })
            FilterChip(selected = cadence == "weekly", onClick = { onSetCadence("weekly") }, label = { Text("Weekly") })
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onSyncNow) { Text("Sync now") }
        syncStatus?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                syncMessage(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    TextButton(onClick = onRotateKey) {
        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text("Rotate key (re-pair)")
    }
    TextButton(onClick = onUnpair) {
        Text("Stop sharing / unpair")
    }
}

private fun syncMessage(result: RemoteReportSync.Result): String = when (result) {
    RemoteReportSync.Result.SUCCESS -> "Report uploaded ✓"
    RemoteReportSync.Result.DISABLED -> "Turn on sharing first"
    RemoteReportSync.Result.NOT_CHILD -> "This device isn't set up as the child"
    RemoteReportSync.Result.NOT_PAIRED -> "Pair with a parent first"
    RemoteReportSync.Result.FAILED -> "Upload failed — check the connection"
}

@Composable
private fun QrScanPane(onScanned: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }
    LaunchedEffect(Unit) { if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA) }

    Spacer(Modifier.height(12.dp))
    Text("Point at the child's QR", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))

    if (hasPermission) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(Dispatchers.Default.asExecutor(), QrScanAnalyzer(onScanned)) }
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                        )
                    } catch (_: Exception) {
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Hold steady — pairing is automatic once the code is in frame.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    } else {
        PrivacyNote("Camera access is needed to scan the pairing code. It is used only for this scan.")
        Spacer(Modifier.height(12.dp))
        Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("Grant camera") }
    }

    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onCancel) { Text("Cancel") }
}

@Composable
private fun PlusBadge() {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            "Plus",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun PrivacyNote(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
