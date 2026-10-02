package com.shantanu.shield.webfilter

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.concurrent.atomic.AtomicReference

/**
 * Which installed apps are browsers, asked of the system rather than guessed.
 *
 * Same approach as [com.shantanu.shield.util.AllowedApps.resolveDefaultPhonePackage]: any app that
 * offers to open an `https://` link IS a browser, whatever it's called. That covers Brave, Opera, Tor,
 * Samsung Internet, whatever a given OEM preinstalls, and anything released next year — none of which
 * a hardcoded name list would ever keep up with.
 *
 * Cached because this is consulted on the lock-decision path; invalidated when apps change.
 */
object BrowserApps {

    private val cache = AtomicReference<Set<String>?>(null)

    /** Drop the cached set. Call on PACKAGE_ADDED / PACKAGE_REMOVED. */
    fun clearCache() = cache.set(null)

    /**
     * Every browser on the device, excluding our own package.
     *
     * Excluding ourselves is not a detail: this set feeds a "block these" rule, and including our own
     * browser would have the app lock itself out of the replacement it provides.
     */
    fun browserPackages(context: Context): Set<String> {
        cache.get()?.let { return it }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = runCatching {
            context.packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())

        // Drop ourselves and the Android resolver/chooser pseudo-package.
        val out = resolved - context.packageName - "android"
        return out.also { cache.set(it) }
    }

    /** True when [packageName] is a browser other than ours. */
    fun isOtherBrowser(context: Context, packageName: String): Boolean =
        packageName != context.packageName && packageName in browserPackages(context)
}
