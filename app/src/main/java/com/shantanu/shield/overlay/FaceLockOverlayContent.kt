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

@Composable
fun FaceLockOverlayContent(
    packageName: String,
    forcedMessageType: Int,
    isKidModeLock: Boolean = false,
    onAuthenticated: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    
    val faceRecognitionManager = remember { FaceRecognitionManager(context) }
    val dataStoreManager = remember { DataStoreManager(context) }

    var isAuthenticating by remember { mutableStateOf(false) }
    var isBlocked by remember { mutableStateOf(false) }
    var showWarningAfterDelay by remember { mutableStateOf(false) }
    val matchStreak = remember { intArrayOf(0) }
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
        if (!isBlocked) showWarningAfterDelay = true
    }

    val finalShowWarning = isBlocked || showWarningAfterDelay

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
        // CAMERA: Only runs if NOT authenticated and NOT permanently blocked
        if (!isBlocked) {
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
                                    if (!isAuthenticating && !isBlocked) {
                                        isAuthenticating = true
                                        coroutineScope.launch {
                                            val storedEmbedding = dataStoreManager.faceEmbedding.first()
                                            if (storedEmbedding == null) { imageProxy.close(); isAuthenticating = false; return@launch }

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
                                                        val matched = faceRecognitionManager.isMatch(currentEmbedding, storedEmbedding)
                                                        if (matched) matchStreak[0]++ else matchStreak[0] = 0
                                                        val classificationDead = framesWithNullEyeProb[0] >= 30
                                                        val livenessOk = (livenessSeenOpen[0] && livenessSeenClosed[0]) || classificationDead
                                                        Log.d("AppLockOverlay", "Face for $packageName matched=$matched streak=${matchStreak[0]} eyeProb=$eyeProb liveness=$livenessOk (open=${livenessSeenOpen[0]} closed=${livenessSeenClosed[0]} nullCnt=${framesWithNullEyeProb[0]})")
                                                        if (matchStreak[0] >= REQUIRED_CONSECUTIVE_MATCHES && livenessOk) {
                                                            onAuthenticated()
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

        if (finalShowWarning) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                if (isKidModeLock) {
                    KidModeLockView()
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
fun KidModeLockView() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(24.dp))
        Text(
            "Your daily usage limit is over",
            style = MaterialTheme.typography.headlineMedium,
            color = Color(0xFFFF9800),
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Please engage yourself in other activities.",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "More screen time can harm your eyes, brain, and reduce your concentration power.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFFFCC80),
            textAlign = TextAlign.Center,
            lineHeight = 26.sp
        )
    }
}


