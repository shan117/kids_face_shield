package com.shantanu.shield.overlay

import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.shantanu.shield.R
import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.face.FaceRecognitionManager
import com.shantanu.shield.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.*

private const val REQUIRED_CONSECUTIVE_MATCHES = 3
// Camera self-heal: if no analyzer frame arrives within this long while the camera should be scanning, the
// front camera is likely contended (by AppFaceGate / the service overlay / a prior LockActivity) and stuck.
// We then force a fresh rebind — capped, so a genuinely-unavailable camera can never churn (which is what
// caused the FGS-teardown freeze).
private const val CAMERA_STALL_MS = 3500L
private const val MAX_CAMERA_REBINDS = 4

@Composable
fun FaceLockOverlayContent(
    packageName: String,
    forcedMessageType: Int,
    isKidModeLock: Boolean = false,
    lockedByParent: Boolean = false,
    identifyMode: Boolean = false,
    // Kid-mode budget lock only: today's used time and the effective daily limit (base + any parent
    // extension). Both default to 0, which renders exactly as before — the figures are shown only when
    // the budget is genuinely exhausted, so a night lock or a per-app-limit lock is unchanged.
    budgetUsedMs: Long = 0L,
    budgetLimitMs: Long = 0L,
    // True when the kid-mode lock is the 22:00–07:00 night lock rather than an exhausted budget.
    // Night lock is evaluated first in shouldLockForKidMode, so it wins the message too.
    isNightLock: Boolean = false,
    // Remote parent-lock only: false = default lock (Phone & Messages stay reachable), true = full lock.
    isFullLock: Boolean = false,
    // Parent-lock only: called with true when the parent taps "Unlock with parent's face" (service enters
    // camera-FGS mode + pauses the monitor churn), and false when the scan is cancelled / times out.
    onParentUnlockCamera: (Boolean) -> Unit = {},
    // Default parent-lock only: launch the default Phone / Messages app so the kid can still reach them.
    onOpenPhone: () -> Unit = {},
    onOpenMessages: () -> Unit = {},
    onAuthenticated: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    
    val faceRecognitionManager = remember { FaceRecognitionManager(context) }
    val dataStoreManager = remember { DataStoreManager(context) }

    var isAuthenticating by remember { mutableStateOf(false) }
    var isBlocked by remember { mutableStateOf(false) }

    // Tap-to-start local parent-face unlock for a remote lock. The camera stays OFF until the parent taps
    // "Unlock with parent's face"; it then runs a single time-boxed scan and fully releases. This avoids the
    // always-on camera bind that caused the FGS-teardown freeze. Only offered if a parent face is enrolled.
    var parentUnlockScanning by remember { mutableStateOf(false) }
    var canFaceUnlock by remember { mutableStateOf(false) }
    LaunchedEffect(lockedByParent) {
        if (lockedByParent) canFaceUnlock = dataStoreManager.faceEmbedding.first() != null
    }
    // Auto-stop the scan after 20s so a failed attempt never holds the camera open (battery + safety).
    LaunchedEffect(parentUnlockScanning) {
        if (parentUnlockScanning) {
            delay(20_000)
            if (parentUnlockScanning) { parentUnlockScanning = false; onParentUnlockCamera(false) }
        }
    }

    // Camera self-heal state. lastCameraFrameMs is a plain holder (written from the analyzer thread every
    // frame) so it never triggers recomposition; cameraRebindCount is Compose state that keys the camera
    // view, so bumping it recreates the AndroidView → a fresh CameraX bind (the automated "recents + back").
    val lastCameraFrameMs = remember { longArrayOf(0L) }
    var cameraRebindCount by remember { mutableStateOf(0) }
    val cameraShouldRun = !isBlocked && (!lockedByParent || parentUnlockScanning)
    LaunchedEffect(cameraRebindCount, cameraShouldRun) {
        if (!cameraShouldRun) return@LaunchedEffect
        lastCameraFrameMs[0] = System.currentTimeMillis()   // grace period after each (re)bind
        while (cameraShouldRun && !isBlocked && cameraRebindCount < MAX_CAMERA_REBINDS) {
            delay(1500)
            if (System.currentTimeMillis() - lastCameraFrameMs[0] > CAMERA_STALL_MS) {
                Log.w("AppLockOverlay", "No camera frames in ${CAMERA_STALL_MS}ms for $packageName — rebinding (self-heal #${cameraRebindCount + 1})")
                cameraRebindCount++   // re-keys the camera view; this effect re-arms
                return@LaunchedEffect
            }
        }
    }
    var showWarningAfterDelay by remember { mutableStateOf(false) }
    val matchStreak = remember { intArrayOf(0) }
    // Multiple-kids identify state (only used when identifyMode = true).
    val identifyStreak = remember { intArrayOf(0) }
    val identifyCandidate = remember { arrayOf<String?>(null) }
    // Blink-based liveness state for this lock session.
    // A genuine human looking at the camera will, within a couple of seconds, naturally have one
    // frame where their eyes are clearly open AND a frame where they're clearly closed (a blink).
    // A printed photo or static image never produces that transition.
    val livenessSeenOpen = remember { booleanArrayOf(false) }
    val livenessSeenClosed = remember { booleanArrayOf(false) }
    // Safety fallback: if ML Kit never returns a usable eye-open probability for this session
    // (rare — only on devices/lighting where classification fails) we don't want to lock the user
    // out forever. We count those "no signal" frames and, past a threshold, fall back to
    // streak-only unlock. Photos where ML Kit DOES classify (the common case) still get
    // blocked by the open+closed requirement.
    val framesWithNullEyeProb = remember { intArrayOf(0) }

    // TTS and Media Setup
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    
    DisposableEffect(Unit) {
        val ttsInstance = TextToSpeech(context) { }
        tts = ttsInstance
        onDispose {
            mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null
            ttsInstance.stop(); ttsInstance.shutdown()
        }
    }

    // Delay before showing warning (Genuine user window)
    LaunchedEffect(Unit) {
        Log.d("AppLockOverlay", "Overlay composed for $packageName; starting 1500ms genuine-user window")
        delay(1500)
        Log.d("AppLockOverlay", "1500ms elapsed for $packageName; isBlocked=$isBlocked -> showWarningAfterDelay=${!isBlocked}")
        if (!isBlocked && !identifyMode) showWarningAfterDelay = true
    }

    // A parent-lock has no camera/genuine-user window, so show its message immediately (not after 1.5s).
    val finalShowWarning = isBlocked || showWarningAfterDelay || lockedByParent

    // Voice trigger
    LaunchedEffect(finalShowWarning, forcedMessageType, tts) {
        if (finalShowWarning && !isKidModeLock && forcedMessageType == 2 && tts != null) {
            val resId = context.resources.getIdentifier("premanand_warning", "raw", context.packageName)
            if (resId != 0) {
                try {
                    val mp = MediaPlayer.create(context, resId)
                    mediaPlayer = mp
                    mp.start()
                } catch (e: Exception) { }
            } else {
                val ttsEngine = tts!!
                ttsEngine.language = Locale("hi", "IN")
                ttsEngine.setPitch(0.45f); ttsEngine.setSpeechRate(0.65f)
                val text = "क्या आपको पता है? ज़्यादा देर तक फ़ोन देखने से आपकी आँखें ख़राब हो सकती हैं। आपको चश्मे लग सकते हैं। आपकी एकाग्रता की शक्ति कम हो सकती है।"
                ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "KidAlert")
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (finalShowWarning) Color.Black else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        // CAMERA: Only runs if NOT authenticated and NOT permanently blocked.
        // NEVER for a remote parent-lock: that overlay is cleared by the parent's *remote* Unlock (the
        // message says "ask them to unlock it"), so it needs no face auth. Running CameraX here — and
        // binding/unbinding it on every overlay recreate while the persistent lock holds — thrashes the
        // camera and races the FGS CAMERA-type teardown, which can SIG-9 the process and leave the
        // full-screen overlay stuck on screen (the "phone freezes after unlock, needs a reboot" bug).
        if (cameraShouldRun) {
            key(cameraRebindCount) {
            Box(modifier = Modifier.size(1.dp).alpha(0f)) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                            val imageAnalyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
                                it.setAnalyzer(Dispatchers.Default.asExecutor()) { imageProxy ->
                                    lastCameraFrameMs[0] = System.currentTimeMillis()   // camera is alive → self-heal stays quiet
                                    if (!isAuthenticating && !isBlocked) {
                                        isAuthenticating = true
                                        coroutineScope.launch {
                                            val storedEmbedding = dataStoreManager.faceEmbedding.first()
                                            // In identify mode the parent face is optional (kids unlock with their own
                                            // face), so only bail on a missing parent embedding in the normal path.
                                            if (!identifyMode && storedEmbedding == null) { imageProxy.close(); isAuthenticating = false; return@launch }

                                            val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
                                            imageProxy.close()

                                            if (bitmap != null) {
                                                val detected = faceRecognitionManager.detectFaceWithEyes(bitmap)
                                                if (detected != null) {
                                                    val eyeProb = detected.leftEyeOpenProb ?: detected.rightEyeOpenProb

                                                    // Update liveness flags from this frame BEFORE deciding whether
                                                    // to skip it. A closed-eye frame is what we need to register a
                                                    // blink, even though we won't use it for the match itself.
                                                    if (eyeProb != null) {
                                                        if (eyeProb > 0.7f) livenessSeenOpen[0] = true
                                                        if (eyeProb < 0.3f) livenessSeenClosed[0] = true
                                                    } else {
                                                        framesWithNullEyeProb[0]++
                                                    }

                                                    // Skip frames where the face clearly has its eyes shut. We do NOT
                                                    // reset the streak here — a momentary blink in the middle of a good
                                                    // auth sequence shouldn't force the user to start over. If ML Kit
                                                    // couldn't classify eyes (eyeProb == null), we let the frame through.
                                                    if (eyeProb != null && eyeProb < 0.5f) {
                                                        Log.d("AppLockOverlay", "Eyes closed (prob=$eyeProb), skipping match for $packageName")
                                                    } else {
                                                        val currentEmbedding = faceRecognitionManager.getEmbedding(detected.bitmap)
                                                        val classificationDead = framesWithNullEyeProb[0] >= 30
                                                        val livenessOk = (livenessSeenOpen[0] && livenessSeenClosed[0]) || classificationDead
                                                        if (identifyMode) {
                                                            // Multiple-kids: identify WHICH kid is using the device, with a
                                                            // parent override. Parent face → unlock; a confident kid match →
                                                            // set the active profile, record a session, then allow (under
                                                            // budget) or stay locked (over budget / night).
                                                            val parentOk = storedEmbedding != null &&
                                                                faceRecognitionManager.isMatch(currentEmbedding, storedEmbedding)
                                                            if (parentOk) matchStreak[0]++ else matchStreak[0] = 0
                                                            if (parentOk && matchStreak[0] >= REQUIRED_CONSECUTIVE_MATCHES && livenessOk) {
                                                                onAuthenticated()
                                                            } else {
                                                                val gallery = dataStoreManager.kidFaceEmbeddings.first()
                                                                val res = com.shantanu.shield.face.FaceMatcher.identify(currentEmbedding, gallery)
                                                                val pid = res.profileId
                                                                if (pid != null && pid == identifyCandidate[0]) identifyStreak[0]++
                                                                else { identifyCandidate[0] = pid; identifyStreak[0] = if (pid != null) 1 else 0 }
                                                                Log.d("AppLockOverlay", "Identify for $packageName -> $pid streak=${identifyStreak[0]} score=${res.score} runnerUp=${res.runnerUp}")
                                                                if (pid != null && identifyStreak[0] >= REQUIRED_CONSECUTIVE_MATCHES && livenessOk) {
                                                                    val nowMs = System.currentTimeMillis()
                                                                    dataStoreManager.setActiveProfileId(pid)
                                                                    dataStoreManager.appendProfileSession(
                                                                        com.shantanu.shield.data.ProfileSession(pid, nowMs, nowMs)
                                                                    )
                                                                    val profile = dataStoreManager.kidProfiles.first().firstOrNull { it.id == pid }
                                                                    val lock = profile != null && com.shantanu.shield.kid.MultiKidEnforcement.shouldLock(
                                                                        profile.usedMs, profile.dailyLimitMinutes, profile.extensionsMs, isOverlayNightWindow(nowMs)
                                                                    )
                                                                    if (lock) isBlocked = true else onAuthenticated()
                                                                }
                                                            }
                                                        } else {
                                                            val matched = faceRecognitionManager.isMatch(currentEmbedding, storedEmbedding!!)
                                                            if (matched) matchStreak[0]++ else matchStreak[0] = 0
                                                            Log.d("AppLockOverlay", "Face for $packageName matched=$matched streak=${matchStreak[0]} eyeProb=$eyeProb liveness=$livenessOk")
                                                            if (matchStreak[0] >= REQUIRED_CONSECUTIVE_MATCHES && livenessOk) {
                                                                onAuthenticated()
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    matchStreak[0] = 0
                                                }
                                            }
                                            isAuthenticating = false
                                        }
                                    } else imageProxy.close()
                                }
                            }
                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalyzer)
                            } catch (e: Exception) { }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    }
                )
            }
            }
        }

        if (finalShowWarning) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                if (lockedByParent) {
                    ParentLockView(
                        scanning = parentUnlockScanning,
                        canFaceUnlock = canFaceUnlock,
                        showCommsAccess = !isFullLock,
                        onStartScan = { onParentUnlockCamera(true); parentUnlockScanning = true },
                        onCancelScan = { parentUnlockScanning = false; onParentUnlockCamera(false) },
                        onOpenPhone = onOpenPhone,
                        onOpenMessages = onOpenMessages,
                    )
                } else if (isKidModeLock) {
                    KidModeLockView(
                        usedMs = budgetUsedMs,
                        limitMs = budgetLimitMs,
                        isNightLock = isNightLock
                    )
                } else {
                    when (forcedMessageType) {
                        0 -> HardwareErrorView()
                        1 -> HealthWarningView()
                        2 -> KidSafeAlertView()
                    }
                }
            }
        }
    }
}

// Night-lock window for the overlay's identify decision: 22:00–06:59 (mirrors the service).
private fun isOverlayNightWindow(nowMs: Long): Boolean {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = nowMs
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    return hour >= 22 || hour < 7
}

@Composable
fun HardwareErrorView() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        Icon(Icons.Default.Warning, null, tint = Color.Red, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("System Error", style = MaterialTheme.typography.headlineMedium, color = Color.Red, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("Hardware module damaged (Code: 0x882). Please contact support.", color = Color.White, textAlign = TextAlign.Center)
    }
}

@Composable
fun HealthWarningView() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        Icon(Icons.Default.Face, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(80.dp))
        Spacer(Modifier.height(24.dp))
        Text("Health Protection", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("Taking a break from the screen helps improve concentration and eye health.", color = Color.White, textAlign = TextAlign.Center)
    }
}

@Composable
fun KidSafeAlertView() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        Text("🙏", fontSize = 60.sp)
        Spacer(Modifier.height(24.dp))
        Text("सावधान!", style = MaterialTheme.typography.headlineLarge, color = Color(0xFFFF9800), fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(24.dp))
        Text("क्या आपको पता है? ज़्यादा देर तक फ़ोन देखने से आपकी आँखें ख़राब हो सकती हैं। आपको चश्मे लग सकते हैं। आपकी एकाग्रता की शक्ति कम हो सकती है।", 
            style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center, lineHeight = 34.sp)
    }
}

@Composable
fun ParentLockView(
    scanning: Boolean = false,
    canFaceUnlock: Boolean = false,
    showCommsAccess: Boolean = false,
    onStartScan: () -> Unit = {},
    onCancelScan: () -> Unit = {},
    onOpenPhone: () -> Unit = {},
    onOpenMessages: () -> Unit = {},
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        Icon(Icons.Default.Lock, null, tint = Color(0xFF4FC3F7), modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(24.dp))
        Text(
            "Locked by parent",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "A parent locked this device remotely. Ask them to unlock it.",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFB0BEC5),
            textAlign = TextAlign.Center
        )
        // Default (non-full) lock leaves Phone & Messages reachable for safety — but the persistent overlay
        // covers the home screen, so the kid can't navigate there on their own. Surface explicit buttons that
        // launch them; the monitor loop then drops the overlay while Phone/Messages is foreground.
        if (showCommsAccess && !scanning) {
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onOpenPhone) { Text("📞  Phone") }
                OutlinedButton(onClick = onOpenMessages) { Text("💬  Messages") }
            }
        }
        // Local override: if a parent face is enrolled on THIS device, the parent can unlock in person with
        // a one-shot face scan (camera starts only on tap, releases when done — never always-on).
        if (canFaceUnlock) {
            Spacer(Modifier.height(36.dp))
            if (scanning) {
                Text(
                    "Scanning… parent, look at the camera",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF4FC3F7),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onCancelScan) { Text("Cancel") }
            } else {
                Button(onClick = onStartScan) { Text("Unlock with parent's face") }
            }
        }
    }
}

/** Formats a duration the way the lock screen shows it: "2h 05m", or "45m" under an hour. */
private fun formatLockDuration(ms: Long): String {
    val totalMinutes = (ms.coerceAtLeast(0L) / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes.toString().padStart(2, '0')}m" else "${minutes}m"
}

@Composable
fun KidModeLockView(usedMs: Long = 0L, limitMs: Long = 0L, isNightLock: Boolean = false) {
    // Night lock is a different situation from an exhausted budget: nothing was overspent, and
    // waiting will not help until morning. Calm blue instead of alarm orange, and copy that says
    // when the phone works again.
    val accent = if (isNightLock) Color(0xFF7986CB) else Color(0xFFFF9800)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Icon(
            if (isNightLock) Icons.Default.Lock else Icons.Default.Warning,
            null,
            tint = accent,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            if (isNightLock) "Sleep time" else "Your daily usage limit is over",
            style = MaterialTheme.typography.headlineMedium,
            color = accent,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )
        // Only when the budget really is exhausted. A night lock or a per-app-limit lock also lands
        // on this view with the daily budget untouched — showing "12m of 2h 00m" there would flatly
        // contradict the headline, so those keep the original layout.
        if (!isNightLock && limitMs > 0L && usedMs >= limitMs) {
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Allowed",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFFCC80)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        formatLockDuration(limitMs),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Used today",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFFCC80)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        formatLockDuration(usedMs),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFFFF9800),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            if (isNightLock) {
                "Screen time is off from 10 PM to 7 AM."
            } else {
                "Please engage yourself in other activities."
            },
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Text(
            if (isNightLock) {
                "Come back tomorrow after 7 AM. Good night!"
            } else {
                "More screen time can harm your eyes, brain, and reduce your concentration power."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (isNightLock) Color(0xFFC5CAE9) else Color(0xFFFFCC80),
            textAlign = TextAlign.Center,
            lineHeight = 26.sp
        )
    }
}


