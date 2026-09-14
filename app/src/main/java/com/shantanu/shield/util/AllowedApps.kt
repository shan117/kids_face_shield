package com.shantanu.shield.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Telephony
import com.shantanu.shield.data.DataStoreManager
import java.util.concurrent.ConcurrentHashMap
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
        // Clock / wallpaper-carousel style utilities never count toward a budget, even on an OEM
        // that ships them without FLAG_SYSTEM. Checked before TIME_SINK_PACKAGES (no overlap today,
        // but the denylist is the stronger statement).
        if (isNonScreenTime(packageName, isSystemApp)) return false
        if (packageName in TIME_SINK_PACKAGES) return true
        return !isSystemApp
    }

    // Context-bound convenience used by the service & Stats. Resolves the system flag
    // from PackageManager, then delegates to the pure classifier above. Unknown /
    // uninstalled packages are treated as not-controlled.
    fun isControlledPackage(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false
        if (packageName in UTILITY_PACKAGES) return false
        // Device-truth + parent override, checked BEFORE the time-sink list: if the parent has said
        // an app is not screen time, that holds even for a curated time-sink like YouTube. (They can
        // still face-lock it from the parent-mode Protect picker, which is a separate path.)
        if (isDeviceUtility(context, packageName)) return false
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
    //
    // Device utilities that are NOT screen time. These leaked into the parent dashboard because
    // several of them ship as system apps that are updated from the Play Store
    // (FLAG_UPDATED_SYSTEM_APP), which the `!isSystemApp || isUpdatedSystemApp` rule admits:
    //   - Clock — alarms, timers, stopwatch. A ringing alarm holds the foreground.
    //   - Wallpaper pickers and lock-screen wallpaper carousels (Realme/Oppo "Pictorial",
    //     MIUI Glance/fashiongallery, Google Wallpapers). The carousel is foreground on every
    //     lock-screen swipe, so it accrues minutes nobody spent using a phone.
    // Denylisted for BOTH views: they must never show in Stats and never count toward a budget.
    val UTILITY_PACKAGES: Set<String> = setOf(
        "com.google.android.apps.maps",       // Google Maps — navigation, not screen-time
        // Clock (alarms / timers / stopwatch), per OEM.
        "com.google.android.deskclock",       // Google Clock (updated system app)
        "com.android.deskclock",              // AOSP / MIUI clock
        "com.android.alarmclock",             // legacy AOSP alarm
        "com.sec.android.app.clockpackage",   // Samsung Clock
        "com.coloros.alarmclock",             // ColorOS / Realme (older)
        "com.oplus.alarmclock",               // ColorOS / Realme (newer)
        "com.oneplus.deskclock",              // OxygenOS
        "com.huawei.deskclock",               // EMUI
        "com.android.BBKClock",               // vivo / iQOO
        "com.transsion.deskclock",            // Tecno / Infinix / itel
        // Wallpaper pickers + lock-screen wallpaper carousels ("magazine lock screen"), per OEM.
        // Best-effort as of the knowledge cutoff — OEMs rename these between skin versions, which
        // is exactly why R1 (no launcher icon) below is the real net and this is only a fast path.
        "com.google.android.apps.wallpaper",  // Google Wallpapers (carousel host on Pixel)
        "com.android.wallpaper.livepicker",   // AOSP live-wallpaper picker
        "com.android.wallpapercropper",       // AOSP wallpaper cropper
        "com.heytap.pictorial",               // Realme / Oppo "Wallpaper Carousel" (Pictorial)
        "com.oplus.pictorial",                // same, newer ColorOS package name
        "com.coloros.pictorial",              // same, older ColorOS package name
        "com.coloros.wallpapers",             // ColorOS wallpaper picker
        "com.oplus.wallpapers",               // ColorOS wallpaper picker (newer)
        "com.miui.miwallpaper",               // MIUI / HyperOS wallpaper engine
        "com.mfashiongallery.emag",           // MIUI wallpaper carousel (Glance for MIUI)
        "com.miui.android.fashiongallery",    // MIUI wallpaper carousel (newer)
        "com.glance.internet",                // Glance — preloaded carousel on many Indian SKUs
        "com.samsung.android.app.dressroom",  // Samsung "Wallpaper services" / Dynamic Lock Screen
        "com.samsung.android.wallpaper.res",  // Samsung wallpaper resources
        "com.vivo.magazine",                  // vivo / iQOO magazine lock screen
        "com.motorola.personalize",           // Motorola wallpapers / personalize
        "com.transsion.magazineservice",      // Tecno / Infinix / itel magazine lock
    )

    // Long-tail catch-all for the same two families on OEMs not named above. Applied to
    // PRE-INSTALLED apps only, so a user-INSTALLED wallpaper browser (a real time-sink the kid
    // can scroll for an hour) is still counted. Deliberately narrow: "clock" alone would also
    // match com.google.android.clockwork.*, so only the two clock-app conventions are matched.
    private val SYSTEM_UTILITY_NAME_HINTS = listOf(
        "deskclock", "alarmclock", "clockpackage", "worldclock",
        "wallpaper", "pictorial", "magazine", "fashiongallery",
    )

    /**
     * True when [packageName] is a device utility whose foreground time is not screen time —
     * either an explicitly denylisted package or a pre-installed clock/wallpaper app matched by
     * name. Pure and unit-testable; consulted by BOTH the kid-controlled predicate and the
     * parent-visibility predicate, so Stats and the budget can never disagree about it.
     */
    fun isNonScreenTime(
        packageName: String,
        isSystemApp: Boolean,
        isUpdatedSystemApp: Boolean = false
    ): Boolean {
        if (packageName in UTILITY_PACKAGES) return true
        // An explicitly curated time-sink always wins over the name heuristic. Without this the
        // "magazine" hint would hide Google News (com.google.android.apps.magazines), which is a
        // real content feed we deliberately control.
        if (packageName in TIME_SINK_PACKAGES) return false
        if (!isSystemApp && !isUpdatedSystemApp) return false
        val lower = packageName.lowercase()
        return SYSTEM_UTILITY_NAME_HINTS.any { it in lower }
    }

    // ---- Device-truth utility detection (OEM-agnostic) ----
    //
    // A curated package list can never cover every OEM skin — there are dozens, and ColorOS/MIUI
    // rename packages between versions. So the list above is only a BACKSTOP. The rules here ask
    // the device what a package actually is, exactly as we already do for the dialer and SMS app,
    // and therefore work on an OEM we have never seen:
    //
    //   R1 (no launcher icon) — a package with no CATEGORY_LAUNCHER activity cannot be opened by
    //      the kid from the home screen, so its foreground time is never "using an app". This is
    //      what catches lock-screen wallpaper carousels (Realme/Oppo Pictorial, MIUI Glance),
    //      wallpaper engines, live-wallpaper pickers, and OEM background services generically.
    //
    //   R2 (clock role) — whatever package handles ACTION_SHOW_ALARMS / ACTION_SET_ALARM *is* this
    //      device's clock, whatever it is called. Restricted to pre-installed apps so a clock app
    //      the kid chose to install is still treated normally.
    //
    //   R3 (home role) — every CATEGORY_HOME package. The home screen is not screen time, and some
    //      launchers (Pixel Launcher) are updated system apps that would otherwise be admitted.
    //
    // Deliberately NOT a rule: querying ACTION_SET_WALLPAPER handlers. Gallery/Photos apps declare
    // it too, and those are real screen time — it would hide the wrong thing.
    //
    // Results are cached: these predicates run on the 250 ms lock-decision path and per bucket in
    // Stats. Call [clearPackageCaches] when an app is installed or removed.

    private val launchableCache = ConcurrentHashMap<String, Boolean>()
    @Volatile private var roleUtilityCache: Set<String>? = null

    // ---- Parent override (L4) ----
    //
    // The last line of defence: whatever the rules above conclude, the PARENT can mark an app
    // "not screen time" from the parent dashboard. Covers any OEM package nobody predicted, and is
    // also right on the merits — families disagree about whether e.g. Spotify counts.
    //
    // Kept as a plain snapshot because the exclusion is read on the 250 ms lock-decision path,
    // where a suspending DataStore read is not affordable. `AppLockApplication` owns the single
    // process-wide collector that keeps it in sync; the service and the UI share that process.
    @Volatile private var parentExcluded: Set<String> = emptySet()

    /** Replace the parent's "not screen time" set. Called only by the collector in AppLockApplication. */
    fun setParentExcluded(packages: Set<String>) { parentExcluded = packages }

    /** Snapshot of the parent's "not screen time" set (for UI that needs it synchronously). */
    fun parentExcluded(): Set<String> = parentExcluded

    /** Drop the cached device-truth results. Call on PACKAGE_ADDED / PACKAGE_REMOVED. */
    fun clearPackageCaches() {
        launchableCache.clear()
        roleUtilityCache = null
    }

    /** R2 + R3: packages that hold a non-screen-time system role on THIS device. Resolved once. */
    private fun roleUtilityPackages(context: Context): Set<String> {
        roleUtilityCache?.let { return it }
        val pm = context.packageManager
        val out = HashSet<String>()

        // R2 — the clock app, whichever package the OEM ships.
        for (action in arrayOf(AlarmClock.ACTION_SHOW_ALARMS, AlarmClock.ACTION_SET_ALARM)) {
            for (ri in pm.queryIntentActivities(Intent(action), 0)) {
                val pkg = ri.activityInfo?.packageName ?: continue
                val preinstalled = runCatching {
                    (pm.getApplicationInfo(pkg, 0).flags and
                        (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                }.getOrDefault(false)
                if (preinstalled) out.add(pkg)
            }
        }

        // R3 — every home / launcher package.
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        for (ri in pm.queryIntentActivities(home, 0)) {
            ri.activityInfo?.packageName?.let { out.add(it) }
        }
        // Settings ships Settings$FallbackHome, which declares CATEGORY_HOME but is not a launcher.
        // Keep Settings out of the utility set so the parent's "Lock System Settings" is unaffected.
        out.remove(TamperProtection.SETTINGS_PACKAGE)
        out.remove(context.packageName)

        return out.also { roleUtilityCache = it }
    }

    /** R1: can the user open this package from the launcher? Cached per package. */
    private fun isLaunchable(context: Context, packageName: String): Boolean =
        launchableCache.getOrPut(packageName) {
            runCatching { context.packageManager.getLaunchIntentForPackage(packageName) != null }
                .getOrDefault(true)   // on failure assume launchable — never hide a real app
        }

    /**
     * True when the device itself says [packageName] is not a user-facing app whose time counts:
     * it has no launcher icon (R1), or it holds the clock/home role (R2/R3). OEM-agnostic — this is
     * what covers the skins the curated [UTILITY_PACKAGES] list will never enumerate.
     */
    fun isDeviceUtility(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false
        if (packageName in parentExcluded) return true     // L4 — the parent's explicit call wins
        if (packageName in roleUtilityPackages(context)) return true
        return !isLaunchable(context, packageName)
    }

    // Pure, side-effect-free parent-visibility classifier — unit-testable without a
    // PackageManager. `isSystemApp`/`isUpdatedSystemApp` come from ApplicationInfo.flags.
    fun isParentVisible(
        packageName: String,
        selfPackage: String,
        isSystemApp: Boolean,
        isUpdatedSystemApp: Boolean
    ): Boolean {
        if (packageName == selfPackage) return false
        // Denylisted utilities + pre-installed clock / wallpaper-carousel apps. This is what kept
        // Clock and the lock-screen wallpaper carousel in the parent dashboard: they are system
        // apps updated from the Play Store, so the rule below admitted them.
        if (isNonScreenTime(packageName, isSystemApp, isUpdatedSystemApp)) return false
        if (packageName in TIME_SINK_PACKAGES) return true
        // User-installed apps + updated-system apps (Chrome/Gmail-type), excluding the
        // pure-system apps the user never installed (Settings, Camera, the dialer, …).
        return !isSystemApp || isUpdatedSystemApp
    }

    fun isVisibleInParentStats(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false
        if (isDeviceUtility(context, packageName)) return false
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
