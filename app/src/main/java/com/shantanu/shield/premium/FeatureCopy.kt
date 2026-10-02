package com.shantanu.shield.premium

/**
 * Human-readable copy for every [Feature], in one place.
 *
 * Exists because the paywall used to advertise a hardcoded list of four strings that had no
 * connection to the runtime config: flip a feature's tier in the Console and the paywall would go on
 * describing a tier that no longer existed. Anything that shows the user what Plus includes now reads
 * the live `premiumFeatures` set and renders it through here, so the sales copy cannot drift from what
 * is actually locked.
 *
 * [title] names the capability. [description] says what the parent gets — not how it is implemented.
 */
object FeatureCopy {

    fun title(feature: Feature): String = when (feature) {
        Feature.APP_LOCK -> "App lock"
        Feature.KID_BUDGET -> "Daily screen-time limit"
        Feature.NIGHT_LOCK -> "Night lock"
        Feature.ALLOWED_PRESETS -> "Always-allowed apps"
        Feature.FREE_PLAY -> "Free play"
        Feature.TAMPER -> "Tamper protection"
        Feature.BASIC_STATS -> "Screen-time stats"
        Feature.MULTI_KID_PROFILES -> "Multiple kids"
        Feature.SCHEDULES -> "Schedules"
        Feature.PER_APP_LIMITS -> "Per-app limits"
        Feature.FULL_STATS -> "Full history & trends"
        Feature.EARNED_TIME -> "Earned time"
        Feature.MULTI_PARENT -> "Multiple parents"
        Feature.NEW_APP_AUTO_BLOCK -> "Auto-block new apps"
        Feature.THEMES -> "Themes"
        Feature.REMOTE_REPORT -> "Remote report"
        Feature.REMOTE_CONTROL -> "Remote control"
        Feature.LOCATION_NOW -> "Find their phone"
        Feature.LOCATION_HISTORY -> "Location history"
        Feature.WEB_FILTER -> "Safe browsing"
    }

    fun description(feature: Feature): String = when (feature) {
        Feature.APP_LOCK -> "Face-unlock any app you choose"
        Feature.KID_BUDGET -> "Cap the day and lock apps when it runs out"
        Feature.NIGHT_LOCK -> "Apps stay locked from 10 PM to 7 AM"
        Feature.ALLOWED_PRESETS -> "Choose what stays open at the limit"
        Feature.FREE_PLAY -> "Grant bonus minutes on the spot"
        Feature.TAMPER -> "Close the routes around Kids Shield"
        Feature.BASIC_STATS -> "Today's usage and this week at a glance"
        Feature.MULTI_KID_PROFILES -> "One phone, a separate budget per child"
        Feature.SCHEDULES -> "Automatic lock windows for school and bedtime"
        Feature.PER_APP_LIMITS -> "Cap one app, not just the whole day"
        Feature.FULL_STATS -> "Four-week trends, categories and insights"
        Feature.EARNED_TIME -> "Let them earn extra minutes"
        Feature.MULTI_PARENT -> "More than one trusted face"
        Feature.NEW_APP_AUTO_BLOCK -> "Newly installed apps lock themselves"
        Feature.THEMES -> "Accent colours and lock-screen styles"
        Feature.REMOTE_REPORT -> "See their screen time on your own phone"
        Feature.REMOTE_CONTROL -> "Lock, unlock and grant time from anywhere"
        Feature.LOCATION_NOW -> "Ask where their phone is, right now"
        Feature.LOCATION_HISTORY -> "Every place you've looked them up before"
        Feature.WEB_FILTER -> "Block adult sites, with a browser you control"
    }

    /**
     * The premium set in a stable, readable order — safety capabilities first, then conveniences,
     * matching the declaration order in [Feature]. Never alphabetical: a list that reshuffles when a
     * tier changes reads as a different product.
     */
    fun ordered(features: Set<Feature>): List<Feature> =
        Feature.entries.filter { it in features }
}
