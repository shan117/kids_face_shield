package com.shantanu.shield

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
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
import com.shantanu.shield.ui.theme.AppShieldTheme
import com.shantanu.shield.util.ImageUtils
import com.shantanu.shield.util.TamperProtection
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startAppLockService()
        setContent {
            AppShieldTheme {
                AppLockGate {
                    MainScreen()
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
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) authenticated = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val mustLock = lockOwnApp && faceEmbedding != null && checkCameraPermission(context) && !authenticated
    if (mustLock) {
        AppFaceGate(onAuthenticated = { authenticated = true })
    } else {
        content()
    }
}

@Composable
fun AppFaceGate(onAuthenticated: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val faceRecognitionManager = remember { FaceRecognitionManager(context) }
    val dataStoreManager = remember { DataStoreManager(context) }
    var isVerifying by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(modifier = Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Lock, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(28.dp))
            Text("Kids Shield is Locked", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Text("Look at the camera to unlock", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }

        // Hidden front camera continuously scanning for the enrolled face.
        Box(modifier = Modifier.size(1.dp).alpha(0f)) {
            AndroidView(factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val imageAnalyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
                        it.setAnalyzer(Dispatchers.Default.asExecutor()) { imageProxy ->
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
                                        if (faceRecognitionManager.isMatch(currentEmbedding, storedEmbedding)) {
                                            withContext(Dispatchers.Main) { onAuthenticated() }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        text = when(selectedTab) {
                            0 -> "Usage Insight"
                            1 -> "Face ID"
                            2 -> "App Shield"
                            else -> "Security Hub"
                        },
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(tonalElevation = 8.dp) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                    label = { Text("Usage") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Face, null) },
                    label = { Text("Enroll") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Lock, null) },
                    label = { Text("Protect") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Setup") }
                )
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            when (selectedTab) {
                0 -> UsageDashboardScreen(viewModel)
                1 -> FaceEnrollmentScreen(viewModel)
                2 -> AppListScreen(viewModel)
                3 -> SettingsScreen(viewModel)
            }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val lockMessageType by viewModel.lockMessageType.collectAsState(initial = 0)

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("System Customization", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        Text("Intruder Feedback Style", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                StyleOption("Hardware Fault", "Simulate a broken module", lockMessageType == 0, Icons.Default.Warning) { viewModel.setLockMessageType(0) }
                StyleOption("Wellness Guide", "Polite eye health warning", lockMessageType == 1, Icons.Default.Favorite) { viewModel.setLockMessageType(1) }
                StyleOption("Spiritual Guide", "Shri Premanand Ji's advice", lockMessageType == 2, Icons.Default.AccountCircle) { viewModel.setLockMessageType(2) }
            }
        }

        Spacer(Modifier.height(24.dp))
        TamperProtectionSection(viewModel)

        Spacer(Modifier.height(24.dp))
        PermissionDashboard()
    }
}

@Composable
fun TamperProtectionSection(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lockDeviceSettings by viewModel.lockDeviceSettings.collectAsState(initial = false)
    val lockOwnApp by viewModel.lockOwnApp.collectAsState(initial = false)
    val faceEmbedding by viewModel.faceEmbedding.collectAsState(initial = null)
    var adminActive by remember { mutableStateOf(TamperProtection.isAdminActive(context)) }

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

    Text("Tamper Protection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TamperCard(
            title = "Lock System Settings",
            description = "Require Face ID to open device Settings, so a child can't reach Force-Stop, app permissions, or uninstall screens.",
            icon = Icons.Default.Settings,
            checked = lockDeviceSettings,
            onCheckedChange = { viewModel.setLockDeviceSettings(it) }
        )
        TamperCard(
            title = "Protect This App",
            description = if (faceEmbedding == null)
                "Enroll your Face ID first, then enable this to require Face ID before Kids Shield opens."
            else
                "Require Face ID to open Kids Shield itself, so your protection settings can't be changed.",
            icon = Icons.Default.Lock,
            checked = lockOwnApp,
            enabled = faceEmbedding != null,
            onCheckedChange = { viewModel.setLockOwnApp(it) }
        )
        TamperCard(
            title = "Prevent Uninstall (Device Admin)",
            description = "Register Kids Shield as a Device Administrator so it cannot be uninstalled. Turning this off here removes admin rights.",
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
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
    var selectedDay by remember { mutableIntStateOf(0) }

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

        if (usageStats.isEmpty()) {
            EmptyUsageState()
        } else {
            LazyColumn(
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
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
fun EmptyUsageState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Info, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))
            Text("No usage data. Check permissions.", color = MaterialTheme.colorScheme.outline)
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

    Text("Security Foundation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
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
fun AppListScreen(viewModel: MainViewModel) {
    val apps by viewModel.filteredApps.collectAsState()
    val protectedApps by viewModel.protectedApps.collectAsState(initial = emptySet())
    val searchQuery by viewModel.searchQuery.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.setSearchQuery(it) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            placeholder = { Text("Search apps to protect...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            shape = RoundedCornerShape(24.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            )
        )
        
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(apps) { app ->
                AppShieldItem(app, protectedApps.contains(app.packageName)) {
                    viewModel.toggleAppProtection(app.packageName)
                }
            }
        }
    }
}

@Composable
fun AppShieldItem(app: AppInfo, isProtected: Boolean, onToggle: () -> Unit) {
    Surface(onClick = onToggle, shape = RoundedCornerShape(20.dp), color = if (isProtected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(bitmap = app.icon.toBitmap().asImageBitmap(), contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(16.dp))
            Text(app.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Switch(checked = isProtected, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
fun FaceEnrollmentScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val faceEmbedding by viewModel.faceEmbedding.collectAsState(initial = null)
    val faceRecognitionManager = remember { FaceRecognitionManager(context) }

    var isEnrolling by remember { mutableStateOf(false) }
    var enrollmentStatus by remember { mutableStateOf("Position your face") }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) isEnrolling = true }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Face ID Registration", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(32.dp))

        Box(modifier = Modifier.size(280.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).shadow(10.dp, CircleShape), contentAlignment = Alignment.Center) {
            if (isEnrolling) {
                AndroidView(factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val imageAnalyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
                            it.setAnalyzer(Dispatchers.Default.asExecutor()) { imageProxy ->
                                coroutineScope.launch {
                                    val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
                                    imageProxy.close()
                                    if (bitmap != null) {
                                        val faceBitmap = faceRecognitionManager.detectFace(bitmap)
                                        if (faceBitmap != null) {
                                            viewModel.saveFaceEmbedding(faceRecognitionManager.getEmbedding(faceBitmap))
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
                Icon(if (faceEmbedding != null) Icons.Default.CheckCircle else Icons.Default.AccountCircle, null, modifier = Modifier.size(100.dp), tint = if (faceEmbedding != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(48.dp))
        Button(onClick = { if (checkCameraPermission(context)) isEnrolling = true else cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
            Text(if (faceEmbedding == null) "Setup Face ID" else "Update Biometrics", fontWeight = FontWeight.Bold)
        }
        Text(enrollmentStatus, modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
    }
}
