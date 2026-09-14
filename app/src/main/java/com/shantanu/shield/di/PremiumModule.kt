package com.shantanu.shield.di

import com.shantanu.shield.billing.BillingManager
import com.shantanu.shield.premium.FirebaseRemoteConfigSource
import com.shantanu.shield.premium.PremiumSource
import com.shantanu.shield.premium.RemoteConfigSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the entitlement sources. Phase 1b swaps these to the real implementations:
 *   LocalDefaultsConfigSource -> FirebaseRemoteConfigSource  (done)
 *   DormantPremiumSource      -> BillingManager              (done — dormant under the promo)
 * Consumers (EntitlementRepository) don't change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PremiumModule {
    @Binds
    abstract fun bindConfigSource(impl: FirebaseRemoteConfigSource): RemoteConfigSource

    @Binds
    abstract fun bindPremiumSource(impl: BillingManager): PremiumSource
}
