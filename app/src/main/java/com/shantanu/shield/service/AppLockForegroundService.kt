package com.shantanu.shield.service

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.shantanu.shield.MainActivity
import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.overlay.FaceLockOverlayContent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@AndroidEntryPoint
class AppLockForegroundService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    @Inject lateinit var dataStoreManager: DataStoreManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    
    private var currentlyUnlockedPackage: String? = null
    private var lastAuthTime: Long = 0
    private var currentForegroundPackage: String? = null
    private var isLockActive: Boolean = false
    private var lockingPackage: String? = null

    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null

    private val REAUTH_INTERVAL_MS = 60 * 1000L
    private val POLLING_INTERVAL_MS = 250L 
    
    private var monitorJob: Job? = null
    private val launcherPackages = mutableSetOf<String>()

    private val serviceLifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle = serviceLifecycleRegistry
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry

    class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val ssrController = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle = registry
        override val savedStateRegistry: SavedStateRegistry = ssrController.savedStateRegistry
        fun onCreate() { 
            ssrController.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) 
        }
        fun onStart() { registry.handleLifecycleEvent(Lifecycle.Event.ON_START) }
        fun onResume() { registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        fun onPause() { registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        fun onStop() { registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        fun onDestroy() { registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
    }

    companion object {
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "AppLockServiceChannel"
        const val ACTION_CHECK_PACKAGE = "ACTION_CHECK_PACKAGE"
        const val EXTRA_PACKAGE_NAME = "EXTRA_PACKAGE_NAME"
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        serviceLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        loadLauncherPackages()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        startAppMonitoring()
    }

    private fun startAppMonitoring() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            while (isActive) {
                val currentPkg = getForegroundPackage(usageStatsManager)
                if (currentPkg != null) {
                    handlePackageChange(currentPkg)
                }
                delay(POLLING_INTERVAL_MS)
            }
        }
    }

    private fun getForegroundPackage(usageStatsManager: UsageStatsManager): String? {
        val time = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(time - 2000, time)
        val event = UsageEvents.Event()
        var latestPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                latestPkg = event.packageName
            }
        }
        return latestPkg
    }

    private fun handlePackageChange(packageName: String) {
        if (packageName == this.packageName) return

        val isSystemUI = packageName == "com.android.systemui"
        val isHome = launcherPackages.contains(packageName) || packageName == "unknown"

        // 1. Detect if we have exited the restricted app (to Home or App Switcher)
        if (isSystemUI || isHome) {
            if (isLockActive || currentlyUnlockedPackage != null) {
                Log.d("AppLock", "Exit to System/Home ($packageName). Hiding overlay and resetting session.")
                currentlyUnlockedPackage = null // MANDATORY RESET ON EXIT
                hideOverlay()
            }
            currentForegroundPackage = packageName
            return
        }

        // 2. Detect if we switched apps or came back from background/switcher
        if (currentForegroundPackage != null && currentForegroundPackage != packageName) {
            Log.d("AppLock", "Package transition detected: $currentForegroundPackage -> $packageName. Forcing scan.")
            currentlyUnlockedPackage = null 
            hideOverlay()
        }
        currentForegroundPackage = packageName

        // 3. Check for protection
        serviceScope.launch {
            val protectedApps = dataStoreManager.protectedApps.first()
            if (protectedApps.contains(packageName)) {
                val currentTime = System.currentTimeMillis()
                val sessionValid = (currentlyUnlockedPackage == packageName && (currentTime - lastAuthTime) < REAUTH_INTERVAL_MS)
                
                if (!sessionValid) {
                    showOverlay(packageName)
                }
            } else {
                // Leaving protected app to an unprotected one
                currentlyUnlockedPackage = null
                hideOverlay()
            }
        }
    }

    private fun showOverlay(packageName: String) {
        if (isLockActive && lockingPackage == packageName) return
        isLockActive = true
        lockingPackage = packageName
        
        updateForegroundService(useCamera = true)

        serviceScope.launch {
            val currentType = dataStoreManager.lockMessageType.first()
            withContext(Dispatchers.Main) {
                if (overlayView != null) hideOverlay()

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                // Architect Fix: Added FLAG_WATCH_OUTSIDE_TOUCH to allow system gestures to pass through
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.CENTER }

                overlayLifecycleOwner = OverlayLifecycleOwner().apply { onCreate() }
                overlayView = ComposeView(this@AppLockForegroundService).apply {
                    setViewTreeLifecycleOwner(overlayLifecycleOwner)
                    setViewTreeSavedStateRegistryOwner(overlayLifecycleOwner)
                    setContent {
                        FaceLockOverlayContent(
                            packageName = packageName,
                            forcedMessageType = currentType,
                            onAuthenticated = {
                                Log.d("AppLock", "Authenticated for $packageName.")
                                currentlyUnlockedPackage = packageName
                                lastAuthTime = System.currentTimeMillis()
                                isLockActive = false
                                hideOverlay()
                            }
                        )
                    }
                }
                windowManager.addView(overlayView, params)
                overlayLifecycleOwner?.onStart()
                overlayLifecycleOwner?.onResume()
            }
        }
    }

    private fun hideOverlay() {
        if (overlayView != null) {
            Log.d("AppLock", "Hiding overlay. Killing camera.")
            if (overlayView!!.isAttachedToWindow) {
                windowManager.removeView(overlayView)
            }
            overlayView = null
            // FORCE CAMERA RELEASE: Set lifecycle to DESTROYED
            overlayLifecycleOwner?.onPause(); overlayLifecycleOwner?.onStop()
            overlayLifecycleOwner?.onDestroy(); overlayLifecycleOwner = null
            updateForegroundService(useCamera = false)
            isLockActive = false
            lockingPackage = null
        }
    }

    private fun updateForegroundService(useCamera: Boolean) {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (useCamera && ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            try {
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: Exception) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("App Shield Active")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Security", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun loadLauncherPackages() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        for (ri in resolveInfos) launcherPackages.add(ri.activityInfo.packageName)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        monitorJob?.cancel()
        hideOverlay()
        serviceLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.cancel()
    }
}
