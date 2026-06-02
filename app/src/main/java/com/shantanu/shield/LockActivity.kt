package com.shantanu.shield

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.shantanu.shield.overlay.FaceLockOverlayContent
import com.shantanu.shield.service.AppLockForegroundService

// Full-screen lock screen used for targets that force-hide third-party overlay windows
// (e.g. the system Settings app enables HIDE_NON_SYSTEM_OVERLAY_WINDOWS for anti-tapjacking,
// so a TYPE_APPLICATION_OVERLAY can never draw over it). A real Activity is not an overlay,
// so it reliably covers such screens.
class LockActivity : ComponentActivity() {

    private var lockedPackage: String = ""
    private var resolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lockedPackage = intent.getStringExtra(EXTRA_PACKAGE) ?: ""
        val messageType = intent.getIntExtra(EXTRA_MESSAGE_TYPE, 0)
        val isKidModeLock = intent.getBooleanExtra(EXTRA_KID_MODE_LOCK, false)

        setContent {
            FaceLockOverlayContent(
                packageName = lockedPackage,
                forcedMessageType = messageType,
                isKidModeLock = isKidModeLock,
                onAuthenticated = {
                    resolved = true
                    reportResult(true)
                    finish()
                }
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Wrong/no face: never fall back into the locked screen behind us — send the user Home.
        resolved = true
        reportResult(false)
        goHome()
        finish()
    }

    override fun onStop() {
        super.onStop()
        // The lock screen left the foreground without authenticating (e.g. Home button).
        // Tell the service so it can re-lock the target the next time it is opened.
        if (!resolved) {
            reportResult(false)
            finish()
        }
    }

    private fun goHome() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) { }
    }

    private fun reportResult(success: Boolean) {
        try {
            startService(Intent(this, AppLockForegroundService::class.java).apply {
                action = AppLockForegroundService.ACTION_LOCK_RESULT
                putExtra(AppLockForegroundService.EXTRA_PACKAGE_NAME, lockedPackage)
                putExtra(AppLockForegroundService.EXTRA_AUTH_SUCCESS, success)
            })
        } catch (e: Exception) { }
    }

    companion object {
        const val EXTRA_PACKAGE = "lock_package"
        const val EXTRA_MESSAGE_TYPE = "lock_message_type"
        const val EXTRA_KID_MODE_LOCK = "lock_kid_mode"

        fun newIntent(context: Context, packageName: String, messageType: Int, isKidModeLock: Boolean = false): Intent {
            return Intent(context, LockActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_MESSAGE_TYPE, messageType)
                putExtra(EXTRA_KID_MODE_LOCK, isKidModeLock)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
        }
    }
}
