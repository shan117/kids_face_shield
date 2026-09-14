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

        // Push notifications (FCM): create the channel so background notifications display, and
        // subscribe every install to the broadcast topic so the Console can reach all users.
        ShieldMessagingService.ensureChannel(this)
        runCatching { FirebaseMessaging.getInstance().subscribeToTopic(ShieldMessagingService.TOPIC_ALL) }
    }
}
