package com.shantanu.shield.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.shantanu.shield.MainActivity

/**
 * Receives Firebase Cloud Messaging pushes. Console "notification" messages are shown automatically
 * by the system when the app is in the background; this service covers the **foreground** case and
 * any **data** messages, and keeps every install reachable via the [TOPIC_ALL] topic.
 *
 * Dormant until you actually send something from the Console. See MESSAGING_SETUP.md.
 */
class ShieldMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Grab this from logcat (tag "ShieldFCM") to send a Console "test message" to one device.
        Log.d(TAG, "FCM registration token: $token")
        runCatching { FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_ALL) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "Shield"
        val body = message.notification?.body ?: message.data["body"] ?: return
        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        ensureChannel(this)
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), notification)
        }.onFailure { Log.w(TAG, "notify failed (POST_NOTIFICATIONS not granted?)", it) }
    }

    companion object {
        const val CHANNEL_ID = "announcements"
        const val TOPIC_ALL = "all"
        private const val TAG = "ShieldFCM"

        /** Create the announcements channel. Must exist before background notifications arrive, so
         *  it's also created at app launch (AppLockApplication). */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Announcements", NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Updates, tips, and news from Shield" }
                context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
        }
    }
}
