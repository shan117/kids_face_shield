package com.shantanu.shield.premium

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the user currently owns premium. Dormant until Google Play Billing is wired at Phase 1b;
 * the real implementation (BillingManager) replaces [DormantPremiumSource] via PremiumModule.
 */
interface PremiumSource {
    val isPremium: Flow<Boolean>
    fun start() {}
}

/** Always false — there is no billing yet, and the launch promo unlocks everything anyway. */
@Singleton
class DormantPremiumSource @Inject constructor() : PremiumSource {
    private val state = MutableStateFlow(false)
    override val isPremium: Flow<Boolean> = state.asStateFlow()
}
