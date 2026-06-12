package com.shantanu.shield.premium

/** Free vs premium classification for a [Feature]. */
enum class Tier { FREE, PREMIUM }

/**
 * Every gateable capability in the app. UI and the service ask
 * [EntitlementRepository.isUnlocked] about these — they never hardcode "is premium".
 *
 * Flipping a premium-candidate free<->premium is a config change (Remote Config), not code.
 * Core safety features are hardcoded always-free and can never be flipped (protects kids).
 */
enum class Feature {
    // --- Core: hardcoded always-free (never gated) ---
    APP_LOCK, KID_BUDGET, NIGHT_LOCK, ALLOWED_PRESETS, FREE_PLAY, TAMPER, BASIC_STATS,

    // --- Premium-candidates: gated via Remote Config once the promo ends ---
    MULTI_KID_PROFILES, SCHEDULES, PER_APP_LIMITS, FULL_STATS, EARNED_TIME,
    MULTI_PARENT, NEW_APP_AUTO_BLOCK, THEMES;

    companion object {
        /** Always free, no matter what the config says — child safety must never be paywalled. */
        val CORE_ALWAYS_FREE: Set<Feature> = setOf(
            APP_LOCK, KID_BUDGET, NIGHT_LOCK, ALLOWED_PRESETS, FREE_PLAY, TAMPER, BASIC_STATS
        )

        /** Shipped default for premium-candidates. Remote Config overrides this after the promo. */
        val DEFAULT_PREMIUM: Set<Feature> = setOf(
            MULTI_KID_PROFILES, SCHEDULES, PER_APP_LIMITS, FULL_STATS, EARNED_TIME,
            MULTI_PARENT, NEW_APP_AUTO_BLOCK, THEMES
        )
    }
}
