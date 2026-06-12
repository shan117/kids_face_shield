package com.shantanu.shield.premium

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the runtime [PaywallConfig]. Implemented locally now; a Firebase Remote Config backed
 * implementation is swapped in at Phase 1b (see PremiumModule) without touching any consumer.
 */
interface RemoteConfigSource {
    val config: Flow<PaywallConfig>
    suspend fun refresh() {}
}

/** Ships the in-app defaults (promo on → everything free). Replaced by Firebase later. */
@Singleton
class LocalDefaultsConfigSource @Inject constructor() : RemoteConfigSource {
    private val state = MutableStateFlow(PaywallConfig.DEFAULT)
    override val config: Flow<PaywallConfig> = state.asStateFlow()
}
