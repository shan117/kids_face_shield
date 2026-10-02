package com.shantanu.shield.di

import com.shantanu.shield.remote.DeviceIdentity
import com.shantanu.shield.remote.FirebaseAnonymousIdentity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the device identity used to record pairing membership. See PHASE7_AUTH_PLAN.md.
 *
 * A separate module from [PremiumModule] so the Phase 7 swap is one obvious place: when `firebase-auth`
 * lands, only [FirebaseAnonymousIdentity]'s body changes and this binding stays as it is.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class IdentityModule {
    @Binds
    abstract fun bindDeviceIdentity(impl: FirebaseAnonymousIdentity): DeviceIdentity
}
