package com.shantanu.shield.webfilter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shantanu.shield.data.DataStoreManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The rules the browser filters against, as one snapshot. */
data class WebFilterConfig(
    val enabledCategories: Set<WebFilterCategory> = WebFilterCategory.DEFAULT_ON,
    val parentAllowed: Set<String> = emptySet(),
    val parentBlocked: Set<String> = emptySet(),
)

@HiltViewModel
class ShieldBrowserViewModel @Inject constructor(
    private val dataStore: DataStoreManager,
) : ViewModel() {

    /**
     * Live rules, so a category the parent changes remotely takes effect on the next navigation rather
     * than at the next app restart.
     *
     * Seeded with the shipped defaults, never with "nothing enabled": the browser may render its first
     * page before the first DataStore emission arrives, and a permissive seed would let one page
     * through unfiltered.
     */
    val config: StateFlow<WebFilterConfig> =
        combine(
            dataStore.webFilterCategories,
            dataStore.webAllowedDomains,
            dataStore.webBlockedDomains,
        ) { categories, allowed, blocked ->
            WebFilterConfig(categories, allowed, blocked)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, WebFilterConfig())

    /** Count a refusal. Category only — never the domain; see DataStoreManager.recordWebBlock. */
    fun recordBlock(decision: FilterDecision) {
        val category = (decision as? FilterDecision.BlockedByCategory)?.category ?: return
        viewModelScope.launch { dataStore.recordWebBlock(category) }
    }
}
