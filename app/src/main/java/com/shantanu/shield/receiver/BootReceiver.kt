package com.shantanu.shield.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shantanu.shield.service.AppLockForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, AppLockForegroundService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
