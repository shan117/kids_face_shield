package com.shantanu.shield.premium

import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.remote.RemoteReportSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Revoke-on-lapse (A2). When a premium feature is no longer entitled (promo ended / subscription lapsed) but
 * is still turned ON, actively switch it OFF — so a feature enabled during the promo doesn't keep running for
 * free. Enforcement-gated features (budget/night/per-app/…) already stop in the service via their `*Unlocked`
 * flags; this handles the user-facing toggles + settings that otherwise persist. Idempotent (sets off once).
 */
@Singleton
class EntitlementRevoker @Inject constructor(
    private val entitlements: EntitlementRepository,
    private val dataStore: DataStoreManager,
    private val reportSync: RemoteReportSync,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        watch(Feature.REMOTE_REPORT, dataStore.remoteShareEnabled) { reportSync.setSharing(false) }
        watch(Feature.REMOTE_CONTROL, dataStore.remoteControlEnabled) { dataStore.setRemoteControlEnabled(false) }
        watch(Feature.MULTI_KID_PROFILES, dataStore.multiKidEnabled) { dataStore.setMultiKidEnabled(false) }
        watch(Feature.NEW_APP_AUTO_BLOCK, dataStore.autoBlockNewApps) { dataStore.setAutoBlockNewApps(false) }
        // Themes: revert to the default accent when Themes lapses.
        scope.launch {
            combine(entitlements.isUnlocked(Feature.THEMES), dataStore.themeAccent) { ok, accent -> ok to accent }
                .collect { (ok, accent) -> if (!ok && accent != DEFAULT_ACCENT) dataStore.setThemeAccent(DEFAULT_ACCENT) }
        }
    }

    /** Turn [isOn] OFF (via [turnOff]) whenever [feature] is locked but the setting is still on. */
    private fun watch(feature: Feature, isOn: Flow<Boolean>, turnOff: suspend () -> Unit) {
        scope.launch {
            combine(entitlements.isUnlocked(feature), isOn) { ok, on -> ok to on }
                .collect { (ok, on) -> if (!ok && on) turnOff() }
        }
    }

    private companion object { const val DEFAULT_ACCENT = "teal" }
}
