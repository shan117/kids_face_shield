package com.shantanu.shield

import android.Manifest
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.face.FaceRecognitionManager
import com.shantanu.shield.service.AppLockForegroundService
import com.shantanu.shield.ui.coachTarget
import com.shantanu.shield.ui.theme.AppShieldTheme
import com.shantanu.shield.util.AppCategory
import com.shantanu.shield.util.ImageUtils
import com.shantanu.shield.util.TamperProtection
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Runtime feature config. Re-fetched on resume so a tier change lands while someone is looking. */
    @javax.inject.Inject lateinit var remoteConfigSource: com.shantanu.shield.premium.RemoteConfigSource

    override fun onResume() {
        super.onResume()
        // The periodic refresh in AppLockApplication bounds staleness at six hours; this makes a
        // Console flip visible as soon as the parent opens the app. Firebase's own minimum fetch
        // interval makes a call inside the window a local no-op, so this is cheap to do every resume.
        lifecycleScope.launch { runCatching { remoteConfigSource.refresh() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startAppLockService()
        setContent {
            val rootViewModel: MainViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val accentKey by rootViewModel.themeAccent.collectAsState(initial = "teal")
            AppShieldTheme(accent = com.shantanu.shield.ui.theme.AppAccent.fromKey(accentKey)) {
                // Device role decides the shell. A *child* device shows a locked-down dashboard with the full
                // app behind a fresh parent face (ChildDeviceRoot); parent/unmanaged devices are unchanged.
                val role by rootViewModel.remoteRole.collectAsState(initial = null)
                when (role) {
                    null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) // role loading
                    "child" -> ChildDeviceRoot()
                    else -> AppLockGate { MainScreen() }
                }
            }
        }
    }

    private fun startAppLockService() {
        val serviceIntent = Intent(this, AppLockForegroundService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}

// Helpers
fun checkCameraPermission(context: Context) = 
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

fun isUsageStatsPermissionGranted(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
    val mode = appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
    return mode == android.app.AppOpsManager.MODE_ALLOWED
}

fun isBatteryOptimizationBypassed(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

fun formatTime(millis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

// Face-locks the whole app when "Protect This App" is enabled. Fails open (no lock) when no face
// is enrolled or camera permission is missing, so the parent can never lock themselves out.
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dataStoreManager = remember { DataStoreManager(context) }
    val lockOwnApp by dataStoreManager.lockOwnApp.collectAsState(initial = false)
    val faceEmbedding by dataStoreManager.faceEmbedding.collectAsState(initial = null)
    var authenticated by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                authenticated = false
                // Leaving the app drops the parent's proof of identity, so parent-only controls
                // re-lock for whoever picks the phone up next.
                com.shantanu.shield.ui.ParentAuthSession.clear()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val mustLock = lockOwnApp && faceEmbedding != null && checkCameraPermission(context) && !authenticated
    if (mustLock) {
        AppFaceGate(onAuthenticated = {
            authenticated = true
            // This gate verifies the PARENT's enrolled face, so passing it is parent proof.
            com.shantanu.shield.ui.ParentAuthSession.markAuthenticated()
        })
    } else {
        content()
    }
}

/**
 * Wraps a control only the parent may use. On a parent-owned device it is a pass-through. On a
 * KID-owned device it demands a parent face scan first — unless [AppLockGate] already verified one
 * this session, in which case the proof carries over and there is no second scan.
 *
 * Blocks rather than falls open when no parent face is enrolled: on a kid's phone an unverifiable
 * control is a control the kid can use.
 */
@Composable
fun ParentGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dataStoreManager = remember { DataStoreManager(context) }
    val ownerType by dataStoreManager.ownerType.collectAsState(initial = "parent")
    val faceEmbedding by dataStoreManager.faceEmbedding.collectAsState(initial = null)
    val alreadyVerified by com.shantanu.shield.ui.ParentAuthSession.authenticated.collectAsState()
    var scanning by remember { mutableStateOf(false) }

    // Parent-owned device: the whole app is the parent's, nothing extra to prove.
    if (ownerType != "kid" || alreadyVerified) { content(); return }

    if (scanning) {
        AppFaceGate(onAuthenticated = {
            com.shantanu.shield.ui.ParentAuthSession.markAuthenticated()
            scanning = false
        })
        return
    }

    val canScan = faceEmbedding != null && checkCameraPermission(context)
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("Parent only", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (canScan) {
                    "This phone is set up for a child. Scan the parent's face to change what counts " +
                        "as screen time."
                } else {
                    "This phone is set up for a child, and no parent face is enrolled — so this " +
                        "can't be unlocked here. Enrol a parent face from the Face tab first."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (canScan) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = { scanning = true }) { Text("Scan parent's face") }
            }
        }
    }
}

private enum class AppFaceAuthStatus { SEARCHING, NO_MATCH }

@Composable
fun AppFaceGate(onAuthenticated: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val faceRecognitionManager = remember { FaceRecognitionManager(context) }
    val dataStoreManager = remember { DataStoreManager(context) }
    var isVerifying by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(AppFaceAuthStatus.SEARCHING) }
    var failedAttempts by remember { mutableIntStateOf(0) }
    var lastFaceSeenAtMs by remember { mutableLongStateOf(0L) }

    // Camera self-heal (same approach as FaceLockOverlayContent): if the front camera is contended and
    // delivers no frames for a few seconds, force a fresh rebind instead of leaving the user stuck (the
    // automated "recents + back"). Capped so a truly-unavailable camera can never churn.
    val lastCameraFrameMs = remember { longArrayOf(0L) }
    var cameraRebindCount by remember { mutableStateOf(0) }

    // Release the front camera when this gate leaves composition (e.g. the parent taps Cancel on the
    // child dashboard). The camera is bound to the Activity lifecycle, which stays RESUMED after the
    // gate disappears — so without this it leaks, draining battery and risking the camera/FGS freeze.
    val cameraProviderRef = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    DisposableEffect(Unit) {
        onDispose { try { cameraProviderRef[0]?.unbindAll() } catch (_: Exception) {} }
    }

    LaunchedEffect(cameraRebindCount) {
        lastCameraFrameMs[0] = System.currentTimeMillis()   // grace period after each (re)bind
        while (cameraRebindCount < 4) {
            kotlinx.coroutines.delay(1500)
            if (System.currentTimeMillis() - lastCameraFrameMs[0] > 3500L) {
                android.util.Log.w("AppFaceGate", "No camera frames in 3.5s — rebinding (self-heal #${cameraRebindCount + 1})")
                cameraRebindCount++
                return@LaunchedEffect
            }
        }
    }

    LaunchedEffect(status) {
        // After ~2.5s with no face seen, revert NO_MATCH back to SEARCHING so
        // the screen doesn't keep accusing the user once they look away.
        while (status == AppFaceAuthStatus.NO_MATCH) {
            kotlinx.coroutines.delay(500)
            if (System.currentTimeMillis() - lastFaceSeenAtMs > 2500L) {
                status = AppFaceAuthStatus.SEARCHING
            }
        }
    }

    val isNoMatch = status == AppFaceAuthStatus.NO_MATCH
    val iconBg = if (isNoMatch) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val iconTint = if (isNoMatch) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    val title = if (isNoMatch) "Face didn't match" else "Kids Shield is Locked"
    val subtitle = if (isNoMatch)
        "Only the enrolled face can unlock this app. Make sure you're in good light and try again."
    else
        "Look at the camera to unlock"

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(modifier = Modifier.size(120.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isNoMatch) Icons.Default.Close else Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = iconTint
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (isNoMatch && failedAttempts > 0) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Failed attempts: $failedAttempts",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Hidden front camera continuously scanning for the enrolled face. Wrapped in key() so the self-heal
        // watchdog can recreate it (fresh CameraX bind) when frames stall.
        androidx.compose.runtime.key(cameraRebindCount) {
        Box(modifier = Modifier.size(1.dp).alpha(0f)) {
            AndroidView(factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProviderRef[0] = cameraProvider   // so onDispose can release the camera
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val imageAnalyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
                        it.setAnalyzer(Dispatchers.Default.asExecutor()) { imageProxy ->
                            lastCameraFrameMs[0] = System.currentTimeMillis()   // camera is alive → self-heal stays quiet
                            if (isVerifying) { imageProxy.close(); return@setAnalyzer }
                            isVerifying = true
                            coroutineScope.launch {
                                val storedEmbedding = dataStoreManager.faceEmbedding.first()
                                if (storedEmbedding == null) { imageProxy.close(); onAuthenticated(); return@launch }
                                val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
                                imageProxy.close()
                                if (bitmap != null) {
                                    val faceBitmap = faceRecognitionManager.detectFace(bitmap)
                                    if (faceBitmap != null) {
                                        val currentEmbedding = faceRecognitionManager.getEmbedding(faceBitmap)
                                        val now = System.currentTimeMillis()
                                        if (faceRecognitionManager.isMatch(currentEmbedding, storedEmbedding)) {
                                            withContext(Dispatchers.Main) { onAuthenticated() }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                // Count attempts at most ~1/second so a wrong-face hold
                                                // doesn't inflate the counter every frame.
                                                if (now - lastFaceSeenAtMs > 900L) failedAttempts++
                                                lastFaceSeenAtMs = now
                                                status = AppFaceAuthStatus.NO_MATCH
                                            }
                                        }
                                    }
                                }
                                isVerifying = false
                            }
                        }
                    }
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalyzer)
                    } catch (e: Exception) { }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }, modifier = Modifier.fillMaxSize())
        }
        }
    }
}

// Child-device shell. On a device set up as the child (remoteRole == "child"), the child sees only a minimal
// dashboard; the full management UI (MainScreen) is revealed only after a FRESH parent face, and that access
// is dropped the instant the app leaves the foreground — so a child can't inherit the parent's unlock.
// Fail-open (like AppLockGate): with no parent face enrolled yet, management is open so the parent can finish
// setup / recover and never gets locked out.
@Composable
fun ChildDeviceRoot() {
    val context = LocalContext.current
    val dataStoreManager = remember { DataStoreManager(context) }
    val faceEmbedding by dataStoreManager.faceEmbedding.collectAsState(initial = null)
    var parentMode by remember { mutableStateOf(false) }
    var requestingFace by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                parentMode = false       // drop parent access when the app backgrounds
                requestingFace = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val canGate = faceEmbedding != null && checkCameraPermission(context)

    when {
        parentMode || !canGate -> MainScreen()
        requestingFace -> Box(Modifier.fillMaxSize()) {
            AppFaceGate(onAuthenticated = { parentMode = true; requestingFace = false })
            TextButton(
                onClick = { requestingFace = false },
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            ) { Text("Cancel") }
        }
        else -> ChildDashboard(onParentSettings = { requestingFace = true })
    }
}

@Composable
private fun ChildDashboard(onParentSettings: () -> Unit) {
    val vm: MainViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val limitMin by vm.dailyLimitMinutes.collectAsState(initial = 0)
    val usedMs by vm.screenTimeUsedMs.collectAsState(initial = 0L)
    val extMs by vm.extensionsTodayMs.collectAsState(initial = 0L)
    val allowedCustom by vm.customAlwaysAllowed.collectAsState(initial = emptySet())
    val multiKid by vm.multiKidEnabled.collectAsState(initial = false)
    var showAsk by remember { mutableStateOf(false) }

    // Mirrors the enforcement formula in AppLockForegroundService: locked when usedMin >= limit + extMin.
    val totalMin = limitMin + (extMs / 60_000L).toInt()
    val usedMin = (usedMs / 60_000L).toInt()
    val leftMin = (totalMin - usedMin).coerceAtLeast(0)
    fun hm(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)   // keep content clear of status + nav bars
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Face, null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(16.dp))
        Text("This device is managed", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))

        when {
            multiKid -> {
                // Per-kid budgets aren't surfaced here yet (v1) — show a generic managed message.
                Text(
                    "Your screen time and apps are set by your parent.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            limitMin > 0 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Time left today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            hm(leftMin),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (leftMin == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { (usedMin.toFloat() / totalMin.coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (leftMin == 0) "Daily limit reached" else "${hm(usedMin)} of ${hm(totalMin)} used",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                Text(
                    "No screen-time limit is set right now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (allowedCustom.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "${allowedCustom.size} app${if (allowedCustom.size == 1) "" else "s"} always allowed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = { showAsk = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Ask for more time")
        }

        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onParentSettings) {
            Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Parent settings")
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showAsk) {
        AlertDialog(
            onDismissRequest = { showAsk = false },
            icon = { Icon(Icons.Default.Face, null) },
            title = { Text("Ask for more time") },
            text = {
                Text("Only a parent can add time. Ask them — they can grant more from “Parent settings” here, or remotely from their own phone.")
            },
            confirmButton = { TextButton(onClick = { showAsk = false }) { Text("OK") } }
        )
    }
}

// Shared snackbar host so any screen can post short feedback ("Gmail now requires
// face unlock", "Free Play started · 30 min") without plumbing the state through
// every composable. Provided at MainScreen and read via LocalSnackbarHostState.
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> {
    error("LocalSnackbarHostState not provided")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val firstRunCompleted by viewModel.firstRunCompleted.collectAsState(initial = null)
    var enrollingFromWizard by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(1) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coachMarks = remember { com.shantanu.shield.ui.CoachMarkController() }
    val seenTours by viewModel.seenTours.collectAsState(initial = emptySet())

    // In Kid Mode (this device enforces a budget) the "Protect" tab — the parent-phone block-list that
    // shields hand-picked apps behind a face — is the wrong model: a kid's phone locks everything *except*
    // the always-allowed list when the budget runs out. So hide Protect here and keep the parent on Settings
    // (Kid Mode config) instead of landing on a tab that no longer exists.
    val ownerType by viewModel.ownerType.collectAsState(initial = "parent")
    val kidMode = ownerType == "kid"
    LaunchedEffect(kidMode) { if (kidMode && selectedTab == 1) selectedTab = 2 }

    if (firstRunCompleted == null) {
        // DataStore loading — render empty to avoid flashing the wizard.
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        return
    }

    if (firstRunCompleted == false) {
        if (enrollingFromWizard) {
            // Rendered as root (no Scaffold), so apply system-bar insets here or the title clips under the
            // status bar. In the Face tab the Scaffold already insets it, so don't pad inside the screen.
            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) { FaceEnrollmentScreen(viewModel) }
            val faceEmbedding by viewModel.faceEmbedding.collectAsState(initial = null)
            LaunchedEffect(faceEmbedding) {
                if (faceEmbedding != null) enrollingFromWizard = false
            }
            return
        }
        FirstRunWelcome(
            viewModel = viewModel,
            onOpenFaceEnrol = { enrollingFromWizard = true },
            onFinish = { viewModel.setFirstRunCompleted(true) }
        )
        return
    }

    // Once first-run is complete, kick off the welcome coach-mark tour exactly once
    // for any user who hasn't yet seen it. Delay until selectedTab == 1 (Protect) so
    // the highlighted targets are composed before the overlay reads their bounds.
    LaunchedEffect(firstRunCompleted, seenTours, selectedTab) {
        if (firstRunCompleted == true &&
            selectedTab == 1 &&
            !seenTours.contains(com.shantanu.shield.ui.CoachTours.FIRST_RUN_ID) &&
            !coachMarks.visible
        ) {
            kotlinx.coroutines.delay(600)
            coachMarks.start(
                com.shantanu.shield.ui.CoachTours.FIRST_RUN_ID,
                com.shantanu.shield.ui.CoachTours.FIRST_RUN
            )
        }
    }

    // First visit to the Settings tab → run the Settings walkthrough exactly once.
    LaunchedEffect(firstRunCompleted, seenTours, selectedTab) {
        if (firstRunCompleted == true &&
            selectedTab == 2 &&
            seenTours.contains(com.shantanu.shield.ui.CoachTours.FIRST_RUN_ID) &&
            !seenTours.contains(com.shantanu.shield.ui.CoachTours.SETTINGS_ID) &&
            !coachMarks.visible
        ) {
            kotlinx.coroutines.delay(600)
            coachMarks.start(
                com.shantanu.shield.ui.CoachTours.SETTINGS_ID,
                com.shantanu.shield.ui.CoachTours.SETTINGS
            )
        }
    }

    // First visit to the Stats tab on a PARENT-owned device → introduce the "not screen time"
    // override once. Skipped on a kid-owned device, where the dashboard renders the Kid view and
    // the override doesn't exist. Replayable from Settings > Help & Onboarding.
    LaunchedEffect(firstRunCompleted, seenTours, selectedTab, kidMode) {
        if (firstRunCompleted == true &&
            selectedTab == 3 &&
            !kidMode &&
            !seenTours.contains(com.shantanu.shield.ui.CoachTours.STATS_ID) &&
            !coachMarks.visible
        ) {
            kotlinx.coroutines.delay(600)
            coachMarks.start(
                com.shantanu.shield.ui.CoachTours.STATS_ID,
                com.shantanu.shield.ui.CoachTours.STATS
            )
        }
    }

    val topBarOverride = remember { mutableStateOf<com.shantanu.shield.ui.TopBarOverride?>(null) }
    // Global paywall request: lets gated controls on ANY tab (e.g. Stats) jump to Settings → Paywall.
    // SettingsScreen consumes the pending flag and opens the Paywall route.
    var pendingPaywall by remember { mutableStateOf(false) }
    CompositionLocalProvider(
        LocalSnackbarHostState provides snackbarHostState,
        com.shantanu.shield.ui.LocalCoachMarks provides coachMarks,
        com.shantanu.shield.ui.LocalRequestTab provides { tab -> selectedTab = tab },
        com.shantanu.shield.ui.LocalRequestPaywall provides { selectedTab = 2; pendingPaywall = true },
        com.shantanu.shield.ui.LocalTopBarOverride provides topBarOverride
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    actionColor = MaterialTheme.colorScheme.primary,
                    actionContentColor = MaterialTheme.colorScheme.primary,
                    dismissActionContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        },
        topBar = {
            val override = topBarOverride.value
            com.shantanu.shield.ui.KfsTopBar(
                title = override?.title ?: when(selectedTab) {
                    0 -> "Face ID"
                    1 -> "App Shield"
                    2 -> "Settings"
                    3 -> "Stats"
                    else -> "App Shield"
                },
                onBack = override?.onBack
            )
        },
        bottomBar = {
            NavigationBar(tonalElevation = 8.dp) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Face, null) },
                    label = { Text("Face") }
                )
                if (!kidMode) {
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Lock, null) },
                        label = { Text("Protect") }
                    )
                }
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Settings") },
                    modifier = Modifier.coachTarget("settings-tab")
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                    label = { Text("Stats") },
                    modifier = Modifier.coachTarget("stats-tab")
                )
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            when (selectedTab) {
                0 -> FaceEnrollmentScreen(viewModel)
                1 -> if (kidMode) SettingsScreen(viewModel, pendingPaywall) { pendingPaywall = false }
                     else ProtectScreen(viewModel)
                2 -> SettingsScreen(viewModel, pendingPaywall) { pendingPaywall = false }
                3 -> com.shantanu.shield.ui.stats.StatsScreen()
            }
        }
    }
        com.shantanu.shield.ui.CoachMarkOverlay(
            controller = coachMarks,
            onTourComplete = { tourId -> viewModel.markTourSeen(tourId) }
        )
    }
    }
}

// ============================================================================
// First-run welcome screen. Shown only once: until the user taps "Get Started"
// (or "Skip"), which flips firstRunCompleted to true. Single fullscreen page
// — no multi-step wizard — listing the 4 setup tasks. Each task auto-checks
// once satisfied, so the user can grant in any order and see progress live.
// ============================================================================
@Composable
private fun FirstRunWelcome(
    viewModel: MainViewModel,
    onOpenFaceEnrol: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    // Stale-aware: a face enrolled on an old model doesn't satisfy the first-run gate (forces re-enrol).
    val faceEnrolled by viewModel.parentFaceEnrolled.collectAsState(initial = false)
    var cameraGranted by remember { mutableStateOf(checkCameraPermission(context)) }
    var usageGranted by remember { mutableStateOf(isUsageStatsPermissionGranted(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    fun notifOk() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
    var notifGranted by remember { mutableStateOf(notifOk()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                cameraGranted = checkCameraPermission(context)
                usageGranted = isUsageStatsPermissionGranted(context)
                overlayGranted = Settings.canDrawOverlays(context)
                notifGranted = notifOk()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { cameraGranted = it }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notifGranted = it }

    val allDone = faceEnrolled && cameraGranted && usageGranted && overlayGranted && notifGranted

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Welcome to Kids Face Shield",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Shield apps on your phone behind your face. Lend the phone to a kid via Free Play, or set up Kid Mode with a daily screen-time budget.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(32.dp))
            Text(
                "Quick setup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))

            SetupTaskRow(
                title = "Enrol your face",
                subtitle = "Required to unlock protected apps",
                done = faceEnrolled,
                actionLabel = if (faceEnrolled) null else "Open camera",
                onAction = onOpenFaceEnrol
            )
            Spacer(Modifier.height(8.dp))
            SetupTaskRow(
                title = "Camera permission",
                subtitle = "Needed for face scanning",
                done = cameraGranted,
                actionLabel = if (cameraGranted) null else "Grant",
                onAction = { cameraLauncher.launch(Manifest.permission.CAMERA) }
            )
            Spacer(Modifier.height(8.dp))
            SetupTaskRow(
                title = "Usage Access",
                subtitle = "Detect which app is on screen",
                done = usageGranted,
                actionLabel = if (usageGranted) null else "Grant",
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            )
            Spacer(Modifier.height(8.dp))
            SetupTaskRow(
                title = "Display over other apps",
                subtitle = "Show the face-unlock screen on top",
                done = overlayGranted,
                actionLabel = if (overlayGranted) null else "Grant",
                onAction = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            )

            if (Build.VERSION.SDK_INT >= 33) {
                Spacer(Modifier.height(8.dp))
                SetupTaskRow(
                    title = "Notifications",
                    subtitle = "Alerts + the always-on running notice",
                    done = notifGranted,
                    actionLabel = if (notifGranted) null else "Grant",
                    onAction = { notifLauncher.launch("android.permission.POST_NOTIFICATIONS") }
                )
            }

            Spacer(Modifier.height(32.dp))
            // First-run is a hard gate: can't proceed until face enrolled + every permission granted.
            Button(
                onClick = onFinish,
                enabled = allDone,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Get Started")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (allDone) "All set — tap Get Started." else "Grant everything above to continue.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SetupTaskRow(
    title: String,
    subtitle: String,
    done: Boolean,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (done)
            MaterialTheme.colorScheme.secondaryContainer
        else
            MaterialTheme.colorScheme.surfaceContainer,
        onClick = if (done) ({}) else onAction
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (done) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (done) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (!done && actionLabel != null) {
                Text(
                    actionLabel,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

// ============================================================================
// Global status banner. Single-line, pinned to the top of every tab. Answers
// the user's first question: "is this thing working?" State priorities:
//   1. setup incomplete  (no face OR critical perms missing)  → ERROR colour
//   2. Free Play active                                        → PRIMARY colour
//   3. shield ON with apps protected                           → SECONDARY
//   4. shield ready but no apps protected yet                  → TERTIARY (nudge)
// ============================================================================
@Composable
private fun StatusBanner(viewModel: MainViewModel) {
    val context = LocalContext.current
    val faceEmbedding by viewModel.faceEmbedding.collectAsState(initial = null)
    val protectedApps by viewModel.protectedApps.collectAsState(initial = emptySet())
    val endAt by viewModel.kidSessionEndAt.collectAsState(initial = 0L)
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val isFreePlay = nowMs < endAt

    LaunchedEffect(endAt) {
        while (System.currentTimeMillis() < endAt) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
        nowMs = System.currentTimeMillis()
    }

    var usageGranted by remember { mutableStateOf(isUsageStatsPermissionGranted(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                usageGranted = isUsageStatsPermissionGranted(context)
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val faceEnrolled = faceEmbedding != null
    val criticalPermsOk = usageGranted && overlayGranted
    val setupComplete = faceEnrolled && criticalPermsOk

    val icon: ImageVector
    val label: String
    val container: Color
    val onContainer: Color
    when {
        !setupComplete -> {
            icon = Icons.Default.Warning
            label = when {
                !faceEnrolled -> "Setup incomplete — enrol your face"
                !usageGranted -> "Setup incomplete — grant Usage Access"
                !overlayGranted -> "Setup incomplete — grant Overlay permission"
                else -> "Setup incomplete"
            }
            container = MaterialTheme.colorScheme.errorContainer
            onContainer = MaterialTheme.colorScheme.onErrorContainer
        }
        isFreePlay -> {
            val rem = (endAt - nowMs).coerceAtLeast(0L)
            val mn = rem / 60_000L
            val sc = (rem % 60_000L) / 1000L
            icon = Icons.Default.PlayArrow
            label = "Free Play active · %d:%02d remaining".format(mn, sc)
            container = MaterialTheme.colorScheme.primaryContainer
            onContainer = MaterialTheme.colorScheme.onPrimaryContainer
        }
        protectedApps.isEmpty() -> {
            icon = Icons.Default.Info
            label = "Shield ready — protect an app below"
            container = MaterialTheme.colorScheme.tertiaryContainer
            onContainer = MaterialTheme.colorScheme.onTertiaryContainer
        }
        else -> {
            icon = Icons.Default.CheckCircle
            label = "Shield ON · ${protectedApps.size} app${if (protectedApps.size == 1) "" else "s"} protected"
            container = MaterialTheme.colorScheme.secondaryContainer
            onContainer = MaterialTheme.colorScheme.onSecondaryContainer
        }
    }

    Surface(
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = label
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = onContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = onContainer,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private enum class SettingsRoute { Hub, KidMode, MultiKid, ScreenTimeApps, RemoteReport, Paywall, Tamper, Appearance, Permissions, Help }

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    openPaywallSignal: Boolean = false,
    onPaywallConsumed: () -> Unit = {},
) {
    var route by remember { mutableStateOf(SettingsRoute.Hub) }
    val coachMarks = com.shantanu.shield.ui.LocalCoachMarks.current
    val requestTab = com.shantanu.shield.ui.LocalRequestTab.current
    // A gated control on another tab asked for the paywall → land on it.
    LaunchedEffect(openPaywallSignal) {
        if (openPaywallSignal) { route = SettingsRoute.Paywall; onPaywallConsumed() }
    }
    // Scope lives on SettingsScreen, which stays mounted across Hub<->detail
    // navigation (only the inner `route` swaps) — so a delayed tour start here
    // survives returning to the Hub.
    val scope = rememberCoroutineScope()
    val back: () -> Unit = { route = SettingsRoute.Hub }

    CompositionLocalProvider(
        com.shantanu.shield.ui.LocalRequestPaywall provides { route = SettingsRoute.Paywall }
    ) {
    when (route) {
        SettingsRoute.KidMode -> KidModeScreen(viewModel, onBack = back, onOpenMultiKid = { route = SettingsRoute.MultiKid })
        SettingsRoute.MultiKid -> SettingsDetailScreen("Multiple Kids", onBack = back) {
            com.shantanu.shield.ui.profiles.MultiKidSection(viewModel)
        }
        SettingsRoute.ScreenTimeApps -> SettingsDetailScreen("What counts as screen time", onBack = back) {
            // ParentGate is the whole point on a kid-owned phone: the parent unlocks with their face,
            // the child cannot. On a parent-owned phone it's a pass-through.
            ParentGate {
                com.shantanu.shield.ui.stats.ScreenTimeAppsSection()
            }
        }
        SettingsRoute.RemoteReport -> com.shantanu.shield.ui.parent.ParentSetupScreen(onBack = back)
        SettingsRoute.Paywall -> com.shantanu.shield.ui.paywall.PaywallScreen(onClose = back)
        SettingsRoute.Tamper -> SettingsDetailScreen("Tamper Protection", onBack = back) {
            TamperProtectionSection(viewModel)
        }
        SettingsRoute.Appearance -> SettingsDetailScreen("Appearance", onBack = back) {
            AppearanceBody(viewModel)
        }
        SettingsRoute.Permissions -> SettingsDetailScreen("Permissions", onBack = back) {
            PermissionDashboard()
        }
        SettingsRoute.Help -> SettingsDetailScreen("Help & Onboarding", onBack = back) {
            HelpOnboardingSection(
                viewModel = viewModel,
                onReplayWelcomeTour = {
                    // Re-arm + jump to Protect; MainScreen's effect starts the tour.
                    viewModel.replayTour(com.shantanu.shield.ui.CoachTours.FIRST_RUN_ID)
                    requestTab?.invoke(1)
                },
                onReplayStatsTour = {
                    // Straight to the full manager rather than replaying the dashboard tour: the
                    // tour points at the ⋮ on the parent dashboard, which doesn't exist on a
                    // kid-owned phone. This screen works on both.
                    route = SettingsRoute.ScreenTimeApps
                },
                onReplaySettingsTour = {
                    // Return to the Hub (where the tour targets live), then start.
                    route = SettingsRoute.Hub
                    scope.launch {
                        delay(450)
                        coachMarks?.start(
                            com.shantanu.shield.ui.CoachTours.SETTINGS_ID,
                            com.shantanu.shield.ui.CoachTours.SETTINGS
                        )
                    }
                }
            )
        }
        SettingsRoute.Hub -> SettingsHub(
            viewModel = viewModel,
            onNavigate = { route = it },
            onPlusClick = { route = SettingsRoute.Paywall }
        )
    }
    }
}

/**
 * Settings "hub" — a short, scannable directory (WhatsApp/iOS style): a Plus hero,
 * then grouped icon-rows that each drill into a focused detail screen. Replaces the
 * old single long scroll so no one screen is overloaded; every feature is one tap away.
 */
@Composable
private fun SettingsHub(
    viewModel: MainViewModel,
    onNavigate: (SettingsRoute) -> Unit,
    onPlusClick: () -> Unit
) {
    val ownerType by viewModel.ownerType.collectAsState(initial = "parent")
    val scrollState = rememberScrollState()
    val coachMarks = com.shantanu.shield.ui.LocalCoachMarks.current
    val density = LocalDensity.current

    // Keep the active Settings-tour target comfortably in view as steps advance.
    LaunchedEffect(coachMarks?.currentIndex, coachMarks?.activeTourId) {
        if (coachMarks?.activeTourId != com.shantanu.shield.ui.CoachTours.SETTINGS_ID) return@LaunchedEffect
        val step = coachMarks.steps.getOrNull(coachMarks.currentIndex) ?: return@LaunchedEffect
        val tid = step.targetId
        if (tid == null) {
            scrollState.animateScrollTo(0)
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(120)
        val targetTop = coachMarks.targets[tid]?.top ?: return@LaunchedEffect
        val desiredTopPx = with(density) { 140.dp.toPx() }
        val delta = (targetTop - desiredTopPx).toInt()
        val newScroll = (scrollState.value + delta).coerceIn(0, scrollState.maxValue)
        scrollState.animateScrollTo(newScroll)
    }

    val kidSubtitle = if (ownerType == "kid")
        "On — daily budget + night-time lock"
    else
        "Off — tap to set up budget & allowed apps"

    val multiKidEnabled by viewModel.multiKidEnabled.collectAsState(initial = false)
    val kidFaces by viewModel.kidFaceEmbeddings.collectAsState(initial = emptyMap())
    val multiKidSubtitle = when {
        multiKidEnabled && kidFaces.size >= 2 -> "On — each kid unlocks with their own face"
        multiKidEnabled -> "On — enrol both kids' faces to activate"
        else -> "Set up a shared phone for two kids"
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(20.dp)) {
        com.shantanu.shield.ui.paywall.PlusEntryCard(
            onClick = onPlusClick,
            promoActive = viewModel.promoActive.collectAsState(initial = true).value,
        )
        Spacer(Modifier.height(28.dp))

        SettingsSectionHeader("Protection")
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.coachTarget("settings-kid-mode")) {
            SettingsHubRow(
                icon = Icons.Default.Face,
                title = "Kid Mode",
                subtitle = kidSubtitle,
                badge = "For your kid's own phone",
                onClick = { onNavigate(SettingsRoute.KidMode) }
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.coachTarget("settings-multikid")) {
            SettingsHubRow(
                icon = Icons.Default.Person,
                title = "Multiple Kids",
                subtitle = multiKidSubtitle,
                badge = "Shared phone · 2 kids",
                onClick = { onNavigate(SettingsRoute.MultiKid) }
            )
        }
        Spacer(Modifier.height(12.dp))
        SettingsHubRow(
            icon = Icons.Default.Share,
            title = "Remote report",
            subtitle = "See your child's weekly screen time on your phone — private & encrypted",
            badge = "Beta",
            onClick = { onNavigate(SettingsRoute.RemoteReport) }
        )
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.coachTarget("settings-tamper")) {
            SettingsHubRow(
                icon = Icons.Default.Lock,
                title = "Tamper Protection",
                subtitle = "Lock settings, app & uninstall",
                onClick = { onNavigate(SettingsRoute.Tamper) }
            )
        }
        Spacer(Modifier.height(28.dp))

        SettingsSectionHeader("Personalization")
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.coachTarget("settings-appearance")) {
            SettingsHubRow(
                icon = Icons.Default.Star,
                title = "Appearance",
                subtitle = "Theme & intruder feedback style",
                onClick = { onNavigate(SettingsRoute.Appearance) }
            )
        }
        Spacer(Modifier.height(28.dp))

        SettingsSectionHeader("Device & Help")
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.coachTarget("settings-permissions")) {
            SettingsHubRow(
                icon = Icons.Default.Settings,
                title = "Permissions",
                subtitle = "System access Kids Shield needs",
                onClick = { onNavigate(SettingsRoute.Permissions) }
            )
        }
        Spacer(Modifier.height(12.dp))
        SettingsHubRow(
            icon = Icons.Default.DateRange,
            title = "What counts as screen time",
            subtitle = "Remove clocks, wallpaper carousels and other phone utilities from the charts",
            onClick = { onNavigate(SettingsRoute.ScreenTimeApps) }
        )
        Spacer(Modifier.height(12.dp))
        SettingsHubRow(
            icon = Icons.Default.Info,
            title = "Help & Onboarding",
            subtitle = "Replay the guided tours",
            onClick = { onNavigate(SettingsRoute.Help) }
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** One tappable directory row: leading icon chip, title, status subtitle, chevron. */
@Composable
private fun SettingsHubRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                if (badge != null) {
                    Spacer(Modifier.height(6.dp))
                    // Highlighted hint pill — e.g. "for a phone that's the child's own".
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Phone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                badge,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** Small-caps group header — one consistent style for the whole hub. */
@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 0.8.sp
    )
}

/**
 * Generic detail sub-screen: publishes its title + back action to the app's single
 * top bar (same pattern as KidModeScreen) and hosts the supplied content in a scroll.
 */
@Composable
private fun SettingsDetailScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val topBarOverride = com.shantanu.shield.ui.LocalTopBarOverride.current
    DisposableEffect(title) {
        topBarOverride?.value = com.shantanu.shield.ui.TopBarOverride(title = title, onBack = onBack)
        onDispose { topBarOverride?.value = null }
    }
    BackHandler(onBack = onBack)
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        content()
    }
}

/** Appearance detail: accent Theme (Plus-gated, self-hides) + Intruder Feedback Style. */
@Composable
private fun AppearanceBody(viewModel: MainViewModel) {
    val themesUnlocked by viewModel.themesUnlocked.collectAsState(initial = false)
    com.shantanu.shield.ui.PremiumGate(themesUnlocked, "Themes", "Subscribe to unlock accent themes.") {
        com.shantanu.shield.ui.theme.ThemePickerSection(viewModel)
    }
    Spacer(Modifier.height(24.dp))
    IntruderFeedbackSection(viewModel)
}

/** The 3-way intruder feedback chooser (extracted from the old Settings list). */
@Composable
private fun IntruderFeedbackSection(viewModel: MainViewModel) {
    val lockMessageType by viewModel.lockMessageType.collectAsState(initial = 0)
    Text("Intruder Feedback Style", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "What a protected app shows when someone other than you tries to open it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            StyleOption("Hardware Fault", "Simulate a broken module", lockMessageType == 0, Icons.Default.Warning) { viewModel.setLockMessageType(0) }
            StyleOption("Wellness Guide", "Polite eye health warning", lockMessageType == 1, Icons.Default.Favorite) { viewModel.setLockMessageType(1) }
            StyleOption("Spiritual Guide", "Shri Premanand Ji's advice", lockMessageType == 2, Icons.Default.AccountCircle) { viewModel.setLockMessageType(2) }
        }
    }
}

@Composable
private fun HelpOnboardingSection(
    viewModel: MainViewModel,
    onReplayWelcomeTour: () -> Unit,
    onReplaySettingsTour: () -> Unit,
    onReplayStatsTour: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column {
            Surface(
                onClick = onReplayWelcomeTour,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Replay welcome tour", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Walk through the welcome tour again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Surface(
                onClick = onReplaySettingsTour,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Replay Settings tour", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Re-explain Kid Mode, Tamper Protection, Appearance, and Permissions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Surface(
                onClick = onReplayStatsTour,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fix wrong screen time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Seeing the Clock, a wallpaper carousel, or another phone utility in " +
                                "your charts? Choose what counts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

// ============================================================================
// Kid Mode setup screen. Lives inside the Setup tab (sub-screen). Top of the
// screen is a single switch "Enable Kid Mode" that maps to ownerType ∈ {parent,
// kid} in DataStore. When enabled, the existing budget slider + always-allowed
// preset selector + custom-app picker + today usage card render below.
// ============================================================================
@Composable
private fun KidModeScreen(viewModel: MainViewModel, onBack: () -> Unit, onOpenMultiKid: () -> Unit = {}) {
    val ownerType by viewModel.ownerType.collectAsState(initial = "parent")
    val isKidEnabled = ownerType == "kid"
    val lockOwnApp by viewModel.lockOwnApp.collectAsState(initial = false)
    val faceEmbedding by viewModel.faceEmbedding.collectAsState(initial = null)
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    var showSelfLockDialog by remember { mutableStateOf(false) }

    val requestTab = com.shantanu.shield.ui.LocalRequestTab.current
    if (showSelfLockDialog) {
        val faceEnrolled = faceEmbedding != null
        AlertDialog(
            onDismissRequest = { showSelfLockDialog = false },
            shape = RoundedCornerShape(24.dp),
            icon = {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = { Text("Protect Kids Shield itself?", fontWeight = FontWeight.Bold) },
            text = {
                val body = if (faceEnrolled) {
                    "Kid Mode is now on. Lock Kids Shield behind Face ID so your kid can't open this app and disable budgets, allowed apps, or tamper protection."
                } else {
                    "Kid Mode is now on. To stop your kid from opening this app and disabling settings, we recommend locking Kids Shield behind Face ID. You'll need to enrol your face first."
                }
                Text(body, style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSelfLockDialog = false
                        if (faceEnrolled) {
                            viewModel.setLockOwnApp(true)
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar("Kids Shield now requires Face ID")
                            }
                        } else {
                            requestTab?.invoke(0)
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar("Enrol your face, then enable Protect This App in Settings")
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp)
                ) { Text(if (faceEnrolled) "Lock it" else "Set up Face ID") }
            },
            dismissButton = {
                TextButton(onClick = { showSelfLockDialog = false }) { Text("Not now") }
            }
        )
    }

    val topBarOverride = com.shantanu.shield.ui.LocalTopBarOverride.current
    DisposableEffect(Unit) {
        topBarOverride?.value = com.shantanu.shield.ui.TopBarOverride(
            title = "Kid Mode",
            onBack = onBack
        )
        onDispose { topBarOverride?.value = null }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            // The budget and the night lock are enforced only by flags in the foreground service. If
            // either is converted to premium, the service stops acting while every control below still
            // reads "on" — so say it here, at the top of the screen that claims to configure them.
            val budgetUnlocked by viewModel.kidBudgetUnlocked.collectAsState(initial = true)
            val nightUnlocked by viewModel.nightLockUnlocked.collectAsState(initial = true)
            // Shown whether or not Kid Mode is currently on. Gating this on `isKidEnabled` was wrong:
            // a parent setting Kid Mode up needs to know the limit won't be enforced BEFORE they switch
            // it on and start relying on it — being told only afterwards is the same silent failure this
            // banner exists to prevent. The wording adapts instead.
            com.shantanu.shield.ui.LapseBanner(
                locked = !budgetUnlocked,
                title = if (isKidEnabled) "Daily limit is not being enforced"
                        else "Daily limit needs Plus",
                body = if (isKidEnabled) {
                    "Kid Mode is on, but the daily screen-time limit needs Kids Shield Plus. Until it " +
                        "is unlocked, your child's screen time is not being capped."
                } else {
                    "The daily screen-time limit needs Kids Shield Plus. Turning Kid Mode on will not " +
                        "cap screen time until it is unlocked."
                },
            )
            com.shantanu.shield.ui.LapseBanner(
                locked = budgetUnlocked && !nightUnlocked,
                title = if (isKidEnabled) "Night lock is not being enforced" else "Night lock needs Plus",
                body = "The 10 PM – 7 AM lock needs Kids Shield Plus. Until it is unlocked, apps stay " +
                    "usable overnight.",
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable Kid Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Enforce a daily screen-time budget and a fixed night-time lock (22:00–07:00) on Play Store apps.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = isKidEnabled,
                        onCheckedChange = { on ->
                            viewModel.setOwnerType(if (on) "kid" else "parent")
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar(
                                    if (on) "Kid Mode enabled · daily budget active"
                                    else "Kid Mode disabled"
                                )
                            }
                            if (on && !lockOwnApp) {
                                showSelfLockDialog = true
                            }
                        }
                    )
                }
            }
            if (isKidEnabled) {
                Spacer(Modifier.height(24.dp))
                KidProtectBody(viewModel, onOpenMultiKid = onOpenMultiKid)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun TamperProtectionSection(viewModel: MainViewModel, forceExpanded: Boolean = false) {
    val context = LocalContext.current
    val lockDeviceSettings by viewModel.lockDeviceSettings.collectAsState(initial = false)
    val lockOwnApp by viewModel.lockOwnApp.collectAsState(initial = false)
    val faceEmbedding by viewModel.faceEmbedding.collectAsState(initial = null)
    var adminActive by remember { mutableStateOf(TamperProtection.isAdminActive(context)) }
    var userExpanded by remember { mutableStateOf(false) }
    val expanded = userExpanded || forceExpanded

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                adminActive = TamperProtection.isAdminActive(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val faceEnrolled = faceEmbedding != null
    val activeCount = listOf(lockDeviceSettings, lockOwnApp && faceEnrolled, adminActive).count { it }
    val maxCount = 3
    val simpleAllOn = lockDeviceSettings && lockOwnApp && faceEnrolled

    // Tamper protection's side-door challenge is a service flag. Locked, the switches below still read
    // "on" while the protection they describe is not running.
    val tamperOn by viewModel.tamperUnlocked.collectAsState(initial = true)
    com.shantanu.shield.ui.LapseBanner(
        locked = !tamperOn && activeCount > 0,
        title = "Tamper protection is not active",
        body = "These switches are on, but blocking the routes around Kids Shield needs Plus. Until " +
            "it is unlocked, a child can reach them.",
    )

    Text(
        "Stop a child from uninstalling the app or changing its settings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    Spacer(Modifier.height(12.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (activeCount == maxCount)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(
                    if (activeCount == maxCount)
                        Color(0xFF4CAF50).copy(alpha = 0.15f)
                    else
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (activeCount == maxCount) Icons.Default.CheckCircle else Icons.Default.Lock,
                    contentDescription = null,
                    tint = if (activeCount == maxCount) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        activeCount == maxCount -> "Maximum protection"
                        activeCount > 0 -> "Partial protection"
                        else -> "Lock down everything"
                    },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "$activeCount of $maxCount protections active",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Switch(
                checked = simpleAllOn,
                enabled = faceEnrolled,
                onCheckedChange = { on ->
                    viewModel.setLockDeviceSettings(on)
                    if (faceEnrolled) viewModel.setLockOwnApp(on)
                }
            )
        }
    }

    Spacer(Modifier.height(4.dp))
    TextButton(onClick = { userExpanded = !userExpanded }) {
        Text(
            if (expanded) "Hide advanced" else "Show advanced",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null
        )
    }

    if (expanded) {
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.coachTarget("tamper-lock-settings")) {
                TamperCard(
                    title = "Lock System Settings",
                    description = "Require Face ID to open device Settings (Force-Stop, app permissions, uninstall).",
                    icon = Icons.Default.Settings,
                    checked = lockDeviceSettings,
                    onCheckedChange = { viewModel.setLockDeviceSettings(it) }
                )
            }
            Box(modifier = Modifier.coachTarget("tamper-protect-app")) {
                TamperCard(
                    title = "Protect This App",
                    description = if (!faceEnrolled)
                        "Enrol your Face ID first."
                    else
                        "Require Face ID to open Kids Shield itself.",
                    icon = Icons.Default.Lock,
                    checked = lockOwnApp,
                    enabled = faceEnrolled,
                    onCheckedChange = { viewModel.setLockOwnApp(it) }
                )
            }
            Box(modifier = Modifier.coachTarget("tamper-prevent-uninstall")) {
                TamperCard(
                    title = "Prevent Uninstall (Device Admin)",
                    description = "Register as Device Admin so the app can't be uninstalled.",
                    icon = Icons.Default.Warning,
                    checked = adminActive,
                    onCheckedChange = { turnOn ->
                        if (turnOn) {
                            context.startActivity(TamperProtection.enableAdminIntent(context))
                        } else {
                            TamperProtection.disableAdmin(context)
                            adminActive = false
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun TamperCard(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
fun StyleOption(title: String, subtitle: String, selected: Boolean, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}

@Composable
fun UsageDashboardScreen(viewModel: MainViewModel) {
    val usageStats by viewModel.usageStats.collectAsState()
    val context = LocalContext.current
    var selectedDay by remember { mutableIntStateOf(0) }
    var usageGranted by remember { mutableStateOf(isUsageStatsPermissionGranted(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                usageGranted = isUsageStatsPermissionGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = selectedDay,
            edgePadding = 20.dp,
            containerColor = Color.Transparent,
            divider = {},
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedDay]),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        ) {
            listOf("Today", "Yesterday", "2 Days", "3 Days", "4 Days").forEachIndexed { index, label ->
                Tab(
                    selected = selectedDay == index,
                    onClick = {
                        selectedDay = index
                        viewModel.fetchUsageStats(index)
                    },
                    text = { Text(label, fontWeight = if (selectedDay == index) FontWeight.Bold else FontWeight.Normal) }
                )
            }
        }

        when {
            !usageGranted -> EmptyUsageState(
                icon = Icons.Default.Warning,
                title = "Usage Access required",
                body = "Grant Usage Access permission to see which apps were used and for how long.",
                actionLabel = "Open Settings",
                onAction = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            )
            usageStats.isEmpty() -> EmptyUsageState(
                icon = Icons.Default.Info,
                title = "No usage recorded",
                body = "No app activity tracked for this period yet."
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(usageStats) { stat ->
                    UsageItem(stat)
                }
            }
        }
    }
}

@Composable
fun UsageItem(stat: AppUsageInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(bitmap = stat.icon.toBitmap().asImageBitmap(), contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stat.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
            }
            Text(formatTime(stat.usageTimeMs), fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun EmptyUsageState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(icon, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = onAction, shape = RoundedCornerShape(14.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun PermissionDashboard() {
    val context = LocalContext.current
    var cameraGranted by remember { mutableStateOf(checkCameraPermission(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var usageGranted by remember { mutableStateOf(isUsageStatsPermissionGranted(context)) }
    var batteryBypassed by remember { mutableStateOf(isBatteryOptimizationBypassed(context)) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraGranted = it }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                cameraGranted = checkCameraPermission(context)
                overlayGranted = Settings.canDrawOverlays(context)
                usageGranted = isUsageStatsPermissionGranted(context)
                batteryBypassed = isBatteryOptimizationBypassed(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Text(
        "Grant these so Kids Shield keeps protecting around the clock.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
    Spacer(Modifier.height(12.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PermissionRow("Camera Access", cameraGranted) { cameraLauncher.launch(Manifest.permission.CAMERA) }
        PermissionRow("Overlay Window", overlayGranted) {
            Toast.makeText(context, "Please enable Overlay for our app", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            context.startActivity(intent)
        }
        PermissionRow("Activity Insight", usageGranted) {
            Toast.makeText(context, "Please grant Usage Access", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            context.startActivity(intent)
        }
        PermissionRow("Always On Service", batteryBypassed) {
            Toast.makeText(context, "Set to 'Don't Optimize' for 24/7 protection", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            context.startActivity(intent)
        }
        ActionRow("Auto-Start / Background", "Allow Kids Shield to launch on its own") {
            val intent = TamperProtection.autoStartIntent(context) ?: TamperProtection.appDetailsIntent(context)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                context.startActivity(TamperProtection.appDetailsIntent(context))
            }
        }
    }
}

@Composable
fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Open", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
fun PermissionRow(title: String, isGranted: Boolean, onGrant: () -> Unit) {
    Surface(
        onClick = if (!isGranted) onGrant else ({}),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning, null, tint = if (isGranted) Color(0xFF4CAF50) else Color.Red)
            Spacer(Modifier.width(12.dp))
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            if (!isGranted) Text("Enable", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectAppList(viewModel: MainViewModel) {
    val apps by viewModel.filteredApps.collectAsState()
    val protectedApps by viewModel.protectedApps.collectAsState(initial = emptySet())
    val searchQuery by viewModel.searchQuery.collectAsState()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 120.dp)
    ) {
        item(key = "title") {
            Column(modifier = Modifier.coachTarget("protect-item")) {
                Text(
                    "Apps that need face unlock",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Usually used on the parent's phone: shield apps behind face authentication so a child can't open them. Lending your phone to your kid for a few minutes? Tap the Free Play button.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        item(key = "kid_phone_tip") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "If your kid has their own phone, activate Kid Mode under the Settings tab instead.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        item(key = "search_field") {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                )
            )
        }

        if (apps.isEmpty()) {
            item(key = "empty") {
                EmptyStateMessage(
                    if (searchQuery.isBlank()) "Loading installed apps…"
                    else "No apps match \"$searchQuery\"."
                )
            }
        } else {
            items(apps, key = { it.packageName }) { app ->
                val wasProtected = protectedApps.contains(app.packageName)
                AppShieldItem(app, wasProtected) {
                    viewModel.toggleAppProtection(app.packageName)
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(
                            if (wasProtected) "${app.name} no longer requires face unlock"
                            else "${app.name} now requires face unlock"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStateMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun AppShieldItem(app: AppInfo, isProtected: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(20.dp),
        color = if (isProtected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Image(
                    bitmap = app.icon.toBitmap().asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                )
                if (isProtected) {
                    Box(
                        modifier = Modifier
                            .offset(x = 4.dp, y = 4.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Protected",
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.SemiBold)
                if (isProtected) {
                    Text(
                        "Face unlock required",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Switch(checked = isProtected, onCheckedChange = { onToggle() })
        }
    }
}

// ============================================================================
// Protect tab — the simple version. The whole screen does ONE thing: let the
// parent pick which apps require face-unlock. A single FAB in the bottom-right
// triggers "Free Play" (temp-kid-mode); when a Free Play session is active the
// FAB is replaced by a pinned countdown banner. The Kid-mode budget/preset
// flow lives under Setup → Kid Mode (not on this tab).
// ============================================================================
@Composable
fun ProtectScreen(viewModel: MainViewModel) {
    val endAt by viewModel.kidSessionEndAt.collectAsState(initial = 0L)
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val isFreePlayActive = nowMs < endAt
    var showDialog by remember { mutableStateOf(false) }
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(endAt) {
        while (System.currentTimeMillis() < endAt) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
        nowMs = System.currentTimeMillis()
    }

    // App lock is enforced by a single service flag. If it locks, protected apps simply stop being
    // locked — the list below would still show them ticked. This is the only place that says otherwise.
    val appLockOn by viewModel.appLockUnlocked.collectAsState(initial = true)
    Column(modifier = Modifier.fillMaxSize()) {
        com.shantanu.shield.ui.LapseBanner(
            locked = !appLockOn,
            title = "Protected apps are not being locked",
            body = "App lock needs Kids Shield Plus. The apps below are still listed, but they will " +
                "open without a face scan until it is unlocked.",
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Box(modifier = Modifier.fillMaxWidth().coachTarget("status-banner")) {
            StatusBanner(viewModel)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ProtectAppList(viewModel)

            if (isFreePlayActive) {
                val remainingMs = (endAt - nowMs).coerceAtLeast(0L)
                FreePlayActiveBanner(
                    remainingMs = remainingMs,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                )
            } else {
                ExtendedFloatingActionButton(
                    onClick = { showDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp)
                        .coachTarget("free-play-fab"),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    text = { Text("Free Play", fontWeight = FontWeight.SemiBold) }
                )
            }
        }
    }

    if (showDialog) {
        FreePlayStartDialog(
            onDismiss = { showDialog = false },
            onConfirm = { minutes ->
                viewModel.startKidSession(minutes)
                showDialog = false
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar("Free Play started · $minutes min")
                }
            }
        )
    }
}

@Composable
private fun FreePlayActiveBanner(remainingMs: Long, modifier: Modifier = Modifier) {
    val remainingMin = remainingMs / 60_000L
    val remainingSec = (remainingMs % 60_000L) / 1000L
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Free Play active",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "%d min %02d sec remaining".format(remainingMin, remainingSec),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FreePlayStartDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val presets = listOf(10, 20, 30, 60)
    var selectedPreset by remember { mutableIntStateOf(30) }
    var customText by remember { mutableStateOf("") }
    val customMin = customText.toIntOrNull()
    val effectiveMinutes = customMin ?: selectedPreset
    val canConfirm = effectiveMinutes in 1..240

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hand the phone to your kid") },
        text = {
            Column {
                Text(
                    "Use this when handing your phone to your kid for a short time. Every app runs without face-unlock; protection resumes automatically when the timer expires. FaceShield itself stays locked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(16.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    presets.forEach { min ->
                        FilterChip(
                            selected = customMin == null && selectedPreset == min,
                            onClick = {
                                selectedPreset = min
                                customText = ""
                            },
                            label = { Text("$min min") }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = customText,
                    onValueChange = { input -> customText = input.filter { it.isDigit() }.take(3) },
                    label = { Text("Custom (1 – 240 min)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(effectiveMinutes) }, enabled = canConfirm) {
                Text("Start ($effectiveMinutes min)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun KidProtectBody(viewModel: MainViewModel, onOpenMultiKid: () -> Unit = {}) {
    val context = LocalContext.current
    val dailyLimitMinutes by viewModel.dailyLimitMinutes.collectAsState(initial = 60)
    // With Multiple Kids on, enforcement uses each kid's OWN limit (set in the Multiple Kids screen);
    // this single global slider has no effect then, so we redirect instead of showing a dead control.
    val multiKidEnabled by viewModel.multiKidEnabled.collectAsState(initial = false)
    val preset by viewModel.alwaysAllowedPreset.collectAsState(initial = 0)
    val presetsUnlocked by viewModel.allowedPresetsUnlocked.collectAsState(initial = true)
    val customAllowed by viewModel.customAlwaysAllowed.collectAsState(initial = emptySet())
    val usedMs by viewModel.screenTimeUsedMs.collectAsState(initial = 0L)
    val extensionsMs by viewModel.extensionsTodayMs.collectAsState(initial = 0L)
    val installedApps by viewModel.installedApps.collectAsState()

    // Resolve default phone & SMS once per recomposition pass. Cheap — just two
    // PackageManager.resolveActivity calls — and avoids putting OEM-specific names
    // in the data layer.
    val defaultPhonePkg = remember(installedApps) {
        com.shantanu.shield.util.AllowedApps.resolveDefaultPhonePackage(context)
    }
    val defaultSmsPkg = remember(installedApps) {
        com.shantanu.shield.util.AllowedApps.resolveDefaultSmsPackage(context)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Make the allow-list model explicit: a kid's phone locks EVERYTHING except the always-allowed
        // apps once the budget runs out (or during a night/schedule lock) — the inverse of the parent
        // "Protect" tab, which is hidden in Kid Mode. Without this the controlled set is invisible.
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "When the daily budget runs out — and during any night or schedule lock — every app locks " +
                        "except the always-allowed ones below. Phone and emergency calls always work.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Daily screen-time budget",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
            Spacer(Modifier.height(8.dp))
            Text(
                "Total minutes per day across Play Store apps. Resets at 07:00 each day.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))
            if (multiKidEnabled) {
                // Multiple Kids on → each child has their own daily limit. Send the parent there so the
                // change actually takes effect (the global slider is ignored by per-kid enforcement).
                Card(
                    onClick = onOpenMultiKid,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Set each kid's limit", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text(
                                "Multiple Kids is on, so this overall slider doesn't apply. Tap to set each child's daily limit (and grant extra time).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "${dailyLimitMinutes} min",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Slider(
                            value = dailyLimitMinutes.toFloat(),
                            onValueChange = { viewModel.setDailyLimitMinutes(it.toInt()) },
                            valueRange = 15f..240f,
                            steps = ((240 - 15) / 15) - 1
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("15 min", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text("240 min", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                "Always-allowed apps",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Apps that never count against the budget and stay usable even after the budget is exhausted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    PresetOption(
                        "A. Phone + Messages",
                        buildPresetASubtitle(context, defaultPhonePkg, defaultSmsPkg),
                        preset == 0
                    ) { viewModel.setAlwaysAllowedPreset(0) }
                    PresetOption(
                        "B. Phone + Messages + WhatsApp",
                        buildPresetBSubtitle(context, defaultPhonePkg, defaultSmsPkg),
                        preset == 1
                    ) { viewModel.setAlwaysAllowedPreset(1) }
                    PresetOption(
                        "C. Custom selection",
                        if (customAllowed.isEmpty()) "Pick any installed Play Store apps below"
                        else "${customAllowed.size} app(s) chosen — tap to edit below",
                        preset == 2,
                        locked = !presetsUnlocked
                    ) { viewModel.setAlwaysAllowedPreset(2) }
                }
            }

            // Custom picker only shows when preset C is selected AND the Custom picker is unlocked.
            // (Default-free; only hidden if ALLOWED_PRESETS has been converted to premium.)
            if (preset == 2 && presetsUnlocked) {
                Spacer(Modifier.height(16.dp))
                // Note that system apps (phone, messages, settings, camera, clock, etc.)
                // are always unrestricted in Kid mode and are not shown in this list.
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "System apps (Phone, Messages, Settings, Camera, Clock, etc.) are always unrestricted by default and are not shown here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Pick the apps that should remain unrestricted even budget is exhausted:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                // Search box for filtering the long installed-apps list. Local state — no
                // need to plumb through the ViewModel since the picker is the only consumer.
                var pickerQuery by remember { mutableStateOf("") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pickerQuery,
                    onValueChange = { pickerQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search apps...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (pickerQuery.isNotEmpty()) {
                            IconButton(onClick = { pickerQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )

                Spacer(Modifier.height(8.dp))
                // Group apps by category for easier scanning. Searching collapses to flat
                // results across all categories; empty categories are hidden.
                val groupedForPicker = remember(installedApps, customAllowed, pickerQuery) {
                    val q = pickerQuery.trim()
                    val filtered = if (q.isEmpty()) installedApps
                        else installedApps.filter { it.name.contains(q, ignoreCase = true) }
                    val sorted = filtered.sortedWith(
                        compareByDescending<AppInfo> { customAllowed.contains(it.packageName) }
                            .thenBy { it.name.lowercase() }
                    )
                    AppCategory.values()
                        .sortedBy { it.order }
                        .mapNotNull { cat ->
                            val catApps = sorted.filter { it.category == cat }
                            if (catApps.isEmpty()) null else cat to catApps
                        }
                }
                val totalVisible = groupedForPicker.sumOf { it.second.size }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                        when {
                            installedApps.isEmpty() -> Text(
                                "Loading installed apps…",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            totalVisible == 0 -> Text(
                                "No apps match \"$pickerQuery\".",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            else -> groupedForPicker.forEach { (category, apps) ->
                                Text(
                                    category.label,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                apps.forEach { app ->
                                    AllowedAppRow(
                                        app = app,
                                        isAllowed = customAllowed.contains(app.packageName),
                                        onToggle = { viewModel.toggleCustomAlwaysAllowed(app.packageName) }
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                "Today",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            run {
                val usedMin = (usedMs / 60_000L).toInt()
                val extensionMin = (extensionsMs / 60_000L).toInt()
                val effectiveLimit = dailyLimitMinutes + extensionMin
                val progress = if (effectiveLimit > 0)
                    (usedMin.toFloat() / effectiveLimit).coerceIn(0f, 1f) else 0f
                val remaining = (effectiveLimit - usedMin).coerceAtLeast(0)
                val overLimit = usedMin >= effectiveLimit
                val overByMin = (usedMin - effectiveLimit).coerceAtLeast(0)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (overLimit)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "$usedMin / $effectiveLimit min used",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (overLimit) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            if (overLimit) {
                                Surface(
                                    color = MaterialTheme.colorScheme.error,
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        "Over budget",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onError,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            color = if (overLimit) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary,
                            trackColor = if (overLimit)
                                MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (overLimit)
                                "$overByMin min over the budget" +
                                    (if (extensionMin > 0) "  •  +$extensionMin min extensions" else "")
                            else
                                "$remaining min remaining" +
                                    (if (extensionMin > 0) "  •  +$extensionMin min extensions" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overLimit) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.outline,
                            fontWeight = if (overLimit) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Updates every minute. Resets at 07:00 each day.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        Spacer(Modifier.height(16.dp))
        BudgetExtensionCard(
            extensionMin = (extensionsMs / 60_000L).toInt(),
            onGrant = { mins -> viewModel.grantExtension(mins) }
        )
        Spacer(Modifier.height(16.dp))
        com.shantanu.shield.ui.earn.EarnedTimeSection(viewModel)

        Spacer(Modifier.height(16.dp))
        com.shantanu.shield.ui.schedules.SchedulesSection(viewModel)

        Spacer(Modifier.height(16.dp))
        com.shantanu.shield.ui.perapp.PerAppLimitsSection(viewModel)

        Spacer(Modifier.height(16.dp))
        com.shantanu.shield.ui.newapp.NewAppBlockSection(viewModel)

        Spacer(Modifier.height(24.dp))
        Text(
            "Kid Mode is active. Non-allowed apps require parent face auth when the daily limit is over or during 22:00-07:00. Lock screen shows a kid-specific message about screen-time impact on eyes, brain, and concentration.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

// Parent-only control to grant extra screen time once the daily budget is hit. The whole
// Kid Mode tab sits behind the "Protect This App" face lock when Kid Mode is on, so these
// buttons are already gated from the kid. Extensions are additive and reset at 07:00.
@Composable
private fun BudgetExtensionCard(extensionMin: Int, onGrant: (Int) -> Unit) {
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Extend today's budget", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (extensionMin > 0)
                    "Granted +$extensionMin min extra today. Tap to add more. Resets at 07:00."
                else
                    "Add extra minutes for today when the daily limit runs out. Resets at 07:00.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(15, 30, 60).forEach { mins ->
                    FilledTonalButton(
                        onClick = {
                            onGrant(mins)
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar("Added +$mins min to today's budget")
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("+$mins") }
                }
            }
        }
    }
}

@Composable
internal fun PresetOption(title: String, subtitle: String, selected: Boolean, locked: Boolean = false, onClick: () -> Unit) {
    // Locked → tapping the row (or radio) opens the paywall instead of selecting it.
    val openPaywall = com.shantanu.shield.ui.LocalRequestPaywall.current
    val tap: () -> Unit = if (locked) openPaywall else onClick
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { tap() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = tap, enabled = !locked)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        if (locked) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(50)) {
                Text(
                    "Plus",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

@Composable
internal fun AllowedAppRow(app: AppInfo, isAllowed: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(14.dp),
        color = if (isAllowed) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
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
                    if (isAllowed) "Always allowed" else "Counts against budget",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Switch(checked = isAllowed, onCheckedChange = { onToggle() })
        }
    }
}

private fun buildPresetASubtitle(context: Context, phonePkg: String?, smsPkg: String?): String {
    val phone = phonePkg?.let { com.shantanu.shield.util.AllowedApps.labelFor(context, it) } ?: "(no default phone)"
    val sms = smsPkg?.let { com.shantanu.shield.util.AllowedApps.labelFor(context, it) } ?: "(no default SMS)"
    return "Allowed: $phone, $sms"
}

private fun buildPresetBSubtitle(context: Context, phonePkg: String?, smsPkg: String?): String {
    val phone = phonePkg?.let { com.shantanu.shield.util.AllowedApps.labelFor(context, it) } ?: "(no default phone)"
    val sms = smsPkg?.let { com.shantanu.shield.util.AllowedApps.labelFor(context, it) } ?: "(no default SMS)"
    return "Allowed: $phone, $sms, WhatsApp"
}

@Composable
fun FaceEnrollmentScreen(
    viewModel: MainViewModel,
    title: String = "Face ID Registration",
    isEnrolled: Boolean? = null,
    onEmbedding: ((FloatArray) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val faceEmbedding by viewModel.faceEmbedding.collectAsState(initial = null)
    // Default to the parent enrolment state unless a caller (e.g. kid enrolment) overrides it.
    val enrolled = isEnrolled ?: (faceEmbedding != null)
    val faceRecognitionManager = remember { FaceRecognitionManager(context) }

    var isEnrolling by remember { mutableStateOf(false) }
    var enrollmentStatus by remember { mutableStateOf("Position your face") }

    // Release the camera the moment enrolment ends (isEnrolling → false) or the screen leaves composition,
    // instead of leaving it bound to the Activity lifecycle (the same leak fixed in AppFaceGate).
    val cameraProviderRef = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    DisposableEffect(isEnrolling) {
        onDispose { try { cameraProviderRef[0]?.unbindAll() } catch (_: Exception) {} }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) isEnrolling = true }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(32.dp))

        Box(modifier = Modifier.size(280.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).shadow(10.dp, CircleShape), contentAlignment = Alignment.Center) {
            if (isEnrolling) {
                AndroidView(factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        cameraProviderRef[0] = cameraProvider   // so onDispose can release the camera
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val imageAnalyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
                            it.setAnalyzer(Dispatchers.Default.asExecutor()) { imageProxy ->
                                coroutineScope.launch {
                                    val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
                                    imageProxy.close()
                                    if (bitmap != null) {
                                        val faceBitmap = faceRecognitionManager.detectFace(bitmap)
                                        if (faceBitmap != null) {
                                            val emb = faceRecognitionManager.getEmbedding(faceBitmap)
                                            if (onEmbedding != null) onEmbedding(emb) else viewModel.saveFaceEmbedding(emb)
                                            isEnrolling = false
                                            enrollmentStatus = "Verified & Registered!"
                                        }
                                    }
                                }
                            }
                        }
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalyzer)
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }, modifier = Modifier.fillMaxSize())
            } else {
                Icon(if (enrolled) Icons.Default.CheckCircle else Icons.Default.AccountCircle, null, modifier = Modifier.size(100.dp), tint = if (enrolled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(48.dp))
        Button(onClick = { if (checkCameraPermission(context)) isEnrolling = true else cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
            Text(if (!enrolled) "Setup Face ID" else "Update Biometrics", fontWeight = FontWeight.Bold)
        }
        Text(enrollmentStatus, modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
    }
}
