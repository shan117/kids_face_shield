package com.shantanu.shield

import android.app.Application
import com.google.firebase.messaging.FirebaseMessaging
import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.fcm.ShieldMessagingService
import com.shantanu.shield.premium.EntitlementRevoker
import com.shantanu.shield.premium.PremiumSource
import com.shantanu.shield.util.AllowedApps
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AppLockApplication : Application() {

    // Kick off Billing at process launch so premium state is reconciled with Play every start.
    // Dormant-safe: under the promo it changes nothing user-visible. Injected as the interface so
    // it stays decoupled from the concrete BillingManager.
    @Inject lateinit var premiumSource: PremiumSource

    // Revoke-on-lapse: switch off premium features that are no longer entitled.
    @Inject lateinit var entitlementRevoker: EntitlementRevoker

    // Source for the parent's "not screen time" overrides, mirrored into AllowedApps below.
    @Inject lateinit var dataStoreManager: DataStoreManager

    // Runtime feature config (promo / per-feature tiers). Re-fetched periodically below.
    @Inject lateinit var remoteConfigSource: com.shantanu.shield.premium.RemoteConfigSource

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        runCatching { premiumSource.start() }
        runCatching { entitlementRevoker.start() }

        // Single process-wide mirror of the parent's "not screen time" set into AllowedApps. The
        // lock decision runs every 250 ms and cannot afford a suspending DataStore read, so the
        // predicate reads a snapshot instead. The service and the UI share this process, so one
        // collector keeps both correct.
        appScope.launch {
            dataStoreManager.statsExcludedPackages.collect { AllowedApps.setParentExcluded(it) }
        }

        // Fold a pre-multi-device parent's single pairing into the device list. THE single call site —
        // idempotent, so running it on every start is harmless, and atomic, so being killed mid-write
        // leaves either the old or the new state. See MULTI_DEVICE_PAIRING_PLAN.md §4.
        appScope.launch { runCatching { dataStoreManager.migratePairedDevicesIfNeeded() } }

        // Keep the feature config current while the process is alive.
        //
        // Remote Config otherwise fetches exactly once, when its singleton is constructed. In a normal
        // app that is fine — the process dies and restarts often. This one is a 24/7 foreground service
        // whose tamper protection actively resists being killed, so a process can live for weeks, and a
        // "go paid" flip in the Console would never reach it. The whole point of config-driven tiers is
        // that they take effect without an app update, so the fetch has to repeat.
        //
        // Firebase enforces its own minimum fetch interval (15 min in release), so this cannot spam the
        // network; a call inside the window is a local no-op.
        appScope.launch {
            while (true) {
                runCatching { remoteConfigSource.refresh() }
                kotlinx.coroutines.delay(CONFIG_REFRESH_INTERVAL_MS)
            }
        }

        // Push notifications (FCM): create the channel so background notifications display, and
        // subscribe every install to the broadcast topic so the Console can reach all users.
        ShieldMessagingService.ensureChannel(this)
        runCatching { FirebaseMessaging.getInstance().subscribeToTopic(ShieldMessagingService.TOPIC_ALL) }
    }

    private companion object {
        /** How often to re-fetch the feature config. Six hours bounds how long a device can keep a
         *  stale tier after a Console flip, without being chatty on a metered connection. */
        const val CONFIG_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
