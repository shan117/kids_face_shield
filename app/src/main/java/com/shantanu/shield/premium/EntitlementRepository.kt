package com.shantanu.shield.premium

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for entitlement, consumed by UI and the service. Combines the runtime
 * config (Remote Config) with the user's premium state (Billing) into a per-feature unlock flow.
 *
 * Consumers call [isUnlocked] and never reason about promo/tier/premium themselves.
 */
@Singleton
class EntitlementRepository @Inject constructor(
    configSource: RemoteConfigSource,
    premiumSource: PremiumSource,
    dataStoreManager: com.shantanu.shield.data.DataStoreManager,
) {
    val config: Flow<PaywallConfig> = configSource.config
    val isPremium: Flow<Boolean> = premiumSource.isPremium

    /**
     * Whether a paired parent's subscription currently covers this device.
     *
     * Validity is recomputed from the stored expiry on every emission rather than stored as a boolean,
     * so a lapsed grant cannot linger as "still true". See ENTITLEMENT_SHARING_PLAN.md.
     */
    private val grantedPremium: Flow<Boolean> = dataStoreManager.premiumGrantUntilMs.map { until ->
        com.shantanu.shield.remote.PremiumGrants.isValid(until, System.currentTimeMillis())
    }

    fun isUnlocked(feature: Feature): Flow<Boolean> =
        combine(config, isPremium, grantedPremium) { cfg, prem, granted ->
            Entitlements.isUnlocked(feature, cfg, prem, granted)
        }
}
