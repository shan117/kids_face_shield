package com.shantanu.shield.ui.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shantanu.shield.premium.EntitlementRepository
import com.shantanu.shield.premium.PaywallConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs the early-access / paywall screen. Reads the runtime [PaywallConfig] (promo vs paywall mode)
 * from the entitlement layer. Purchase actions are dormant until Billing is wired (Phase 1b).
 */
@HiltViewModel
class PaywallViewModel @Inject constructor(
    entitlements: EntitlementRepository
) : ViewModel() {
    val config: StateFlow<PaywallConfig> =
        entitlements.config.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), PaywallConfig.DEFAULT
        )
}
