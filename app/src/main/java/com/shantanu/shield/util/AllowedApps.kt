package com.shantanu.shield.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import com.shantanu.shield.data.DataStoreManager
import kotlinx.coroutines.flow.first

// Helpers for the Kid Mode "always-allowed" presets. See KID_MODE_FEATURE_PLAN.md.
//
// We resolve the default phone & SMS packages at runtime rather than hard-coding
// names like "com.google.android.dialer" so the feature works on any OEM
// (Samsung, Xiaomi, Pixel, etc.).
object AllowedApps {

    const val PRESET_PHONE_MESSAGES = 0
    const val PRESET_PHONE_MESSAGES_WHATSAPP = 1
    const val PRESET_CUSTOM = 2

    private const val WHATSAPP_PACKAGE = "com.whatsapp"

    // ---- Canonical "controlled app" predicate (Kid Mode budget + lock + Stats) ----
    //
    // A *controlled* app is one a kid can sink addictive time into, so it must count
    // against the daily budget, be lockable when the budget is over / at night, and
    // appear in the screen-time dashboard. The three consumers (budget poll, lock
    // enforcement, Stats) all route through isControlledPackage() so they can never
    // drift apart again.
    //
    // Rule:
    //   - Our own app is never controlled.
    //   - Any normal user-installed app (not FLAG_SYSTEM) is controlled — Instagram,
    //     TikTok, games, etc.
    //   - Pre-installed (system) apps are NOT controlled by default, so utilities the
    //     kid genuinely needs — Gmail, Maps, Photos, Phone, Messages, Settings — stay
    //     free.
    //   - EXCEPT a curated set of pre-installed "time-sink" apps (browsers, YouTube,
    //     video/game hubs, news feeds) that ARE controlled even though they ship as
    //     system apps, because they are the addictive vectors a budget exists to limit.
    //
    // (The parent-mode per-app Protect picker is deliberately broader than this and is
    // not gated by this predicate — a parent may still face-lock Gmail in parent mode.)
    val TIME_SINK_PACKAGES: Set<String> = setOf(
        // Browsers — the main "endless content" vector, often pre-installed.
        "com.android.chrome",                 // Google Chrome
        "com.sec.android.app.sbrowser",       // Samsung Internet
        "com.android.browser",                // AOSP / legacy default browser
        "com.mi.globalbrowser",               // Xiaomi (Mi) Browser
        "com.mi.globalbrowser.mini",          // Xiaomi Mint Browser
        "com.heytap.browser",                 // Oppo / Realme browser
        "com.vivo.browser",                   // Vivo browser
        "com.huawei.browser",                 // Huawei browser
        // Video.
        "com.google.android.youtube",         // YouTube
        "com.google.android.videos",          // Google TV / Play Movies
        // Game hub.
        "com.google.android.play.games",      // Google Play Games
        // News / infinite-scroll feeds.
        "com.google.android.apps.magazines",  // Google News
    )

    // Pure, side-effect-free classifier — extracted so it can be unit-tested without a
    // PackageManager. `isSystemApp` is (ApplicationInfo.flags and FLAG_SYSTEM != 0).
    fun isControlled(packageName: String, selfPackage: String, isSystemApp: Boolean): Boolean {
        if (packageName == selfPackage) return false
        if (packageName in TIME_SINK_PACKAGES) return true
        return !isSystemApp
    }

    // Context-bound convenience used by the service & Stats. Resolves the system flag
    // from PackageManager, then delegates to the pure classifier above. Unknown /
    // uninstalled packages are treated as not-controlled.
    fun isControlledPackage(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false
        if (packageName in TIME_SINK_PACKAGES) return true
        val isSystem = try {
            (context.packageManager.getApplicationInfo(packageName, 0).flags and
                ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        return isControlled(packageName, context.packageName, isSystem)
    }

    // ---- Parent-dashboard visibility ----
    //
    // The PARENT Stats view is broader than the kid budget: it shows the parent where
    // time goes on the device, so it includes everyday apps the budget leaves free —
    // Gmail, Photos, Drive, etc. — on top of the controlled set. It still hides pure
    // *utilities* that aren't really "screen time" (navigation), starting with Maps.
    //
    // This affects the PARENT dashboard only — it never changes the kid budget, the lock
    // decision, or the kid dashboard, which all stay on isControlledPackage().
    //
    // Concretely: any user-installed app or *updated*-system app (Chrome, Gmail, Maps as
    // raw candidates), minus the curated utility denylist. Extend UTILITY_PACKAGES to drop
    // more pure utilities from the parent view.
    val UTILITY_PACKAGES: Set<String> = setOf(
        "com.google.android.apps.maps",   // Google Maps — navigation, not screen-time
    )

    // Pure, side-effect-free parent-visibility classifier — unit-testable without a
    // PackageManager. `isSystemApp`/`isUpdatedSystemApp` come from ApplicationInfo.flags.
    fun isParentVisible(
        packageName: String,
        selfPackage: String,
        isSystemApp: Boolean,
        isUpdatedSystemApp: Boolean
    ): Boolean {
        if (packageName == selfPackage) return false
        if (packageName in UTILITY_PACKAGES) return false
        if (packageName in TIME_SINK_PACKAGES) return true
        // User-installed apps + updated-system apps (Chrome/Gmail-type), excluding the
        // pure-system apps the user never installed (Settings, Camera, the dialer, …).
        return !isSystemApp || isUpdatedSystemApp
    }

    fun isVisibleInParentStats(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false
        val flags = try {
            context.packageManager.getApplicationInfo(packageName, 0).flags
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        return isParentVisible(
            packageName,
            context.packageName,
            isSystemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            isUpdatedSystemApp = (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        )
    }

    fun resolveDefaultPhonePackage(context: Context): String? {
        // ACTION_DIAL resolves to the default dialer on every Android version we support.
        val intent = Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:") }
        return context.packageManager
            .resolveActivity(intent, 0)
            ?.activityInfo
            ?.packageName
    }

    fun resolveDefaultSmsPackage(context: Context): String? {
        // Telephony.Sms.getDefaultSmsPackage is the canonical API for this since KitKat.
        return Telephony.Sms.getDefaultSmsPackage(context)
    }

    // Compute the always-allowed set for the current preset choice. Our own package is
    // always included so the parent can never lock themselves out of the app that
    // manages the budget.
    fun computeAlwaysAllowedSet(
        context: Context,
        preset: Int,
        customAllowed: Set<String>
    ): Set<String> {
        val out = LinkedHashSet<String>()
        out.add(context.packageName)
        when (preset) {
            PRESET_PHONE_MESSAGES -> {
                resolveDefaultPhonePackage(context)?.let { out.add(it) }
                resolveDefaultSmsPackage(context)?.let { out.add(it) }
            }
            PRESET_PHONE_MESSAGES_WHATSAPP -> {
                resolveDefaultPhonePackage(context)?.let { out.add(it) }
                resolveDefaultSmsPackage(context)?.let { out.add(it) }
                out.add(WHATSAPP_PACKAGE)
            }
            PRESET_CUSTOM -> {
                out.addAll(customAllowed)
            }
        }
        return out
    }

    // Suspending convenience that reads the current preset + custom set from DataStore.
    suspend fun computeAlwaysAllowedSet(
        context: Context,
        dataStore: DataStoreManager
    ): Set<String> {
        val preset = dataStore.alwaysAllowedPreset.first()
        val custom = dataStore.customAlwaysAllowed.first()
        return computeAlwaysAllowedSet(context, preset, custom)
    }

    // Best-effort human-readable label for a package, falling back to the package name.
    fun labelFor(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
