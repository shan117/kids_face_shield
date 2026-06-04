package com.shantanu.shield.service

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
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
import com.shantanu.shield.LockActivity
import com.shantanu.shield.MainActivity
import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.overlay.FaceLockOverlayContent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
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
    private var currentForegroundClass: String? = null
    private var isLockActive: Boolean = false
    private var lockingPackage: String? = null
    private var lockingPackageEventTime: Long = 0L

    // Cached Device-Admin state. `DevicePolicyManager.isAdminActive` is a synchronous IPC to the
    // system_server and was previously invoked on every package change AND every 250 ms watchdog
    // tick (both on the service's Main-thread coroutines), which contributed to an ANR on app
    // launch. Admin state changes only when the user explicitly toggles it from system Settings,
    // so a 5-second TTL is safe and removes ~99 % of the IPC pressure.
    @Volatile private var cachedAdminActive: Boolean = false
    @Volatile private var cachedAdminCheckedAt: Long = 0L
    private val adminCacheTtlMs: Long = 5_000L

    // Free-Play / Temp-Kid-Mode end-at (wall-clock ms). Updated by a long-running
    // collector started in onCreate so the lock-decision path can answer sync via
    // isFreePlayActive() without an additional DataStore read on every check.
    @Volatile private var cachedKidSessionEndAtMs: Long = 0L

    private fun isFreePlayActive(): Boolean = System.currentTimeMillis() < cachedKidSessionEndAtMs

    private fun isAdminActiveCached(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - cachedAdminCheckedAt > adminCacheTtlMs) {
            cachedAdminActive = com.shantanu.shield.util.TamperProtection.isAdminActive(this)
            cachedAdminCheckedAt = now
        }
        return cachedAdminActive
    }

    // Re-lock support for OEMs (e.g. Motorola) that do NOT fire ACTIVITY_RESUMED when an
    // already-running app is resumed from recents. When the user leaves a protected app while
    // its lock is still unresolved, we "arm" it; a subsequent launcher ACTIVITY_PAUSED with no
    // competing foreground event is treated as a silent re-entry and re-asserts the lock.
    private var armedPackage: String? = null
    private var lastLauncherLeftProcessed: Long = 0L

    // Settings (and any target that force-hides overlay windows) is locked with a full-screen
    // LockActivity instead of an overlay. While that lock screen is up, this is true so the
    // watchdog doesn't try to re-launch it. It is cleared when LockActivity reports its result.
    private var activityLockActive: Boolean = false
    private var activityLockPackage: String? = null

    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null

    private val REAUTH_INTERVAL_MS = 60 * 1000L
    private val POLLING_INTERVAL_MS = 250L
    private val SCREEN_TIME_POLL_INTERVAL_MS = 60_000L
    
    private var monitorJob: Job? = null
    private var screenTimePollJob: Job? = null
    private val launcherPackages = mutableSetOf<String>()
    private val settingsPackages = mutableSetOf<String>()
    // Settings activities that act as the app's "home / entry" — i.e. what launches when the user
    // taps the Settings icon from the launcher or the gear in quick settings. Sub-pages (Wi-Fi,
    // Developer options → USB debugging, Application details, etc.) deliberately fall outside
    // this set so deep-link entries into Settings are NOT challenged with a face lock.
    private val settingsHomeClasses = mutableSetOf<String>()
    // Activities resolved from well-known Settings sub-page Intent actions (Bluetooth, Wi-Fi,
    // App Details, Developer Options, etc.). When the foreground class matches one of these, we
    // are certain it is a deep link to a sub-page (quick-settings long-press, app "Open settings"
    // buttons, etc.) and must NOT challenge the user.
    private val settingsSubPageClasses = mutableSetOf<String>()
    // The uninstall / admin-deactivation side doors. These are technically Settings sub-pages,
    // but they MUST stay locked whenever Device Admin is active — otherwise the user can simply
    // navigate Settings → Security → Device Admin Apps to deactivate us, or open the package's
    // App Info screen to tap Uninstall. We override the general sub-page bypass for these.
    private val criticalSettingsClasses = mutableSetOf<String>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val FGS_DOWNGRADE_DELAY_MS = 5_000L
    private val downgradeFgsTask = Runnable {
        Log.d("AppLock", "Downgrading FGS type to SPECIAL_USE only (camera fully released).")
        try {
            updateForegroundService(useCamera = false)
        } catch (e: Exception) {
            Log.e("AppLock", "Failed to downgrade FGS type", e)
        }
    }

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
        const val FREE_PLAY_NOTIFICATION_ID = 102
        const val FREE_PLAY_CHANNEL_ID = "FreePlayChannel"
        const val ACTION_CHECK_PACKAGE = "ACTION_CHECK_PACKAGE"
        const val EXTRA_PACKAGE_NAME = "EXTRA_PACKAGE_NAME"
        const val ACTION_LOCK_RESULT = "ACTION_LOCK_RESULT"
        const val EXTRA_AUTH_SUCCESS = "EXTRA_AUTH_SUCCESS"
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        serviceLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        loadLauncherPackages()
        loadSettingsPackages()
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            Log.e("AppLock", "startForeground failed in onCreate", e)
        }
        startAppMonitoring()
        startScreenTimePolling()
        // Keep the Free-Play cache in lock-step with DataStore so the lock decision
        // path can answer synchronously. Collector exits when the service does.
        // Also: post Free-Play start/end notifications by watching for transitions,
        // and schedule the "ended" notification via a coroutine timer.
        serviceScope.launch {
            var firstEmission = true
            var previousEndAt = 0L
            var endJob: Job? = null
            dataStoreManager.kidSessionEndAt.collect { endAt ->
                cachedKidSessionEndAtMs = endAt
                val now = System.currentTimeMillis()
                if (!firstEmission) {
                    val wasActive = previousEndAt > now
                    val isActive = endAt > now
                    if (!wasActive && isActive) {
                        val durationMin = ((endAt - now + 30_000L) / 60_000L).toInt().coerceAtLeast(1)
                        postFreePlayStartedNotification(durationMin)
                    }
                }
                endJob?.cancel()
                if (endAt > now) {
                    endJob = launch {
                        delay((endAt - System.currentTimeMillis()).coerceAtLeast(0L))
                        postFreePlayEndedNotification()
                    }
                }
                previousEndAt = endAt
                firstEmission = false
            }
        }
    }

    private fun startAppMonitoring() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            Log.d("AppLock", "App monitoring started.")
            while (isActive) {
                val scan = scanRecentEvents(usageStatsManager)
                if (scan.lockedAppExited) {
                    Log.d("AppLock", "Locked app '$lockingPackage' went to background. Clearing session.")
                    if (isLockActive) armedPackage = lockingPackage
                    currentlyUnlockedPackage = null
                    lastAuthTime = 0
                    hideOverlay()
                    currentForegroundPackage = null
                }
                if (scan.latestPkg != null) {
                    handlePackageChange(scan.latestPkg, scan.latestPkgClass, scan.latestPkgTime)
                }
                // Silent re-entry fallback: the launcher just went to background, but no
                // ACTIVITY_RESUMED identified the destination app (the Motorola resume-from-recents
                // case). If a lock is armed and nothing is currently showing, re-assert it.
                val armed = armedPackage
                if (armed != null
                    && !isLockActive
                    && scan.launcherLeftTime > lastLauncherLeftProcessed
                    && scan.launcherLeftTime > scan.latestPkgTime) {
                    lastLauncherLeftProcessed = scan.launcherLeftTime
                    Log.d("AppLock", "Launcher left (${scan.launcherLeftTime}) with no foreground event; re-asserting lock for $armed.")
                    // Re-entry path is only armed for non-Settings packages (Settings uses LockActivity,
                    // not the overlay, so isLockActive stays false → arming never happens). Pass null
                    // class — class is not consulted for non-Settings packages anyway.
                    handlePackageChange(armed, null, System.currentTimeMillis())
                }
                runWatchdog()
                delay(POLLING_INTERVAL_MS)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Phase 4 — Screen-time polling.
    //
    // Every 60s we ask UsageStatsManager for total foreground time per package
    // since the most recent 07:00 cutoff, exclude system apps + always-allowed
    // apps + our own package, and store the controlled total in DataStore so
    // the Kid Mode tab's "Today" card can render live numbers.
    //
    // We also handle the daily extension reset here: at the 07:00 boundary the
    // window naturally rolls (so used-time drops to 0 from UsageStats), but
    // extensionsTodayMs is bookkeeping that needs an explicit reset.
    // -------------------------------------------------------------------------
    private fun startScreenTimePolling() {
        screenTimePollJob?.cancel()
        screenTimePollJob = serviceScope.launch {
            Log.d("ScreenTime", "Screen-time polling started.")
            while (isActive) {
                try {
                    pollScreenTime()
                } catch (t: Throwable) {
                    Log.e("ScreenTime", "poll failed: ${t.message}", t)
                }
                delay(SCREEN_TIME_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollScreenTime() {
        val now = System.currentTimeMillis()

        // Per-day reset: if the day-key changed since last seen, zero out today's
        // extensions and stamp the new key.
        val todayKey = dayKeyForBudget(now)
        val lastReset = dataStoreManager.screenTimeLastResetDate.first()
        if (todayKey != lastReset) {
            dataStoreManager.setExtensionsTodayMs(0L)
            dataStoreManager.setScreenTimeLastResetDate(todayKey)
            Log.d("ScreenTime", "Daily reset: extensions zeroed for $todayKey")
        }

        val windowStart = mostRecentSevenAm(now)
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usm.queryAndAggregateUsageStats(windowStart, now) ?: return
        if (stats.isEmpty()) {
            // No permission or no events — nothing to do.
            return
        }

        val allowed = com.shantanu.shield.util.AllowedApps.computeAlwaysAllowedSet(this, dataStoreManager)
        val pm = packageManager
        var controlledMs = 0L
        for ((pkg, stat) in stats) {
            if (pkg in allowed) continue
            if (pkg == packageName) continue
            // Only user-installed Play-Store apps count against the budget. All
            // system apps (Phone, Messages, Settings, Camera, Clock, the launcher,
            // even updated-system apps like Google Phone on Pixel) are excluded so
            // their usage doesn't eat into the kid's screen-time quota.
            val flags = try {
                pm.getApplicationInfo(pkg, 0).flags
            } catch (_: PackageManager.NameNotFoundException) {
                continue
            }
            val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem) continue

            controlledMs += stat.totalTimeInForeground
        }
        dataStoreManager.setScreenTimeUsedMs(controlledMs)
    }

    // Most-recent 07:00 boundary: today's 07:00 if we're past it, else yesterday's 07:00.
    private fun mostRecentSevenAm(nowMs: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMs
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) < 7) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        cal.set(java.util.Calendar.HOUR_OF_DAY, 7)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // YYYY-MM-DD key for the budget-day containing nowMs. The budget-day starts at
    // 07:00, so 02:00 on May 5 belongs to budget-day "2026-05-04".
    private fun dayKeyForBudget(nowMs: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMs
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) < 7) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        return String.format(
            "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    // -------------------------------------------------------------------------
    // Phase 5 — Kid Mode enforcement.
    //
    // shouldLockForKidMode answers: "in this exact moment, should we force a
    // face-auth on this package because the kid is using it past the budget
    // or during the 22:00-06:59 night window?"
    //
    // Returns false fast when owner is Parent so the parent-only path is
    // unaffected. Returns false for system apps, our own app, and packages in
    // the always-allowed set so emergency comms (Phone/SMS/WhatsApp) keep working.
    // -------------------------------------------------------------------------
    private suspend fun shouldLockForKidMode(pkg: String, nowMs: Long): Boolean {
        val ownerType = dataStoreManager.ownerType.first()
        if (ownerType != "kid") return false
        if (pkg == this.packageName) return false

        // All system apps are unrestricted in Kid Mode (Phone, Messages, Settings,
        // Camera, Clock, etc. — both pure system AND updated-system apps like the
        // Google Phone/Messages dialer on Pixels). The user-installed Play Store apps
        // are the only ones the budget applies to. Settings can still be locked
        // independently via the parent-mode `lockDeviceSettings` toggle (which uses
        // the `protectedApps` path and runs regardless of owner type).
        val flags = try {
            packageManager.getApplicationInfo(pkg, 0).flags
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (isSystem) return false

        val allowed = com.shantanu.shield.util.AllowedApps.computeAlwaysAllowedSet(this, dataStoreManager)
        if (pkg in allowed) return false

        // Hard lock during the configured night window — irrespective of budget.
        if (isNightWindow(nowMs)) return true

        // Budget-exhausted check (day window only).
        val limit = dataStoreManager.dailyLimitMinutes.first()
        val used = dataStoreManager.screenTimeUsedMs.first()
        val extensions = dataStoreManager.extensionsTodayMs.first()
        val usedMin = (used / 60_000L).toInt()
        val extensionMin = (extensions / 60_000L).toInt()
        return usedMin >= limit + extensionMin
    }

    private fun isNightWindow(nowMs: Long): Boolean {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMs
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        // Night-lock window: 22:00 inclusive to 06:59 inclusive (i.e. open at 07:00 sharp).
        return hour >= 22 || hour < 7
    }

    // The effective protected set = user-selected apps, plus the system Settings package when the
    // parent has enabled "Lock System Settings" (closes the Force-Stop / App-Info side door).
    private suspend fun effectiveProtectedApps(): Set<String> {
        val base = dataStoreManager.protectedApps.first()
        return if (dataStoreManager.lockDeviceSettings.first()) {
            base + settingsPackages
        } else {
            base
        }
    }

    private fun runWatchdog() {
        val pkg = currentForegroundPackage ?: return
        val cls = currentForegroundClass
        if (pkg == this.packageName) return
        if (isLockActive) return
        if (activityLockActive) return
        if (pkg == "com.android.systemui") return
        if (launcherPackages.contains(pkg) || pkg == "unknown") return
        serviceScope.launch {
            val protectedApps = effectiveProtectedApps()
            val isCriticalSideDoor = isCriticalSettingsClass(pkg, cls)
            val adminActive = isAdminActiveCached()
            val challengeCritical = isCriticalSideDoor && adminActive
            val kidModeLock = shouldLockForKidMode(pkg, System.currentTimeMillis())
            val freePlayActive = isFreePlayActive()
            val shouldLock = when {
                challengeCritical -> true
                freePlayActive -> false
                protectedApps.contains(pkg) && !isSettingsSubPage(pkg, cls) -> true
                kidModeLock -> true
                else -> false
            }
            if (!shouldLock) return@launch
            val currentTime = System.currentTimeMillis()
            val sessionValid = (currentlyUnlockedPackage == pkg && (currentTime - lastAuthTime) < REAUTH_INTERVAL_MS)
            if (sessionValid) return@launch
            if (isLockActive) return@launch
            Log.d("AppLock", "Watchdog: $pkg foreground without overlay (critical=$challengeCritical kid=$kidModeLock). Forcing show.")
            withContext(Dispatchers.Main) {
                if (!isLockActive) enforceLock(pkg, System.currentTimeMillis(), forceActivity = kidModeLock, isKidModeLock = kidModeLock)
            }
        }
    }

    private data class EventScan(
        val latestPkg: String?,
        val latestPkgClass: String?,
        val latestPkgTime: Long,
        val lockedAppExited: Boolean,
        val launcherLeftTime: Long
    )

    private fun scanRecentEvents(usageStatsManager: UsageStatsManager): EventScan {
        val time = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(time - 2000, time)
        val event = UsageEvents.Event()
        var latestPkg: String? = null
        var latestPkgClass: String? = null
        var latestPkgTime: Long = 0L
        var launcherLeftTime: Long = 0L
        var watchedFgTime: Long = 0L
        var watchedBgTime: Long = 0L
        val watchedPkg = lockingPackage
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (event.timeStamp >= latestPkgTime) {
                        latestPkg = event.packageName
                        latestPkgClass = event.className
                        latestPkgTime = event.timeStamp
                    }
                    if (watchedPkg != null && event.packageName == watchedPkg && event.timeStamp > watchedFgTime) {
                        watchedFgTime = event.timeStamp
                    }
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (watchedPkg != null && event.packageName == watchedPkg && event.timeStamp > watchedBgTime) {
                        watchedBgTime = event.timeStamp
                    }
                    if (launcherPackages.contains(event.packageName) && event.timeStamp > launcherLeftTime) {
                        launcherLeftTime = event.timeStamp
                    }
                }
            }
        }
        // The locked app has truly exited only if its most recent transition was to background.
        // A stale MOVE_TO_BACKGROUND that is followed by a newer MOVE_TO_FOREGROUND (e.g. Settings'
        // homepage pause/resume during launch) means the app is still in the foreground.
        val lockedAppExited = watchedBgTime > 0L && watchedBgTime > watchedFgTime
        return EventScan(latestPkg, latestPkgClass, latestPkgTime, lockedAppExited, launcherLeftTime)
    }

    private fun handlePackageChange(packageName: String, className: String?, eventTime: Long = System.currentTimeMillis()) {
        // Self-processing is normally skipped to avoid the service trying to lock
        // its own UI. During a Free-Play session, however, we DO want to lock the
        // FaceShield app so the kid can't open Settings inside it — so we let the
        // handler run for self in that case and rely on the lock check below.
        if (packageName == this.packageName && !isFreePlayActive()) return

        val isSystemUI = packageName == "com.android.systemui"
        val isHome = launcherPackages.contains(packageName) || packageName == "unknown"

        // 1. Detect if we have exited the restricted app (to Home or App Switcher)
        if (isSystemUI || isHome) {
            if (isLockActive || currentlyUnlockedPackage != null) {
                Log.d("AppLock", "Exit to System/Home ($packageName). Hiding overlay and resetting session.")
                // If the overlay was still showing (lock unresolved), arm it so a silent
                // resume-from-recents on OEMs that don't fire ACTIVITY_RESUMED re-locks it.
                if (isLockActive) armedPackage = lockingPackage
                currentlyUnlockedPackage = null // MANDATORY RESET ON EXIT
                hideOverlay()
            }
            currentForegroundPackage = packageName
            currentForegroundClass = className
            return
        }

        // A real, confirmed foreground app (ACTIVITY_RESUMED fired). If it isn't the armed app,
        // the user genuinely moved elsewhere, so the pending re-lock no longer applies.
        if (armedPackage != null && armedPackage != packageName) {
            Log.d("AppLock", "Different app '$packageName' confirmed foreground; disarming '$armedPackage'.")
            armedPackage = null
        }

        // 2. Detect package change OR re-entry of the same locked package via a NEW MOVE_TO_FOREGROUND event
        //    (e.g., user went to App Switcher and tapped the same app again — same currentForegroundPackage,
        //     but a brand-new event with a strictly newer timestamp).
        val isPackageChanged = currentForegroundPackage != null && currentForegroundPackage != packageName
        val isReEntry = isLockActive &&
                lockingPackage == packageName &&
                eventTime > lockingPackageEventTime + 100L
        if (isPackageChanged) {
            Log.d("AppLock", "Package transition: $currentForegroundPackage -> $packageName. Forcing scan.")
            currentlyUnlockedPackage = null
            hideOverlay()
        } else if (isReEntry) {
            Log.d("AppLock", "Re-entry of locked $packageName detected (new event $eventTime > previous $lockingPackageEventTime). Refreshing overlay.")
            currentlyUnlockedPackage = null
            lastAuthTime = 0
            hideOverlay()
        }
        currentForegroundPackage = packageName
        currentForegroundClass = className

        // 3. Check for protection
        serviceScope.launch {
            val protectedApps = effectiveProtectedApps()
            val isCriticalSideDoor = isCriticalSettingsClass(packageName, className)
            val adminActive = isAdminActiveCached()
            // Critical Settings pages (App Details = Uninstall, Device Admin Apps = deactivation)
            // get locked whenever Device Admin is active, even if the general Settings lock toggle
            // is off — they are the only two doors the user could otherwise walk through to remove
            // our protection.
            val challengeCritical = isCriticalSideDoor && adminActive
            val kidModeLock = shouldLockForKidMode(packageName, System.currentTimeMillis())
            val freePlayActive = isFreePlayActive()
            val freePlaySelfLock = freePlayActive && packageName == this@AppLockForegroundService.packageName
            val shouldLock = when {
                challengeCritical -> true
                freePlaySelfLock -> true
                freePlayActive -> false
                protectedApps.contains(packageName) && !isSettingsSubPage(packageName, className) -> true
                kidModeLock -> true
                else -> false
            }
            if (shouldLock) {
                val currentTime = System.currentTimeMillis()
                val sessionValid = (currentlyUnlockedPackage == packageName && (currentTime - lastAuthTime) < REAUTH_INTERVAL_MS)
                if (!sessionValid) {
                    if (kidModeLock) Log.d("AppLock", "Kid Mode lock fired for $packageName")
                    if (freePlaySelfLock) Log.d("AppLock", "Free-Play self-lock fired for $packageName")
                    enforceLock(packageName, eventTime, forceActivity = kidModeLock, isKidModeLock = kidModeLock)
                }
            } else if (!protectedApps.contains(packageName)) {
                // Leaving protected app to an unprotected one (or skipping a bypassable sub-page).
                currentlyUnlockedPackage = null
                hideOverlay()
            } else {
                Log.d("AppLock", "Settings sub-page entry ($className). Skipping lock.")
            }
        }
    }

    // True when the foreground activity is inside the Settings package but is NOT one of its
    // launcher / home entry activities AND is NOT a "critical" admin/uninstall side door. A
    // class counts as bypassable sub-page when ANY of these hold:
    //   1. It was resolved from a well-known sub-page Intent action (Bluetooth, Wi-Fi, …).
    //   2. Its name carries a Settings$NestedActivity alias suffix (Settings$BluetoothSettingsActivity).
    //   3. Its name ends with the generic SubSettings wrapper used for nested screens.
    //   4. Home classes were resolved and this class is not among them.
    // Critical classes (App Details, Device Admin Apps) ALWAYS short-circuit this to false so
    // they remain lockable. If home resolution failed at startup, fall back to locking (safer).
    private fun isSettingsSubPage(packageName: String, className: String?): Boolean {
        if (!settingsPackages.contains(packageName)) return false
        if (className == null) return false
        // Critical side doors are NEVER bypassable.
        if (isCriticalSettingsClass(packageName, className)) return false
        // The class is explicitly known to be a sub-page.
        if (settingsSubPageClasses.contains(className)) return true
        // Class name is a nested alias like Settings$BluetoothSettingsActivity → sub-page.
        if (className.contains('$')) return true
        // Generic sub-page wrappers used in modern Settings.
        val lower = className.lowercase()
        if (lower.endsWith(".subsettings") || lower.endsWith(".subsettings2")) return true
        // Whitelist check: if we know the home classes, anything else under Settings is a sub-page.
        if (settingsHomeClasses.isNotEmpty()) return !settingsHomeClasses.contains(className)
        return false
    }

    // Choose the enforcement mechanism: a full-screen Activity for targets that force-hide
    // overlay windows (Settings), and the lightweight overlay for everything else.
    // forceActivity=true is used by Kid Mode locks. The overlay path can be partially bypassed
    // on some OEMs (system gestures, the brief gap between Home → re-open recents → re-foreground),
    // so kid-mode goes through the same robust full-screen LockActivity the system Settings lock
    // uses. Back/Home from LockActivity sends the user Home and re-arms the lock on next entry.
    private fun enforceLock(packageName: String, eventTime: Long, forceActivity: Boolean = false, isKidModeLock: Boolean = false) {
        // If overlay permission was revoked (e.g., user cleared app data, or first-run
        // before granting), fall back to the full-screen LockActivity path so we don't
        // crash with BadTokenException when adding TYPE_APPLICATION_OVERLAY.
        val mustUseActivity = settingsPackages.contains(packageName) ||
            forceActivity ||
            !Settings.canDrawOverlays(this)
        if (mustUseActivity) {
            launchLockActivity(packageName, eventTime, isKidModeLock)
        } else {
            showOverlay(packageName, eventTime)
        }
    }

    private fun launchLockActivity(packageName: String, eventTime: Long, isKidModeLock: Boolean = false) {
        if (activityLockActive && activityLockPackage == packageName) return
        activityLockActive = true
        activityLockPackage = packageName
        // Drop any pending overlay so the two mechanisms never overlap.
        if (overlayView != null) hideOverlay()
        serviceScope.launch {
            val msgType = dataStoreManager.lockMessageType.first()
            withContext(Dispatchers.Main) {
                try {
                    startActivity(LockActivity.newIntent(this@AppLockForegroundService, packageName, msgType, isKidModeLock))
                    Log.d("AppLock", "launchLockActivity pkg=$packageName eventTime=$eventTime kidMode=$isKidModeLock")
                } catch (e: Exception) {
                    Log.e("AppLock", "Failed to launch LockActivity", e)
                    activityLockActive = false
                    activityLockPackage = null
                }
            }
        }
    }

    private fun showOverlay(packageName: String, eventTime: Long = System.currentTimeMillis()) {
        // Hard-stop if SYSTEM_ALERT_WINDOW was revoked at runtime. addView on type 2038
        // without the permission throws BadTokenException and crashes the service.
        // Fall back to the activity path so the user is still protected.
        if (!Settings.canDrawOverlays(this)) {
            Log.w("AppLock", "showOverlay: overlay permission missing, routing to LockActivity")
            launchLockActivity(packageName, eventTime, isKidModeLock = false)
            return
        }
        // Skip only if an overlay is already up for this exact entry (same package and same event timestamp).
        // A newer event timestamp for the same package means the user re-entered → recreate the overlay.
        if (isLockActive && lockingPackage == packageName && eventTime <= lockingPackageEventTime) return
        Log.d("AppLock", "showOverlay CREATE pkg=$packageName eventTime=$eventTime prevLockTime=$lockingPackageEventTime wasActive=$isLockActive")
        // Cancel any pending FGS-type downgrade so we keep CAMERA capability uninterrupted.
        mainHandler.removeCallbacks(downgradeFgsTask)
        if (isLockActive && overlayView != null) hideOverlay()
        // hideOverlay() re-scheduled a downgrade; cancel it again now that we're showing.
        mainHandler.removeCallbacks(downgradeFgsTask)
        isLockActive = true
        lockingPackage = packageName
        lockingPackageEventTime = eventTime

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
                                armedPackage = null
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
            try {
                if (overlayView!!.isAttachedToWindow) {
                    windowManager.removeView(overlayView)
                }
            } catch (e: Exception) {
                Log.e("AppLock", "Failed to removeView", e)
            }
            overlayView = null
            // FORCE CAMERA RELEASE: Set lifecycle to DESTROYED
            overlayLifecycleOwner?.onPause(); overlayLifecycleOwner?.onStop()
            overlayLifecycleOwner?.onDestroy(); overlayLifecycleOwner = null
            // Delay FGS type downgrade so CameraX can finish its async camera close on its
            // worker thread. Stripping FOREGROUND_SERVICE_TYPE_CAMERA before the camera is
            // fully released causes the camera service to reject CameraX's IPC calls with
            // SecurityException, which the OS then escalates to SIG 9 on the entire process.
            mainHandler.removeCallbacks(downgradeFgsTask)
            mainHandler.postDelayed(downgradeFgsTask, FGS_DOWNGRADE_DELAY_MS)
            isLockActive = false
            lockingPackage = null
            lockingPackageEventTime = 0L
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
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Security", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(FREE_PLAY_CHANNEL_ID, "Free Play sessions", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifies when a Free Play session starts or ends"
            }
        )
    }

    private fun postFreePlayStartedNotification(durationMin: Int) {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, FREE_PLAY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Free Play started")
            .setContentText("All apps unlocked for $durationMin min. Tap to view.")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            getSystemService(NotificationManager::class.java)
                .notify(FREE_PLAY_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("AppLock", "POST_NOTIFICATIONS not granted; skipping Free Play start notification")
        }
    }

    private fun postFreePlayEndedNotification() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, FREE_PLAY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Free Play ended")
            .setContentText("Face-unlock protection has resumed.")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            getSystemService(NotificationManager::class.java)
                .notify(FREE_PLAY_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("AppLock", "POST_NOTIFICATIONS not granted; skipping Free Play end notification")
        }
    }

    private fun loadLauncherPackages() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        for (ri in resolveInfos) launcherPackages.add(ri.activityInfo.packageName)
        // com.android.settings ships Settings$FallbackHome which also declares CATEGORY_HOME, so it
        // resolves here. Never treat the Settings package as "home" or it can never be locked.
        launcherPackages.remove(com.shantanu.shield.util.TamperProtection.SETTINGS_PACKAGE)
        Log.d("AppLock", "Launcher/home packages: $launcherPackages")
    }

    // Resolve the real Settings package(s) for this device. com.android.settings is the AOSP/Motorola
    // default, but some OEMs differ, and the App-Info/Force-Stop screen lives in the same package.
    private fun loadSettingsPackages() {
        settingsPackages.add(com.shantanu.shield.util.TamperProtection.SETTINGS_PACKAGE)
        try {
            val pm = packageManager
            // Settings home activity launched via Settings icon tap (gear in quick settings or notification).
            pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.let {
                    settingsPackages.add(it.packageName)
                    settingsHomeClasses.add(it.name)
                }
            // Settings home activity launched via app drawer (CATEGORY_LAUNCHER on Settings package).
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY).forEach { ri ->
                if (ri.activityInfo.packageName == com.shantanu.shield.util.TamperProtection.SETTINGS_PACKAGE
                    || settingsPackages.contains(ri.activityInfo.packageName)) {
                    settingsHomeClasses.add(ri.activityInfo.name)
                }
            }
            // App Details / Force-Stop activity — this is the uninstall side door. We register its
            // class as CRITICAL so it stays locked whenever Device Admin is active, regardless of
            // the general "lock Settings" toggle. (Yes, this means viewing any app's details will
            // be challenged when admin is on — that's the cost of closing the uninstall path.)
            pm.resolveActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.let {
                settingsPackages.add(it.packageName)
                criticalSettingsClasses.add(it.name)
            }

            // Pre-resolve the most common Settings sub-page Intent actions so quick-settings tile
            // long-presses (Bluetooth, Wi-Fi, NFC, etc.) and app "Open Settings" deep-links are
            // recognised as sub-pages even when their alias classNames are not obviously distinct.
            val subPageActions = listOf(
                Settings.ACTION_BLUETOOTH_SETTINGS,
                Settings.ACTION_WIFI_SETTINGS,
                Settings.ACTION_WIRELESS_SETTINGS,
                Settings.ACTION_AIRPLANE_MODE_SETTINGS,
                Settings.ACTION_DATA_USAGE_SETTINGS,
                Settings.ACTION_DATA_ROAMING_SETTINGS,
                Settings.ACTION_DISPLAY_SETTINGS,
                Settings.ACTION_SOUND_SETTINGS,
                Settings.ACTION_DATE_SETTINGS,
                Settings.ACTION_LOCALE_SETTINGS,
                Settings.ACTION_INPUT_METHOD_SETTINGS,
                Settings.ACTION_DEVICE_INFO_SETTINGS,
                Settings.ACTION_LOCATION_SOURCE_SETTINGS,
                Settings.ACTION_PRIVACY_SETTINGS,
                Settings.ACTION_SECURITY_SETTINGS,
                Settings.ACTION_ACCESSIBILITY_SETTINGS,
                Settings.ACTION_APPLICATION_SETTINGS,
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
                Settings.ACTION_MEMORY_CARD_SETTINGS,
                Settings.ACTION_NFC_SETTINGS,
                Settings.ACTION_PRINT_SETTINGS,
                Settings.ACTION_QUICK_LAUNCH_SETTINGS,
                Settings.ACTION_SEARCH_SETTINGS,
                Settings.ACTION_SYNC_SETTINGS,
                Settings.ACTION_VPN_SETTINGS,
                Settings.ACTION_WIFI_IP_SETTINGS,
                Settings.ACTION_HARD_KEYBOARD_SETTINGS,
                Settings.ACTION_DREAM_SETTINGS,
                Settings.ACTION_HOME_SETTINGS,
                Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
                Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS,
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
                Settings.ACTION_BATTERY_SAVER_SETTINGS,
                Settings.ACTION_USAGE_ACCESS_SETTINGS,
                Settings.ACTION_VOICE_INPUT_SETTINGS,
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
            )
            for (action in subPageActions) {
                try {
                    pm.resolveActivity(Intent(action), PackageManager.MATCH_DEFAULT_ONLY)
                        ?.activityInfo?.let {
                            if (settingsPackages.contains(it.packageName)) {
                                settingsSubPageClasses.add(it.name)
                            }
                        }
                } catch (e: Exception) { /* missing on this API level — ignore */ }
            }
            // Aliases sometimes collapse a sub-page back to the resolved home class; in that case
            // we keep home behavior (i.e. don't lie about it being a sub-page).
            settingsSubPageClasses.removeAll(settingsHomeClasses)
            // Critical pages override the sub-page bypass — never let them slip into that set.
            settingsSubPageClasses.removeAll(criticalSettingsClasses)
        } catch (e: Exception) {
            Log.e("AppLock", "Failed to resolve settings packages", e)
        }
        // Ensure no resolved Settings package is also classified as a launcher/home (FallbackHome).
        launcherPackages.removeAll(settingsPackages)
        Log.d("AppLock", "Settings packages=$settingsPackages home=$settingsHomeClasses subPages=$settingsSubPageClasses critical=$criticalSettingsClasses")
    }

    // True when the foreground class is the uninstall / admin-deactivation side door. The Device
    // Admin Apps screen has no public Intent action, so we detect it heuristically by class name
    // (every OEM variant of that screen carries "deviceadmin" in its class).
    private fun isCriticalSettingsClass(packageName: String, className: String?): Boolean {
        if (!settingsPackages.contains(packageName)) return false
        if (className == null) return false
        if (criticalSettingsClasses.contains(className)) return true
        val lower = className.lowercase()
        return lower.contains("deviceadmin")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LOCK_RESULT) {
            handleLockResult(
                intent.getStringExtra(EXTRA_PACKAGE_NAME),
                intent.getBooleanExtra(EXTRA_AUTH_SUCCESS, false)
            )
        }
        return START_STICKY
    }

    // Result reported by LockActivity (the full-screen lock used for Settings). On success we
    // start the normal grace period so re-entering the just-unlocked target doesn't re-lock it;
    // on dismissal we clear state so the next open re-locks.
    private fun handleLockResult(packageName: String?, success: Boolean) {
        activityLockActive = false
        activityLockPackage = null
        if (packageName == null) return
        if (success) {
            currentlyUnlockedPackage = packageName
            lastAuthTime = System.currentTimeMillis()
            armedPackage = null
            Log.d("AppLock", "LockActivity AUTH success for $packageName; grace started.")
        } else {
            currentlyUnlockedPackage = null
            Log.d("AppLock", "LockActivity dismissed without auth for $packageName.")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // If the user swipes Kids Shield out of recents, some OEMs tear down the whole process.
    // Schedule a near-immediate restart so monitoring resumes. (Cannot survive an explicit
    // Force Stop — that is what Device Admin / Lock Settings are for.)
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val restartIntent = Intent(applicationContext, AppLockForegroundService::class.java)
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getService(this, 1, restartIntent, flags)
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pendingIntent)
        } catch (e: Exception) {
            Log.w("AppLock", "Failed to schedule restart on task removal", e)
        }
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        super.onDestroy()
        monitorJob?.cancel()
        screenTimePollJob?.cancel()
        hideOverlay()
        mainHandler.removeCallbacks(downgradeFgsTask)
        serviceLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.cancel()
    }
}
