package com.shantanu.shield.premium

/** Free vs premium classification for a [Feature]. */
enum class Tier { FREE, PREMIUM }

/**
 * Every gateable capability in the app. UI and the service ask
 * [EntitlementRepository.isUnlocked] about these — they never hardcode "is premium".
 *
 * Every feature's free/premium status is **config-driven** (Remote Config `feature_tiers_json`), so
 * ANY feature can be converted to premium later with no app update. [DEFAULT_FREE] / [DEFAULT_PREMIUM]
 * are only the shipped DEFAULTS — the config can move any feature either way.
 *
 * Note: converting a default-free feature to premium also requires its enforcement *gate* to exist in
 * code (the default-premium features already have theirs; default-free ones get gates per feature).
 */
enum class Feature {
    // --- Default-free at launch (flippable to premium via config) ---
    APP_LOCK, KID_BUDGET, NIGHT_LOCK, ALLOWED_PRESETS, FREE_PLAY, TAMPER, BASIC_STATS,
    /** Ask the child's phone where it is, right now. */
    LOCATION_NOW,
    /** Filtered in-app browser; every other browser on the phone is blocked. */
    WEB_FILTER,

    // --- Default-premium (gated via Remote Config once the promo ends) ---
    MULTI_KID_PROFILES, SCHEDULES, PER_APP_LIMITS, FULL_STATS, EARNED_TIME,
    MULTI_PARENT, NEW_APP_AUTO_BLOCK, THEMES, REMOTE_REPORT, REMOTE_CONTROL,
    /** The log of past location requests. Recorded regardless; this gates seeing more than the latest. */
    LOCATION_HISTORY;

    companion object {
        /** Features that ship FREE by default. Not a hard guarantee — the config can convert any of
         *  these to premium (each needs its enforcement gate added in code to take effect). */
        val DEFAULT_FREE: Set<Feature> = setOf(
            APP_LOCK, KID_BUDGET, NIGHT_LOCK, ALLOWED_PRESETS, FREE_PLAY, TAMPER, BASIC_STATS,
            LOCATION_NOW, WEB_FILTER
        )

        /** Features that ship PREMIUM by default. Remote Config can override either set. */
        val DEFAULT_PREMIUM: Set<Feature> = setOf(
            MULTI_KID_PROFILES, SCHEDULES, PER_APP_LIMITS, FULL_STATS, EARNED_TIME,
            MULTI_PARENT, NEW_APP_AUTO_BLOCK, THEMES, REMOTE_REPORT, REMOTE_CONTROL,
            LOCATION_HISTORY
        )
    }
}
