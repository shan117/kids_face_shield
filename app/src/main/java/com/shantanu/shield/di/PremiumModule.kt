package com.shantanu.shield.di

import com.shantanu.shield.premium.DormantPremiumSource
import com.shantanu.shield.premium.LocalDefaultsConfigSource
import com.shantanu.shield.premium.PremiumSource
import com.shantanu.shield.premium.RemoteConfigSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the entitlement sources. Phase 1b swaps these to the real implementations:
 *   LocalDefaultsConfigSource -> FirebaseRemoteConfigSource
 *   DormantPremiumSource      -> BillingManager
 * Consumers (EntitlementRepository) don't change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PremiumModule {
    @Binds
    abstract fun bindConfigSource(impl: LocalDefaultsConfigSource): RemoteConfigSource

    @Binds
    abstract fun bindPremiumSource(impl: DormantPremiumSource): PremiumSource
}
