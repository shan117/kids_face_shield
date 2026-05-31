package com.shantanu.shield.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.shantanu.shield.receiver.AdminReceiver

object TamperProtection {

    const val SETTINGS_PACKAGE = "com.android.settings"

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun adminComponent(context: Context) = ComponentName(context, AdminReceiver::class.java)

    fun isAdminActive(context: Context): Boolean =
        dpm(context).isAdminActive(adminComponent(context))

    fun enableAdminIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Enable to prevent Kids Shield from being uninstalled."
            )
        }

    fun disableAdmin(context: Context) {
        val manager = dpm(context)
        val component = adminComponent(context)
        if (manager.isAdminActive(component)) {
            manager.removeActiveAdmin(component)
        }
    }

    // Best-effort OEM "auto-start / background launch" management page. Returns null if the
    // current device exposes no such page (e.g. stock Android / Motorola), in which case the
    // caller should fall back to the app details page.
    fun autoStartIntent(context: Context): Intent? {
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
        )
        val pm = context.packageManager
        for (component in candidates) {
            val intent = Intent().setComponent(component)
            if (pm.resolveActivity(intent, 0) != null) {
                return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return null
    }

    // Fallback page used when no OEM auto-start page exists.
    fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
