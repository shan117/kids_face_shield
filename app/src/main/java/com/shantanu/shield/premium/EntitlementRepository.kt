package com.shantanu.shield.premium

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    premiumSource: PremiumSource
) {
    val config: Flow<PaywallConfig> = configSource.config
    val isPremium: Flow<Boolean> = premiumSource.isPremium

    fun isUnlocked(feature: Feature): Flow<Boolean> =
        combine(config, isPremium) { cfg, prem -> Entitlements.isUnlocked(feature, cfg, prem) }
}
