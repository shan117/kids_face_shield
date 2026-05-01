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

@Composable
fun FaceLockOverlayContent(
    packageName: String,
    forcedMessageType: Int,
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
        delay(1500)
        if (!isBlocked) showWarningAfterDelay = true
    }

    val finalShowWarning = isBlocked || showWarningAfterDelay

    // Voice trigger
    LaunchedEffect(finalShowWarning, forcedMessageType, tts) {
        if (finalShowWarning && forcedMessageType == 2 && tts != null) {
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
                                            if (storedEmbedding == null) { imageProxy.close(); return@launch }
                                            
                                            val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
                                            imageProxy.close()
                                            
                                            if (bitmap != null) {
                                                val faceBitmap = faceRecognitionManager.detectFace(bitmap)
                                                if (faceBitmap != null) {
                                                    val currentEmbedding = faceRecognitionManager.getEmbedding(faceBitmap)
                                                    if (faceRecognitionManager.isMatch(currentEmbedding, storedEmbedding)) {
                                                        onAuthenticated()
                                                    } else {
                                                        // WRONG FACE: Set blocked state permanently for this session
                                                        isBlocked = true
                                                    }
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
                when (forcedMessageType) {
                    0 -> HardwareErrorView()
                    1 -> HealthWarningView()
                    2 -> KidSafeAlertView()
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
