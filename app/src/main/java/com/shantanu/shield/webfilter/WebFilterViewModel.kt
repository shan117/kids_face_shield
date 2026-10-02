package com.shantanu.shield.webfilter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shantanu.shield.data.DataStoreManager
import com.shantanu.shield.premium.EntitlementRepository
import com.shantanu.shield.premium.Feature
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the Web filtering screen — both layers.
 *
 * The in-app browser (Phase 1/2) is the primary control and is fully settable here. Private DNS
 * (Phase 0) is read-only by nature: the app can verify it but never set it.
 */
@HiltViewModel
class WebFilterViewModel @Inject constructor(
    monitor: PrivateDnsMonitor,
    private val dataStore: DataStoreManager,
    entitlements: EntitlementRepository,
) : ViewModel() {

    // ---- Layer 1: the in-app browser ----

    val unlocked: StateFlow<Boolean> =
        entitlements.isUnlocked(Feature.WEB_FILTER)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val enabled: StateFlow<Boolean> =
        dataStore.webFilterEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val categories: StateFlow<Set<WebFilterCategory>> =
        dataStore.webFilterCategories
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebFilterCategory.DEFAULT_ON)

    val allowedDomains: StateFlow<Set<String>> =
        dataStore.webAllowedDomains.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val blockedDomains: StateFlow<Set<String>> =
        dataStore.webBlockedDomains.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Per-category counts of refused navigations. Counts only — the app never stores which sites. */
    val blockCounts: StateFlow<Map<WebFilterCategory, Int>> =
        dataStore.webBlockCounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setEnabled(on: Boolean) {
        viewModelScope.launch { dataStore.setWebFilterEnabled(on) }
    }

    fun toggleCategory(category: WebFilterCategory, on: Boolean) {
        viewModelScope.launch {
            val current = dataStore.webFilterCategories.first()
            dataStore.setWebFilterCategories(
                if (on) current + category else current - category
            )
        }
    }

    /** Add a parent override. [allow] true = always allow, false = always block. */
    fun addOverride(domain: String, allow: Boolean) {
        viewModelScope.launch { dataStore.setWebDomainOverride(domain, allow = allow) }
    }

    fun removeOverride(domain: String) {
        viewModelScope.launch { dataStore.setWebDomainOverride(domain, allow = null, remove = true) }
    }

    fun clearCounts() {
        viewModelScope.launch { dataStore.clearWebBlockCounts() }
    }

    // ---- Layer 2: device-wide Private DNS (read-only) ----

    /** Live Private DNS status, so the card flips to "on" the moment the parent saves the hostname. */
    val dnsState: StateFlow<PrivateDnsState> =
        monitor.state.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            // Seeded with a synchronous read rather than a guess: a parent returning to this screen
            // should never see "off" flash before the real value arrives.
            monitor.current(),
        )

    /** Whether the child can reach Android Settings to undo the device-wide filter. */
    val settingsLocked: StateFlow<Boolean> =
        dataStore.lockDeviceSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
