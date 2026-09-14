package com.shantanu.shield.premium

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase Remote Config implementation of [RemoteConfigSource]. Ships the same in-app defaults
 * (promo on -> everything free) via [setDefaultsAsync], so the app is fully functional before the
 * first fetch and if Firebase is ever unreachable. The flow updates when a fetch activates.
 *
 * The "go paid" flip is a Firebase Console change (promo_active=false, paywall_enabled=true) — no
 * app update. See FIREBASE_SETUP.md.
 */
@Singleton
class FirebaseRemoteConfigSource @Inject constructor() : RemoteConfigSource {

    private val rc: FirebaseRemoteConfig = FirebaseRemoteConfig.getInstance().apply {
        setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder()
                // Debug: 0 = every launch fetches fresh, so console promo/tier flips reflect immediately
                // while testing. Release: 15 min throttle (Firebase best practice, avoids rate limits).
                .setMinimumFetchIntervalInSeconds(
                    if (com.shantanu.shield.BuildConfig.DEBUG) 0 else 900
                )
                .build()
        )
        setDefaultsAsync(
            mapOf(
                "promo_active" to true,
                "free_until_epoch_ms" to 0L,
                "paywall_enabled" to false,
                "price_tier" to "default",
                "feature_tiers_json" to "",
                "paywall_variant" to "a",
            )
        )
    }

    private val state = MutableStateFlow(read())
    override val config: Flow<PaywallConfig> = state.asStateFlow()

    init {
        rc.fetchAndActivate().addOnCompleteListener { state.value = read() }
    }

    override suspend fun refresh() {
        rc.fetchAndActivate().addOnCompleteListener { state.value = read() }
    }

    private fun read(): PaywallConfig = PaywallConfig(
        promoActive = rc.getBoolean("promo_active"),
        freeUntilEpochMs = rc.getLong("free_until_epoch_ms"),
        paywallEnabled = rc.getBoolean("paywall_enabled"),
        priceTier = rc.getString("price_tier").ifBlank { "default" },
        premiumFeatures = parseFeatureTiers(rc.getString("feature_tiers_json")) ?: Feature.DEFAULT_PREMIUM,
        paywallVariant = rc.getString("paywall_variant").ifBlank { "a" },
    )

    // feature_tiers_json e.g. {"THEMES":"free","MULTI_KID_PROFILES":"premium"}. Unlisted -> default map.
    private fun parseFeatureTiers(json: String): Set<Feature>? {
        if (json.isBlank()) return null
        return runCatching {
            val obj = org.json.JSONObject(json)
            val out = Feature.DEFAULT_PREMIUM.toMutableSet()
            for (f in Feature.entries) {
                if (obj.has(f.name)) {
                    if (obj.getString(f.name).equals("premium", ignoreCase = true)) out.add(f) else out.remove(f)
                }
            }
            out
        }.getOrNull()
    }
}
