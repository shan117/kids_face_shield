package com.shantanu.shield.service

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
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
import com.shantanu.shield.premium.EntitlementRepository
import com.shantanu.shield.premium.Feature
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.shantanu.shield.remote.CommandType
import com.shantanu.shield.remote.RemoteCommandApplier
import com.shantanu.shield.remote.RemoteCommandRepository
import com.shantanu.shield.remote.RemoteReportSync
import com.shantanu.shield.remote.Sealed
import javax.inject.Inject

@AndroidEntryPoint
class AppLockForegroundService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    @Inject lateinit var dataStoreManager: DataStoreManager
    @Inject lateinit var entitlementRepository: EntitlementRepository
    @Inject lateinit var remoteCommandRepository: RemoteCommandRepository
    @Inject lateinit var remoteCommandApplier: RemoteCommandApplier
    @Inject lateinit var remoteReportSync: RemoteReportSync
    @Inject lateinit var locationProvider: com.shantanu.shield.location.LocationProvider
    @Inject lateinit var locationResponder: com.shantanu.shield.location.LocationResponder
    @Inject lateinit var privateDnsMonitor: com.shantanu.shield.webfilter.PrivateDnsMonitor
    @Inject lateinit var grantRepository: com.shantanu.shield.remote.GrantRepository
    @Inject lateinit var requestRepository: com.shantanu.shield.remote.RequestRepository

    // Live Firestore listener for parent→child commands (Remote Control); null unless this is a paired,
    // control-enabled child. Re-attached on state change; removed in onDestroy.
    private var commandListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var grantListener: com.google.firebase.firestore.ListenerRegistration? = null
    private val requestListeners = com.shantanu.shield.remote.ListenerBag()
    /** Dedupes the replayed snapshot a Firestore listener delivers on reconnect. */
    @Volatile private var lastNotifiedTimeRequest: String? = null
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

    // Persistent remote parent-lock. While true the device stays locked across ALL apps — the monitor
    // loop re-asserts the lock on every foreground change so Recents/Home can't slip past the overlay.
    // Cleared by a successful (parent) face auth or a remote UNLOCK command.
    @Volatile private var remoteLockActive = false
    // When true the remote lock is FULL — it also covers Phone & Messages. Default leaves them usable for
    // emergency safety. Set per-command from LOCK_NOW's arg; reset on UNLOCK.
    @Volatile private var remoteLockFull = false
    // True while a local "unlock with parent's face" scan is running on the parent-lock overlay. The camera
    // is on only during this window; the monitor loop pauses all overlay management so the one-shot scan
    // isn't torn down mid-capture.
    @Volatile private var parentFaceScanActive = false
    // Default dialer + SMS packages (resolved per-OEM at runtime), exempted from a normal remote lock.
    private var dialerPackage: String? = null
    private var smsPackage: String? = null

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

    // Cached per-package foreground ms (from the 60s poll) so the per-app-limit lock check doesn't
    // rescan UsageStats on every decision.
    @Volatile private var perAppUsageMs: Map<String, Long> = emptyMap()

    // Same idea but per kid (Multiple-kids): profileId -> (package -> foreground ms today), so a
    // per-kid per-app cap can be evaluated without a per-decision UsageStats rescan.
    @Volatile private var perProfileAppUsageMs: Map<String, Map<String, Long>> = emptyMap()

    // Premium entitlement gates (B3). Default TRUE = permissive: during the promo (everything
    // unlocked) and before the first emission, premium enforcement is unchanged. When billing flips
    // to paid and the user isn't premium, these go false and the matching premium *enforcement*
    // stops. Core safety (app lock, single-kid budget, night lock) is hardcoded-free and NEVER gated.
    @Volatile private var multiKidUnlocked: Boolean = true
    @Volatile private var perAppLimitsUnlocked: Boolean = true
    @Volatile private var newAppUnlocked: Boolean = true
    // Default-free features that can be converted to premium via config (default true = free).
    @Volatile private var nightLockUnlocked: Boolean = true
    @Volatile private var kidBudgetUnlocked: Boolean = true
    @Volatile private var freePlayUnlocked: Boolean = true
    @Volatile private var tamperUnlocked: Boolean = true
    // APP_LOCK: the core parent-mode per-app face lock. Gating it = the whole app can be made paid.
    @Volatile private var appLockUnlocked: Boolean = true
    @Volatile private var webFilterUnlocked: Boolean = true
    // Defaults FALSE, unlike the entitlement flags above: blocking every browser on the phone is a
    // large, surprising change, so it happens only once the parent has explicitly asked for it — never
    // during the moment before the first DataStore emission lands.
    @Volatile private var webFilterEnabledNow: Boolean = false

    // Gated by the FREE_PLAY entitlement: when locked (premium, non-subscriber), Free Play no longer
    // bypasses locks — i.e. the feature is off. Default true = free.
    private fun isFreePlayActive(): Boolean =
        freePlayUnlocked && System.currentTimeMillis() < cachedKidSessionEndAtMs

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
    @Volatile private var kidModeLockShown = false   // current overlay is a kid budget/night lock (auto-clears)

    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null

    private val REAUTH_INTERVAL_MS = 60 * 1000L
    private val POLLING_INTERVAL_MS = 250L
    private val SCREEN_TIME_POLL_INTERVAL_MS = 60_000L
    // Child auto-sync: while the service runs, re-upload the report at most this often so the parent's live
    // view stays fresh without a manual "Sync now". syncNow() is gated (no-ops unless child + share-on + paired).
    private val AUTO_REPORT_SYNC_INTERVAL_MS = 30 * 60_000L
    @Volatile private var lastAutoReportSyncMs = 0L
    // Debounce app install/uninstall bursts (e.g. a restore installs dozens at once) into a single upload.
    private val APP_CHANGE_SYNC_DEBOUNCE_MS = 10_000L
    private var appChangeSyncJob: Job? = null
    
    private var monitorJob: Job? = null
    private var screenTimePollJob: Job? = null

    // Premium "Auto-lock new apps": a runtime receiver (reliable for implicit PACKAGE_ADDED, unlike a
    // manifest one) that locks each newly-installed app and notifies the parent.
    private val packageAddedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REMOVED) return
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return  // an update, not a new install/removal
            val pkg = intent.data?.schemeSpecificPart ?: return
            // The device-truth utility checks (launcher icon / clock + home role) are cached per
            // package; the set of installed packages just changed, so drop the cache.
            com.shantanu.shield.util.AllowedApps.clearPackageCaches()
            // A newly installed browser must be blocked too, so the resolved browser set can't go stale.
            com.shantanu.shield.webfilter.BrowserApps.clearCache()
            if (action == Intent.ACTION_PACKAGE_ADDED) serviceScope.launch { handleNewAppInstalled(pkg) }
            // The child's installed-app list changed → re-upload so the parent's picker stays current. Debounced
            // so a burst (restore / bulk update) collapses to one upload. syncNow() is gated (child + share-on).
            scheduleAppChangeSync()
        }
    }
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
        /** Child-visible "your location was shared" notice. */
        const val LOCATION_NOTIFICATION_ID = 103
        /** "Web filtering turned off" notice. */
        const val WEB_FILTER_NOTIFICATION_ID = 104
        /** "Your child is asking for more time". */
        const val TIME_REQUEST_NOTIFICATION_ID = 105
        /** Once a day. One tiny document per linked child — cheap, and bounds how long a cancelled
         *  family keeps premium to the lease window rather than to this interval. */
        const val GRANT_RENEWAL_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val FREE_PLAY_CHANNEL_ID = "FreePlayChannel"
        const val ACTION_CHECK_PACKAGE = "ACTION_CHECK_PACKAGE"
        const val EXTRA_PACKAGE_NAME = "EXTRA_PACKAGE_NAME"
        const val ACTION_LOCK_RESULT = "ACTION_LOCK_RESULT"
        const val EXTRA_AUTH_SUCCESS = "EXTRA_AUTH_SUCCESS"
        const val ACTION_APPROVE_NEW_APP = "ACTION_APPROVE_NEW_APP"
        const val NEW_APP_CHANNEL_ID = "NewAppChannel"
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        serviceLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        loadLauncherPackages()
        loadSettingsPackages()
        dialerPackage = com.shantanu.shield.util.AllowedApps.resolveDefaultPhonePackage(this)
        smsPackage = com.shantanu.shield.util.AllowedApps.resolveDefaultSmsPackage(this)
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            Log.e("AppLock", "startForeground failed in onCreate", e)
        }
        startAppMonitoring()
        startScreenTimePolling()
        // File-based health heartbeat (Realme logcat is suppressed) — diagnoses the multi-day freeze.
        com.shantanu.shield.util.Diag.start(this)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            packageAddedReceiver,
            android.content.IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Keep the Free-Play cache in lock-step with DataStore so the lock decision
        // path can answer synchronously. Collector exits when the service does.
        // Also: post Free-Play start/end notifications by watching for transitions,
        // and schedule the "ended" notification via a coroutine timer.
        // Premium entitlement gates: revoke premium *enforcement* when paid mode is on and the user
        // isn't premium. Permissive (true) during the promo. Core safety is never affected.
        serviceScope.launch { entitlementRepository.isUnlocked(Feature.MULTI_KID_PROFILES).collect { multiKidUnlocked = it } }
        serviceScope.launch { entitlementRepository.isUnlocked(Feature.PER_APP_LIMITS).collect { perAppLimitsUnlocked = it } }
        serviceScope.launch { entitlementRepository.isUnlocked(Feature.NEW_APP_AUTO_BLOCK).collect { newAppUnlocked = it } }
        serviceScope.launch { entitlementRepository.isUnlocked(Feature.NIGHT_LOCK).collect { nightLockUnlocked = it } }
        serviceScope.launch { entitlementRepository.isUnlocked(Feature.KID_BUDGET).collect { kidBudgetUnlocked = it } }
        serviceScope.launch { entitlementRepository.isUnlocked(Feature.FREE_PLAY).collect { freePlayUnlocked = it } }
        serviceScope.launch { entitlementRepository.isUnlocked(Feature.TAMPER).collect { tamperUnlocked = it } }
        serviceScope.launch { entitlementRepository.isUnlocked(Feature.APP_LOCK).collect { appLockUnlocked = it } }
        serviceScope.launch { entitlementRepository.isUnlocked(Feature.WEB_FILTER).collect { webFilterUnlocked = it } }

        // ---- Shared entitlement (ENTITLEMENT_SHARING_PLAN.md) ----
        //
        // PARENT side: one payment has to cover the family, but every premium feature runs on the
        // CHILD's phone, which made no purchase. So while this device owns a subscription, it re-issues
        // a short grant to each linked child once a day. Renewal rather than a fixed end date because
        // the subscription's expiry does not exist on the device — only "am I premium right now" does.
        //
        // Revocation needs no delivery: stop being premium, stop renewing, and the grant decays.
        serviceScope.launch {
            while (true) {
                runCatching { renewPremiumGrants() }
                delay(GRANT_RENEWAL_INTERVAL_MS)
            }
        }

        // PARENT side: hear the child asking for more time.
        //
        // The only child→parent channel in the app, and the reason it exists: the child's "Ask for more
        // time" button used to open a dialog telling them to go find their parent in person. A request
        // nobody receives is what makes a child campaign to have the app removed.
        // Attaches only OUTSIDE the child role, mirroring the grant listener below. A device that was a
        // parent and later became a child keeps its old pairedDevices entries and their keys, so without
        // this guard a child's phone would decrypt its former children's asks and raise parent
        // notifications on it.
        serviceScope.launch {
            combine(
                dataStoreManager.remoteRole,
                dataStoreManager.pairedDevices,
            ) { role, devices -> if (role == "child") emptyList() else devices }
                .map { devices -> devices.associateBy { it.pairingId } }
                .distinctUntilChanged { old, new -> old.keys == new.keys }
                .collect { byId ->
                    requestListeners.sync(byId.keys) { id ->
                        val device = byId.getValue(id)
                        requestRepository.listen(id) { sealed ->
                            serviceScope.launch { onTimeRequest(device, sealed) }
                        }
                    }
                }
        }

        // CHILD side: adopt grants the paired parent publishes. Attaches only in the child role — a
        // parent device must never be able to grant itself premium.
        serviceScope.launch {
            combine(
                dataStoreManager.remoteRole,
                dataStoreManager.remotePairingId,
                dataStoreManager.remotePairingKey,
            ) { role, pairingId, keyHex -> Triple(role, pairingId, keyHex) }
                .collect { (role, pairingId, keyHex) ->
                    grantListener?.remove()
                    grantListener = null
                    if (role != "child" || pairingId.isBlank() || keyHex.isBlank()) return@collect
                    val keyBytes = com.shantanu.shield.remote.PairingManager
                        .Pairing(pairingId, keyHex).keyBytes()
                    grantListener = grantRepository.listen(pairingId) { sealed ->
                        serviceScope.launch { onPremiumGrant(sealed, keyBytes) }
                    }
                }
        }
        // Cached rather than read inside shouldLockForKidMode: that function runs on the 250 ms lock
        // path and a suspending DataStore read there would cost a frame on every app switch.
        serviceScope.launch { dataStoreManager.webFilterEnabled.collect { webFilterEnabledNow = it } }

        // Web filtering (Phase 0) can only be verified, never enforced — Private DNS is not writable by
        // a normal app. So the one guarantee worth offering is that it cannot be switched off QUIETLY:
        // watch for filtering→not-filtering and say so. Only on a kid-owned device, and only after it
        // has been seen working, so this can never fire at a parent who simply hasn't set it up.
        serviceScope.launch {
            var everFiltered = false
            combine(
                dataStoreManager.ownerType,
                privateDnsMonitor.state,
            ) { owner, dns -> owner to dns }
                .collect { (owner, dns) ->
                    if (owner != "kid") return@collect
                    if (dns.isFiltering) {
                        everFiltered = true
                    } else if (everFiltered) {
                        everFiltered = false      // one notice per lapse, not one per network blip
                        Log.w("AppLock", "web filtering stopped: Private DNS no longer filtering")
                        postWebFilterOffNotification()
                    }
                }
        }

        // Remote Control: while the child has it enabled AND is paired, listen for parent→child commands
        // and apply them (grant time / set limit via DataStore here; LOCK_NOW via the lock path below).
        // E2E — a command only applies if it decrypts under the pairing key. See PARENT_REMOTE_CONTROL_PLAN.md.
        serviceScope.launch {
            combine(
                dataStoreManager.remoteRole,
                dataStoreManager.remotePairingId,
                dataStoreManager.remoteControlEnabled,
                dataStoreManager.locationSharingEnabled,
            ) { role, pairingId, controlEnabled, locationEnabled ->
                // EITHER consent opens the channel; what may actually be acted on is decided per
                // command by CommandConsent. A child who consented to location sharing alone must be
                // able to receive a location request — and, just as importantly, a child who consented
                // to location alone must still be immune to LOCK_NOW.
                Triple(role, pairingId, controlEnabled || locationEnabled)
            }
                .collect { (role, pairingId, shouldListen) ->
                    commandListener?.remove()
                    commandListener = null
                    if (role == "child" && pairingId.isNotBlank() && shouldListen) {
                        commandListener = remoteCommandRepository.listen(pairingId) { sealed ->
                            serviceScope.launch { onRemoteCommand(sealed) }
                        }
                    }
                }
        }

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
                com.shantanu.shield.util.Diag.onPoll()
                // A local parent-face unlock scan is running: pause all overlay management so the one-shot
                // camera capture isn't recreated/torn down mid-scan. Resolves via onAuthenticated (unlock)
                // or onParentFaceScan(false) (cancel / 20s timeout).
                if (parentFaceScanActive) {
                    runWatchdog()
                    delay(POLLING_INTERVAL_MS)
                    continue
                }
                val scan = scanRecentEvents(usageStatsManager)
                // Skip the normal "locked app went to background → hide" teardown while a remote lock is
                // active: the dedicated manager below owns the overlay then. Showing the camera overlay over
                // a *real* foreground app (e.g. the dialer) churns that app's UsageStats FG/BG events, so
                // lockedAppExited would otherwise flip true every poll and thrash the overlay (create/destroy
                // ~every 250ms) — leaving Phone usable under full lock.
                if (scan.lockedAppExited && !remoteLockActive) {
                    Log.d("AppLock", "Locked app '$lockingPackage' went to background. Clearing session.")
                    // Now that it's background, the kill lands → stops playback + stales its recents thumbnail.
                    if (isLockActive) { armedPackage = lockingPackage; lockingPackage?.let { killLockedApp(it) } }
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
                // Persistent parent lock manager: while active, hold the overlay over whatever's foreground
                // and re-show it the instant Recents/Home dismissed it. Cleared by parent face / remote
                // UNLOCK only, so the kid can't navigate out of it. `isLockActive` is set synchronously by
                // showOverlay/hideOverlay, so it's a stable signal here — we show once and leave it up
                // (no per-poll recreate → no thrash).
                if (remoteLockActive) {
                    val pkg = scan.latestPkg ?: currentForegroundPackage ?: packageName
                    // Default remote lock leaves Phone & Messages usable (emergency safety); a FULL lock
                    // covers them too. The platform emergency dialer is a secure system UI above app
                    // overlays, so true emergency calls remain possible either way.
                    val isPhoneOrSms = pkg == dialerPackage || pkg == smsPackage
                    val shouldBlock = remoteLockFull || !isPhoneOrSms
                    if (shouldBlock && !isLockActive) {
                        enforceLock(pkg, System.currentTimeMillis(), forceActivity = false, isKidModeLock = true, lockedByParent = true)
                    } else if (!shouldBlock && isLockActive) {
                        // Default lock + Phone/SMS in the foreground → let it through (hide the overlay).
                        hideOverlay()
                    }
                }
                runWatchdog()
                // Auto-clear a kid budget/night lock the moment it no longer applies (e.g. parent granted extra
                // time, limit raised, or night window ended) — without this the overlay stays up over the app.
                if (isLockActive && kidModeLockShown && !remoteLockActive && !activityLockActive) {
                    val lp = lockingPackage
                    if (lp != null && !shouldLockForKidMode(lp, System.currentTimeMillis())) {
                        Log.d("AppLock", "Kid lock cleared for $lp (budget/night no longer applies).")
                        isLockActive = false
                        currentlyUnlockedPackage = null
                        armedPackage = null
                        hideOverlay()
                    }
                }
                // Orphan reconcile: an overlay is still attached but NO lock is wanted (state desynced by a
                // teardown race). Left alone, this full-screen touchable window eats every tap = frozen phone.
                // We still hold its reference, so remove it. (No-op in the common case — overlayView is null.)
                if (overlayView != null && !isLockActive && !remoteLockActive &&
                    !activityLockActive && !parentFaceScanActive
                ) {
                    Log.w("AppLock", "Orphan overlay detected with no active lock — removing.")
                    hideOverlay()
                }
                // Keep media suppressed while a lock is up or armed (covers minimize→background/PiP playback,
                // incl. Premium audio). Re-grab if the app clawed focus back and restarted (isMusicActive).
                val lockSuppress = isLockActive || remoteLockActive || armedPackage != null
                if (lockSuppress) {
                    if (!audioFocusHeld || audioManager.isMusicActive) grabAudioFocus()
                } else if (audioFocusHeld) {
                    releaseAudioFocus()
                }
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
                    maybeAutoSyncReport()
                } catch (t: Throwable) {
                    Log.e("ScreenTime", "poll failed: ${t.message}", t)
                }
                delay(SCREEN_TIME_POLL_INTERVAL_MS)
            }
        }
    }

    // Rate-limited background report upload (child side). Set the timestamp before syncing so a failure
    // (offline) doesn't retry every 60 s — the periodic worker + after-command sync cover the gaps.
    private suspend fun maybeAutoSyncReport() {
        val now = System.currentTimeMillis()
        if (now - lastAutoReportSyncMs < AUTO_REPORT_SYNC_INTERVAL_MS) return
        lastAutoReportSyncMs = now
        remoteReportSync.syncNow()
    }

    // App install/uninstall → re-upload, but coalesced: each event restarts a short timer, so a burst of
    // changes (a restore, a bulk update) results in ONE upload once things settle. Gated inside syncNow().
    private fun scheduleAppChangeSync() {
        appChangeSyncJob?.cancel()
        appChangeSyncJob = serviceScope.launch {
            delay(APP_CHANGE_SYNC_DEBOUNCE_MS)
            remoteReportSync.syncNow()
        }
    }

    private suspend fun pollScreenTime() {
        val now = System.currentTimeMillis()

        // Per-day reset at the 07:00 budget boundary: zero today's used-time AND extensions,
        // then stamp the new key. Zeroing used-time here (rather than relying only on the
        // windowed re-measure below) guarantees a clean slate the instant the day rolls over,
        // even if no app has been opened yet this morning.
        val todayKey = dayKeyForBudget(now)
        val lastReset = dataStoreManager.screenTimeLastResetDate.first()
        if (todayKey != lastReset) {
            dataStoreManager.setScreenTimeUsedMs(0L)
            dataStoreManager.setExtensionsTodayMs(0L)
            dataStoreManager.resetAllProfileExtensions()   // multi-kid: clear per-kid grants too
            dataStoreManager.setScreenTimeLastResetDate(todayKey)
            Log.d("ScreenTime", "Daily reset: used + extensions zeroed for $todayKey")
        }

        // Measure foreground time per package since the most recent 07:00 using the SAME
        // event-based method the Stats charts use (UsageMeasure). This is precise to the
        // 07:00 boundary — unlike queryAndAggregateUsageStats, whose per-bucket
        // totalTimeInForeground leaks midnight→07:00 usage into today's budget. Run off the
        // main thread: a full-day event scan every 60s shouldn't touch the UI dispatcher.
        val windowStart = mostRecentSevenAm(now)
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val perPackageMs = withContext(Dispatchers.IO) {
            com.shantanu.shield.util.UsageMeasure.foregroundMsByPackage(usm, windowStart, now)
        }
        if (perPackageMs.isEmpty()) {
            // No usage-access permission, or genuinely no activity since 07:00. Leave the
            // current value untouched (the reset above already cleared it at the rollover),
            // rather than overwriting a valid enforcement value with 0.
            return
        }

        // Cache for per-app-limit checks (avoids a UsageStats rescan on every lock decision).
        perAppUsageMs = perPackageMs

        var controlledMs = 0L
        for ((pkg, ms) in perPackageMs) {
            if (ms <= 0L) continue
            // Canonical predicate: user-installed apps + curated time-sinks (Chrome,
            // YouTube, …) count; pre-installed utilities (Gmail, Maps, Settings) don't.
            //
            // Always-allowed apps (WhatsApp / custom) ARE counted here: their time counts
            // toward the daily total, so the budget ring and the Stats charts include the
            // same apps. They are still never *locked* — shouldLockForKidMode keeps its own
            // allowed-set bypass — so an allowed app consumes the shared budget yet stays
            // openable, and only the non-allowed apps lock once the limit is reached.
            if (!com.shantanu.shield.util.AllowedApps.isControlledPackage(this, pkg)) continue
            controlledMs += ms
        }
        dataStoreManager.setScreenTimeUsedMs(controlledMs)

        // Per-profile attribution for Multiple-kids mode (only once both kids are enrolled).
        if (isMultiKidActive()) {
            // Keep the active kid's session ticking up to now, so their usage window grows.
            val activeId = com.shantanu.shield.kid.MultiKidEnforcement.activeProfileId(
                dataStoreManager.profileSessions.first(), now
            )
            if (activeId != null) {
                dataStoreManager.extendLatestSession(activeId, now, com.shantanu.shield.kid.MultiKidEnforcement.DEFAULT_GRACE_MS)
            }
            updatePerProfileUsage(windowStart, now)
        }
    }

    // Recompute each kid profile's used-time from its attributed sessions (UsageMeasure ∩
    // ProfileSession), controlled apps only — same predicate/window as the flat poll. Idempotent.
    private suspend fun updatePerProfileUsage(windowStart: Long, now: Long) {
        val profiles = dataStoreManager.kidProfiles.first()
        if (profiles.isEmpty()) return
        val sessions = dataStoreManager.profileSessions.first().filter { it.endMs >= windowStart }
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val result = withContext(Dispatchers.IO) {
            val appUsage = LinkedHashMap<String, Map<String, Long>>()
            val profs = profiles.map { p ->
                // Each kid's own allow-list is excluded from THEIR budget.
                val perKidAllowed = com.shantanu.shield.util.AllowedApps.computeAlwaysAllowedSet(
                    this@AppLockForegroundService, p.allowedPreset, p.customAllowed
                )
                var used = 0L
                val perPkgTotals = HashMap<String, Long>()
                for (s in sessions) {
                    if (s.profileId != p.id) continue
                    val ws = maxOf(s.startMs, windowStart)
                    val we = minOf(s.endMs, now)
                    if (we <= ws) continue
                    val perPkg = com.shantanu.shield.util.UsageMeasure.foregroundMsByPackage(usm, ws, we)
                    for ((pkg, ms) in perPkg) {
                        if (ms <= 0L) continue
                        if (!com.shantanu.shield.util.AllowedApps.isControlledPackage(this@AppLockForegroundService, pkg)) continue
                        // Per-app usage covers every controlled app (incl. this kid's allowed ones)
                        // so a per-app cap is enforceable even on an otherwise-allowed app.
                        perPkgTotals[pkg] = (perPkgTotals[pkg] ?: 0L) + ms
                        if (pkg in perKidAllowed) continue
                        used += ms
                    }
                }
                appUsage[p.id] = perPkgTotals
                p.copy(usedMs = used)
            }
            profs to appUsage
        }
        perProfileAppUsageMs = result.second
        // Write ONLY usedMs, atomically re-reading the current profiles inside the edit — never a full
        // rewrite from this stale snapshot, or a concurrent extension grant (extensionsMs) would be
        // clobbered back to its pre-grant value, silently re-locking the kid.
        if (result.first != profiles) {
            dataStoreManager.updateProfileUsedMs(result.first.associate { it.id to it.usedMs })
        }
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
        val multiKid = isMultiKidActive()
        // Independent modes: enforce if single Kid Mode is on OR Multiple-kids is active (both kids
        // enrolled). Multiple Kids no longer rides on the single Kid Mode toggle.
        if (ownerType != "kid" && !multiKid) return false
        if (pkg == this.packageName) return false

        // Only *controlled* apps are subject to the budget / night lock. Pre-installed
        // utilities (Phone, Messages, Settings, Camera, Gmail, Maps) stay free; curated
        // time-sinks (Chrome, YouTube, …) and all user-installed apps are controlled.
        // Same predicate as the budget poll & Stats, so the three classify apps the same
        // way. (Settings can still be locked independently via the parent-mode
        // `lockDeviceSettings` toggle, which runs regardless of owner.)
        if (!com.shantanu.shield.util.AllowedApps.isControlledPackage(this, pkg)) return false

        // ---- Multiple-kids branch (gated; enforces only once BOTH kids are enrolled) ----
        // Each kid has their OWN allow-list, so the per-kid "free app" bypass lives inside this branch;
        // the global allow-list below applies to single-kid only. When the toggle is OFF (every
        // single-kid/parent install) this block is skipped, so the existing flow is unchanged.
        if (multiKid) {
            val activeId = com.shantanu.shield.kid.MultiKidEnforcement.activeProfileId(
                dataStoreManager.profileSessions.first(), nowMs
            )
            val active = dataStoreManager.kidProfiles.first().firstOrNull { it.id == activeId }
            if (active != null) {
                // Per-app cap for THIS kid — checked before the allowed bypass, so a cap can
                // override an otherwise-allowed app (mirrors the single-kid ordering below).
                val kidLimits = dataStoreManager.kidPerAppLimits.first()[active.id].orEmpty()
                if (perAppLimitsUnlocked && kidLimits.isNotEmpty() &&
                    com.shantanu.shield.kid.PerAppLimits.isOver(pkg, perProfileAppUsageMs[active.id].orEmpty(), kidLimits)
                ) return true
                // This kid's own allowed apps are free (never locked, don't count).
                val perKidAllowed = com.shantanu.shield.util.AllowedApps.computeAlwaysAllowedSet(
                    this, active.allowedPreset, active.customAllowed
                )
                if (pkg in perKidAllowed) return false
                // Same browser containment as the single-kid path below. Repeated rather than hoisted
                // because each branch has its own always-allowed set, and that bypass must keep
                // running first: without this the whole feature is silently inert once Multiple-kids
                // is active, which is worse than it not existing.
                if (webFilterUnlocked && webFilterEnabledNow &&
                    com.shantanu.shield.webfilter.BrowserApps.isOtherBrowser(this, pkg)
                ) return true
                return com.shantanu.shield.kid.MultiKidEnforcement.shouldLock(
                    usedMs = active.usedMs,
                    dailyLimitMinutes = active.dailyLimitMinutes,
                    extensionsMs = active.extensionsMs,
                    isNight = nightLockUnlocked && isNightWindow(nowMs)
                )
            }
            // Stale identity (both kids enrolled): any kid's allowed app opens without a scan (so
            // emergency comms work), otherwise lock so the identify camera runs to pick the kid.
            if (pkg in multiKidUnionAllowed()) return false
            return true
        }

        // Per-app limit (single-kid): lock a specific app once its own usage reaches its limit,
        // independent of the overall budget. Applies before the allowed-set bypass, so a parent can
        // cap even an otherwise-allowed app. (Multi-kid per-app limits are a later add.)
        val perAppLimits = dataStoreManager.perAppLimits.first()
        if (perAppLimitsUnlocked && perAppLimits.isNotEmpty() &&
            com.shantanu.shield.kid.PerAppLimits.isOver(pkg, perAppUsageMs, perAppLimits)) return true

        // Single-kid: always-allowed apps (Phone/SMS/WhatsApp/custom) are never locked, so emergency
        // comms keep working at/over budget and at night. Their time still COUNTS toward the budget.
        val allowed = com.shantanu.shield.util.AllowedApps.computeAlwaysAllowedSet(this, dataStoreManager)
        if (pkg in allowed) return false

        // Web filtering: with it on, every OTHER browser is blocked so the child is funnelled into the
        // filtered in-app browser. Positioned AFTER the always-allowed bypass on purpose — emergency
        // comms must never be collateral damage — and gated on the entitlement plus the parent's own
        // switch, so it is impossible to enable this by accident. BrowserApps excludes our own package,
        // so the app can never lock itself out of the browser it provides.
        if (webFilterUnlocked && webFilterEnabledNow &&
            com.shantanu.shield.webfilter.BrowserApps.isOtherBrowser(this, pkg)
        ) return true

        // Hard lock during the configured night window — gated by the NIGHT_LOCK entitlement.
        if (nightLockUnlocked && isNightWindow(nowMs)) return true

        // Budget-exhausted check (day window only) — gated by the KID_BUDGET entitlement.
        if (!kidBudgetUnlocked) return false
        val limit = dataStoreManager.dailyLimitMinutes.first()
        val used = dataStoreManager.screenTimeUsedMs.first()
        val extensions = dataStoreManager.extensionsTodayMs.first()
        val usedMin = (used / 60_000L).toInt()
        val extensionMin = (extensions / 60_000L).toInt()
        return usedMin >= limit + extensionMin
    }

    /**
     * Today's (usedMs, effectiveLimitMs) for whoever the budget currently applies to — the identified
     * kid under Multiple-kids, otherwise the single-kid counters. The effective limit includes any
     * parent-granted extension, exactly as [shouldLockForKidMode] computes it, so the figures on the
     * lock screen can never contradict the decision that raised it.
     *
     * Returns (0, 0) when no budget applies (no identified kid) — the overlay then renders as before.
     */
    /**
     * Answer a parent's location request.
     *
     * Raises the foreground service to include the `location` type for the duration of the fix and
     * lowers it again in a `finally`, so a failure can never leave the service permanently claiming
     * location. That temporary elevation is the whole reason this app does not need
     * ACCESS_BACKGROUND_LOCATION. See LOCATION_FEATURE_PLAN.md §5.
     */
    private suspend fun handleLocationRequest(requestedAtMs: Long) {
        try {
            // Preserve whatever the camera state already is. A face-unlock overlay may be scanning
            // right now, and dropping CAMERA here would kill its camera mid-scan.
            updateForegroundService(useCamera = fgsCameraActive, useLocation = true)
            val fix = locationResponder.respond(
                requestedAtMs = requestedAtMs,
                onFixShared = { postLocationSharedNotification() },
            )
            Log.d("AppLock", "location request answered: ${fix?.status}")
        } catch (e: Exception) {
            Log.e("AppLock", "location request failed", e)
        } finally {
            // Drop only the location type; re-read the camera flag because an overlay may have come or
            // gone while the fix was in flight.
            updateForegroundService(useCamera = fgsCameraActive, useLocation = false)
        }
    }

    /**
     * Parent: publish a fresh grant to every linked child, if this device is premium.
     *
     * Silent no-op unless this is a paired parent that actually owns a subscription — so a child device
     * or a free parent never writes anything.
     */
    /**
     * Parent: a child is asking for more time.
     *
     * Notifies once per distinct ask. Stale requests are ignored — a parent clearing notifications in
     * the evening must not be prompted about an ask from breakfast, because the child's situation has
     * moved on and time granted for a forgotten reason reads as the app behaving randomly.
     */
    private suspend fun onTimeRequest(
        device: com.shantanu.shield.remote.PairedDevice,
        sealed: com.shantanu.shield.remote.Sealed?,
    ) {
        if (sealed == null) return
        val plaintext = com.shantanu.shield.remote.ReportCrypto
            .decrypt(sealed, device.pairing().keyBytes()) ?: return
        val request = com.shantanu.shield.remote.TimeRequestCodec.decode(plaintext) ?: return

        if (!com.shantanu.shield.remote.TimeRequests.isFresh(request, System.currentTimeMillis())) {
            Log.i("AppLock", "time request from ${device.label} is stale — ignored")
            return
        }
        // The listener replays on reconnect; without this a single ask could notify repeatedly.
        val key = "${device.pairingId}:${request.requestedAtMs}"
        if (key == lastNotifiedTimeRequest) return
        lastNotifiedTimeRequest = key

        Log.i("AppLock", "time request: ${device.label} asked for ${request.minutes} min")
        postTimeRequestNotification(device.label, request.minutes)
    }

    private suspend fun renewPremiumGrants() {
        if (dataStoreManager.remoteRole.first() != "parent") return
        if (!entitlementRepository.isPremium.first()) return

        val devices = dataStoreManager.pairedDevices.first()
        if (devices.isEmpty()) return

        val grant = com.shantanu.shield.remote.PremiumGrants.renew(System.currentTimeMillis())
        val plaintext = com.shantanu.shield.remote.PremiumGrantCodec.encode(grant)
        for (device in devices) {
            val sealed = com.shantanu.shield.remote.ReportCrypto
                .encrypt(plaintext, device.pairing().keyBytes())
            val ok = grantRepository.write(device.pairingId, sealed)
            if (!ok) Log.w("AppLock", "premium grant upload failed for ${device.label}")
        }
        Log.i("AppLock", "premium grants renewed for ${devices.size} device(s)")
    }

    /**
     * Child: adopt a grant from the paired parent.
     *
     * The grant must decrypt under the pairing key. That is the authenticity check — knowing a pairing
     * id is not enough to hand yourself free premium, because a forged document will not decrypt.
     */
    private suspend fun onPremiumGrant(sealed: com.shantanu.shield.remote.Sealed?, keyBytes: ByteArray) {
        if (sealed == null) return
        val plaintext = com.shantanu.shield.remote.ReportCrypto.decrypt(sealed, keyBytes)
        if (plaintext == null) {
            // Almost always a leftover document from a rotated key, not an attack. Either way: ignore.
            Log.w("AppLock", "premium grant won't decrypt with this device's key — ignored")
            return
        }
        val grant = com.shantanu.shield.remote.PremiumGrantCodec.decode(plaintext) ?: return
        dataStoreManager.setPremiumGrantUntil(grant.premiumUntilMs)
        Log.i("AppLock", "premium grant adopted, valid until ${grant.premiumUntilMs}")
    }

    private suspend fun kidBudgetSnapshot(nowMs: Long): Pair<Long, Long> {
        if (isMultiKidActive()) {
            val activeId = com.shantanu.shield.kid.MultiKidEnforcement.activeProfileId(
                dataStoreManager.profileSessions.first(), nowMs
            )
            val active = dataStoreManager.kidProfiles.first().firstOrNull { it.id == activeId }
                ?: return 0L to 0L
            return active.usedMs to (active.dailyLimitMinutes * 60_000L + active.extensionsMs)
        }
        val limitMs = dataStoreManager.dailyLimitMinutes.first() * 60_000L +
            dataStoreManager.extensionsTodayMs.first()
        return dataStoreManager.screenTimeUsedMs.first() to limitMs
    }

    private fun isNightWindow(nowMs: Long): Boolean {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMs
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        // Night-lock window: 22:00 inclusive to 06:59 inclusive (i.e. open at 07:00 sharp).
        return hour >= 22 || hour < 7
    }

    // True when a multi-kid lock should run the IDENTIFY camera (vs a plain budget/night lock):
    // multi-kid on, a controlled non-allowed app, kid faces enrolled, and no kid currently
    // identified (stale). Mirrors the stale-lock branch in shouldLockForKidMode.
    // Multi-kid only ENFORCES once BOTH kids' faces are enrolled. If the toggle is on but fewer than
    // two faces exist, this is false → the device falls back to single-kid (and the UI prompts the
    // parent to finish enrolment). So a half-configured multi-kid never half-works.
    private suspend fun isMultiKidActive(): Boolean =
        multiKidUnlocked &&
        dataStoreManager.multiKidEnabled.first() && dataStoreManager.kidFacesEnrolled(2).first()

    // Union of every kid's allow-list — apps that bypass the identify camera, so any kid's allowed
    // app (and emergency comms) opens without a scan while no kid is identified yet.
    private suspend fun multiKidUnionAllowed(): Set<String> {
        val out = HashSet<String>()
        for (p in dataStoreManager.kidProfiles.first()) {
            out += com.shantanu.shield.util.AllowedApps.computeAlwaysAllowedSet(this, p.allowedPreset, p.customAllowed)
        }
        return out
    }

    private suspend fun isIdentifyLock(pkg: String, nowMs: Long): Boolean {
        if (!isMultiKidActive()) return false
        if (!com.shantanu.shield.util.AllowedApps.isControlledPackage(this, pkg)) return false
        if (pkg in multiKidUnionAllowed()) return false
        val activeId = com.shantanu.shield.kid.MultiKidEnforcement.activeProfileId(
            dataStoreManager.profileSessions.first(), nowMs
        )
        return activeId == null
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
        // Fall back to the armed package: after a locked app is backgrounded, currentForegroundPackage is
        // cleared; if the kid re-opens it from recents before the (laggy) foreground event lands, this still
        // re-asserts the lock instead of leaving a gap where the app is visible without the overlay.
        val pkg = currentForegroundPackage ?: armedPackage ?: return
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
            val challengeCritical = isCriticalSideDoor && adminActive && tamperUnlocked
            val kidModeLock = shouldLockForKidMode(pkg, System.currentTimeMillis())
            val freePlayActive = isFreePlayActive()
            val shouldLock = when {
                challengeCritical -> true
                freePlayActive -> false
                appLockUnlocked && protectedApps.contains(pkg) && !(isSettingsSubPage(pkg, cls) && settingsSessionActive(pkg)) -> true
                kidModeLock -> true
                else -> false
            }
            if (!shouldLock) return@launch
            val currentTime = System.currentTimeMillis()
            val sessionValid = (currentlyUnlockedPackage == pkg && (currentTime - lastAuthTime) < REAUTH_INTERVAL_MS)
            if (sessionValid) return@launch
            if (isLockActive) return@launch
            val identifyMode = kidModeLock && isIdentifyLock(pkg, System.currentTimeMillis())
            Log.d("AppLock", "Watchdog: $pkg foreground without overlay (critical=$challengeCritical kid=$kidModeLock identify=$identifyMode). Forcing show.")
            withContext(Dispatchers.Main) {
                if (!isLockActive) enforceLock(pkg, System.currentTimeMillis(), forceActivity = false, isKidModeLock = kidModeLock, identifyMode = identifyMode)
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
            val challengeCritical = isCriticalSideDoor && adminActive && tamperUnlocked
            val kidModeLock = shouldLockForKidMode(packageName, System.currentTimeMillis())
            val freePlayActive = isFreePlayActive()
            val freePlaySelfLock = freePlayActive && packageName == this@AppLockForegroundService.packageName
            val shouldLock = when {
                challengeCritical -> true
                freePlaySelfLock -> true
                freePlayActive -> false
                appLockUnlocked && protectedApps.contains(packageName) && !(isSettingsSubPage(packageName, className) && settingsSessionActive(packageName)) -> true
                kidModeLock -> true
                else -> false
            }
            if (shouldLock) {
                val currentTime = System.currentTimeMillis()
                val sessionValid = (currentlyUnlockedPackage == packageName && (currentTime - lastAuthTime) < REAUTH_INTERVAL_MS)
                if (!sessionValid) {
                    if (kidModeLock) Log.d("AppLock", "Kid Mode lock fired for $packageName")
                    if (freePlaySelfLock) Log.d("AppLock", "Free-Play self-lock fired for $packageName")
                    val identifyMode = kidModeLock && isIdentifyLock(packageName, System.currentTimeMillis())
                    enforceLock(packageName, eventTime, forceActivity = false, isKidModeLock = kidModeLock, identifyMode = identifyMode)
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
    // A Settings sub-page is only bypassable as a *continuation* of an already-unlocked Settings session
    // (navigating inside Settings after the face check). Cold-opening straight to a sub-page (OEMs reopen
    // Settings to the last screen) is NOT a session → still locks. Closes the "first tap locks, later taps
    // land on a sub-page and skip" hole.
    private fun settingsSessionActive(packageName: String): Boolean =
        settingsPackages.contains(packageName) &&
        currentlyUnlockedPackage == packageName &&
        (System.currentTimeMillis() - lastAuthTime) < REAUTH_INTERVAL_MS

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
    // Kid Mode locks use the OVERLAY (forceActivity=false), NOT a full-screen Activity: a
    // background Activity launch is blocked on Android 12+/many OEMs (RealmeUI 15) once the
    // ~10s post-foreground grace window expires, so the budget/night lock would silently never
    // appear. The overlay (TYPE_APPLICATION_OVERLAY, granted SYSTEM_ALERT_WINDOW) is not subject
    // to background-activity-launch limits, and the watchdog re-asserts it every poll if a system
    // gesture dismisses it — covering the partial-bypass case the Activity was meant to solve.
    // Route an applied remote command to the lock UI. Budget commands (grant/limit) are already applied in
    // the applier; only the lock actions need the service. LOCK_NOW reuses the parent-face kid-mode lock.
    private suspend fun onRemoteCommand(sealed: Sealed) {
        val applied = remoteCommandApplier.apply(sealed) ?: return
        when (applied.type) {
            CommandType.LOCK_NOW -> {
                // Persistent parent lock: set the flags so the monitor loop re-asserts it on every app
                // switch (Recents/Home can't slip past it), then show it now. Drawn as an overlay because a
                // background full-screen Activity is blocked on Android 12+/many OEMs (RealmeUI 15).
                // arg == LOCK_FLAG_FULL → also cover Phone & Messages; otherwise they stay usable (safety).
                remoteLockActive = true
                remoteLockFull = applied.arg == com.shantanu.shield.remote.RemoteCommand.LOCK_FLAG_FULL
                val pkg = currentForegroundPackage ?: packageName
                enforceLock(pkg, System.currentTimeMillis(), forceActivity = false, isKidModeLock = true, lockedByParent = true)
            }
            CommandType.UNLOCK -> {
                // Remote release: clear the persistent flags and drop the overlay.
                remoteLockActive = false
                remoteLockFull = false
                parentFaceScanActive = false
                isLockActive = false
                hideOverlay()
            }
            CommandType.REQUEST_LOCATION -> handleLocationRequest(applied.issuedAtMs)
            // Budget/config commands are applied to DataStore in the applier. Re-upload the report so the
            // parent's live listener reflects the change at once, instead of waiting for the periodic sync.
            CommandType.GRANT_EXTRA_TIME, CommandType.SET_DAILY_LIMIT,
            CommandType.SET_ALLOWED_PRESET, CommandType.SET_AUTO_BLOCK,
            CommandType.SET_CUSTOM_ALLOWED, CommandType.SET_PER_APP_LIMIT,
            CommandType.SET_WEB_CATEGORIES ->
                serviceScope.launch { remoteReportSync.syncNow() }
        }
    }

    // Tap-to-start local parent-face unlock on the parent-lock overlay. The camera is bound only for the
    // duration of a scan; the monitor loop is paused (parentFaceScanActive) so the one-shot capture isn't
    // recreated/torn down mid-scan. On end (cancel/timeout) we drop the overlay so CameraX is released
    // (lifecycle destroy) BEFORE the FGS camera-type downgrade — then the monitor re-shows it camera-free.
    private fun onParentFaceScan(active: Boolean) {
        parentFaceScanActive = active
        if (active) {
            updateForegroundService(useCamera = true)   // Android 14+ needs CAMERA-FGS to open the camera
        } else {
            hideOverlay()   // releases the camera + schedules the safe FGS downgrade; monitor re-shows
        }
    }

    private fun enforceLock(packageName: String, eventTime: Long, forceActivity: Boolean = false, isKidModeLock: Boolean = false, identifyMode: Boolean = false, lockedByParent: Boolean = false) {
        // Stop the locked app leaking under the overlay: grab exclusive audio focus (pauses its video/music)
        // and kill it in the background (stops playback + stales its recents thumbnail; the watchdog re-locks
        // if the kid taps it again). Only kill genuine controlled apps — never the launcher/dialer/our own.
        grabAudioFocus()
        if (packageName != this.packageName &&
            com.shantanu.shield.util.AllowedApps.isControlledPackage(this, packageName)
        ) killLockedApp(packageName)

        // If overlay permission was revoked (e.g., user cleared app data, or first-run
        // before granting), fall back to the full-screen LockActivity path so we don't
        // crash with BadTokenException when adding TYPE_APPLICATION_OVERLAY.
        val mustUseActivity = settingsPackages.contains(packageName) ||
            forceActivity ||
            !Settings.canDrawOverlays(this)
        if (mustUseActivity) {
            launchLockActivity(packageName, eventTime, isKidModeLock, identifyMode)
        } else {
            showOverlay(packageName, eventTime, lockedByParent, isKidModeLock, identifyMode)
        }
    }

    private fun launchLockActivity(packageName: String, eventTime: Long, isKidModeLock: Boolean = false, identifyMode: Boolean = false) {
        if (activityLockActive && activityLockPackage == packageName) return
        activityLockActive = true
        activityLockPackage = packageName
        // Drop any pending overlay so the two mechanisms never overlap.
        if (overlayView != null) hideOverlay()
        serviceScope.launch {
            val msgType = dataStoreManager.lockMessageType.first()
            withContext(Dispatchers.Main) {
                try {
                    startActivity(LockActivity.newIntent(this@AppLockForegroundService, packageName, msgType, isKidModeLock, identifyMode))
                    Log.d("AppLock", "launchLockActivity pkg=$packageName eventTime=$eventTime kidMode=$isKidModeLock identify=$identifyMode")
                } catch (e: Exception) {
                    Log.e("AppLock", "Failed to launch LockActivity", e)
                    activityLockActive = false
                    activityLockPackage = null
                }
            }
        }
    }

    private fun showOverlay(packageName: String, eventTime: Long = System.currentTimeMillis(), lockedByParent: Boolean = false, isKidModeLock: Boolean = false, identifyMode: Boolean = false) {
        // Hard-stop if SYSTEM_ALERT_WINDOW was revoked at runtime. addView on type 2038
        // without the permission throws BadTokenException and crashes the service.
        // Fall back to the activity path so the user is still protected.
        if (!Settings.canDrawOverlays(this)) {
            Log.w("AppLock", "showOverlay: overlay permission missing, routing to LockActivity")
            launchLockActivity(packageName, eventTime, isKidModeLock, identifyMode)
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
        kidModeLockShown = isKidModeLock && !lockedByParent
        lockingPackage = packageName
        lockingPackageEventTime = eventTime

        // A parent-lock overlay uses no camera (no face auth), so don't put the service into CAMERA-FGS
        // mode for it — that avoids the camera-type teardown race on hide that can SIG-9 the process.
        updateForegroundService(useCamera = !lockedByParent)

        serviceScope.launch {
            val currentType = dataStoreManager.lockMessageType.first()
            // Budget figures for the kid-mode lock screen. Read here, alongside the existing
            // lockMessageType read and before the same post-suspend abort check below, so no new
            // suspension window is opened.
            val nowForLock = System.currentTimeMillis()
            val (budgetUsedMs, budgetLimitMs) =
                if (isKidModeLock && !lockedByParent) kidBudgetSnapshot(nowForLock)
                else 0L to 0L
            // Same condition shouldLockForKidMode uses for the night branch, so the message always
            // names the real reason: at night the budget may be untouched, and telling the kid their
            // limit is over would simply be false.
            val isNightLock = isKidModeLock && !lockedByParent &&
                nightLockUnlocked && isNightWindow(nowForLock)
            // Offer Phone/Messages on the kid-mode lock only when this device actually has a dialer —
            // on a Wi-Fi tablet the button would resolve to nothing.
            val kidCommsAccess = isKidModeLock && !lockedByParent &&
                com.shantanu.shield.util.AllowedApps.resolveDefaultPhonePackage(this@AppLockForegroundService) != null
            withContext(Dispatchers.Main) {
                if (overlayView != null) hideOverlay()

                // The read above suspends; a hide (auto-clear / app exit) may have run meanwhile and
                // cleared the lock. Attaching now would orphan a full-screen window nobody will remove
                // (isLockActive is false so no hide path fires). Abort if the lock is no longer wanted.
                if (!isLockActive || lockingPackage != packageName || lockingPackageEventTime != eventTime) {
                    Log.d("AppLock", "showOverlay aborted post-suspend: lock no longer active for $packageName.")
                    return@withContext
                }

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
                            isKidModeLock = isKidModeLock,
                            lockedByParent = lockedByParent,
                            identifyMode = identifyMode,
                            budgetUsedMs = budgetUsedMs,
                            budgetLimitMs = budgetLimitMs,
                            isNightLock = isNightLock,
                            kidCommsAccess = kidCommsAccess,
                            isFullLock = remoteLockFull,
                            onParentUnlockCamera = { active -> onParentFaceScan(active) },
                            onOpenPhone = { openCommsApp(sms = false) },
                            onOpenMessages = { openCommsApp(sms = true) },
                            onAuthenticated = {
                                Log.d("AppLock", "Authenticated for $packageName.")
                                currentlyUnlockedPackage = packageName
                                lastAuthTime = System.currentTimeMillis()
                                isLockActive = false
                                remoteLockActive = false   // a (parent) face auth clears the parent lock
                                parentFaceScanActive = false
                                armedPackage = null
                                hideOverlay()
                            }
                        )
                    }
                }
                windowManager.addView(overlayView, params)
                com.shantanu.shield.util.Diag.onOverlayAdded()
                overlayLifecycleOwner?.onStart()
                overlayLifecycleOwner?.onResume()
            }
        }
    }

    // Default remote lock only: launch the default Phone / Messages app from the lock screen so the kid can
    // still reach them (emergency safety). The monitor loop drops the overlay while they're foreground. The
    // overlay holds SYSTEM_ALERT_WINDOW, so this background→activity launch is exempt from BAL restrictions.
    private fun openCommsApp(sms: Boolean) {
        try {
            // Launch the EXACT resolved package where possible, so the foreground app matches the loop's
            // dialerPackage/smsPackage exemption (which is what drops the overlay). Fall back to the standard
            // action/category if the package or its launch intent can't be resolved.
            val target = if (sms) smsPackage else dialerPackage
            val intent = (target?.let { packageManager.getLaunchIntentForPackage(it) }
                ?: if (sms) Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
                   else Intent(Intent.ACTION_DIAL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("AppLock", "openCommsApp(sms=$sms) failed", e)
        }
    }

    // ---- Anti-leak: pause + kill the locked app so it can't run under the overlay / in recents ----
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as android.media.AudioManager }
    private val audioFocusListener = android.media.AudioManager.OnAudioFocusChangeListener { }
    private var audioFocusReq: android.media.AudioFocusRequest? = null
    @Volatile private var audioFocusHeld = false

    // Permanent GAIN (not transient) so a locked app's media stays paused — won't resume the instant we let go.
    // Held for as long as the lock condition is active (see the monitor loop), so YouTube-Premium-style
    // background/PiP playback after "minimize" can't keep going while the gate is up.
    private fun grabAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = android.media.AudioFocusRequest
                    .Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(audioFocusListener).build()
                audioFocusReq = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusListener, android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN,
                )
            }
            audioFocusHeld = true
        } catch (_: Exception) {}
    }

    private fun releaseAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusReq?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusReq = null
            } else {
                @Suppress("DEPRECATION") audioManager.abandonAudioFocus(audioFocusListener)
            }
        } catch (_: Exception) {}
        audioFocusHeld = false
    }

    // Best-effort: kills the app's background process → stops playback + stales its recents thumbnail. No-op on
    // a foreground process; OEMs may restrict it. Re-tried from the monitor loop when the app backgrounds.
    private fun killLockedApp(pkg: String) {
        if (pkg == packageName) return
        try {
            (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(pkg)
        } catch (_: Exception) {}
    }

    private fun hideOverlay() {
        kidModeLockShown = false
        val view = overlayView
        if (view != null) {
            Log.d("AppLock", "Hiding overlay. Killing camera.")
            // ALWAYS attempt removeView — never gate on isAttachedToWindow. During the async add that
            // flag can be transiently false, and skipping removeView while nulling the reference below
            // ORPHANS the window (attached at the WM level, reference lost) → a full-screen touchable
            // overlay that eats every tap until reboot. removeView on a not-attached view just throws
            // IllegalArgumentException, which we catch; removeViewImmediate is the fallback.
            try {
                windowManager.removeView(view)
                com.shantanu.shield.util.Diag.onOverlayRemoved()
            } catch (e: Exception) {
                try { windowManager.removeViewImmediate(view); com.shantanu.shield.util.Diag.onOverlayRemoved() }
                catch (e2: Exception) { Log.e("AppLock", "Failed to removeView", e2) }
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

    /**
     * Whether the service is currently claiming the CAMERA foreground type.
     *
     * Tracked because location and camera share this one call: a location request that arrives while a
     * face-unlock overlay is scanning must not silently drop CAMERA and kill the running camera.
     * Anything that toggles the location type reads this to preserve the camera state.
     */
    @Volatile private var fgsCameraActive: Boolean = false

    private fun updateForegroundService(useCamera: Boolean, useLocation: Boolean = false) {
        fgsCameraActive = useCamera
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (useCamera && ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            // `location` is added only while a fix is in flight, and only when the runtime permission is
            // actually held — a type whose permission is missing makes startForeground throw. This is
            // what lets the app read location without ACCESS_BACKGROUND_LOCATION at all.
            if (useLocation && locationProvider.hasPermission()) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else if (useLocation) {
                Log.w("AppLock", "location fix requested but the runtime permission is not held")
            }
            try {
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: Exception) {
                // Dropping to SPECIAL_USE also drops `location`, and with a foreground-only location
                // permission that makes every subsequent read return nothing at all — which surfaces to
                // the parent as "couldn't get a fix", blaming signal for a permission problem. Must be
                // loud: it is otherwise invisible from both phones.
                Log.e(
                    "AppLock",
                    "startForeground(type=$type camera=$useCamera location=$useLocation) failed — " +
                        "falling back to SPECIAL_USE; location reads will now return nothing",
                    e,
                )
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
        nm.createNotificationChannel(
            NotificationChannel(NEW_APP_CHANNEL_ID, "New app alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifies when a new app is installed and auto-locked"
            }
        )
    }

    // Premium "Auto-lock new apps": lock a newly-installed user app and notify the parent.
    private suspend fun handleNewAppInstalled(pkg: String) {
        if (pkg == packageName) return
        if (!newAppUnlocked) return                          // premium gate (B3)
        if (!dataStoreManager.autoBlockNewApps.first()) return
        // Only user-facing apps (the same predicate that defines "an app worth controlling").
        if (!com.shantanu.shield.util.AllowedApps.isControlledPackage(this, pkg)) return
        val current = dataStoreManager.protectedApps.first()
        if (pkg !in current) dataStoreManager.setProtectedApps(current + pkg)
        postNewAppNotification(pkg)
    }

    private fun postNewAppNotification(pkg: String) {
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
        val approve = PendingIntent.getService(
            this, pkg.hashCode(),
            Intent(this, AppLockForegroundService::class.java).apply {
                action = ACTION_APPROVE_NEW_APP
                putExtra(EXTRA_PACKAGE_NAME, pkg)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, NEW_APP_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("New app locked")
            .setContentText("$label was installed and is locked until you allow it.")
            .setContentIntent(open)
            .addAction(0, "Allow", approve)
            .setAutoCancel(true)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(pkg.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.w("AppLock", "POST_NOTIFICATIONS not granted; skipping new-app notification")
        }
    }

    /**
     * Tell the child their location was just shared.
     *
     * Not optional, and not configurable away by the parent. Android's status-bar indicator shows that
     * location was *used*, never by whom or to whom it went. A monitoring feature the monitored person
     * cannot see is a tracker; this notification is what keeps this a family feature.
     * See LOCATION_FEATURE_PLAN.md §10.
     */
    /**
     * "Aarav is asking for 15 more minutes."
     *
     * Tapping opens the app, where the request card offers Grant / Not now. Deliberately not an
     * action button on the notification itself: granting screen time from a lock screen, with no
     * context about how much the child has already used today, is a decision a parent would regret.
     */
    private fun postTimeRequestNotification(childLabel: String, minutes: Int) {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, FREE_PLAY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$childLabel is asking for more time")
            .setContentText("They'd like $minutes more minutes. Tap to decide.")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            getSystemService(NotificationManager::class.java)
                .notify(TIME_REQUEST_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("AppLock", "POST_NOTIFICATIONS not granted; skipping time-request notification")
        }
    }

    private fun postLocationSharedNotification() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, FREE_PLAY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Location shared")
            .setContentText("Your parent asked where this phone is, and it was sent to them.")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            getSystemService(NotificationManager::class.java)
                .notify(LOCATION_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("AppLock", "POST_NOTIFICATIONS not granted; skipping location-shared notification")
        }
    }

    /**
     * Web filtering stopped. Fires only after it was previously seen working on a kid-owned device, so
     * it reports a change rather than nagging about a feature the parent never enabled.
     */
    private fun postWebFilterOffNotification() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, FREE_PLAY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Web filtering turned off")
            .setContentText("This phone can now reach any website. Tap to set it up again.")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            getSystemService(NotificationManager::class.java)
                .notify(WEB_FILTER_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("AppLock", "POST_NOTIFICATIONS not granted; skipping web-filter notification")
        }
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
        // EVERY startForegroundService() must be answered by startForeground() within ~5s, or Android
        // 12+ kills us with ForegroundServiceDidNotStartInTimeException. onCreate covers the first start,
        // but a start while we're ALREADY running only hits onStartCommand (no onCreate) — which is the
        // common "reopen the app after the Activity was destroyed but the service survived" case. So
        // re-assert foreground here too. Idempotent when already foreground.
        try {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            Log.e("AppLock", "startForeground failed in onStartCommand", e)
        }
        if (intent?.action == ACTION_LOCK_RESULT) {
            handleLockResult(
                intent.getStringExtra(EXTRA_PACKAGE_NAME),
                intent.getBooleanExtra(EXTRA_AUTH_SUCCESS, false)
            )
        }
        if (intent?.action == ACTION_APPROVE_NEW_APP) {
            val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            if (pkg != null) {
                serviceScope.launch {
                    val current = dataStoreManager.protectedApps.first()
                    if (pkg in current) dataStoreManager.setProtectedApps(current - pkg)
                }
                try { getSystemService(NotificationManager::class.java).cancel(pkg.hashCode()) } catch (e: Exception) {}
            }
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
        try { unregisterReceiver(packageAddedReceiver) } catch (e: Exception) {}
        monitorJob?.cancel()
        screenTimePollJob?.cancel()
        hideOverlay()
        mainHandler.removeCallbacks(downgradeFgsTask)
        commandListener?.remove()
        grantListener?.remove()
        requestListeners.clear()
        serviceLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.cancel()
    }
}
