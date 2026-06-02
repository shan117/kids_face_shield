package com.shantanu.shield.util

import android.content.Context
import android.content.Intent
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
