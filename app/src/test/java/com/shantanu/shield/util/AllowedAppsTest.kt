package com.shantanu.shield.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the canonical "controlled app" classifier shared by the Kid Mode budget poll,
 * the lock decision, and the Stats dashboard. If these expectations drift, those three
 * surfaces silently disagree again (the exact bug this predicate was introduced to fix).
 */
class AllowedAppsTest {

    private val self = "com.shantanu.shield"

    @Test
    fun `our own app is never controlled`() {
        assertFalse(AllowedApps.isControlled(self, self, isSystemApp = false))
        assertFalse(AllowedApps.isControlled(self, self, isSystemApp = true))
    }

    @Test
    fun `normal user-installed apps are controlled`() {
        // Instagram, TikTok, a game — not system, not in the time-sink list.
        assertTrue(AllowedApps.isControlled("com.instagram.android", self, isSystemApp = false))
        assertTrue(AllowedApps.isControlled("com.zhiliaoapp.musically", self, isSystemApp = false))
        assertTrue(AllowedApps.isControlled("com.some.random.game", self, isSystemApp = false))
    }

    @Test
    fun `pre-installed utilities stay free`() {
        // Gmail, Maps, the dialer — system apps that are NOT addictive time-sinks.
        assertFalse(AllowedApps.isControlled("com.google.android.gm", self, isSystemApp = true))
        assertFalse(AllowedApps.isControlled("com.google.android.apps.maps", self, isSystemApp = true))
        assertFalse(AllowedApps.isControlled("com.google.android.dialer", self, isSystemApp = true))
        assertFalse(AllowedApps.isControlled("com.android.settings", self, isSystemApp = true))
    }

    @Test
    fun `pre-installed time-sinks are controlled despite the system flag`() {
        // The whole point: Chrome/YouTube ship as system apps but must count.
        assertTrue(AllowedApps.isControlled("com.android.chrome", self, isSystemApp = true))
        assertTrue(AllowedApps.isControlled("com.google.android.youtube", self, isSystemApp = true))
        assertTrue(AllowedApps.isControlled("com.sec.android.app.sbrowser", self, isSystemApp = true))
        assertTrue(AllowedApps.isControlled("com.google.android.play.games", self, isSystemApp = true))
    }

    @Test
    fun `every curated time-sink is controlled regardless of flag`() {
        for (pkg in AllowedApps.TIME_SINK_PACKAGES) {
            assertTrue("expected $pkg to be controlled as system app",
                AllowedApps.isControlled(pkg, self, isSystemApp = true))
            assertTrue("expected $pkg to be controlled as user app",
                AllowedApps.isControlled(pkg, self, isSystemApp = false))
        }
    }

    // ---- Parent dashboard visibility ----

    @Test
    fun `parent view shows Gmail but not Maps`() {
        // Both are updated-system Google apps; Maps is denylisted as a pure utility.
        assertTrue(AllowedApps.isParentVisible("com.google.android.gm", self, isSystemApp = true, isUpdatedSystemApp = true))
        assertFalse(AllowedApps.isParentVisible("com.google.android.apps.maps", self, isSystemApp = true, isUpdatedSystemApp = true))
    }

    @Test
    fun `parent view hides pure-system apps and self but shows user apps`() {
        assertFalse(AllowedApps.isParentVisible(self, self, isSystemApp = false, isUpdatedSystemApp = false))
        // Pure system, never updated from the store — Settings, dialer, etc.
        assertFalse(AllowedApps.isParentVisible("com.android.settings", self, isSystemApp = true, isUpdatedSystemApp = false))
        // A normal user-installed app shows.
        assertTrue(AllowedApps.isParentVisible("com.instagram.android", self, isSystemApp = false, isUpdatedSystemApp = false))
    }

    // ---- Non-screen-time utilities (Clock, wallpaper carousel) ----

    @Test
    fun `clock is hidden from both views on every OEM`() {
        // Google Clock is a system app updated from the Play Store — the case that leaked into
        // the parent dashboard before isNonScreenTime existed.
        assertFalse(AllowedApps.isParentVisible("com.google.android.deskclock", self, isSystemApp = true, isUpdatedSystemApp = true))
        assertFalse(AllowedApps.isControlled("com.google.android.deskclock", self, isSystemApp = true))
        // OEM clocks, explicit + name-matched.
        assertFalse(AllowedApps.isParentVisible("com.coloros.alarmclock", self, isSystemApp = true, isUpdatedSystemApp = false))
        assertFalse(AllowedApps.isParentVisible("com.sec.android.app.clockpackage", self, isSystemApp = true, isUpdatedSystemApp = true))
        assertFalse(AllowedApps.isParentVisible("com.someoem.deskclock", self, isSystemApp = true, isUpdatedSystemApp = false))
    }

    @Test
    fun `wallpaper carousel is hidden from both views`() {
        // Realme/Oppo lock-screen wallpaper carousel — foreground on every lock-screen swipe.
        assertFalse(AllowedApps.isParentVisible("com.heytap.pictorial", self, isSystemApp = true, isUpdatedSystemApp = true))
        assertFalse(AllowedApps.isControlled("com.heytap.pictorial", self, isSystemApp = true))
        assertFalse(AllowedApps.isParentVisible("com.google.android.apps.wallpaper", self, isSystemApp = true, isUpdatedSystemApp = true))
        assertFalse(AllowedApps.isParentVisible("com.mfashiongallery.emag", self, isSystemApp = true, isUpdatedSystemApp = false))
    }

    @Test
    fun `carousel packages are hidden across OEMs`() {
        val carousels = listOf(
            "com.heytap.pictorial",              // Realme / Oppo
            "com.oplus.pictorial",
            "com.coloros.pictorial",
            "com.mfashiongallery.emag",          // MIUI / HyperOS
            "com.miui.android.fashiongallery",
            "com.glance.internet",               // Glance
            "com.samsung.android.app.dressroom", // Samsung
            "com.vivo.magazine",                 // vivo
            "com.motorola.personalize",          // Motorola
            "com.transsion.magazineservice",     // Tecno / Infinix / itel
        )
        for (pkg in carousels) {
            assertFalse("$pkg should not be screen time",
                AllowedApps.isParentVisible(pkg, self, isSystemApp = true, isUpdatedSystemApp = true))
            assertFalse("$pkg should not count toward the budget",
                AllowedApps.isControlled(pkg, self, isSystemApp = true))
        }
    }

    @Test
    fun `an explicit time-sink beats the name heuristic`() {
        // Google News is com.google.android.apps.magazines — the "magazine" hint must not hide it.
        assertTrue(AllowedApps.isParentVisible("com.google.android.apps.magazines", self, isSystemApp = true, isUpdatedSystemApp = true))
        assertTrue(AllowedApps.isControlled("com.google.android.apps.magazines", self, isSystemApp = true))
    }

    @Test
    fun `a user-installed wallpaper app is still counted`() {
        // The name heuristic must apply to PRE-INSTALLED apps only — a wallpaper browser the kid
        // installed and scrolls for an hour is real screen time.
        assertTrue(AllowedApps.isControlled("com.wallpaperscraft.wallpaper", self, isSystemApp = false))
        assertTrue(AllowedApps.isParentVisible("com.wallpaperscraft.wallpaper", self, isSystemApp = false, isUpdatedSystemApp = false))
    }

    @Test
    fun `the clock heuristic does not catch Wear OS clockwork packages`() {
        assertTrue(AllowedApps.isParentVisible("com.google.android.clockwork.home", self, isSystemApp = true, isUpdatedSystemApp = true))
    }

    @Test
    fun `parent view is a superset of the kid-controlled set (minus utilities)`() {
        // Anything the kid budget controls must also be visible to the parent, except
        // utilities the parent view deliberately drops.
        for (pkg in AllowedApps.TIME_SINK_PACKAGES) {
            if (pkg in AllowedApps.UTILITY_PACKAGES) continue
            assertTrue("expected $pkg visible to parent",
                AllowedApps.isParentVisible(pkg, self, isSystemApp = true, isUpdatedSystemApp = true))
        }
    }
}
